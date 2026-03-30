package com.movtery.zalithlauncher.feature.version.install

import android.app.Activity
import com.kdt.mcgui.ProgressLayout
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.event.value.InstallGameEvent
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.feature.version.VersionsManager
import com.movtery.zalithlauncher.task.Task
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper
import net.kdt.pojavlaunch.tasks.AsyncMinecraftDownloader
import net.kdt.pojavlaunch.tasks.MinecraftDownloader
import org.apache.commons.io.FileUtils
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class GameInstaller(
    private val activity: Activity,
    installEvent: InstallGameEvent
) {
    private val realVersion: String = installEvent.minecraftVersion
    private val customVersionName: String = installEvent.customVersionName
    private val taskMap: Map<Addon, InstallTaskItem> = installEvent.taskMap

    private val targetVersionFolder = VersionsManager.getVersionPath(customVersionName)
    private val vanillaVersionFolder = VersionsManager.getVersionPath(realVersion)

    fun installGame() {
        Logging.i("Minecraft Downloader", "Start downloading the version: $realVersion")

        if (taskMap.isNotEmpty()) {
            ProgressKeeper.submitProgress(
                ProgressLayout.INSTALL_RESOURCE,
                0,
                R.string.download_install_download_file,
                0,
                0,
                0
            )
        }

        val mcVersion = AsyncMinecraftDownloader.getListedVersion(realVersion)
        MinecraftDownloader(activity).start(
            mcVersion,
            realVersion,
            object : AsyncMinecraftDownloader.DoneListener {
                override fun onDownloadDone() {
                    Task.runTask {
                        if (taskMap.isEmpty()) {
                            // Vanilla-only install.
                            // If the user chose a custom instance name, copy the downloaded vanilla JSON
                            // into the custom version folder so the custom instance becomes a valid version.
                            if (realVersion != customVersionName && VersionsManager.isVersionExists(realVersion)) {
                                val vanillaJsonFile =
                                    File(vanillaVersionFolder, "${vanillaVersionFolder.name}.json")
                                if (vanillaJsonFile.exists() && vanillaJsonFile.isFile) {
                                    FileUtils.copyFile(
                                        vanillaJsonFile,
                                        File(targetVersionFolder, "$customVersionName.json")
                                    )
                                }
                            }

                            // Vanilla finishes here, so this instance can be selected immediately.
                            VersionsManager.setCurrentVersionAndRefresh(
                                customVersionName,
                                "GameInstaller:vanillaInstallComplete",
                                true
                            )
                            return@runTask null
                        }

                        // Separate mod tasks from the ModLoader task.
                        // Only one ModLoader task is expected at a time.
                        val modTasks: MutableList<InstallTaskItem> = ArrayList()
                        val modLoaderTask = AtomicReference<Pair<Addon, InstallTaskItem>>()

                        taskMap.forEach { (addon, taskItem) ->
                            if (taskItem.isMod) {
                                modTasks.add(taskItem)
                            } else {
                                modLoaderTask.set(Pair(addon, taskItem))
                            }
                        }

                        // Install mods first.
                        modTasks.forEach { task ->
                            Logging.i("Install Version", "Installing Mod: ${task.selectedVersion}")
                            val file = task.task.run(customVersionName)
                            val endTask = task.endTask
                            file?.let { endTask?.endTask(activity, it) }
                        }

                        // Then prepare/install the selected ModLoader.
                        modLoaderTask.get()?.let { taskPair ->
                            ProgressKeeper.submitProgress(
                                ProgressLayout.INSTALL_RESOURCE,
                                0,
                                R.string.mod_download_progress,
                                taskPair.first.addonName
                            )

                            Logging.i(
                                "Install Version",
                                "Installing ModLoader: ${taskPair.second.selectedVersion}"
                            )
                            val file = taskPair.second.task.run(customVersionName)
                            return@runTask Pair(file, taskPair.second)
                        }

                        null
                    }.ended { taskPair ->
                        taskPair?.let { pair ->
                            pair.first?.let {
                                pair.second.endTask?.endTask(activity, it)
                            }
                        }
                        // Do not select the version here for ModLoader installs.
                        // Fabric/Forge/NeoForge/Quilt complete later in JavaGUILauncherActivity,
                        // and VersionsManager pending selection will handle that flow.
                    }.onThrowable { e ->
                        Tools.showErrorRemote(e)
                    }.execute()
                }

                override fun onDownloadFailed(throwable: Throwable) {
                    Tools.showErrorRemote(throwable)
                    if (taskMap.isNotEmpty()) {
                        ProgressLayout.clearProgress(ProgressLayout.INSTALL_RESOURCE)
                    }
                }
            }
        )
    }
}
