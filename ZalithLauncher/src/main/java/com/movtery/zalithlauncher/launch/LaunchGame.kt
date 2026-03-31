package com.movtery.zalithlauncher.launch

import android.app.Activity
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.kdt.mcgui.ProgressLayout
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.event.single.AccountUpdateEvent
import com.movtery.zalithlauncher.feature.accounts.AccountType
import com.movtery.zalithlauncher.feature.accounts.AccountUtils
import com.movtery.zalithlauncher.feature.accounts.AccountsManager
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.feature.version.Version
import com.movtery.zalithlauncher.renderer.Renderers
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.setting.AllStaticSettings
import com.movtery.zalithlauncher.setting.Settings
import com.movtery.zalithlauncher.support.touch_controller.ControllerProxy
import com.movtery.zalithlauncher.task.TaskExecutors
import com.movtery.zalithlauncher.ui.dialog.LifecycleAwareTipDialog
import com.movtery.zalithlauncher.ui.dialog.TipDialog
import com.movtery.zalithlauncher.utils.ZHTools
import com.movtery.zalithlauncher.utils.http.NetworkUtils
import com.movtery.zalithlauncher.utils.stringutils.StringUtils
import net.kdt.pojavlaunch.Architecture
import net.kdt.pojavlaunch.JMinecraftVersionList
import net.kdt.pojavlaunch.Logger
import net.kdt.pojavlaunch.MinecraftGLSurface
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.authenticator.microsoft.PresentedException
import net.kdt.pojavlaunch.lifecycle.ContextAwareDoneListener
import net.kdt.pojavlaunch.multirt.MultiRTUtils
import net.kdt.pojavlaunch.plugins.FFmpegPlugin
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper
import net.kdt.pojavlaunch.services.GameService
import net.kdt.pojavlaunch.tasks.AsyncMinecraftDownloader
import net.kdt.pojavlaunch.tasks.MinecraftDownloader
import net.kdt.pojavlaunch.utils.JREUtils
import net.kdt.pojavlaunch.value.MinecraftAccount
import org.greenrobot.eventbus.EventBus

object LaunchGame {

    @JvmStatic
    fun preLaunch(context: Context, version: Version) {
        val networkAvailable = NetworkUtils.isNetworkAvailable(context)

        if (!networkAvailable) {
            // Network is unavailable, so online login cannot be performed.
            // Still allow launch by temporarily using an offline account.
            Toast.makeText(
                context,
                context.getString(R.string.account_login_no_network),
                Toast.LENGTH_SHORT
            ).show()

            launchDownloadAndStart(context, version, networkAvailable, setOfflineAccount = true)
            return
        }

        if (AccountUtils.isNoLoginRequired(AccountsManager.currentAccount)) {
            launchDownloadAndStart(context, version, networkAvailable, setOfflineAccount = false)
            return
        }

        val currentAccount = AccountsManager.currentAccount ?: run {
            setGameProgress(false)
            return
        }

        AccountsManager.performLogin(
            context,
            currentAccount,
            { _ ->
                EventBus.getDefault().post(AccountUpdateEvent())
                TaskExecutors.runInUIThread {
                    Toast.makeText(
                        context,
                        context.getString(R.string.account_login_done),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                // Login finished, start the game.
                launchDownloadAndStart(context, version, networkAvailable, setOfflineAccount = false)
            },
            { exception ->
                val errorMessage = if (exception is PresentedException) {
                    exception.toString(context)
                } else {
                    exception.message
                }

                TaskExecutors.runInUIThread {
                    TipDialog.Builder(context)
                        .setTitle(R.string.generic_error)
                        .setMessage("${context.getString(R.string.account_login_skip)}\r\n$errorMessage")
                        .setWarning()
                        .setConfirmClickListener {
                            launchDownloadAndStart(
                                context,
                                version,
                                networkAvailable,
                                setOfflineAccount = true
                            )
                        }
                        .setCenterMessage(false)
                        .showDialog()
                }

                setGameProgress(false)
            }
        )

        setGameProgress(true)
    }

    private fun launchDownloadAndStart(
        context: Context,
        version: Version,
        networkAvailable: Boolean,
        setOfflineAccount: Boolean
    ) {
        version.offlineAccountLogin = setOfflineAccount

        val versionName = version.getVersionName()
        val mcVersion = AsyncMinecraftDownloader.getListedVersion(versionName)
        val listener = ContextAwareDoneListener(context, version)

        // If the network is unavailable, skip downloading and launch directly.
        if (!networkAvailable) {
            listener.onDownloadDone()
        } else {
            MinecraftDownloader(context).start(mcVersion, versionName, listener)
        }
    }

    private fun setGameProgress(show: Boolean) {
        if (show) {
            ProgressKeeper.submitProgress(
                ProgressLayout.CHECKING_MODS,
                0,
                R.string.mod_check_progress_message,
                0,
                0,
                0
            )
            ProgressKeeper.submitProgress(
                ProgressLayout.DOWNLOAD_MINECRAFT,
                0,
                R.string.newdl_downloading_game_files,
                0,
                0,
                0
            )
        } else {
            ProgressLayout.clearProgress(ProgressLayout.DOWNLOAD_MINECRAFT)
            ProgressLayout.clearProgress(ProgressLayout.CHECKING_MODS)
        }
    }

    @Throws(Throwable::class)
    @JvmStatic
    fun runGame(
        activity: AppCompatActivity,
        minecraftVersion: Version,
        version: JMinecraftVersionList.Version
    ) {
        ensureRendererIsValid(activity)

        var account = AccountsManager.currentAccount!!
        if (minecraftVersion.offlineAccountLogin) {
            account = MinecraftAccount().apply {
                username = account.username
                accountType = AccountType.LOCAL.type
            }
        }

        val customArgs = minecraftVersion.getJavaArgs().takeIf { it.isNotBlank() } ?: ""
        val javaRuntime = resolveRuntime(
            activity,
            minecraftVersion,
            version.javaVersion?.majorVersion ?: 8
        )
        val gameDir = minecraftVersion.getGameDir()

        Tools.startOldLegacy4JMitigation(activity, gameDir)
        Tools.startControllableMitigation(activity, gameDir)

        logLaunchInfo(
            minecraftVersion = minecraftVersion,
            javaArguments = customArgs.ifBlank { "NONE" },
            javaRuntime = javaRuntime,
            account = account
        )

        minecraftVersion.modCheckResult?.let { modCheckResult ->
            if (modCheckResult.hasTouchController) {
                Logger.appendToLog(
                    "Mod Perception: TouchController mod found, enabling controller proxy automatically."
                )
                ControllerProxy.startProxy(activity)
                AllStaticSettings.useControllerProxy = true
            }

            if (modCheckResult.hasSodiumOrEmbeddium) {
                Logger.appendToLog(
                    "Mod Perception: Sodium or Embeddium found, disable-warning tool may be loaded later."
                )
            }
            if (modCheckResult.isLegacy4j) {
                Logger.appendToLog(
                    "Mod Perception: Legacy4J mod found, disable-warning tool maybe loaded later."
                )
                Settings.Manager.put(AllSettings.gamepadSdlPassthru.key, true).save()
                MinecraftGLSurface.sdlEnabled = true;
            }
        }

        JREUtils.redirectAndPrintJRELog()
        launchJvm(activity, account, minecraftVersion, javaRuntime, customArgs)

        // We usually block in the launch call above even if the game crashes,
        // but reset this anyway to be safe.
        GameService.setActive(false)
    }

    private fun ensureRendererIsValid(activity: AppCompatActivity) {
        if (!Renderers.isCurrentRendererValid()) {
            Renderers.setCurrentRenderer(activity, AllSettings.renderer.getValue())
        }
    }

    private fun resolveRuntime(
        activity: Activity,
        version: Version,
        targetJavaVersion: Int
    ): String {
        val versionRuntime = version.getJavaDir()
            .takeIf { it.isNotEmpty() && it.startsWith(Tools.LAUNCHERPROFILES_RTPREFIX) }
            ?.removePrefix(Tools.LAUNCHERPROFILES_RTPREFIX)
            ?: ""

        if (versionRuntime.isNotEmpty()) {
            return versionRuntime
        }

        // If no Java runtime is selected for this version, choose one automatically.
        var runtime = AllSettings.defaultRuntime.getValue()
        val pickedRuntime = MultiRTUtils.read(runtime)

        if (pickedRuntime.javaVersion == 0 || pickedRuntime.javaVersion < targetJavaVersion) {
            runtime = MultiRTUtils.getNearestJreName(targetJavaVersion) ?: run {
                activity.runOnUiThread {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.game_autopick_runtime_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return runtime
            }
        }

        return runtime
    }

    private fun logLaunchInfo(
        minecraftVersion: Version,
        javaArguments: String,
        javaRuntime: String,
        account: MinecraftAccount
    ) {
        val mcInfo = minecraftVersion.getVersionInfo()?.getInfoString()
            ?: minecraftVersion.getVersionName()

        Logger.appendToLog("--------- Start launching the game")
        Logger.appendToLog("Info: Launcher version: ${ZHTools.getVersionName()} (${ZHTools.getVersionCode()})")
        Logger.appendToLog("Info: Architecture: ${Architecture.archAsString(Tools.DEVICE_ARCHITECTURE)}")
        Logger.appendToLog("Info: Device model: ${StringUtils.insertSpace(Build.MANUFACTURER, Build.MODEL)}")
        Logger.appendToLog("Info: API version: ${Build.VERSION.SDK_INT}")
        Logger.appendToLog("Info: Renderer: ${Renderers.getCurrentRenderer().getRendererName()}")
        Logger.appendToLog("Info: Selected Minecraft version: ${minecraftVersion.getVersionName()}")
        Logger.appendToLog("Info: Minecraft info: $mcInfo")
        Logger.appendToLog("Info: Game path: ${minecraftVersion.getGameDir().absolutePath} (Isolation: ${minecraftVersion.isIsolation()})")
        Logger.appendToLog("Info: Custom Java arguments: $javaArguments")
        Logger.appendToLog("Info: Java runtime: $javaRuntime")
        Logger.appendToLog("Info: Account: ${account.username} (${account.accountType})")
        Logger.appendToLog("---------\r\n")
    }

    @Throws(Throwable::class)
    @JvmStatic
    private fun launchJvm(
        activity: AppCompatActivity,
        account: MinecraftAccount,
        minecraftVersion: Version,
        javaRuntime: String,
        customArgs: String
    ) {
        warnIfMemoryIsInsufficient(activity)

        val runtime = MultiRTUtils.forceReread(javaRuntime)
        val versionInfo = Tools.getVersionInfo(minecraftVersion)
        val gameDirPath = minecraftVersion.getGameDir()

        // Pre-launch preparation.
        Tools.disableSplash(gameDirPath)
        val launchClassPath = Tools.generateLaunchClassPath(versionInfo, minecraftVersion)

        val launchArgs = LaunchArgs(
            account,
            gameDirPath,
            minecraftVersion,
            versionInfo,
            minecraftVersion.getVersionName(),
            runtime,
            launchClassPath
        ).getAllArgs()

        FFmpegPlugin.discover(activity)
        JREUtils.launchWithUtils(activity, runtime, minecraftVersion, launchArgs, customArgs)
    }

    private fun warnIfMemoryIsInsufficient(activity: AppCompatActivity) {
        var freeDeviceMemory = Tools.getFreeDeviceMemory(activity)
        val freeAddressSpace = if (Architecture.is32BitsDevice()) {
            Tools.getMaxContinuousAddressSpaceSize()
        } else {
            -1
        }

        Logging.i("MemStat", "Free RAM: $freeDeviceMemory Addressable: $freeAddressSpace")

        val stringId = if (freeDeviceMemory > freeAddressSpace && freeAddressSpace != -1) {
            freeDeviceMemory = freeAddressSpace
            R.string.address_memory_warning_msg
        } else {
            R.string.memory_warning_msg
        }

        if (AllSettings.ramAllocation.value.getValue() > freeDeviceMemory) {
            val builder = TipDialog.Builder(activity)
                .setTitle(R.string.generic_warning)
                .setMessage(
                    activity.getString(
                        stringId,
                        freeDeviceMemory,
                        AllSettings.ramAllocation.value.getValue()
                    )
                )
                .setWarning()
                .setCenterMessage(false)
                .setShowCancel(false)

            if (LifecycleAwareTipDialog.haltOnDialog(activity.lifecycle, builder)) {
                return
            }
        }
    }
}