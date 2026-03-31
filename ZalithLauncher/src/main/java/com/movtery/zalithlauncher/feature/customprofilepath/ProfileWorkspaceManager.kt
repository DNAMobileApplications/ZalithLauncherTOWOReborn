package com.movtery.zalithlauncher.feature.customprofilepath

import android.content.Context
import org.apache.commons.io.FileUtils
import java.io.File

/**
 * Central bridge between persisted profile storage and file-based install logic.
 *
 * Legacy profiles:
 * - working directory is the real .minecraft location
 * - no prepare/commit copy required
 *
 * Scoped profiles:
 * - persistent location is a tree Uri
 * - working directory is app-private
 * - prepare copies from SAF into workspace
 * - commit copies workspace back into SAF
 */
object ProfileWorkspaceManager {
    @Volatile
    private var currentWorkspace: ProfileWorkspace? = null

    fun isScopedProfile(profile: ProfileItemNew): Boolean {
        return profile.storageType == StorageType.SCOPED
    }

    fun prepareWorkspace(context: Context, profile: ProfileItemNew): ProfileWorkspace {
        val workspace = when (profile.storageType) {
            StorageType.LEGACY -> {
                val root = File(profile.requireLegacyPath())
                val mcDir = File(root, ".minecraft")
                mcDir.mkdirs()
                ProfileWorkspace(profile.id, root, mcDir)
            }
            StorageType.SCOPED -> {
                val root = File(context.filesDir, "scoped_workspaces/${profile.id}")
                val mcDir = File(root, ".minecraft")

                FileUtils.deleteQuietly(root)
                mcDir.mkdirs()

                val tree = ScopedStorageTools.getOrCreateDocumentTree(context, profile.requireTreeUri())
                val minecraftDoc = ScopedStorageTools.getOrCreateChildDirectory(tree, ".minecraft")
                ScopedStorageTools.copyDocumentTreeToFile(context, minecraftDoc, mcDir)

                ProfileWorkspace(profile.id, root, mcDir)
            }
        }

        ensureStandardDirectories(workspace)
        currentWorkspace = workspace
        return workspace
    }

    fun commitWorkspace(context: Context, profile: ProfileItemNew) {
        if (profile.storageType != StorageType.SCOPED) return
        val workspace = requireWorkspace(profile.id)

        val tree = ScopedStorageTools.getOrCreateDocumentTree(context, profile.requireTreeUri())
        val minecraftDoc = ScopedStorageTools.getOrCreateChildDirectory(tree, ".minecraft")

        ScopedStorageTools.clearDocumentChildren(minecraftDoc)
        ScopedStorageTools.copyFileTreeToDocument(context, workspace.minecraftDir, minecraftDoc)
    }

    fun cleanupWorkspace(profile: ProfileItemNew) {
        val workspace = currentWorkspace ?: return
        if (workspace.profileId != profile.id) return

        if (profile.storageType == StorageType.SCOPED) {
            FileUtils.deleteQuietly(workspace.rootDir)
        }
        currentWorkspace = null
    }

    fun getActiveWorkspaceOrNull(): ProfileWorkspace? = currentWorkspace

    fun requireWorkspace(profileId: String): ProfileWorkspace {
        return currentWorkspace?.takeIf { it.profileId == profileId }
            ?: error("No prepared workspace for profileId=$profileId")
    }

    private fun ensureStandardDirectories(workspace: ProfileWorkspace) {
        workspace.minecraftDir.mkdirs()
        workspace.versionsDir.mkdirs()
        workspace.librariesDir.mkdirs()
        workspace.assetsDir.mkdirs()
        workspace.resourcesDir.mkdirs()
    }
}
