package net.kdt.pojavlaunch;

import static com.movtery.zalithlauncher.launch.LaunchGame.preLaunch;
import static net.kdt.pojavlaunch.Tools.currentDisplayMetrics;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.View;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.palette.graphics.Palette;

import com.kdt.mcgui.ProgressLayout;
import com.movtery.anim.AnimPlayer;
import com.movtery.anim.animations.Animations;
import com.movtery.zalithlauncher.InfoDistributor;
import com.movtery.zalithlauncher.R;
import com.movtery.zalithlauncher.context.ContextExecutor;
import com.movtery.zalithlauncher.databinding.ActivityLauncherBinding;
import com.movtery.zalithlauncher.event.single.LaunchGameEvent;
import com.movtery.zalithlauncher.event.single.MainBackgroundChangeEvent;
import com.movtery.zalithlauncher.event.single.PageOpacityChangeEvent;
import com.movtery.zalithlauncher.event.single.SwapToLoginEvent;
import com.movtery.zalithlauncher.event.sticky.MinecraftVersionValueEvent;
import com.movtery.zalithlauncher.event.value.AddFragmentEvent;
import com.movtery.zalithlauncher.event.value.DownloadProgressKeyEvent;
import com.movtery.zalithlauncher.event.value.InstallGameEvent;
import com.movtery.zalithlauncher.event.value.InstallLocalModpackEvent;
import com.movtery.zalithlauncher.event.value.LocalLoginEvent;
import com.movtery.zalithlauncher.event.value.MicrosoftLoginEvent;
import com.movtery.zalithlauncher.event.value.OtherLoginEvent;
import com.movtery.zalithlauncher.feature.accounts.AccountType;
import com.movtery.zalithlauncher.feature.accounts.AccountsManager;
import com.movtery.zalithlauncher.feature.accounts.LocalAccountUtils;
import com.movtery.zalithlauncher.feature.background.BackgroundManager;
import com.movtery.zalithlauncher.feature.background.BackgroundType;
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathManager;
import com.movtery.zalithlauncher.feature.customprofilepath.ScopedVersionsManager;
import com.movtery.zalithlauncher.feature.download.item.ModLoaderWrapper;
import com.movtery.zalithlauncher.feature.log.Logging;
import com.movtery.zalithlauncher.feature.mod.modpack.install.InstallExtra;
import com.movtery.zalithlauncher.feature.mod.modpack.install.InstallLocalModPack;
import com.movtery.zalithlauncher.feature.mod.modpack.install.ModPackInfo;
import com.movtery.zalithlauncher.feature.mod.modpack.install.ModPackUtils;
import com.movtery.zalithlauncher.feature.notice.CheckNewNotice;
import com.movtery.zalithlauncher.feature.notice.NoticeInfo;
import com.movtery.zalithlauncher.feature.update.UpdateUtils;
import com.movtery.zalithlauncher.feature.version.Version;
import com.movtery.zalithlauncher.feature.version.VersionsManager;
import com.movtery.zalithlauncher.feature.version.install.GameInstaller;
import com.movtery.zalithlauncher.feature.version.install.InstallTask;
import com.movtery.zalithlauncher.plugins.renderer.RendererPlugin;
import com.movtery.zalithlauncher.plugins.renderer.RendererPluginManager;
import com.movtery.zalithlauncher.setting.AllSettings;
import com.movtery.zalithlauncher.task.Task;
import com.movtery.zalithlauncher.task.TaskExecutors;
import com.movtery.zalithlauncher.ui.activity.BaseActivity;
import com.movtery.zalithlauncher.ui.activity.ErrorActivity;
import com.movtery.zalithlauncher.ui.dialog.EditTextDialog;
import com.movtery.zalithlauncher.ui.dialog.TipDialog;
import com.movtery.zalithlauncher.ui.fragment.AccountFragment;
import com.movtery.zalithlauncher.ui.fragment.BaseFragment;
import com.movtery.zalithlauncher.ui.fragment.DownloadFragment;
import com.movtery.zalithlauncher.ui.fragment.DownloadModFragment;
import com.movtery.zalithlauncher.ui.fragment.SettingsFragment;
import com.movtery.zalithlauncher.ui.subassembly.settingsbutton.ButtonType;
import com.movtery.zalithlauncher.ui.subassembly.settingsbutton.SettingsButtonWrapper;
import com.movtery.zalithlauncher.ui.subassembly.view.DraggableViewWrapper;
import com.movtery.zalithlauncher.utils.StoragePermissionsUtils;
import com.movtery.zalithlauncher.utils.ZHTools;
import com.movtery.zalithlauncher.utils.anim.ViewAnimUtils;
import com.movtery.zalithlauncher.utils.file.FileTools;
import com.movtery.zalithlauncher.utils.image.ImageUtils;
import com.movtery.zalithlauncher.utils.stringutils.ShiftDirection;
import com.movtery.zalithlauncher.utils.stringutils.StringUtils;

import net.kdt.pojavlaunch.authenticator.microsoft.MicrosoftBackgroundLogin;
import net.kdt.pojavlaunch.contracts.OpenDocumentWithExtension;
import net.kdt.pojavlaunch.fragments.MainMenuFragment;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.progresskeeper.TaskCountListener;
import net.kdt.pojavlaunch.services.ProgressServiceKeeper;
import net.kdt.pojavlaunch.tasks.AsyncVersionList;
import net.kdt.pojavlaunch.utils.NotificationUtils;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;
import java.util.concurrent.Future;

public class LauncherActivity extends BaseActivity {

    private final AnimPlayer noticeAnimPlayer = new AnimPlayer();
    private ActivityLauncherBinding binding;
    private SettingsButtonWrapper mSettingsButtonWrapper;
    private ProgressServiceKeeper mProgressServiceKeeper;
    private NotificationManager mNotificationManager;
    private Future<?> checkNotice;

    private ActivityResultLauncher<String> mRequestNotificationPermissionLauncher;
    private WeakReference<Runnable> mRequestNotificationPermissionRunnable;
    public final ActivityResultLauncher<Object> modInstallerLauncher =
            registerForActivityResult(new OpenDocumentWithExtension("jar"), uris -> {
                if (uris != null) {
                    Tools.launchModInstaller(this, uris.get(0));
                }
            });
    private final FragmentManager.FragmentLifecycleCallbacks fragmentCallbackListener =
            new FragmentManager.FragmentLifecycleCallbacks() {
                @Override
                public void onFragmentResumed(@NonNull FragmentManager fm, @NonNull Fragment fragment) {
                    if (fragment instanceof MainMenuFragment) {
                        mSettingsButtonWrapper.setButtonType(ButtonType.SETTINGS);
                    } else {
                        mSettingsButtonWrapper.setButtonType(ButtonType.HOME);
                    }
                }
            };

    private final TaskCountListener doubleLaunchPreventionListener = taskCount -> {
        if (taskCount > 0) {
            TaskExecutors.runInUIThread(() ->
                    mNotificationManager.cancel(NotificationUtils.NOTIFICATION_ID_GAME_START));
        }
    };

    private void initializeScopedManagers() {
        ProfilePathManager.init(getApplicationContext());
        ScopedVersionsManager.init(getApplicationContext());
    }

    @Subscribe
    public void event(PageOpacityChangeEvent event) {
        setPageOpacity(event.getProgress());
    }

    @Subscribe
    public void event(MainBackgroundChangeEvent event) {
        refreshBackground();
        setPageOpacity(AllSettings.getPageOpacity().getValue());
    }

    @Subscribe
    public void event(SwapToLoginEvent event) {
        Fragment currentFragment = getCurrentFragment();

        // If the currently visible fragment is null, or the AccountFragment is already visible,
        // there is nothing to do.
        if (currentFragment == null || getVisibleFragment(AccountFragment.TAG) != null) {
            return;
        }
        ZHTools.swapFragmentWithAnim(currentFragment, AccountFragment.class, AccountFragment.TAG, null);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void event(LaunchGameEvent event) {
        if (binding.progressLayout.hasProcesses()) {
            Toast.makeText(this, R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
            return;
        }
        Version version = VersionsManager.INSTANCE.getCurrentVersion();
        if (version == null) {
            Toast.makeText(this, R.string.error_no_version, Toast.LENGTH_LONG).show();
            return;
        }
        if (AccountsManager.INSTANCE.getAllAccounts().isEmpty()) {
            Toast.makeText(this, R.string.account_no_saved_accounts, Toast.LENGTH_LONG).show();
            EventBus.getDefault().post(new SwapToLoginEvent());
            return;
        }
        RendererPlugin rendererPlugin =
                RendererPluginManager.getConfigurablePluginOrNull(version.getRenderer());

        if (rendererPlugin != null) {
            StoragePermissionsUtils.checkPermissions(
                    this,
                    R.string.generic_warning,
                    getString(
                            R.string.permissions_storage_for_renderer_config,
                            rendererPlugin.getDisplayName(),
                            InfoDistributor.APP_NAME
                    ),
                    new StoragePermissionsUtils.PermissionGranted() {
                        @Override
                        public void granted() {
                            launchGame(version);
                        }
                        @Override
                        public void cancelled() {
                            launchGame(version);
                        }
                    }
            );
            return;
        }
        launchGame(version);
    }

    @Subscribe
    public void event(MicrosoftLoginEvent event) {
        new MicrosoftBackgroundLogin(false, event.getUri().getQueryParameter("code")).performLogin(
                this,
                null,
                AccountsManager.INSTANCE.getDoneListener(),
                AccountsManager.INSTANCE.getErrorListener()
        );
    }

    @Subscribe
    public void event(OtherLoginEvent event) {
        Task.runTask(() -> {
                    event.getAccount().save();
                    Logging.i("Account", "Saved account: " + event.getAccount().username);
                    return null;
                })
                .onThrowable(e -> Logging.e("Account", "Failed to save account: " + e))
                .finallyTask(() -> AccountsManager.INSTANCE.getDoneListener().onLoginDone(event.getAccount()))
                .execute();
    }

    @Subscribe
    public void event(LocalLoginEvent event) {
        String userName = event.getUserName();
        MinecraftAccount localAccount = new MinecraftAccount();
        localAccount.username = userName;
        localAccount.accountType = AccountType.LOCAL.getType();

        try {
            localAccount.save();
            Logging.i("Account", "Saved account: " + localAccount.username);
        } catch (IOException e) {
            Logging.e("Account", "Failed to save account: " + e);
        }

        AccountsManager.INSTANCE.getDoneListener().onLoginDone(localAccount);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void event(InstallLocalModpackEvent event) {
        InstallExtra installExtra = event.getInstallExtra();
        if (!installExtra.startInstall) {
            return;
        }

        if (binding.progressLayout.hasProcesses()) {
            Toast.makeText(this, R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
            return;
        }

        File modpackFile = new File(installExtra.modpackPath);
        ModPackInfo info = ModPackUtils.determineModpack(modpackFile);

        if (info.getType() == ModPackUtils.ModPackEnum.UNKNOWN) {
            InstallLocalModPack.showUnSupportDialog(this);
        }

        String modPackName = info.getName() != null
                ? info.getName()
                : FileTools.getFileNameWithoutExtension(modpackFile);

        new EditTextDialog.Builder(this)
                .setTitle(R.string.version_install_new)
                .setEditText(modPackName)
                .setAsRequired()
                .setConfirmListener((editText, checked) -> {
                    String customName = editText.getText().toString();

                    if (FileTools.isFilenameInvalid(editText)) {
                        return false;
                    }

                    if (VersionsManager.INSTANCE.isVersionExists(customName, true)) {
                        editText.setError(getString(R.string.version_install_exists));
                        return false;
                    }

                    Task.runTask(() -> {
                                ModLoaderWrapper modLoaderWrapper =
                                        InstallLocalModPack.installModPack(
                                                this,
                                                info.getType(),
                                                modpackFile,
                                                customName
                                        );

                                if (modLoaderWrapper != null) {
                                    InstallTask downloadTask = modLoaderWrapper.getDownloadTask();

                                    if (downloadTask != null) {
                                        runOnUiThread(() -> Toast.makeText(
                                                this,
                                                getString(R.string.modpack_prepare_mod_loader_installation),
                                                Toast.LENGTH_SHORT
                                        ).show());

                                        Logging.i(
                                                "Install Version",
                                                "Installing ModLoader: " + modLoaderWrapper.getModLoaderVersion()
                                        );

                                        File file = downloadTask.run(customName);
                                        if (file != null) {
                                            return new kotlin.Pair<>(modLoaderWrapper, file);
                                        }
                                    }
                                }

                                return null;
                            })
                            .beforeStart(
                                    TaskExecutors.getAndroidUI(),
                                    () -> ProgressLayout.setProgress(
                                            ProgressLayout.INSTALL_RESOURCE,
                                            0,
                                            R.string.generic_waiting
                                    )
                            )
                            .ended(filePair -> {
                                if (filePair != null) {
                                    try {
                                        ModPackUtils.startModLoaderInstall(
                                                filePair.getFirst(),
                                                LauncherActivity.this,
                                                filePair.getSecond(),
                                                customName
                                        );
                                    } catch (Throwable e) {
                                        throw new RuntimeException(e);
                                    }
                                }
                            })
                            .onThrowable(
                                    TaskExecutors.getAndroidUI(),
                                    e -> Tools.showErrorRemote(
                                            this,
                                            R.string.modpack_install_download_failed,
                                            e
                                    )
                            )
                            .finallyTask(
                                    TaskExecutors.getAndroidUI(),
                                    () -> ProgressLayout.clearProgress(ProgressLayout.INSTALL_RESOURCE)
                            )
                            .execute();

                    return true;
                })
                .showDialog();
    }

    @Subscribe
    public void event(InstallGameEvent event) {
        new GameInstaller(this, event).installGame();
    }

    @Subscribe
    public void event(DownloadProgressKeyEvent event) {
        if (event.getObserve()) {
            binding.progressLayout.observe(event.getProgressKey());
        } else {
            binding.progressLayout.unObserve(event.getProgressKey());
        }
    }

    @Subscribe
    public synchronized void event(AddFragmentEvent event) {
        Fragment currentFragment = getCurrentFragment();
        if (currentFragment == null) {
            return;
        }

        try {
            AddFragmentEvent.FragmentActivityCallBack activityCallBack =
                    event.getFragmentActivityCallback();

            if (activityCallBack != null) {
                activityCallBack.callBack(currentFragment.requireActivity());
            }

            ZHTools.addFragment(
                    currentFragment,
                    event.getFragmentClass(),
                    event.getFragmentTag(),
                    event.getBundle()
            );
        } catch (Exception e) {
            Logging.e("LauncherActivity", "Failed to open a new fragment.", e);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        initializeScopedManagers();

        binding = ActivityLauncherBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setupFragments();
        setupViews();
        setupNotificationPermissionLauncher();
        checkNotificationPermission();

        mNotificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        registerProgressListeners();
        loadVersionList();
        checkNotice();
        checkDownloadedPackagesAndUpdates();
    }

    @Override
    protected void onResume() {
        super.onResume();

        initializeScopedManagers();

        setPageOpacity(AllSettings.getPageOpacity().getValue());
        VersionsManager.INSTANCE.refresh("LauncherActivity:onResume", false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                fragmentCallbackListener,
                true
        );
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        binding.progressLayout.cleanUpObservers();
        ProgressKeeper.removeTaskCountListener(binding.progressLayout);
        ProgressKeeper.removeTaskCountListener(mProgressServiceKeeper);

        getSupportFragmentManager().unregisterFragmentLifecycleCallbacks(fragmentCallbackListener);
        ContextExecutor.clearActivity();
    }

    @Override
    public void onAttachedToWindow() {
        LauncherPreferences.computeNotchSize(this);
    }

    private void setupNotificationPermissionLauncher() {
        mRequestNotificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isAllowed -> {
                    if (!isAllowed) {
                        handleNoNotificationPermission();
                        return;
                    }

                    Runnable runnable =
                            Tools.getWeakReference(mRequestNotificationPermissionRunnable);
                    if (runnable != null) {
                        runnable.run();
                    }
                }
        );
    }

    private void registerProgressListeners() {
        ProgressKeeper.addTaskCountListener(doubleLaunchPreventionListener);
        ProgressKeeper.addTaskCountListener(
                mProgressServiceKeeper = new ProgressServiceKeeper(this)
        );
        ProgressKeeper.addTaskCountListener(binding.progressLayout);
    }

    private void loadVersionList() {
        new AsyncVersionList().getVersionList(
                versions -> EventBus.getDefault().postSticky(
                        new MinecraftVersionValueEvent(versions)
                ),
                false
        );
    }

    private void checkDownloadedPackagesAndUpdates() {
        // Check already downloaded packages, or check for updates.
        Task.runTask(() -> {
            UpdateUtils.checkDownloadedPackage(this, false, true);
            return null;
        }).execute();
    }

    private void setupFragments() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                Fragment currentFragment = getCurrentFragment();

                if (currentFragment instanceof BaseFragment
                        && !((BaseFragment) currentFragment).onBackPressed()) {
                    // The fragment rejected the back press event.
                    return;
                }

                // If there is only one or zero fragments left in the back stack,
                // exit the launcher directly.
                if (getSupportFragmentManager().getBackStackEntryCount() <= 1) {
                    finish();
                } else {
                    getSupportFragmentManager().popBackStackImmediate();
                }
            }
        });

        FragmentManager fragmentManager = getSupportFragmentManager();

        // If there are no fragments in the back stack, add the main fragment.
        if (fragmentManager.getBackStackEntryCount() < 1) {
            fragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .addToBackStack(MainMenuFragment.TAG)
                    .add(
                            R.id.container_fragment,
                            MainMenuFragment.class,
                            null,
                            MainMenuFragment.TAG
                    )
                    .commit();
        }
    }

    private void setupViews() {
        refreshBackground();
        setPageOpacity(AllSettings.getPageOpacity().getValue());

        mSettingsButtonWrapper = new SettingsButtonWrapper(binding.settingButton);
        mSettingsButtonWrapper.setOnTypeChangeListener(
                type -> ViewAnimUtils.setViewAnim(binding.settingButton, Animations.Pulse)
        );

        binding.downloadButton.setOnClickListener(v -> openDownloadFragment());
        binding.settingButton.setOnClickListener(v -> handleSettingsButtonClick());

        binding.appTitleText.setText(InfoDistributor.APP_NAME);
        binding.appTitleText.setOnClickListener(v -> shiftAppTitle());

        observeProgressKeys();
        setupNoticeUI();
        setupNoticeDrag();

        // April Fools' easter egg.
        binding.hair.setVisibility(ZHTools.checkDate(4, 1) ? View.VISIBLE : View.GONE);
    }

    private void openDownloadFragment() {
        Fragment fragment = getCurrentFragment();
        if (fragment == null) {
            return;
        }

        if (!(fragment instanceof DownloadFragment || fragment instanceof DownloadModFragment)) {
            ViewAnimUtils.setViewAnim(binding.downloadButton, Animations.Pulse);
            ZHTools.swapFragmentWithAnim(fragment, DownloadFragment.class, DownloadFragment.TAG, null);
        }
    }

    private void handleSettingsButtonClick() {
        ViewAnimUtils.setViewAnim(binding.settingButton, Animations.Pulse);

        Fragment fragment = getCurrentFragment();
        if (fragment instanceof MainMenuFragment) {
            ZHTools.swapFragmentWithAnim(fragment, SettingsFragment.class, SettingsFragment.TAG, null);
        } else {
            // The settings button also acts as a home button.
            Tools.backToMainMenu(this);
        }
    }

    private void shiftAppTitle() {
        String shiftedString = StringUtils.shiftString(
                binding.appTitleText.getText().toString(),
                ShiftDirection.RIGHT,
                1
        );

        if (new Random().nextInt(100) < 20 && shiftedString.equals(InfoDistributor.APP_NAME)) {
            ErrorActivity.showEasterEgg(this);
            return;
        }

        binding.appTitleText.setText(shiftedString);
    }

    private void observeProgressKeys() {
        binding.progressLayout.observe(ProgressLayout.DOWNLOAD_MINECRAFT);
        binding.progressLayout.observe(ProgressLayout.UNPACK_RUNTIME);
        binding.progressLayout.observe(ProgressLayout.INSTALL_RESOURCE);
        binding.progressLayout.observe(ProgressLayout.LOGIN_ACCOUNT);
        binding.progressLayout.observe(ProgressLayout.DOWNLOAD_VERSION_LIST);
        binding.progressLayout.observe(ProgressLayout.CHECKING_MODS);
    }

    private void setupNoticeUI() {
        binding.noticeGotButton.setOnClickListener(v -> {
            setNotice(false);
            AllSettings.getNoticeDefault().put(false).save();
        });
    }

    private void setupNoticeDrag() {
        new DraggableViewWrapper(binding.noticeLayout, new DraggableViewWrapper.AttributesFetcher() {
            @NonNull
            @Override
            public DraggableViewWrapper.ScreenPixels getScreenPixels() {
                return new DraggableViewWrapper.ScreenPixels(
                        0,
                        0,
                        currentDisplayMetrics.widthPixels - binding.noticeLayout.getWidth(),
                        currentDisplayMetrics.heightPixels - binding.noticeLayout.getHeight()
                );
            }

            @NonNull
            @Override
            public int[] get() {
                return new int[]{
                        (int) binding.noticeLayout.getX(),
                        (int) binding.noticeLayout.getY()
                };
            }

            @Override
            public void set(int x, int y) {
                binding.noticeLayout.setX(x);
                binding.noticeLayout.setY(y);
            }
        }).init();
    }

    private void launchGame(Version version) {
        LocalAccountUtils.checkUsageAllowed(new LocalAccountUtils.CheckResultListener() {
            @Override
            public void onUsageAllowed() {
                preLaunch(LauncherActivity.this, version);
            }

            @Override
            public void onUsageDenied() {
                if (!AllSettings.getLocalAccountReminders().getValue()) {
                    preLaunch(LauncherActivity.this, version);
                    return;
                }

                LocalAccountUtils.openDialog(
                        LauncherActivity.this,
                        checked -> {
                            LocalAccountUtils.saveReminders(checked);
                            preLaunch(LauncherActivity.this, version);
                        },
                        getString(R.string.account_no_microsoft_account)
                                + getString(R.string.account_purchase_minecraft_account_tip),
                        R.string.account_continue_to_launch_the_game
                );
            }
        });
    }

    private void checkNotice() {
        checkNotice = TaskExecutors.getDefault().submit(() ->
                CheckNewNotice.checkNewNotice(noticeInfo -> {
                    if (checkNotice.isCancelled() || noticeInfo == null) {
                        return;
                    }

                    // Show the notice panel if notice display is enabled in preferences,
                    // or if the detected notice number differs from the saved preference.
                    if (AllSettings.getNoticeDefault().getValue()
                            || noticeInfo.numbering != AllSettings.getNoticeNumbering().getValue()) {
                        TaskExecutors.runInUIThread(() -> setNotice(true));
                        AllSettings.getNoticeDefault()
                                .put(true)
                                .put(AllSettings.getNoticeNumbering(), noticeInfo.numbering)
                                .save();
                    }
                })
        );
    }

    private void setNotice(boolean show) {
        if (show) {
            NoticeInfo noticeInfo = CheckNewNotice.getNoticeInfo();
            if (noticeInfo == null) {
                return;
            }

            binding.noticeGotButton.setClickable(true);
            binding.noticeTitleView.setText(noticeInfo.title);
            binding.noticeMessageView.setText(noticeInfo.content);
            binding.noticeDateView.setText(noticeInfo.date);

            Linkify.addLinks(binding.noticeMessageView, Linkify.WEB_URLS);
            binding.noticeMessageView.setMovementMethod(LinkMovementMethod.getInstance());

            noticeAnimPlayer.clearEntries();
            noticeAnimPlayer.apply(
                            new AnimPlayer.Entry(binding.noticeLayout, Animations.BounceEnlarge))
                    .setOnStart(() -> binding.noticeLayout.setVisibility(View.VISIBLE))
                    .start();
            return;
        }

        binding.noticeGotButton.setClickable(false);

        noticeAnimPlayer.clearEntries();
        noticeAnimPlayer.apply(
                        new AnimPlayer.Entry(binding.noticeLayout, Animations.BounceShrink))
                .setOnStart(() -> binding.noticeLayout.setVisibility(View.VISIBLE))
                .setOnEnd(() -> binding.noticeLayout.setVisibility(View.GONE))
                .start();
    }

    private void refreshBackground() {
        BackgroundManager.setBackgroundImage(
                this,
                BackgroundType.MAIN_MENU,
                binding.backgroundView,
                this::refreshTopBarColor
        );
    }

    private void refreshTopBarColor(boolean loadFromBackground) {
        int backgroundMenuTop = ContextCompat.getColor(this, R.color.background_menu_top);

        if (loadFromBackground) {
            Bitmap bitmap = ImageUtils.getBitmapFromImageView(binding.backgroundView);
            if (bitmap != null) {
                Palette palette = Palette.from(bitmap).generate();
                boolean isDarkMode = ZHTools.isDarkMode(this);

                binding.topLayout.setBackgroundColor(
                        isDarkMode
                                ? palette.getDarkVibrantColor(backgroundMenuTop)
                                : palette.getLightVibrantColor(backgroundMenuTop)
                );

                int mutedColor = isDarkMode
                        ? palette.getLightMutedColor(0xFFFFFFFF)
                        : palette.getDarkMutedColor(0xFFFFFFFF);

                ColorStateList colorStateList = ColorStateList.valueOf(mutedColor);
                binding.appTitleText.setTextColor(mutedColor);
                binding.downloadButton.setImageTintList(colorStateList);
                binding.settingButton.setImageTintList(colorStateList);
                return;
            }
        }

        binding.topLayout.setBackgroundColor(backgroundMenuTop);
        binding.appTitleText.setTextColor(
                ContextCompat.getColor(this, R.color.menu_bar_text)
        );

        ColorStateList colorStateList = ColorStateList.valueOf(0xFFFFFFFF);
        binding.downloadButton.setImageTintList(colorStateList);
        binding.settingButton.setImageTintList(colorStateList);
    }

    private Fragment getCurrentFragment() {
        return getVisibleFragment(binding.containerFragment.getId());
    }

    @SuppressWarnings("SameParameterValue")
    private Fragment getVisibleFragment(String tag) {
        return getVisibleFragmentIfAvailable(getSupportFragmentManager().findFragmentByTag(tag));
    }

    private Fragment getVisibleFragment(int id) {
        return getVisibleFragmentIfAvailable(getSupportFragmentManager().findFragmentById(id));
    }

    private Fragment getVisibleFragmentIfAvailable(Fragment fragment) {
        return fragment != null && fragment.isVisible() ? fragment : null;
    }

    private void checkNotificationPermission() {
        if (AllSettings.getSkipNotificationPermissionCheck().getValue()
                || ZHTools.checkForNotificationPermission()) {
            return;
        }

        if (ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.POST_NOTIFICATIONS
        )) {
            showNotificationPermissionReasoning();
            return;
        }

        askForNotificationPermission(null);
    }

    private void showNotificationPermissionReasoning() {
        new TipDialog.Builder(this)
                .setTitle(R.string.notification_permission_dialog_title)
                .setMessage(getString(
                        R.string.notification_permission_dialog_text,
                        InfoDistributor.APP_NAME,
                        InfoDistributor.APP_NAME
                ))
                .setConfirmClickListener(checked -> askForNotificationPermission(null))
                .setCancelClickListener(this::handleNoNotificationPermission)
                .showDialog();
    }

    private void handleNoNotificationPermission() {
        AllSettings.getSkipNotificationPermissionCheck().put(true).save();
        Toast.makeText(this, R.string.notification_permission_toast, Toast.LENGTH_LONG).show();
    }

    public void askForNotificationPermission(Runnable onSuccessRunnable) {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }

        if (onSuccessRunnable != null) {
            mRequestNotificationPermissionRunnable = new WeakReference<>(onSuccessRunnable);
        }

        mRequestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    private void setPageOpacity(int pageOpacity) {
        BigDecimal opacity = BigDecimal.valueOf(pageOpacity)
                .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);

        float pageAlpha = opacity.floatValue();
        binding.containerFragment.setAlpha(pageAlpha);

        BigDecimal adjustedOpacity = BackgroundManager.hasBackgroundImage(BackgroundType.MAIN_MENU)
                ? opacity.subtract(BigDecimal.valueOf(0.1)).max(BigDecimal.ZERO)
                : BigDecimal.ONE;

        binding.topLayout.setAlpha(adjustedOpacity.floatValue());
    }
}
