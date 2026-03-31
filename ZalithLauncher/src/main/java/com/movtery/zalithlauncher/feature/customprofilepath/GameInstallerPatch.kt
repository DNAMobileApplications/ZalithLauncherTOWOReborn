package com.movtery.zalithlauncher.feature.customprofilepath

import android.app.Activity
import com.movtery.zalithlauncher.feature.version.install.GameInstaller

/**
 * Integration example only.
 *
 * Wrap the existing GameInstaller lifecycle with workspace prepare/commit.
 */
object GameInstallerPatch {
    fun installWithWorkspace(
        activity: Activity,
        currentProfile: ProfileItemNew,
        install: () -> Unit,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Throwable) -> Unit)? = null
    ) {
        try {
            ProfileWorkspaceManager.prepareWorkspace(activity.applicationContext, currentProfile)
            install.invoke()
            ProfileWorkspaceManager.commitWorkspace(activity.applicationContext, currentProfile)
            onSuccess?.invoke()
        } catch (t: Throwable) {
            onFailure?.invoke(t)
            throw t
        } finally {
            ProfileWorkspaceManager.cleanupWorkspace(currentProfile)
        }
    }

    @Suppress("unused")
    fun exampleUsage(activity: Activity, profile: ProfileItemNew, installer: GameInstaller) {
        installWithWorkspace(
            activity = activity,
            currentProfile = profile,
            install = { installer.installGame() }
        )
    }
}
