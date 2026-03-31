package net.kdt.pojavlaunch;

import android.annotation.SuppressLint;
import android.content.ClipboardManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;

import com.getkeepsafe.taptargetview.TapTargetView;
import com.movtery.zalithlauncher.R;
import com.movtery.zalithlauncher.databinding.ActivityJavaGuiLauncherBinding;
import com.movtery.zalithlauncher.event.value.JvmExitEvent;
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathHome;
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathManager;
import com.movtery.zalithlauncher.feature.customprofilepath.ScopedInstallHelper;
import com.movtery.zalithlauncher.feature.customprofilepath.ScopedVersionsManager;
import com.movtery.zalithlauncher.feature.log.Logging;
import com.movtery.zalithlauncher.launch.LaunchArgs;
import com.movtery.zalithlauncher.setting.AllSettings;
import com.movtery.zalithlauncher.task.Task;
import com.movtery.zalithlauncher.task.TaskExecutors;
import com.movtery.zalithlauncher.ui.activity.BaseActivity;
import com.movtery.zalithlauncher.ui.dialog.TipDialog;
import com.movtery.zalithlauncher.utils.NewbieGuideUtils;
import com.movtery.zalithlauncher.utils.ZHTools;
import com.movtery.zalithlauncher.utils.image.Dimension;
import com.movtery.zalithlauncher.utils.image.ImageUtils;
import com.movtery.zalithlauncher.utils.path.LibPath;
import com.movtery.zalithlauncher.utils.path.PathManager;

import net.kdt.pojavlaunch.customcontrols.keyboard.AwtCharSender;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.utils.JREUtils;
import net.kdt.pojavlaunch.utils.MathUtils;

import org.apache.commons.io.IOUtils;
import org.greenrobot.eventbus.Subscribe;
import org.lwjgl.glfw.CallbackBridge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class JavaGUILauncherActivity extends BaseActivity implements View.OnTouchListener {
    public static final String EXTRAS_JRE_NAME = "jre_name";
    public static final String SUBSCRIBE_JVM_EXIT_EVENT = "subscribe_jvm_exit_event";
    public static final String FORCE_SHOW_LOG = "force_show_log";

    private ActivityJavaGuiLauncherBinding binding;
    private GestureDetector mGestureDetector;

    private boolean mIsVirtualMouseEnabled;
    private boolean mSubscribeJvmExitEvent;
    private String mPendingScopedProfileName;

    private void initializeScopedManagers() {
        ProfilePathManager.init(getApplicationContext());
        ScopedVersionsManager.init(getApplicationContext());
    }

    private String extractProfileName(String javaArgs) {
        if (javaArgs == null) return null;

        String key = "-DprofileName=";
        int start = javaArgs.indexOf(key);
        if (start < 0) return null;

        start += key.length();
        if (start >= javaArgs.length()) return null;

        // Quoted value
        if (javaArgs.charAt(start) == '"') {
            start++;
            int end = javaArgs.indexOf('"', start);
            if (end == -1) end = javaArgs.length();
            return javaArgs.substring(start, end).trim();
        }

        int nextJar = javaArgs.indexOf(" -jar ", start);
        int nextMc = javaArgs.indexOf(" -mcversion ", start);
        int nextLoader = javaArgs.indexOf(" -loader ", start);
        int nextDir = javaArgs.indexOf(" -dir ", start);

        int end = javaArgs.length();
        if (nextJar != -1 && nextJar < end) end = nextJar;
        if (nextMc != -1 && nextMc < end) end = nextMc;
        if (nextLoader != -1 && nextLoader < end) end = nextLoader;
        if (nextDir != -1 && nextDir < end) end = nextDir;

        return javaArgs.substring(start, end).trim();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initializeScopedManagers();

        binding = ActivityJavaGuiLauncherBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        try {
            File latestLogFile = new File(PathManager.DIR_GAME_HOME, "latestlog.txt");
            if (!latestLogFile.exists() && !latestLogFile.createNewFile()) {
                throw new IOException("Failed to create a new log file");
            }
            Logger.begin(latestLogFile.getAbsolutePath());
        } catch (IOException e) {
            Tools.showError(this, e, true);
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        MainActivity.GLOBAL_CLIPBOARD = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        binding.awtTouchChar.setCharacterSender(new AwtCharSender());

        mGestureDetector = new GestureDetector(this, new SingleTapConfirm());
        binding.mainTouchpad.setFocusable(false);
        binding.mainTouchpad.setVisibility(View.GONE);

        binding.installmodMousePri.setOnTouchListener(this);
        binding.installmodMouseSec.setOnTouchListener(this);
        binding.installmodWindowMoveup.setOnTouchListener(this);
        binding.installmodWindowMovedown.setOnTouchListener(this);
        binding.installmodWindowMoveleft.setOnTouchListener(this);
        binding.installmodWindowMoveright.setOnTouchListener(this);

        binding.mousePointer.setImageDrawable(ZHTools.customMouse(this));

        binding.mousePointer.post(() -> {
            ViewGroup.LayoutParams params = binding.mousePointer.getLayoutParams();
            Drawable drawable = binding.mousePointer.getDrawable();
            Dimension mousescale = ImageUtils.resizeWithRatio(
                    drawable.getIntrinsicWidth(),
                    drawable.getIntrinsicHeight(),
                    AllSettings.getMouseScale().getValue()
            );
            params.width = (int) (mousescale.width * 0.5);
            params.height = (int) (mousescale.height * 0.5);
        });

        binding.mainTouchpad.setOnTouchListener(new View.OnTouchListener() {
            float prevX = 0;
            float prevY = 0;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();

                float x = event.getX();
                float y = event.getY();
                float mouseX = binding.mousePointer.getX();
                float mouseY = binding.mousePointer.getY();

                if (mGestureDetector.onTouchEvent(event)) {
                    sendScaledMousePosition(mouseX, mouseY);
                    AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON1_DOWN_MASK);
                } else if (action == MotionEvent.ACTION_MOVE) {
                    mouseX = Math.max(0, Math.min(CallbackBridge.physicalWidth, mouseX + x - prevX));
                    mouseY = Math.max(0, Math.min(CallbackBridge.physicalHeight, mouseY + y - prevY));
                    placeMouseAt(mouseX, mouseY);
                    sendScaledMousePosition(mouseX, mouseY);
                }

                prevY = y;
                prevX = x;
                return true;
            }
        });

        binding.textureView.setOnTouchListener((v, event) -> {
            float x = event.getX();
            float y = event.getY();
            if (mGestureDetector.onTouchEvent(event)) {
                sendScaledMousePosition(x + binding.textureView.getX(), y);
                AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON1_DOWN_MASK);
                return true;
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    sendScaledMousePosition(x + binding.textureView.getX(), y);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_POINTER_UP:
                default:
                    break;
            }
            return true;
        });

        try {
            placeMouseAt(CallbackBridge.physicalWidth / 2f, CallbackBridge.physicalHeight / 2f);
            Bundle extras = getIntent().getExtras();
            if (extras == null) {
                finish();
                return;
            }

            mSubscribeJvmExitEvent = extras.getBoolean(SUBSCRIBE_JVM_EXIT_EVENT, false);

            if (extras.getBoolean(FORCE_SHOW_LOG, false)) {
                binding.launcherLoggerView.forceShow(this::forceClose);
                showLogFloodWarning();
            }

            final String javaArgs = extras.getString("javaArgs");
            mPendingScopedProfileName = extractProfileName(javaArgs);

            final Uri resourceUri = extras.getParcelable("modUri");
            final String jreName = extras.getString(EXTRAS_JRE_NAME, null);

            if (extras.getBoolean("openLogOutput", false)) {
                openLogOutput(null);
            }

            if (javaArgs != null) {
                startModInstaller(null, javaArgs, jreName);
            } else if (resourceUri != null) {
                AlertDialog dialog = ZHTools.showTaskRunningDialog(
                        this,
                        getString(R.string.multirt_progress_caching)
                );
                Task.runTask(() -> {
                            startModInstallerWithUri(resourceUri, jreName);
                            return null;
                        })
                        .ended(TaskExecutors.getAndroidUI(), r -> dialog.dismiss())
                        .execute();
            }
        } catch (Throwable th) {
            Tools.showError(this, th, true);
        }

        getOnBackPressedDispatcher().addCallback(new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                forceClose();
            }
        });
    }

    @Subscribe
    public void event(JvmExitEvent event) {
        if (!mSubscribeJvmExitEvent) {
            return;
        }

        boolean installSucceeded = true;
        try {
            installSucceeded = event.getExitCode() == 0;
        } catch (Throwable ignored) {
        }

        if (installSucceeded && ProfilePathHome.isScopedStorage()) {
            try {
                initializeScopedManagers();
                if (mPendingScopedProfileName != null && !mPendingScopedProfileName.isEmpty()) {
                    ScopedInstallHelper.copyVersionToScoped(
                            getApplicationContext(),
                            mPendingScopedProfileName
                    );
                }
            } catch (Throwable t) {
                Logging.e("ScopedInstall", "Final scoped copy failed", t);
            }
        }

        ZHTools.killProcess();
    }

    private void showLogFloodWarning() {
        if (NewbieGuideUtils.showOnlyOne("LogFloodWarning")) return;
        TapTargetView.showFor(
                this,
                NewbieGuideUtils.getSimpleTarget(
                        this,
                        binding.launcherLoggerView.getBinding().toggleLog,
                        getString(R.string.version_install_log_flood_warning)
                )
        );
    }

    private void startModInstallerWithUri(Uri uri, String jreName) {
        try {
            File cacheFile = new File(getCacheDir(), "mod-installer-temp");
            InputStream contentStream = getContentResolver().openInputStream(uri);
            if (contentStream == null) throw new IOException("Failed to open content stream");
            try (FileOutputStream fileOutputStream = new FileOutputStream(cacheFile)) {
                IOUtils.copy(contentStream, fileOutputStream);
            }
            contentStream.close();
            startModInstaller(cacheFile, null, jreName);
        } catch (IOException e) {
            Tools.showError(this, e, true);
        }
    }

    public Runtime selectRuntime(File modFile) {
        int javaVersion = getJavaVersion(modFile);
        if (javaVersion == -1) {
            finalErrorDialog(getString(R.string.execute_jar_failed_to_read_file));
            return null;
        }
        String nearestRuntime = MultiRTUtils.getNearestJreName(javaVersion);
        if (nearestRuntime == null) {
            finalErrorDialog(getString(R.string.multirt_nocompatiblert, javaVersion));
            return null;
        }
        Runtime selectedRuntime = MultiRTUtils.forceReread(nearestRuntime);
        int selectedJavaVersion = Math.max(javaVersion, selectedRuntime.javaVersion);
        if (selectedJavaVersion > 17) {
            finalErrorDialog(getString(R.string.execute_jar_incompatible_runtime, selectedJavaVersion));
            return null;
        }
        return selectedRuntime;
    }

    private File findModPath(List<String> argList) {
        int argsSize = argList.size();
        for (int i = 0; i < argsSize; i++) {
            if (!argList.get(i).equals("-jar")) continue;
            int pathIndex = i + 1;
            if (pathIndex >= argsSize) return null;
            return new File(argList.get(pathIndex));
        }
        return null;
    }

    private List<String> splitPreservingQuotes(String str) {
        List<String> result = new ArrayList<>();
        StringBuilder currentPart = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);

            if (c == '"' && (i == 0 || str.charAt(i - 1) != '\\')) {
                inQuotes = !inQuotes;
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (currentPart.length() > 0) {
                    result.add(currentPart.toString());
                    currentPart.setLength(0);
                }
            } else {
                currentPart.append(c);
            }
        }

        if (currentPart.length() > 0) {
            result.add(currentPart.toString());
        }

        return result;
    }

    private void startModInstaller(File modFile, String javaArgs, String jreName) {
        new Thread(() -> {
            initializeScopedManagers();

            List<String> argList = javaArgs != null ? splitPreservingQuotes(javaArgs) : null;
            File selectedMod = modFile;
            if (selectedMod == null && argList != null) {
                selectedMod = findModPath(argList);
            }

            Runtime selectedRuntime;
            if (jreName == null) {
                if (selectedMod == null) {
                    selectedRuntime = MultiRTUtils.forceReread(AllSettings.getDefaultRuntime().getValue());
                } else {
                    selectedRuntime = selectRuntime(selectedMod);
                    if (selectedRuntime == null) return;
                }
            } else {
                selectedRuntime = MultiRTUtils.forceReread(jreName);
            }

            launchJavaRuntime(selectedRuntime, modFile, argList);
        }, "JREMainThread").start();
    }

    private void finalErrorDialog(CharSequence msg) {
        runOnUiThread(() -> new TipDialog.Builder(this)
                .setTitle(R.string.generic_error)
                .setMessage(msg.toString())
                .setWarning()
                .setCenterMessage(false)
                .setConfirmClickListener(checked -> finish())
                .setCancelable(false)
                .showDialog());
    }

    @Override
    public void onResume() {
        super.onResume();
        initializeScopedManagers();
        final int uiOptions = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        final View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(uiOptions);
    }

    @SuppressLint({"ClickableViewAccessibility", "NonConstantResourceId"})
    @Override
    public boolean onTouch(View v, MotionEvent e) {
        boolean isDown;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                isDown = true;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_UP:
                isDown = false;
                break;
            default:
                return false;
        }

        switch (v.getId()) {
            case R.id.installmod_mouse_pri:
                AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON1_DOWN_MASK, isDown);
                break;
            case R.id.installmod_mouse_sec:
                AWTInputBridge.sendMousePress(AWTInputEvent.BUTTON3_DOWN_MASK, isDown);
                break;
        }

        if (isDown) {
            switch (v.getId()) {
                case R.id.installmod_window_moveup:
                    AWTInputBridge.nativeMoveWindow(0, -10);
                    break;
                case R.id.installmod_window_movedown:
                    AWTInputBridge.nativeMoveWindow(0, 10);
                    break;
                case R.id.installmod_window_moveleft:
                    AWTInputBridge.nativeMoveWindow(-10, 0);
                    break;
                case R.id.installmod_window_moveright:
                    AWTInputBridge.nativeMoveWindow(10, 0);
                    break;
            }
        }
        return true;
    }

    public void placeMouseAt(float x, float y) {
        binding.mousePointer.setX(x);
        binding.mousePointer.setY(y);
    }

    @SuppressWarnings("SuspiciousNameCombination")
    void sendScaledMousePosition(float x, float y) {
        x = androidx.core.math.MathUtils.clamp(
                x,
                binding.textureView.getX(),
                binding.textureView.getX() + binding.textureView.getWidth()
        );
        y = androidx.core.math.MathUtils.clamp(
                y,
                binding.textureView.getY(),
                binding.textureView.getY() + binding.textureView.getHeight()
        );

        AWTInputBridge.sendMousePos(
                (int) MathUtils.map(
                        x,
                        binding.textureView.getX(),
                        binding.textureView.getX() + binding.textureView.getWidth(),
                        0,
                        AWTCanvasView.AWT_CANVAS_WIDTH
                ),
                (int) MathUtils.map(
                        y,
                        binding.textureView.getY(),
                        binding.textureView.getY() + binding.textureView.getHeight(),
                        0,
                        AWTCanvasView.AWT_CANVAS_HEIGHT
                )
        );
    }

    public void forceClose(View v) {
        forceClose();
    }

    public void forceClose() {
        ZHTools.dialogForceClose(this);
    }

    public void openLogOutput(View v) {
        binding.launcherLoggerView.setVisibilityWithAnim(true);
    }

    public void toggleVirtualMouse(View v) {
        mIsVirtualMouseEnabled = !mIsVirtualMouseEnabled;
        binding.mainTouchpad.setVisibility(mIsVirtualMouseEnabled ? View.VISIBLE : View.GONE);
        Toast.makeText(
                this,
                mIsVirtualMouseEnabled ? R.string.control_mouseon : R.string.control_mouseoff,
                Toast.LENGTH_SHORT
        ).show();
    }

    public void launchJavaRuntime(Runtime runtime, File modFile, List<String> javaArgs) {
        initializeScopedManagers();
        JREUtils.redirectAndPrintJRELog();
        try {
            List<String> javaArgList = new ArrayList<>(LaunchArgs.getCacioJavaArgs(runtime.javaVersion == 8));
            if (javaArgs != null) {
                javaArgList.addAll(javaArgs);
            }
            if (modFile != null) {
                javaArgList.add("-jar");
                javaArgList.add(modFile.getAbsolutePath());
            }

            boolean disableSecurityManager = getIntent().getBooleanExtra("disableSecurityManager", false);

            if (AllSettings.getJavaSandbox().getValue() && !disableSecurityManager) {
                Collections.reverse(javaArgList);
                javaArgList.add("-Xbootclasspath/a:" + LibPath.PRO_GRADE.getAbsolutePath());
                javaArgList.add("-Djava.security.manager=net.sourceforge.prograde.sm.ProGradeJSM");
                javaArgList.add("-Djava.security.policy=" + LibPath.JAVA_SANDBOX_POLICY.getAbsolutePath());
                Collections.reverse(javaArgList);
            }

            Logger.appendToLog("Info: Java arguments: " + Arrays.toString(javaArgList.toArray(new String[0])));
            JREUtils.launchWithUtils(this, runtime, null, javaArgList, AllSettings.getJavaArgs().getValue());
        } catch (Throwable th) {
            Tools.showError(this, th, true);
        }
    }

    public void toggleKeyboard(View view) {
        binding.awtTouchChar.switchKeyboardState();
    }

    public void performCopy(View view) {
        AWTInputBridge.sendKey(' ', AWTInputEvent.VK_CONTROL, 1);
        AWTInputBridge.sendKey(' ', AWTInputEvent.VK_C);
        AWTInputBridge.sendKey(' ', AWTInputEvent.VK_CONTROL, 0);
    }

    public void performPaste(View view) {
        AWTInputBridge.sendKey(' ', AWTInputEvent.VK_CONTROL, 1);
        AWTInputBridge.sendKey(' ', AWTInputEvent.VK_V);
        AWTInputBridge.sendKey(' ', AWTInputEvent.VK_CONTROL, 0);
    }

    public int getJavaVersion(File modFile) {
        try (ZipFile zipFile = new ZipFile(modFile)) {
            ZipEntry manifest = zipFile.getEntry("META-INF/MANIFEST.MF");
            if (manifest == null) return -1;

            String manifestString = Tools.read(zipFile.getInputStream(manifest));
            String mainClass = Tools.extractUntilCharacter(manifestString, "Main-Class:", '\n');
            if (mainClass == null) return -1;

            mainClass = mainClass.trim().replace('.', '/') + ".class";
            ZipEntry mainClassFile = zipFile.getEntry(mainClass);
            if (mainClassFile == null) return -1;

            InputStream classStream = zipFile.getInputStream(mainClassFile);
            byte[] bytesWeNeed = new byte[8];
            int readCount = classStream.read(bytesWeNeed);
            classStream.close();
            if (readCount < 8) return -1;

            ByteBuffer byteBuffer = ByteBuffer.wrap(bytesWeNeed);
            if (byteBuffer.getInt() != 0xCAFEBABE) return -1;
            short minorVersion = byteBuffer.getShort();
            short majorVersion = byteBuffer.getShort();
            Logging.i("JavaGUILauncher", majorVersion + "," + minorVersion);
            return classVersionToJavaVersion(majorVersion);
        } catch (Exception e) {
            Logging.e("JavaVersion", "Exception thrown", e);
            return -1;
        }
    }

    public static int classVersionToJavaVersion(int majorVersion) {
        if (majorVersion < 46) return 2;
        return majorVersion - 44;
    }
}
