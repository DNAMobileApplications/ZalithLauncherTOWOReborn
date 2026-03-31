package com.movtery.zalithlauncher.feature.customprofilepath

import java.io.File

data class ProfileWorkspace(
    val profileId: String,
    val rootDir: File,
    val minecraftDir: File
) {
    val versionsDir: File get() = File(minecraftDir, "versions")
    val librariesDir: File get() = File(minecraftDir, "libraries")
    val assetsDir: File get() = File(minecraftDir, "assets")
    val resourcesDir: File get() = File(minecraftDir, "resources")
}
