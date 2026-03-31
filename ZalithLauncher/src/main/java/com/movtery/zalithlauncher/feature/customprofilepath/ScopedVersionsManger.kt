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
import java.io.FileOutputStream

object ScopedVersionsManager {
    private const val TAG = "ScopedVersionsManager"

    private var appContext: Context? = null

    @JvmStatic
    fun init(context: Context) {
        appContext = context.applicationContext
        ProfilePathManager.init(context)
    }

    @JvmStatic
    fun isInitialized(): Boolean = appContext != null

    @JvmStatic
    fun getInitializedContextOrNull(): Context? = appContext

    private fun getContextOrNull(): Context? = appContext

    private fun requireContext(): Context {
        return appContext
            ?: throw IllegalStateException("ScopedVersionsManager.init(context) must be called before use")
    }

    private fun currentTreeUri(): Uri {
        return Uri.parse(ProfilePathManager.getCurrentPath())
    }

    private fun getTreeKey(treeUri: Uri): String {
        return treeUri.toString().hashCode().toString()
    }

    private fun currentTree(treeUri: Uri): DocumentFile? {
        val context = getContextOrNull() ?: return null
        return DocumentFile.fromTreeUri(context, treeUri)
    }

    private fun getOrCreateDir(parent: DocumentFile, name: String): DocumentFile? {
        return parent.findFile(name)?.takeIf { it.isDirectory } ?: parent.createDirectory(name)
    }

    private fun getMinecraftDir(treeUri: Uri): DocumentFile? {
        val root = currentTree(treeUri) ?: return null
        return getOrCreateDir(root, ".minecraft")
    }

    private fun getVersionsDir(treeUri: Uri): DocumentFile? {
        val minecraft = getMinecraftDir(treeUri) ?: return null
        return getOrCreateDir(minecraft, "versions")
    }

    private fun getCacheRoot(treeUri: Uri): File {
        val root = File(
            File(requireContext().cacheDir, "scoped_versions_cache"),
            getTreeKey(treeUri)
        )
        if (!root.exists()) {
            root.mkdirs()
        }
        return root
    }

    private fun getVersionCacheDir(treeUri: Uri, versionName: String): File {
        val dir = File(getCacheRoot(treeUri), versionName)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun isVersionExists(versionName: String, checkJson: Boolean = false): Boolean {
        if (!isInitialized()) {
            Logging.i(TAG, "isVersionExists() called before init; returning false for scoped path.")
            return false
        }

        val treeUri = currentTreeUri()
        val versionsDir = getVersionsDir(treeUri) ?: return false
        val versionDir = versionsDir.findFile(versionName) ?: return false
        if (!versionDir.isDirectory) {
            return false
        }

        return if (checkJson) {
            versionDir.findFile("$versionName.json")?.isFile == true
        } else {
            true
        }
    }

    fun loadVersions(refreshVersionInfo: Boolean = false): List<Version> {
        if (!isInitialized()) {
            Logging.i(TAG, "loadVersions() called before init; returning empty list for scoped path.")
            return emptyList()
        }

        val treeUri = currentTreeUri()
        val versionsDir = getVersionsDir(treeUri) ?: return emptyList()
        val versionDocs = versionsDir.listFiles().filter { it.isDirectory }

        val versionsHomeLogical = ProfilePathHome.getVersionsHome()
        val result = mutableListOf<Version>()

        versionDocs.forEach { versionDoc ->
            runCatching {
                val cachedFolder = mirrorScopedVersionToCache(treeUri, versionDoc)
                val jsonFile = File(cachedFolder, "${cachedFolder.name}.json")
                val isVersion = jsonFile.exists() && jsonFile.isFile

                if (isVersion) {
                    val versionInfoFile = File(
                        File(cachedFolder, InfoDistributor.LAUNCHER_NAME),
                        "VersionInfo.json"
                    )
                    if (refreshVersionInfo) {
                        FileUtils.deleteQuietly(versionInfoFile)
                    }
                    if (!versionInfoFile.exists()) {
                        VersionInfoUtils.parseJson(jsonFile)?.save(cachedFolder)
                    }
                }

                val versionConfig = VersionConfig.parseConfig(cachedFolder)
                result.add(
                    Version(
                        versionsHomeLogical,
                        cachedFolder.absolutePath,
                        versionConfig,
                        isVersion
                    )
                )
            }.onFailure {
                Logging.e(TAG, "Failed to load scoped version: ${versionDoc.name}", it)
            }
        }

        return result
    }

    fun renameVersion(version: Version, newName: String) {
        if (!isInitialized()) {
            Logging.i(TAG, "renameVersion() called before init; ignoring scoped rename request.")
            return
        }

        val treeUri = currentTreeUri()
        val versionsDir = getVersionsDir(treeUri) ?: return
        val oldName = version.getVersionName()
        val sourceDir = versionsDir.findFile(oldName) ?: return

        versionsDir.findFile(newName)?.let { deleteDocumentFile(it) }

        val createdDir = versionsDir.createDirectory(newName) ?: return
        copyDocumentDirectory(sourceDir, createdDir)

        renameChildIfExists(createdDir, "$oldName.json", "$newName.json")
        renameChildIfExists(createdDir, "$oldName.jar", "$newName.jar")

        deleteDocumentFile(sourceDir)

        val cacheOld = getVersionCacheDir(treeUri, oldName)
        val cacheNew = File(getCacheRoot(treeUri), newName)
        FileUtils.deleteQuietly(cacheNew)
        if (cacheOld.exists()) {
            cacheOld.renameTo(cacheNew)
        }
    }

    fun copyVersion(version: Version, name: String, copyAllFile: Boolean) {
        if (!isInitialized()) {
            Logging.i(TAG, "copyVersion() called before init; ignoring scoped copy request.")
            return
        }

        val treeUri = currentTreeUri()
        val versionsDir = getVersionsDir(treeUri) ?: return
        val sourceDir = versionsDir.findFile(version.getVersionName())
            ?: error("Scoped source version not found: ${version.getVersionName()}")

        val existingTarget = versionsDir.findFile(name)
        if (existingTarget != null) {
            deleteDocumentFile(existingTarget)
        }

        val targetDir = versionsDir.createDirectory(name)
            ?: error("Failed to create scoped version directory: $name")

        if (copyAllFile) {
            copyDocumentDirectory(sourceDir, targetDir)
            renameChildIfExists(targetDir, "${version.getVersionName()}.json", "$name.json")
            renameChildIfExists(targetDir, "${version.getVersionName()}.jar", "$name.jar")
        } else {
            copyChildIfExists(sourceDir, targetDir, "${version.getVersionName()}.json", "$name.json")
            copyChildIfExists(sourceDir, targetDir, "${version.getVersionName()}.jar", "$name.jar")
        }

        val cachedFolder = mirrorScopedVersionToCache(treeUri, targetDir)
        version.getVersionConfig().copy().let { config ->
            config.setVersionPath(cachedFolder)
            config.setIsolationType(VersionConfig.IsolationType.ENABLE)
            config.saveWithThrowable()
        }

        syncCacheBackToScoped(targetDir, cachedFolder)
    }

    private fun mirrorScopedVersionToCache(treeUri: Uri, versionDir: DocumentFile): File {
        val cacheDir = getVersionCacheDir(treeUri, versionDir.name ?: "unknown")
        FileUtils.deleteQuietly(cacheDir)
        cacheDir.mkdirs()
        copyDocumentDirectoryToFile(versionDir, cacheDir)
        return cacheDir
    }

    private fun syncCacheBackToScoped(targetDir: DocumentFile, sourceDir: File) {
        sourceDir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                val subDir = getOrCreateDir(targetDir, child.name) ?: return@forEach
                syncCacheBackToScoped(subDir, child)
            } else {
                val existing = targetDir.findFile(child.name)
                if (existing != null && existing.isFile) {
                    existing.delete()
                }

                val created = targetDir.createFile("*/*", child.name) ?: return@forEach
                requireContext().contentResolver.openOutputStream(created.uri)?.use { output ->
                    child.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun copyDocumentDirectory(source: DocumentFile, target: DocumentFile) {
        source.listFiles().forEach { child ->
            if (child.isDirectory) {
                val childName = child.name ?: return@forEach
                val subDir = getOrCreateDir(target, childName) ?: return@forEach
                copyDocumentDirectory(child, subDir)
            } else if (child.isFile) {
                val mimeType = child.type ?: "*/*"
                val name = child.name ?: return@forEach
                target.findFile(name)?.takeIf { it.isFile }?.delete()

                val created = target.createFile(mimeType, name) ?: return@forEach
                requireContext().contentResolver.openInputStream(child.uri)?.use { input ->
                    requireContext().contentResolver.openOutputStream(created.uri)?.use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun copyDocumentDirectoryToFile(source: DocumentFile, target: File) {
        if (!target.exists()) {
            target.mkdirs()
        }

        source.listFiles().forEach { child ->
            val childName = child.name ?: return@forEach
            if (child.isDirectory) {
                copyDocumentDirectoryToFile(child, File(target, childName))
            } else if (child.isFile) {
                val outFile = File(target, childName)
                requireContext().contentResolver.openInputStream(child.uri)?.use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun renameChildIfExists(parent: DocumentFile, oldName: String, newName: String) {
        val child = parent.findFile(oldName) ?: return
        child.renameTo(newName)
    }

    private fun copyChildIfExists(
        sourceParent: DocumentFile,
        targetParent: DocumentFile,
        sourceName: String,
        targetName: String
    ) {
        val child = sourceParent.findFile(sourceName) ?: return
        if (!child.isFile) {
            return
        }

        targetParent.findFile(targetName)?.takeIf { it.isFile }?.delete()

        val created = targetParent.createFile(child.type ?: "*/*", targetName) ?: return
        requireContext().contentResolver.openInputStream(child.uri)?.use { input ->
            requireContext().contentResolver.openOutputStream(created.uri)?.use { output ->
                input.copyTo(output)
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
