package com.movtery.zalithlauncher.utils

import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathHome
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.utils.path.PathManager
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.IOException

class LauncherProfiles {
    companion object {

        /**
         * Returns the launcher_profiles.json file location.
         *
         * In scoped storage mode, loader installers still need a real filesystem path,
         * so this file is written to a staging directory inside app cache.
         */
        @JvmStatic
        fun getLauncherProfilesFile(): File {
            return if (ProfilePathHome.isScopedStorage()) {
                File(PathManager.DIR_CACHE, "scoped_loader_root/.minecraft/launcher_profiles.json")
            } else {
                File(ProfilePathHome.getGameHome(), "launcher_profiles.json")
            }
        }

        /**
         * Writes a default launcher_profiles.json file.
         * If this file is missing, Forge, NeoForge, and similar installers may fail.
         */
        @JvmStatic
        fun generateLauncherProfiles() {
            runCatching {
                val file = getLauncherProfilesFile()
                val profilesJsonString =
                    """{"profiles":{"default":{"lastVersionId":"latest-release"}},"selectedProfile":"default"}"""

                if (file.parentFile?.exists() == false) {
                    file.parentFile?.mkdirs()
                }

                if (!file.exists()) {
                    if (!file.createNewFile()) {
                        throw IOException("Failed to create launcher_profiles.json file!")
                    }
                }

                // Always overwrite to guarantee valid content
                FileUtils.write(file, profilesJsonString, Charsets.UTF_8, false)

                Logging.i(
                    "Write launcher_profiles.json",
                    "launcher_profiles.json was written successfully!\r\n" +
                            "File Location: ${file.absolutePath}\r\n" +
                            "Contents: $profilesJsonString"
                )
            }.getOrElse { e ->
                Logging.e(
                    "Write launcher_profiles.json",
                    "Unable to generate launcher_profiles.json file!",
                    e
                )
            }
        }
    }
}
