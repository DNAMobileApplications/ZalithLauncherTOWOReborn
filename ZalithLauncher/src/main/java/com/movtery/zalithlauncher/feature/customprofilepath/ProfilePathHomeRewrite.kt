package com.movtery.zalithlauncher.feature.customprofilepath

import java.io.File

/**
 * Example of how to adapt ProfilePathHome without rewriting the entire codebase.
 *
 * Existing file-based classes can keep calling getVersionsHome(), getLibrariesHome(), etc.
 * For scoped profiles, these methods should resolve inside the active workspace.
 */
object ProfilePathHomeRewrite {
    /**
     * Replace this with however your real app resolves the current profile.
     */
    var currentProfileProvider: (() -> ProfileItemNew)? = null

    fun getMinecraftHome(): String {
        val profile = requireNotNull(currentProfileProvider?.invoke()) {
            "currentProfileProvider is not set"
        }

        val workspace = ProfileWorkspaceManager.getActiveWorkspaceOrNull()
        if (profile.storageType == StorageType.SCOPED && workspace != null && workspace.profileId == profile.id) {
            return workspace.minecraftDir.absolutePath
        }

        return when (profile.storageType) {
            StorageType.LEGACY -> File(profile.requireLegacyPath(), ".minecraft").absolutePath
            StorageType.SCOPED -> File(profile.requireLegacyPathOrFallback(), ".minecraft").absolutePath
        }
    }

    fun getVersionsHome(): String = File(getMinecraftHome(), "versions").absolutePath
    fun getLibrariesHome(): String = File(getMinecraftHome(), "libraries").absolutePath
    fun getAssetsHome(): String = File(getMinecraftHome(), "assets").absolutePath
    fun getResourcesHome(): String = File(getMinecraftHome(), "resources").absolutePath

    /**
     * Optional fallback for app startup before workspace preparation.
     * You can replace this with a dedicated app-private scratch root.
     */
    private fun ProfileItemNew.requireLegacyPathOrFallback(): String {
        return legacyPath ?: error(
            "Scoped profile '$name' needs either an active workspace or an app-private fallback root"
        )
    }
}
