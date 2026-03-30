package com.movtery.zalithlauncher.feature.version

import com.google.gson.annotations.SerializedName
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathHome
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.utils.path.PathManager
import net.kdt.pojavlaunch.Tools
import org.apache.commons.io.FileUtils
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Current game state information (supports legacy config migration)
 *
 * @property version Currently selected version name
 * @property favoritesMap Favorite groups map <GroupName, Set of version names>
 */
data class CurrentGameInfo(
    @SerializedName("version")
    var version: String = "",
    @SerializedName("favoritesInfo")
    val favoritesMap: MutableMap<String, MutableSet<String>> = ConcurrentHashMap()
) {

    /**
     * Safely saves current state to file
     */
    fun saveCurrentInfo() {
        val infoFile = getInfoFile()

        runCatching {
            FileUtils.writeByteArrayToFile(
                infoFile,
                Tools.GLOBAL_GSON.toJson(this).toByteArray(Charsets.UTF_8)
            )
        }.onFailure { e ->
            Logging.e("CurrentGameInfo", "Save failed: ${infoFile.absolutePath}", e)
        }
    }

    companion object {

        /**
         * 🔥 FIX: Do NOT use scoped storage path for this file
         */
        private fun getInfoFile(): File {
            return if (ProfilePathHome.isScopedStorage()) {
                File(PathManager.DIR_DATA, "CurrentInfo.cfg")
            } else {
                File(ProfilePathHome.getGameHome(), "CurrentInfo.cfg")
            }
        }

        private fun getLegacyInfoFile(): File {
            return if (ProfilePathHome.isScopedStorage()) {
                File(PathManager.DIR_DATA, "CurrentVersion.cfg")
            } else {
                File(ProfilePathHome.getGameHome(), "CurrentVersion.cfg")
            }
        }

        /**
         * Refresh and return latest game info (handles legacy migration automatically)
         */
        fun refreshCurrentInfo(): CurrentGameInfo {
            val infoFile = getInfoFile()
            val legacyInfoFile = getLegacyInfoFile()

            return try {
                when {
                    infoFile.exists() -> loadFromJsonFile(infoFile)
                    legacyInfoFile.exists() -> migrateLegacyConfig(legacyInfoFile)
                    else -> createNewConfig()
                }
            } catch (e: Exception) {
                Logging.e("CurrentGameInfo", "Refresh failed", e)
                createNewConfig()
            }
        }

        private fun loadFromJsonFile(infoFile: File): CurrentGameInfo {
            return Tools.GLOBAL_GSON.fromJson(
                infoFile.readText(),
                CurrentGameInfo::class.java
            ).also { info ->
                checkNotNull(info) { "Deserialization returned null" }
            }
        }

        private fun migrateLegacyConfig(infoFile: File): CurrentGameInfo {
            return CurrentGameInfo().apply {
                version = if (infoFile.exists()) infoFile.readText() else ""
                infoFile.delete()
            }.applyPostActions()
        }

        private fun createNewConfig(): CurrentGameInfo {
            return CurrentGameInfo().applyPostActions()
        }

        private fun CurrentGameInfo.applyPostActions(): CurrentGameInfo {
            saveCurrentInfo()
            return this
        }
    }
}