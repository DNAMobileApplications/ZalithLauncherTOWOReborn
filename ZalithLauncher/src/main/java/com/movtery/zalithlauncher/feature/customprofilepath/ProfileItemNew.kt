package com.movtery.zalithlauncher.feature.customprofilepath

data class ProfileItemNew(
    val id: String,
    val name: String,
    val storageType: StorageType,
    val legacyPath: String? = null,
    val treeUri: String? = null
) {
    fun requireLegacyPath(): String {
        return legacyPath?.takeIf { it.isNotBlank() }
            ?: error("Profile '$name' does not have a legacy path")
    }

    fun requireTreeUri(): String {
        return treeUri?.takeIf { it.isNotBlank() }
            ?: error("Profile '$name' does not have a tree Uri")
    }
}