package com.movtery.zalithlauncher.feature.customprofilepath

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.movtery.zalithlauncher.InfoDistributor
import com.movtery.zalithlauncher.feature.version.VersionConfig
import com.movtery.zalithlauncher.feature.version.utils.VersionInfoUtils
import java.io.File

object ScopedInstallHelper {

    @JvmStatic
    fun copyVersionToScoped(context: Context, versionName: String) {
        ProfilePathManager.init(context)

        val treeUri = Uri.parse(ProfilePathManager.getCurrentPath())
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return

        val minecraft = root.findFile(".minecraft") ?: root.createDirectory(".minecraft") ?: return
        val versions = minecraft.findFile("versions") ?: minecraft.createDirectory("versions") ?: return

        val stagedVersionsDir = File(context.cacheDir, "scoped_loader_root/.minecraft/versions")
        if (!stagedVersionsDir.exists() || !stagedVersionsDir.isDirectory) return

        val requestedDir = File(stagedVersionsDir, versionName)

        fun hasJson(dir: File): Boolean {
            return dir.listFiles()?.any { it.isFile && it.name.endsWith(".json") } == true
        }

        fun hasJar(dir: File): Boolean {
            return dir.listFiles()?.any { it.isFile && it.name.endsWith(".jar") } == true
        }

        fun looksLikeLoaderOutput(dir: File): Boolean {
            val name = dir.name.lowercase()
            return hasJson(dir) && (
                    hasJar(dir) ||
                            "fabric" in name ||
                            "forge" in name ||
                            "quilt" in name ||
                            "neoforge" in name
                    )
        }

        val loaderDir = stagedVersionsDir.listFiles()
            ?.filter { it.isDirectory && it.name != versionName }
            ?.sortedByDescending { it.lastModified() }
            ?.firstOrNull { looksLikeLoaderOutput(it) }

        if (!requestedDir.exists() && loaderDir == null) return

        versions.findFile(versionName)?.let { deleteDocumentFile(it) }
        val versionDir = versions.createDirectory(versionName) ?: return

        if (requestedDir.exists() && requestedDir.isDirectory) {
            copyFileTreeToDocument(context, versionDir, requestedDir)
        }

        if (
            loaderDir != null &&
            loaderDir.exists() &&
            loaderDir.isDirectory &&
            loaderDir.absolutePath != requestedDir.absolutePath
        ) {
            copyFileTreeToDocument(context, versionDir, loaderDir)

            val loaderJsonName = "${loaderDir.name}.json"
            val loaderJarName = "${loaderDir.name}.jar"
            val targetJsonName = "$versionName.json"
            val targetJarName = "$versionName.jar"

            if (loaderJsonName != targetJsonName) {
                versionDir.findFile(targetJsonName)?.delete()
                versionDir.findFile(loaderJsonName)?.renameTo(targetJsonName)
            }

            if (loaderJarName != targetJarName) {
                versionDir.findFile(targetJarName)?.delete()
                versionDir.findFile(loaderJarName)?.renameTo(targetJarName)
            }
        }

        val metadataSourceDir = when {
            loaderDir != null && loaderDir.exists() -> loaderDir
            requestedDir.exists() -> requestedDir
            else -> return
        }

        val launcherDir = versionDir.findFile(InfoDistributor.LAUNCHER_NAME)
            ?: versionDir.createDirectory(InfoDistributor.LAUNCHER_NAME)
            ?: return

        val preferredJson = File(metadataSourceDir, "$versionName.json")
        val fallbackJson = File(metadataSourceDir, "${metadataSourceDir.name}.json")
        val versionJson = when {
            preferredJson.exists() && preferredJson.isFile -> preferredJson
            fallbackJson.exists() && fallbackJson.isFile -> fallbackJson
            else -> null
        }

        if (versionJson != null) {
            VersionInfoUtils.parseJson(versionJson)?.save(metadataSourceDir)
        }

        val config = VersionConfig.parseConfig(metadataSourceDir)
        config.setVersionPath(metadataSourceDir)
        config.saveWithThrowable()

        val localLauncherDir = File(metadataSourceDir, InfoDistributor.LAUNCHER_NAME)
        if (localLauncherDir.exists()) {
            copyFileTreeToDocument(context, launcherDir, localLauncherDir)
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
