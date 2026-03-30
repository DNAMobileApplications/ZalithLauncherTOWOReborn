package com.movtery.zalithlauncher.feature.customprofilepath

data class ProfilePathLocation(
    val rawPath: String,
    val isScoped: Boolean
) {
    fun getGameHomePath(): String = "$rawPath/.minecraft"
    fun getVersionsHomePath(): String = "${getGameHomePath()}/versions"
    fun getLibrariesHomePath(): String = "${getGameHomePath()}/libraries"
    fun getAssetsHomePath(): String = "${getGameHomePath()}/assets"
    fun getResourcesHomePath(): String = "${getGameHomePath()}/resources"
}