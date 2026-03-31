package com.movtery.zalithlauncher.feature.customprofilepath

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.movtery.zalithlauncher.InfoDistributor
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.feature.version.Version
import com.movtery.zalithlauncher.feature.version.VersionConfig
import com.movtery.zalithlauncher.feature.version.utils.VersionInfoUtils
import org.apache.commons.io.FileUtils
import java.io.File

object ScopedVersionsManager {
    private const val STAGED_ROOT_RELATIVE = "scoped_loader_root/.minecraft"
    private var appContext: Context? = null

    @JvmStatic
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    @JvmStatic
    fun getInitializedContextOrNull(): Context? = appContext

    private fun getContextOrThrow(): Context {
        return appContext ?: throw IllegalStateException("ScopedVersionsManager is not initialized")
    }

    private fun getRootTreeDir(context: Context): DocumentFile? {
        val currentPath = ProfilePathManager.getCurrentPath()
        if (!currentPath.startsWith("content://")) return null
        return DocumentFile.fromTreeUri(context, Uri.parse(currentPath))
    }

    private fun getScopedMinecraftDir(context: Context): DocumentFile? {
        val root = getRootTreeDir(context) ?: return null
        return root.findFile(".minecraft") ?: root.createDirectory(".minecraft")
    }

    private fun getScopedVersionsDir(context: Context): DocumentFile? {
        val minecraft = getScopedMinecraftDir(context) ?: return null
        return minecraft.findFile("versions") ?: minecraft.createDirectory("versions")
    }

    private fun getStagedMinecraftDir(context: Context): File {
        return File(context.cacheDir, STAGED_ROOT_RELATIVE).apply { mkdirs() }
    }

    private fun getStagedVersionsDir(context: Context): File {
        return File(getStagedMinecraftDir(context), "versions").apply { mkdirs() }
    }

    @JvmStatic
    fun isVersionExists(versionName: String, checkJson: Boolean = false): Boolean {
        val context = appContext ?: return false
        syncScopedVersionsToCache(context)

        val localDir = File(getStagedVersionsDir(context), versionName)
        if (localDir.exists()) {
            return if (checkJson) {
                File(localDir, "$versionName.json").exists() ||
                        (localDir.listFiles()?.any { it.isFile && it.name.endsWith(".json") } == true)
            } else {
                true
            }
        }

        val remoteDir = getScopedVersionsDir(context)?.findFile(versionName) ?: return false
        return if (checkJson) {
            remoteDir.findFile("$versionName.json")?.isFile == true ||
                    remoteDir.listFiles().any { it.isFile && it.name?.endsWith(".json") == true }
        } else {
            remoteDir.exists()
        }
    }

    @JvmStatic
    fun loadVersions(refreshVersionInfo: Boolean): List<Version> {
        val context = getContextOrThrow()
        syncScopedVersionsToCache(context)

        val versionsHome = getStagedVersionsDir(context)
        val versions = mutableListOf<Version>()

        versionsHome.listFiles()?.forEach { versionFile ->
            if (!versionFile.exists() || !versionFile.isDirectory) {
                return@forEach
            }

            runCatching {
                var isVersion = false
                val primaryJson = File(versionFile, "${versionFile.name}.json")
                val anyJson = versionFile.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".json") }
                val versionJson = when {
                    primaryJson.exists() && primaryJson.isFile -> primaryJson
                    anyJson != null -> anyJson
                    else -> null
                }

                if (versionJson != null) {
                    isVersion = true

                    val versionInfoFile = File(getZalithVersionPath(versionFile), "VersionInfo.json")
                    if (refreshVersionInfo) {
                        FileUtils.deleteQuietly(versionInfoFile)
                    }
                    if (!versionInfoFile.exists()) {
                        VersionInfoUtils.parseJson(versionJson)?.save(versionFile)
                    }
                }

                val versionConfig = VersionConfig.parseConfig(versionFile)
                versions.add(
                    Version(
                        versionsHome.absolutePath,
                        versionFile.absolutePath,
                        versionConfig,
                        isVersion
                    )
                )
            }.onFailure { throwable ->
                Logging.e(
                    "ScopedVersionsManager",
                    "Failed to process scoped version folder: ${versionFile.absolutePath}",
                    throwable
                )
            }
        }

        return versions
    }

    @JvmStatic
    fun renameVersion(version: Version, newName: String) {
        val context = getContextOrThrow()
        syncScopedVersionsToCache(context)

        val stagedVersionsDir = getStagedVersionsDir(context)
        val oldName = version.getVersionName()
        val sourceDir = File(stagedVersionsDir, oldName)
        val targetDir = File(stagedVersionsDir, newName)

        FileUtils.deleteQuietly(targetDir)
        if (sourceDir.exists()) {
            FileUtils.copyDirectory(sourceDir, targetDir)
            FileUtils.deleteQuietly(sourceDir)
        } else {
            targetDir.mkdirs()
        }

        renamePrimaryArtifacts(targetDir, oldName, newName)
        updateVersionConfig(targetDir)

        val scopedVersionsDir = getScopedVersionsDir(context) ?: return
        scopedVersionsDir.findFile(oldName)?.let { deleteDocumentFile(it) }
        copyLocalVersionToScoped(context, targetDir, newName)
    }

    @JvmStatic
    fun copyVersion(version: Version, newName: String, copyAllFile: Boolean) {
        val context = getContextOrThrow()
        syncScopedVersionsToCache(context)

        val sourceDir = version.getVersionPath()
        val targetDir = File(getStagedVersionsDir(context), newName)
        val originalName = version.getVersionName()

        FileUtils.deleteQuietly(targetDir)
        targetDir.mkdirs()

        if (copyAllFile) {
            FileUtils.copyDirectory(sourceDir, targetDir)
        } else {
            File(sourceDir, "$originalName.json").takeIf { it.exists() }?.let {
                FileUtils.copyFile(it, File(targetDir, "$newName.json"))
            }
            File(sourceDir, "$originalName.jar").takeIf { it.exists() }?.let {
                FileUtils.copyFile(it, File(targetDir, "$newName.jar"))
            }
        }

        renamePrimaryArtifacts(targetDir, originalName, newName)

        version.getVersionConfig().copy().let { config ->
            config.setVersionPath(targetDir)
            config.setIsolationType(VersionConfig.IsolationType.ENABLE)
            config.saveWithThrowable()
        }

        copyLocalVersionToScoped(context, targetDir, newName)
    }

    @JvmStatic
    fun syncScopedVersionsToCache() {
        val context = getInitializedContextOrNull() ?: return
        syncScopedVersionsToCache(context)
    }

    private fun syncScopedVersionsToCache(context: Context) {
        val scopedVersionsDir = getScopedVersionsDir(context) ?: return
        val stagedVersionsDir = getStagedVersionsDir(context)

        val remoteNames = mutableSetOf<String>()
        scopedVersionsDir.listFiles().forEach { remoteVersionDir ->
            if (!remoteVersionDir.isDirectory) return@forEach
            val remoteName = remoteVersionDir.name ?: return@forEach
            remoteNames.add(remoteName)
            val localTargetDir = File(stagedVersionsDir, remoteName)
            copyDocumentTreeToFile(context, remoteVersionDir, localTargetDir)
        }

        stagedVersionsDir.listFiles()?.forEach { localVersionDir ->
            if (localVersionDir.isDirectory && localVersionDir.name !in remoteNames) {
                FileUtils.deleteQuietly(localVersionDir)
            }
        }
    }

    private fun renamePrimaryArtifacts(targetDir: File, oldName: String, newName: String) {
        if (!targetDir.exists()) return

        val oldJson = File(targetDir, "$oldName.json")
        val oldJar = File(targetDir, "$oldName.jar")
        val newJson = File(targetDir, "$newName.json")
        val newJar = File(targetDir, "$newName.jar")

        if (oldJson.exists() && oldJson.absolutePath != newJson.absolutePath) {
            FileUtils.deleteQuietly(newJson)
            oldJson.renameTo(newJson)
        }
        if (oldJar.exists() && oldJar.absolutePath != newJar.absolutePath) {
            FileUtils.deleteQuietly(newJar)
            oldJar.renameTo(newJar)
        }
    }

    private fun updateVersionConfig(versionDir: File) {
        runCatching {
            val config = VersionConfig.parseConfig(versionDir)
            config.setVersionPath(versionDir)
            config.saveWithThrowable()
        }.onFailure {
            Logging.e("ScopedVersionsManager", "Failed to update version config: ${versionDir.absolutePath}", it)
        }
    }

    private fun getZalithVersionPath(folder: File): File {
        return File(folder, InfoDistributor.LAUNCHER_NAME)
    }

    private fun copyLocalVersionToScoped(context: Context, sourceDir: File, targetName: String) {
        val scopedVersionsDir = getScopedVersionsDir(context) ?: return
        scopedVersionsDir.findFile(targetName)?.let { deleteDocumentFile(it) }
        val targetDir = scopedVersionsDir.createDirectory(targetName) ?: return
        copyFileTreeToDocument(context, targetDir, sourceDir)
    }

    private fun copyDocumentTreeToFile(context: Context, sourceDir: DocumentFile, targetDir: File) {
        if (!sourceDir.isDirectory) return
        if (targetDir.exists()) {
            FileUtils.deleteQuietly(targetDir)
        }
        targetDir.mkdirs()

        sourceDir.listFiles().forEach { child ->
            if (child.isDirectory) {
                copyDocumentTreeToFile(context, child, File(targetDir, child.name ?: return@forEach))
            } else if (child.isFile) {
                val targetFile = File(targetDir, child.name ?: return@forEach)
                targetFile.parentFile?.mkdirs()
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun copyFileTreeToDocument(context: Context, targetDir: DocumentFile, sourceDir: File) {
        sourceDir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                val subDir = targetDir.findFile(file.name)
                    ?.takeIf { it.isDirectory }
                    ?: targetDir.createDirectory(file.name)
                    ?: return@forEach
                copyFileTreeToDocument(context, subDir, file)
            } else if (file.isFile) {
                targetDir.findFile(file.name)?.takeIf { it.isFile }?.delete()
                val doc = targetDir.createFile("*/*", file.name) ?: return@forEach
                context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                    file.inputStream().use { input ->
                        input.copyTo(out)
                    }
                }
            }
        }
    }

    private fun deleteDocumentFile(file: DocumentFile) {
        if (file.isDirectory) {
            file.listFiles().forEach { deleteDocumentFile(it) }
        }
        file.delete()
    }
}
