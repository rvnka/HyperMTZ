package app.hypermtz.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import app.hypermtz.util.LogManager;

/**
 * Accessibility Service that intercepts MIUI's theme trial-period broadcast
 * and auto-clicks ThemeManager approval dialogs.
 *
 * Runs in the ":intercept" process (android:process=":intercept" in manifest)
 * together with KeepAliveService. Sharing a process with a foreground service
 * drastically reduces the chance of MIUI killing the service after screen-off.
 *
 * ── Broadcast intercept ───────────────────────────────────────────────────
 *
 * MIUI sends "miui.intent.action.CHECK_TIME_UP" as an ordered broadcast to
 * signal ThemeManager that a theme trial period has elapsed. By registering
 * at priority 1000 and calling abortBroadcast(), this service prevents
 * ThemeManager from ever receiving the signal — the theme persists.
 *
 * abortBroadcast() is guarded by isOrderedBroadcast() to avoid an
 * UnsupportedOperationException if a ROM variant sends it as a regular
 * broadcast. The intercept is still recorded in that case.
 *
 * ── Receiver flags (Android 13+) ─────────────────────────────────────────
 *
 * CHECK_TIME_UP is sent by MIUI's ThemeManager (a privileged system package).
 * On Android 13+ (API 33), system/privileged apps can deliver broadcasts to
 * NOT_EXPORTED receivers. We register NOT_EXPORTED following ThemeStore's
 * approach, which is both correct and more secure.
 *
 * ── Keep-alive ────────────────────────────────────────────────────────────
 *
 * KeepAliveService is started on onCreate() and stopped on onDestroy().
 * Both services share the ":intercept" process, so the foreground service
 * keeps the entire process alive — including this accessibility service.
 */
public class ThemeInterceptService extends AccessibilityService {

    public static final String ACTION_STATE_CHANGED = "app.hypermtz.action_Service_UP";
    private static final String ACTION_THEME_CHECK  = "miui.intent.action.CHECK_TIME_UP";

    public static final String PREFS_NAME         = "hypermtz_state";
    public static final String KEY_CONNECTED_TIME = "connected_time";
    public static final String KEY_INTERCEPT_TIME = "intercept_time";

    private static final String TAG = "ThemeInterceptService";

    /**
     * Button texts to click when ThemeManager shows an approval dialog.
     * Chinese strings listed first (most MIUI ROMs are CN locale).
     *
     *  应用   — Apply (theme)
     *  确定   — OK / Confirm
     *  安装   — Install
     *  继续   — Continue
     *  允许   — Allow
     *  跳过   — Skip
     *  忽略   — Ignore
     *  取消限制 — Cancel restriction (MIUI theme trial dialog)
     */
    private static final String[] APPROVAL_TEXTS = {
        "应用", "确定", "安装", "继续", "允许", "跳过", "忽略", "取消限制",
        "Apply", "Install", "OK", "Continue", "Allow", "Ignore"
    };

    private boolean receiverRegistered = false;
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.getDefault());

    private final BroadcastReceiver themeCheckReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "BroadcastReceiver received: " + action);

            if (ACTION_THEME_CHECK.equals(action)) {
                // abortBroadcast() prevents ThemeManager from receiving the cleanup
                // signal, so the theme trial period never triggers a reset.
                //
                // isOrderedBroadcast() guard: some ROM variants send CHECK_TIME_UP as
                // a regular (non-ordered) broadcast. abortBroadcast() throws
                // UnsupportedOperationException on non-ordered broadcasts. The guard
                // keeps the receiver alive in that edge case while still recording the
                // intercept so the UI shows the correct timestamp.
                if (isOrderedBroadcast()) {
                    abortBroadcast();
                    Log.d(TAG, "CHECK_TIME_UP ordered broadcast aborted");
                } else {
                    Log.w(TAG, "CHECK_TIME_UP was NOT ordered — cannot abort, only logging");
                }
                recordIntercept();
                // Refresh the KeepAliveService notification to show the new timestamp.
                KeepAliveService.refresh(ThemeInterceptService.this);
            }
            // SCREEN_OFF — no-op, but being in the IntentFilter keeps the
            // wakelock held briefly which helps the process survive screen-off.
        }
    };

    // ── Static helper ──────────────────────────────────────────────────────────

    public static boolean isRunning(Context context) {
        AccessibilityManager manager =
                (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null) return false;
        List<AccessibilityServiceInfo> enabled =
                manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo info : enabled) {
            ServiceInfo si = info.getResolveInfo().serviceInfo;
            if (context.getPackageName().equals(si.packageName)
                    && ThemeInterceptService.class.getName().equals(si.name)) {
                return true;
            }
        }
        return false;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
        saveTimestamp(KEY_CONNECTED_TIME, TIME_FMT.format(LocalDateTime.now()));
        registerThemeReceiver();
        broadcastStateChanged();
        // Start KeepAliveService — keeps the :intercept process foreground.
        KeepAliveService.start(this);
    }

    @Override
    public void onServiceConnected() {
        Log.d(TAG, "onServiceConnected");
        broadcastStateChanged();
        KeepAliveService.refresh(this);
        super.onServiceConnected();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        unregisterThemeReceiver();
        broadcastStateChanged();
        // Stop KeepAliveService when accessibility is revoked.
        KeepAliveService.stop(this);
        super.onDestroy();
    }

    // ── AccessibilityService ───────────────────────────────────────────────────

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !pkg.toString().contains("thememanager")) return;

        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            if (tryClickApprovalButton(root)) {
                Log.d(TAG, "Clicked approval button in ThemeManager");
                recordIntercept();
                KeepAliveService.refresh(this);
            }
        } finally {
            root.recycle();
        }
    }

    @Override
    public void onInterrupt() {}

    // ── Private helpers ────────────────────────────────────────────────────────

    private boolean tryClickApprovalButton(AccessibilityNodeInfo root) {
        for (String text : APPROVAL_TEXTS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo node : nodes) {
                if (node != null
                        && node.isClickable()
                        && node.isEnabled()
                        && node.isVisibleToUser()) {
                    boolean clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.d(TAG, "performAction(CLICK) on \"" + text + "\": " + clicked);
                    return clicked;
                }
            }
        }
        return false;
    }

    private void recordIntercept() {
        saveTimestamp(KEY_INTERCEPT_TIME, TIME_FMT.format(LocalDateTime.now()));
        broadcastStateChanged();
        // Log the intercept event via LogManager so statistics are tracked.
        LogManager.log(this, LogManager.LogType.ALARM_INTERCEPT,
                "CHECK_TIME_UP intercepted",
                "time=" + TIME_FMT.format(LocalDateTime.now()));
    }

    private void registerThemeReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_THEME_CHECK);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY); // 1000

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+: use NOT_EXPORTED.
            // MIUI's ThemeManager is a privileged system package — it can still
            // deliver broadcasts to NOT_EXPORTED receivers (system privilege bypass).
            registerReceiver(themeCheckReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            try {
                // Pre-API 33: use reflection to call the hidden overload that existed
                // since API 26 (equivalent to no-flag registration on older APIs).
                Context.class
                        .getMethod("registerReceiver", BroadcastReceiver.class, IntentFilter.class)
                        .invoke(this, themeCheckReceiver, filter);
            } catch (Exception e) {
                // Fallback to plain registerReceiver (API < 26 path).
                registerReceiver(themeCheckReceiver, filter);
            }
        }
        receiverRegistered = true;
        Log.d(TAG, "BroadcastReceiver registered, priority=" + filter.getPriority());
    }

    private void unregisterThemeReceiver() {
        if (!receiverRegistered) return;
        try {
            unregisterReceiver(themeCheckReceiver);
        } catch (Exception ignored) {}
        receiverRegistered = false;
    }

    private void saveTimestamp(String key, String value) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(key, value).apply();
    }

    private void broadcastStateChanged() {
        Intent intent = new Intent(ACTION_STATE_CHANGED);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }
}
