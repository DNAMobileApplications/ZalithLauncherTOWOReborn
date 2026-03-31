package com.movtery.zalithlauncher.feature.version

import android.content.Context
import com.movtery.zalithlauncher.InfoDistributor
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.event.single.RefreshVersionsEvent
import com.movtery.zalithlauncher.event.single.RefreshVersionsEvent.MODE.END
import com.movtery.zalithlauncher.event.single.RefreshVersionsEvent.MODE.START
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathHome
import com.movtery.zalithlauncher.feature.customprofilepath.ScopedVersionsManager
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.feature.version.favorites.FavoritesVersionUtils
import com.movtery.zalithlauncher.feature.version.utils.VersionInfoUtils
import com.movtery.zalithlauncher.task.Task
import com.movtery.zalithlauncher.task.TaskExecutors
import com.movtery.zalithlauncher.ui.dialog.EditTextDialog
import com.movtery.zalithlauncher.utils.ZHTools
import com.movtery.zalithlauncher.utils.file.FileTools
import com.movtery.zalithlauncher.utils.stringutils.SortStrings
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.kdt.pojavlaunch.Tools
import org.apache.commons.io.FileUtils
import org.greenrobot.eventbus.EventBus
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

object VersionsManager {
    private val versions = CopyOnWriteArrayList<Version>()

    lateinit var currentGameInfo: CurrentGameInfo
        private set

    private val coroutineScope = CoroutineScope(Dispatchers.IO + CoroutineName("VersionsManager"))
    private val refreshMutex = Mutex()

    private var isRefreshing: Boolean = false
    private var lastRefreshTime: Long = 0L

    @Volatile
    private var pendingSelectedVersionName: String? = null

    @JvmStatic
    fun canRefresh(): Boolean {
        return !isRefreshing && ZHTools.getCurrentTimeMillis() - lastRefreshTime > 500
    }

    fun getVersions(): List<Version> = versions.toList()

    fun isVersionExists(versionName: String, checkJson: Boolean = false): Boolean {
        return if (ProfilePathHome.isScopedStorage()) {
            ScopedVersionsManager.isVersionExists(versionName, checkJson)
        } else {
            val folder = File(ProfilePathHome.getVersionsHome(), versionName)
            if (checkJson) {
                File(folder, "${folder.name}.json").exists()
            } else {
                folder.exists()
            }
        }
    }

    fun setPendingSelectedVersion(versionName: String?) {
        pendingSelectedVersionName = versionName?.takeIf { it.isNotBlank() }
        Logging.i("VersionsManager", "Pending selected version set to: $pendingSelectedVersionName")
    }

    fun clearPendingSelectedVersion() {
        pendingSelectedVersionName = null
    }

    fun refresh(tag: String, refreshVersionInfo: Boolean = false) {
        Logging.i("VersionsManager", "$tag initiated a version refresh task")

        coroutineScope.launch {
            refreshMutex.withLock {
                lastRefreshTime = ZHTools.getCurrentTimeMillis()
                handleRefreshOperation(refreshVersionInfo)
            }
        }
    }

    private fun handleRefreshOperation(refreshVersionInfo: Boolean) {
        isRefreshing = true
        EventBus.getDefault().post(RefreshVersionsEvent(START))

        try {
            versions.clear()

            if (ProfilePathHome.isScopedStorage()) {
                versions.addAll(ScopedVersionsManager.loadVersions(refreshVersionInfo))
            } else {
                val versionsHome = ProfilePathHome.getVersionsHome()
                File(versionsHome).listFiles()?.forEach { versionFile ->
                    runCatching {
                        processVersionFile(versionsHome, versionFile, refreshVersionInfo)
                    }.onFailure { throwable ->
                        Logging.e(
                            "VersionsManager",
                            "Failed to process version folder: ${versionFile.absolutePath}",
                            throwable
                        )
                    }
                }
            }

            versions.sortWith { first, second ->
                var result = -SortStrings.compareClassVersions(
                    first.getVersionInfo()?.minecraftVersion ?: first.getVersionName(),
                    second.getVersionInfo()?.minecraftVersion ?: second.getVersionName()
                )

                if (result == 0) {
                    result = SortStrings.compareChar(
                        first.getVersionName(),
                        second.getVersionName()
                    )
                }

                result
            }

            currentGameInfo = CurrentGameInfo.refreshCurrentInfo()
            applyPendingSelectionIfAvailable()

            EventBus.getDefault().post(RefreshVersionsEvent(END))
        } finally {
            isRefreshing = false
        }
    }

    private fun applyPendingSelectionIfAvailable() {
        val pendingName = pendingSelectedVersionName ?: return
        val pendingVersion = getVersion(pendingName)

        if (pendingVersion != null) {
            Logging.i("VersionsManager", "Applying pending selected version: $pendingName")
            saveCurrentVersion(pendingName)
            pendingSelectedVersionName = null
        } else {
            Logging.i("VersionsManager", "Pending selected version not found yet: $pendingName")
        }
    }

    private fun processVersionFile(
        versionsHome: String,
        versionFile: File,
        refreshVersionInfo: Boolean
    ) {
        if (!versionFile.exists() || !versionFile.isDirectory) {
            return
        }

        var isVersion = false
        val jsonFile = File(versionFile, "${versionFile.name}.json")

        if (jsonFile.exists() && jsonFile.isFile) {
            isVersion = true

            val versionInfoFile = File(getZalithVersionPath(versionFile), "VersionInfo.json")
            if (refreshVersionInfo) {
                FileUtils.deleteQuietly(versionInfoFile)
            }
            if (!versionInfoFile.exists()) {
                VersionInfoUtils.parseJson(jsonFile)?.save(versionFile)
            }
        }

        val versionConfig = VersionConfig.parseConfig(versionFile)
        val version = Version(
            versionsHome,
            versionFile.absolutePath,
            versionConfig,
            isVersion
        )

        versions.add(version)

        Logging.i(
            "VersionsManager",
            "Identified and added version: ${version.getVersionName()}, " +
                    "Path: (${version.getVersionPath()}), " +
                    "Info: ${version.getVersionInfo()?.getInfoString()}"
        )
    }

    fun getCurrentVersion(): Version? {
        if (versions.isEmpty()) {
            return null
        }

        applyPendingSelectionIfAvailable()

        fun getFirstValidVersion(): Version? {
            return versions.find { it.isValid() }?.apply {
                saveCurrentVersion(getVersionName())
            }
        }

        return runCatching {
            val versionName = currentGameInfo.version
            getVersion(versionName) ?: run {
                Logging.i("VersionsManager", "Saved current version not found: $versionName")
                getFirstValidVersion()
            }
        }.getOrElse { throwable ->
            Logging.e("Get Current Version", Tools.printToString(throwable))
            getFirstValidVersion()
        }
    }

    fun checkVersionExistsByName(versionName: String?): Boolean {
        return versionName?.let { name ->
            versions.any { it.getVersionName() == name }
        } ?: false
    }

    fun getZalithVersionPath(version: Version): File {
        return File(version.getVersionPath(), InfoDistributor.LAUNCHER_NAME)
    }

    fun getZalithVersionPath(folder: File): File {
        return File(folder, InfoDistributor.LAUNCHER_NAME)
    }

    fun getZalithVersionPath(name: String): File {
        return File(getVersionPath(name), InfoDistributor.LAUNCHER_NAME)
    }

    fun getVersionIconFile(version: Version): File {
        return File(getZalithVersionPath(version), "VersionIcon.png")
    }

    fun getVersionIconFile(name: String): File {
        return File(getZalithVersionPath(name), "VersionIcon.png")
    }

    fun getVersionPath(name: String): File {
        return if (ProfilePathHome.isScopedStorage()) {
            val context = ScopedVersionsManager.getInitializedContextOrNull()
            File(
                context?.cacheDir ?: File("."),
                "scoped_loader_root/.minecraft/versions/$name"
            )
        } else {
            File(ProfilePathHome.getVersionsHome(), name)
        }
    }
    fun saveCurrentVersion(versionName: String) {
        runCatching {
            if (!::currentGameInfo.isInitialized) {
                currentGameInfo = CurrentGameInfo.refreshCurrentInfo()
            }

            currentGameInfo.apply {
                version = versionName
                saveCurrentInfo()
            }
        }.onFailure { throwable ->
            Logging.e("Save Current Version", Tools.printToString(throwable))
        }
    }

    fun setCurrentVersionAndRefresh(
        versionName: String,
        tag: String,
        refreshVersionInfo: Boolean = false
    ) {
        clearPendingSelectedVersion()
        saveCurrentVersion(versionName)
        refresh(tag, refreshVersionInfo)
    }

    private fun validateVersionName(
        context: Context,
        newName: String,
        versionInfo: VersionInfo?
    ): String? {
        return when {
            isVersionExists(newName, true) -> {
                context.getString(R.string.version_install_exists)
            }

            versionInfo?.loaderInfo?.takeIf { it.isNotEmpty() }?.let {
                newName == versionInfo.minecraftVersion
            } ?: false -> {
                context.getString(R.string.version_install_cannot_use_mc_name)
            }

            else -> null
        }
    }

    fun openRenameDialog(
        context: Context,
        version: Version,
        beforeRename: (() -> Unit)? = null
    ) {
        EditTextDialog.Builder(context)
            .setTitle(R.string.version_manager_rename)
            .setEditText(version.getVersionName())
            .setAsRequired()
            .setConfirmListener { editText, _ ->
                val newName = editText.text.toString()

                if (newName == version.getVersionName()) {
                    return@setConfirmListener true
                }

                if (FileTools.isFilenameInvalid(editText)) {
                    return@setConfirmListener false
                }

                val error = validateVersionName(context, newName, version.getVersionInfo())
                if (error != null) {
                    editText.error = error
                    return@setConfirmListener false
                }

                beforeRename?.invoke()
                renameVersion(version, newName)
                true
            }
            .showDialog()
    }

    private fun renameVersion(version: Version, name: String) {
        if (ProfilePathHome.isScopedStorage()) {
            val currentVersionName = getCurrentVersion()?.getVersionName()

            if (version.getVersionName() == currentVersionName) {
                saveCurrentVersion(name)
            }

            FavoritesVersionUtils.renameVersion(version.getVersionName(), name)
            ScopedVersionsManager.renameVersion(version, name)
            refresh("VersionsManager:renameVersion(scoped)")
            return
        }

        val currentVersionName = getCurrentVersion()?.getVersionName()

        if (version.getVersionName() == currentVersionName) {
            saveCurrentVersion(name)
        }

        FavoritesVersionUtils.renameVersion(version.getVersionName(), name)

        val versionFolder = version.getVersionPath()
        val renamedFolder = File(ProfilePathHome.getVersionsHome(), name)

        FileUtils.deleteQuietly(renamedFolder)

        val originalName = versionFolder.name

        FileTools.renameFile(versionFolder, renamedFolder)

        val originalJsonFile = File(renamedFolder, "$originalName.json")
        val originalJarFile = File(renamedFolder, "$originalName.jar")
        val renamedJsonFile = File(renamedFolder, "$name.json")
        val renamedJarFile = File(renamedFolder, "$name.jar")

        FileTools.renameFile(originalJsonFile, renamedJsonFile)
        FileTools.renameFile(originalJarFile, renamedJarFile)

        FileUtils.deleteQuietly(versionFolder)

        refresh("VersionsManager:renameVersion")
    }

    fun openCopyDialog(context: Context, version: Version) {
        val dialog = ZHTools.createTaskRunningDialog(context)

        EditTextDialog.Builder(context)
            .setTitle(R.string.version_manager_copy)
            .setMessage(R.string.version_manager_copy_tip)
            .setCheckBoxText(R.string.version_manager_copy_all)
            .setShowCheckBox(true)
            .setEditText(version.getVersionName())
            .setAsRequired()
            .setConfirmListener { editText, checked ->
                val newName = editText.text.toString()

                if (newName == version.getVersionName()) {
                    return@setConfirmListener true
                }

                if (FileTools.isFilenameInvalid(editText)) {
                    return@setConfirmListener false
                }

                val error = validateVersionName(context, newName, version.getVersionInfo())
                if (error != null) {
                    editText.error = error
                    return@setConfirmListener false
                }

                Task.runTask {
                    copyVersion(version, newName, checked)
                }.beforeStart(TaskExecutors.getAndroidUI()) {
                    dialog.show()
                }.onThrowable { throwable ->
                    Tools.showErrorRemote(throwable)
                }.finallyTask(TaskExecutors.getAndroidUI()) {
                    dialog.dismiss()
                    setCurrentVersionAndRefresh(newName, "VersionsManager:openCopyDialog")
                }.execute()

                true
            }
            .showDialog()
    }

    private fun copyVersion(version: Version, name: String, copyAllFile: Boolean) {
        if (ProfilePathHome.isScopedStorage()) {
            ScopedVersionsManager.copyVersion(version, name, copyAllFile)
            return
        }

        val versionsFolder = version.getVersionsFolder()
        val newVersionFolder = File(versionsFolder, name)
        val originalName = version.getVersionName()

        val newJsonFile = File(newVersionFolder, "$name.json")
        val newJarFile = File(newVersionFolder, "$name.jar")
        val originalVersionFolder = version.getVersionPath()

        if (copyAllFile) {
            FileUtils.copyDirectory(originalVersionFolder, newVersionFolder)

            val copiedJsonFile = File(newVersionFolder, "$originalName.json")
            val copiedJarFile = File(newVersionFolder, "$originalName.jar")
            if (copiedJsonFile.exists()) {
                copiedJsonFile.renameTo(newJsonFile)
            }
            if (copiedJarFile.exists()) {
                copiedJarFile.renameTo(newJarFile)
            }
        } else {
            val originalJsonFile = File(originalVersionFolder, "$originalName.json")
            val originalJarFile = File(originalVersionFolder, "$originalName.jar")

            newVersionFolder.mkdirs()

            if (originalJsonFile.exists()) {
                originalJsonFile.copyTo(newJsonFile)
            }
            if (originalJarFile.exists()) {
                originalJarFile.copyTo(newJarFile)
            }
        }

        version.getVersionConfig().copy().let { config ->
            config.setVersionPath(newVersionFolder)
            config.setIsolationType(VersionConfig.IsolationType.ENABLE)
            config.saveWithThrowable()
        }
    }

    private fun getVersion(name: String?): Version? {
        return name?.let { versionName ->
            versions.find { it.getVersionName() == versionName }?.takeIf { it.isValid() }
        }
    }
}
