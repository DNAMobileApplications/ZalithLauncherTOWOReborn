package com.movtery.zalithlauncher.feature.version.install

import android.content.Intent
import com.google.gson.JsonParser
import com.kdt.mcgui.ProgressLayout
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathHome
import com.movtery.zalithlauncher.utils.path.LibPath
import com.movtery.zalithlauncher.utils.path.PathManager
import net.kdt.pojavlaunch.JavaGUILauncherActivity
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class InstallArgsUtils(
    private val mcVersion: String,
    private val loaderVersion: String
) {

    private fun getInstallDir(customName: String): String {
        return if (ProfilePathHome.isScopedStorage()) {
            File(PathManager.DIR_CACHE, "scoped_loader_root/.minecraft").apply {
                mkdirs()
            }.absolutePath
        } else {
            ProfilePathHome.getGameHome()
        }
    }

    fun setFabric(intent: Intent, jarFile: File, customName: String) {
        val installDir = getInstallDir(customName)

        val args = buildString {
            append("-DprofileName=\"$customName\" ")
            append("-javaagent:${LibPath.MIO_FABRIC_AGENT.absolutePath} ")
            append("-jar ${jarFile.absolutePath} client ")
            append("-mcversion \"$mcVersion\" ")
            append("-loader \"$loaderVersion\" ")
            append("-dir \"$installDir\"")
        }

        intent.putExtra("javaArgs", args)
        intent.putExtra(JavaGUILauncherActivity.SUBSCRIBE_JVM_EXIT_EVENT, true)
        intent.putExtra(JavaGUILauncherActivity.FORCE_SHOW_LOG, true)
    }

    fun setQuilt(intent: Intent, jarFile: File, customName: String) {
        val installDir = getInstallDir(customName)
        val args =
            "-jar ${jarFile.absolutePath} install client \"$mcVersion\" \"$loaderVersion\" --install-dir=\"$installDir\""

        intent.putExtra("javaArgs", args)
        intent.putExtra(JavaGUILauncherActivity.SUBSCRIBE_JVM_EXIT_EVENT, true)
        intent.putExtra(JavaGUILauncherActivity.FORCE_SHOW_LOG, true)
    }

    @Throws(Throwable::class)
    fun setForge(intent: Intent, jarFile: File, customName: String) {
        forgeLikeCustomVersionName(jarFile, customName)

        val args =
            "-javaagent:${LibPath.FORGE_INSTALLER.absolutePath}=\"$loaderVersion\" -jar ${jarFile.absolutePath}"

        intent.putExtra("javaArgs", args)
    }

    fun setNeoForge(intent: Intent, jarFile: File, customName: String) {
        val installDir = getInstallDir(customName)
        val args = "-jar ${jarFile.absolutePath} --installClient \"$installDir\""

        intent.putExtra("javaArgs", args)
        intent.putExtra(JavaGUILauncherActivity.SUBSCRIBE_JVM_EXIT_EVENT, true)
        intent.putExtra(JavaGUILauncherActivity.FORCE_SHOW_LOG, true)
        intent.putExtra("disableSecurityManager", true)
    }

    fun setOptiFine(intent: Intent, jarFile: File, customName: String) {
        val args =
            "-javaagent:${LibPath.FORGE_INSTALLER.absolutePath}=OFNPS " +
                    "-javaagent:${LibPath.OPTIFINE_RENAMER.absolutePath}=\"$customName\" " +
                    "-jar ${jarFile.absolutePath}"

        intent.putExtra("javaArgs", args)
    }

    /**
     * Updates the version name inside install_profile.json for Forge-like installers
     * so the generated version folder uses the custom launcher name.
     */
    @Throws(Throwable::class)
    private fun forgeLikeCustomVersionName(jarFile: File, customName: String) {
        val tempJarFile = File(jarFile.parentFile, "${jarFile.nameWithoutExtension}_temp.jar")
        val profileJson = File(jarFile.parentFile, "install_profile.json")

        try {
            updateProgress(0)

            if (tempJarFile.exists()) {
                tempJarFile.delete()
            }

            extractInstallProfile(jarFile, profileJson)
            updateProgress(50)

            modifyJsonFile(profileJson, customName)
            writeTempJarFile(jarFile, tempJarFile, profileJson)
            updateProgress(100)

            if (!jarFile.delete()) {
                throw IOException("Failed to delete original installer file.")
            }

            if (!tempJarFile.renameTo(jarFile)) {
                throw IOException("Failed to rename temp installer file to original.")
            }

            profileJson.delete()
        } catch (e: Exception) {
            throw RuntimeException(e)
        } finally {
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_RESOURCE)
        }
    }

    private fun updateProgress(progress: Int) {
        ProgressKeeper.submitProgress(
            ProgressLayout.INSTALL_RESOURCE,
            progress,
            R.string.mod_forge_custom_version
        )
    }

    /**
     * Extracts install_profile.json from the installer archive.
     */
    @Throws(Throwable::class)
    private fun extractInstallProfile(jarFile: File, profileJson: File) {
        val zipFile = ZipFile(jarFile)
        val entry = zipFile.getEntry("install_profile.json")
            ?: throw IOException("File \"install_profile.json\" not found in the installer.")

        profileJson.outputStream().use { outputStream ->
            zipFile.getInputStream(entry).use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        }
    }

    /**
     * Modifies install_profile.json so the installer uses a custom version name.
     */
    @Throws(Throwable::class)
    private fun modifyJsonFile(profileJson: File, customName: String) {
        val jsonObject = JsonParser.parseString(profileJson.readText()).asJsonObject

        if (jsonObject.has("spec")) {
            if (!jsonObject.has("version")) {
                throw IOException("Unable to find version key.")
            }
            jsonObject.addProperty("version", customName)
        } else {
            if (!jsonObject.has("install")) {
                throw IOException("Unable to find install key.")
            }

            val install = jsonObject.get("install").asJsonObject
            if (!install.has("target")) {
                throw IOException("Unable to find install-target key.")
            }

            install.addProperty("target", customName)
            jsonObject.add("install", install)
        }

        profileJson.writeText(jsonObject.toString())
    }

    @Throws(Throwable::class)
    private fun writeTempJarFile(jarFile: File, tempJarFile: File, profileJson: File) {
        fun shouldSkip(entryName: String): Boolean {
            return entryName.startsWith("META-INF/") &&
                    (entryName.endsWith(".SF") || entryName.endsWith(".RSA"))
        }

        ZipFile(jarFile).use { zipFile ->
            ZipOutputStream(tempJarFile.outputStream()).use { zos ->
                zipFile.entries().asSequence().forEach { originalEntry ->
                    zos.putNextEntry(ZipEntry(originalEntry.name))

                    if (originalEntry.name == "install_profile.json") {
                        profileJson.inputStream().use { input ->
                            input.copyTo(zos)
                        }
                    } else if (!originalEntry.isDirectory && !shouldSkip(originalEntry.name)) {
                        zipFile.getInputStream(originalEntry).use { input ->
                            input.copyTo(zos)
                        }
                    }

                    zos.closeEntry()
                }
            }
        }
    }
}
