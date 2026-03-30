package com.movtery.zalithlauncher.feature.customprofilepath

class ProfilePathHome {
    companion object {
        @JvmStatic
        fun getCurrentLocation(): ProfilePathLocation {
            val rawPath = ProfilePathManager.getCurrentPath()
            return ProfilePathLocation(
                rawPath = rawPath,
                isScoped = rawPath.startsWith("content://")
            )
        }

        @JvmStatic
        fun isScopedStorage(): Boolean {
            return getCurrentLocation().isScoped
        }

        @JvmStatic
        fun getGameHome(): String {
            return getCurrentLocation().getGameHomePath()
        }

        @JvmStatic
        fun getVersionsHome(): String {
            return getCurrentLocation().getVersionsHomePath()
        }

        @JvmStatic
        fun getLibrariesHome(): String {
            return getCurrentLocation().getLibrariesHomePath()
        }

        @JvmStatic
        fun getAssetsHome(): String {
            return getCurrentLocation().getAssetsHomePath()
        }

        @JvmStatic
        fun getResourcesHome(): String {
            return getCurrentLocation().getResourcesHomePath()
        }
    }
}