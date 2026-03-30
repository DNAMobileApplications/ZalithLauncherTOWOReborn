package com.movtery.zalithlauncher.feature.customprofilepath

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.feature.version.VersionsManager
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.ui.subassembly.customprofilepath.ProfileItem
import com.movtery.zalithlauncher.utils.StoragePermissionsUtils
import com.movtery.zalithlauncher.utils.path.PathManager
import net.kdt.pojavlaunch.Tools
import java.io.File
import java.io.FileWriter
import java.util.UUID

object ProfilePathManager {
    private const val SCOPED_STORAGE_PREFS = "scoped_storage_prefs"
    private const val KEY_SCOPED_LOCATIONS = "scoped_locations"

    private var appContext: Context? = null

    private val defaultPath: String = PathManager.DIR_GAME_HOME
    private var profilePathData: MutableList<ProfileItem> = mutableListOf()

    data class ScopedProfileItem(
        val id: String,
        val title: String,
        val treeUri: String
    )

    @JvmStatic
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    @JvmStatic
    fun isInitialized(): Boolean = appContext != null

    private fun getContextOrNull(): Context? = appContext

    private fun ensureContext(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    fun setCurrentPathId(id: String) {
        AllSettings.launcherProfile.put(id).save()
        VersionsManager.refresh("ProfilePathManager:setCurrentPathId")
    }

    fun refreshPath() {
        val configFile = PathManager.FILE_PROFILE_PATH
        if (!configFile.exists()) {
            profilePathData = mutableListOf()
            return
        }

        val json = Tools.read(configFile).takeIf { it.isNotEmpty() } ?: run {
            profilePathData = mutableListOf()
            return
        }

        profilePathData = parseProfileData(json)
    }

    private fun parseProfileData(json: String): MutableList<ProfileItem> {
        val jsonObject = JsonParser.parseString(json).asJsonObject
        return jsonObject.entrySet().mapNotNull { (key, value) ->
            runCatching {
                val profilePath = Tools.GLOBAL_GSON.fromJson(value, ProfilePathJsonObject::class.java)
                ProfileItem(key, profilePath.title, profilePath.path)
            }.onFailure { e ->
                Logging.e("parseProfileItem", "Failed to parse profile item: $key", e)
            }.getOrNull()
        }.toMutableList()
    }

    fun getCurrentPath(): String {
        if (!StoragePermissionsUtils.checkPermissions()) return defaultPath

        val profileId = AllSettings.launcherProfile.getValue()
        val path = when {
            profileId == "default" -> defaultPath
            else -> findAnyProfilePath(profileId) ?: defaultPath
        }

        if (!path.startsWith("content://")) {
            createNoMediaFile(path)
        }

        return path
    }

    fun getAllPath(): List<ProfileItem> = profilePathData.toList()

    fun addPath(profile: ProfileItem) {
        profilePathData.add(profile)
        save()
    }

    fun removePathById(id: String) {
        val removed = profilePathData.removeAll { it.id == id }
        if (removed) {
            if (AllSettings.launcherProfile.getValue() == id) {
                setCurrentPathId("default")
            }
            save()
        }
    }

    fun removePathByPath(path: String) {
        val removedItem = profilePathData.firstOrNull { it.path == path }
        val removed = profilePathData.removeAll { it.path == path }
        if (removed) {
            if (removedItem != null && AllSettings.launcherProfile.getValue() == removedItem.id) {
                setCurrentPathId("default")
            }
            save()
        }
    }

    fun containsPath(path: String): Boolean {
        return profilePathData.any { it.path == path }
    }

    private fun findProfilePath(profileId: String): String? {
        if (profilePathData.isEmpty()) refreshPath()
        return profilePathData.firstOrNull { it.id == profileId }?.path
    }

    private fun findAnyProfilePath(profileId: String): String? {
        findProfilePath(profileId)?.let { return it }
        return getScopedProfilePath(profileId)
    }

    private fun getScopedProfilePath(profileId: String): String? {
        val context = getContextOrNull()
        if (context == null) {
            Logging.i(
                "ProfilePathManager",
                "App context not initialized while resolving scoped profile id=$profileId, falling back to default path."
            )
            return null
        }
        return getScopedProfiles(context).firstOrNull { it.id == profileId }?.treeUri
    }

    private fun createNoMediaFile(path: String) {
        val noMediaFile = File(path, ".nomedia")
        if (!noMediaFile.exists()) {
            runCatching { noMediaFile.createNewFile() }
                .onFailure { e ->
                    Logging.e("createNoMedia", "Failed to create .nomedia in $path", e)
                }
        }
    }

    fun save() {
        save(profilePathData)
    }

    fun save(items: List<ProfileItem>) {
        val jsonObject = JsonObject()

        items.forEach { item ->
            if (item.id == "default") return@forEach
            if (item.path.startsWith("content://")) return@forEach

            val profilePathJsonObject = ProfilePathJsonObject(item.title, item.path)
            jsonObject.add(item.id, Tools.GLOBAL_GSON.toJsonTree(profilePathJsonObject))
        }

        runCatching {
            FileWriter(PathManager.FILE_PROFILE_PATH).use { writer ->
                Tools.GLOBAL_GSON.toJson(jsonObject, writer)
            }
        }.onFailure { e ->
            Logging.e("Write Profile", "Failed to write to game path configuration", e)
        }
    }

    fun getScopedProfiles(context: Context): MutableList<ScopedProfileItem> {
        ensureContext(context)
        val prefs = context.getSharedPreferences(SCOPED_STORAGE_PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SCOPED_LOCATIONS, null) ?: return mutableListOf()

        return runCatching {
            val jsonArray = JsonParser.parseString(raw).asJsonArray
            jsonArray.mapNotNull { element ->
                runCatching {
                    val obj = element.asJsonObject
                    ScopedProfileItem(
                        id = obj.get("id").asString,
                        title = obj.get("name").asString,
                        treeUri = obj.get("treeUri").asString
                    )
                }.getOrNull()
            }.toMutableList()
        }.getOrElse {
            Logging.e("ScopedProfiles", "Failed to parse scoped profile list", it)
            mutableListOf()
        }
    }

    fun getScopedProfiles(): MutableList<ScopedProfileItem> {
        val context = getContextOrNull()
        return if (context != null) {
            getScopedProfiles(context)
        } else {
            Logging.i("ProfilePathManager", "App context not initialized for getScopedProfiles(), returning empty list.")
            mutableListOf()
        }
    }

    fun addScopedProfile(context: Context, title: String, treeUri: String): String {
        ensureContext(context)
        val items = getScopedProfiles(context)
        val existing = items.indexOfFirst { it.treeUri == treeUri }

        val id = if (existing >= 0) {
            val oldId = items[existing].id
            items[existing] = items[existing].copy(title = title)
            oldId
        } else {
            val newId = UUID.randomUUID().toString()
            items.add(
                ScopedProfileItem(
                    id = newId,
                    title = title,
                    treeUri = treeUri
                )
            )
            newId
        }

        saveScopedProfiles(context, items)
        return id
    }

    fun renameScopedProfile(context: Context, id: String, newTitle: String): Boolean {
        ensureContext(context)
        val items = getScopedProfiles(context)
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) {
            return false
        }

        items[index] = items[index].copy(title = newTitle)
        saveScopedProfiles(context, items)
        return true
    }

    fun removeScopedProfileById(context: Context, id: String): Boolean {
        ensureContext(context)
        val items = getScopedProfiles(context)
        val removedItem = items.firstOrNull { it.id == id } ?: return false

        val removed = items.removeAll { it.id == id }
        if (removed) {
            saveScopedProfiles(context, items)

            if (AllSettings.launcherProfile.getValue() == removedItem.id) {
                setCurrentPathId("default")
            }
        }

        return removed
    }

    fun removeScopedProfileByUri(context: Context, treeUri: String): Boolean {
        ensureContext(context)
        val items = getScopedProfiles(context)
        val removedItem = items.firstOrNull { it.treeUri == treeUri } ?: return false

        val removed = items.removeAll { it.treeUri == treeUri }
        if (removed) {
            saveScopedProfiles(context, items)

            if (AllSettings.launcherProfile.getValue() == removedItem.id) {
                setCurrentPathId("default")
            }
        }

        return removed
    }

    private fun saveScopedProfiles(context: Context, items: List<ScopedProfileItem>) {
        ensureContext(context)
        val prefs = context.getSharedPreferences(SCOPED_STORAGE_PREFS, Context.MODE_PRIVATE)
        val jsonArray = JsonArray()

        items.forEach { item ->
            jsonArray.add(
                JsonObject().apply {
                    addProperty("id", item.id)
                    addProperty("name", item.title)
                    addProperty("treeUri", item.treeUri)
                }
            )
        }

        runCatching {
            prefs.edit()
                .putString(KEY_SCOPED_LOCATIONS, Tools.GLOBAL_GSON.toJson(jsonArray))
                .apply()
        }.onFailure {
            Logging.e("ScopedProfiles", "Failed to save scoped profile list", it)
        }
    }
}
