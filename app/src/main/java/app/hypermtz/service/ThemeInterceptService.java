package app.hypermtz.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.content.ContextCompat;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import app.hypermtz.util.LogManager;

/**
 * Accessibility Service that intercepts MIUI's theme trial-period broadcast
 * and auto-clicks ThemeManager approval dialogs.
 *
 * Runs in the ":intercept" process together with {@link KeepAliveService}.
 * The foreground service keeps the process alive after screen-off.
 *
 * ── Broadcast intercept ───────────────────────────────────────────────────
 * MIUI sends "miui.intent.action.CHECK_TIME_UP" as an ordered broadcast.
 * Registering at priority 1000 and calling abortBroadcast() prevents
 * ThemeManager from receiving the signal — the theme persists.
 *
 * ── Dialog auto-click ─────────────────────────────────────────────────────
 * onAccessibilityEvent() watches ThemeManager windows and clicks known
 * approval button texts automatically.
 *
 * ── Receiver registration ─────────────────────────────────────────────────
 * FIX: Removed pointless pre-API33 reflection. Context.registerReceiver(
 * BroadcastReceiver, IntentFilter) is public API on all versions. The
 * reflection was calling the exact same overload without any benefit.
 */
public class ThemeInterceptService extends AccessibilityService {

    public static final String ACTION_STATE_CHANGED = "app.hypermtz.action_Service_UP";
    private static final String ACTION_THEME_CHECK  = "miui.intent.action.CHECK_TIME_UP";

    public static final String PREFS_NAME         = "hypermtz_state";
    public static final String KEY_CONNECTED_TIME = "connected_time";
    public static final String KEY_INTERCEPT_TIME = "intercept_time";

    /**
     * Sent by FileApplyDialogFragment (main process) before launching ThemeManager
     * intentionally. ThemeInterceptService receives this broadcast and sets an
     * in-memory suppress flag so it does not auto-click the normal apply dialog.
     *
     * WHY NOT SharedPreferences: both processes have separate in-memory SP caches.
     * A write from the main process is never reflected in the :intercept process's
     * cached instance. A directed broadcast is the correct cross-process signal.
     */
    public static final String ACTION_SUPPRESS_AUTO_CLICK =
            "app.hypermtz.ACTION_SUPPRESS_AUTO_CLICK";

    /**
     * Milliseconds to suppress accessibility auto-click AND broadcast interception
     * after receiving ACTION_SUPPRESS_AUTO_CLICK. 120 s is generous enough for
     * ThemeManager to finish applying on slow devices.
     *
     * IMPORTANT: We suppress BOTH auto-click AND CHECK_TIME_UP interception during
     * this window. ThemeManager sends CHECK_TIME_UP as a license verification step
     * during the decompression phase. If we abort that broadcast, ThemeManager
     * fails with "解压主题包失败" (Failed to decompress theme package).
     * After the install window expires, interception resumes as normal.
     */
    private static final long INSTALL_SUPPRESS_MS = 120_000L;

    private static final String TAG = "ThemeInterceptService";

    /**
     * Button texts to auto-click when ThemeManager shows an authorization dialog.
     * Chinese texts listed first — most MIUI ROMs use CN locale.
     */
    private static final String[] APPROVAL_TEXTS = {
        "应用", "确定", "安装", "继续", "允许", "跳过", "忽略", "取消限制",
        "Apply", "Install", "OK", "Continue", "Allow", "Ignore"
    };

    private boolean receiverRegistered       = false;
    /** Epoch ms of last ACTION_SUPPRESS_AUTO_CLICK; 0 = never. */
    private long    suppressAutoClickUntilMs = 0L;

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.getDefault());

    private final BroadcastReceiver themeCheckReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            // Main process signals it is about to launch ThemeManager intentionally.
            // Set an in-memory timestamp so onAccessibilityEvent() suppresses auto-click.
            if (ACTION_SUPPRESS_AUTO_CLICK.equals(action)) {
                suppressAutoClickUntilMs = System.currentTimeMillis() + INSTALL_SUPPRESS_MS;
                Log.d(TAG, "Auto-click suppressed for " + INSTALL_SUPPRESS_MS / 1000 + "s");
                return;
            }

            if (!ACTION_THEME_CHECK.equals(action)) return;

            Log.d(TAG, "CHECK_TIME_UP received");

            // ── DO NOT abort during HyperMTZ-initiated installs ───────────────────
            // ThemeManager sends CHECK_TIME_UP as a license verification step during
            // the decompression phase. Aborting it here causes ThemeManager to fail
            // with "解压主题包失败" (Failed to decompress theme package).
            //
            // suppressAutoClickUntilMs is set when FileApplyDialogFragment sends
            // ACTION_SUPPRESS_AUTO_CLICK just before launching ThemeManager.
            // We reuse that flag here: both auto-click suppression AND broadcast
            // interception are disabled during the active install window.
            //
            // After the 120-second window, normal interception resumes and all
            // subsequent CHECK_TIME_UP broadcasts are aborted (keeping the theme alive).
            if (System.currentTimeMillis() < suppressAutoClickUntilMs) {
                long remaining = (suppressAutoClickUntilMs - System.currentTimeMillis()) / 1000;
                Log.d(TAG, "CHECK_TIME_UP — NOT aborting, HyperMTZ install in progress ("
                        + remaining + "s left). Letting ThemeManager complete decompression.");
                // Keep the :intercept process alive even though we're passing this through.
                // Do NOT call recordIntercept() here — we did not intercept anything; the
                // broadcast is being delivered normally. Calling recordIntercept() would
                // mislead the user by showing a fake "intercepted" timestamp in the UI.
                KeepAliveService.refresh(ThemeInterceptService.this);
                return;
            }

            if (isOrderedBroadcast()) {
                abortBroadcast();
                Log.d(TAG, "Broadcast aborted");
            } else {
                // Some ROMs send CHECK_TIME_UP as a normal (non-ordered) broadcast.
                // abortBroadcast() would throw UnsupportedOperationException here.
                // We still record the intercept for statistics.
                Log.w(TAG, "NOT ordered — cannot abort, logging only");
            }
            recordIntercept();
            KeepAliveService.refresh(ThemeInterceptService.this);
        }
    };

    // ── Static helper ─────────────────────────────────────────────────────────

    /**
     * Returns true if this accessibility service is currently enabled.
     * Runs an accessibility manager IPC — call on a background thread.
     */
    public static boolean isRunning(Context context) {
        AccessibilityManager am =
                (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<AccessibilityServiceInfo> enabled =
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo info : enabled) {
            ServiceInfo si = info.getResolveInfo().serviceInfo;
            if (context.getPackageName().equals(si.packageName)
                    && ThemeInterceptService.class.getName().equals(si.name)) {
                return true;
            }
        }
        return false;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
        saveTimestamp(KEY_CONNECTED_TIME, TIME_FMT.format(LocalDateTime.now()));
        registerThemeReceiver();
        broadcastStateChanged();
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
        KeepAliveService.stop(this);
        super.onDestroy();
    }

    // ── AccessibilityService ──────────────────────────────────────────────────

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !pkg.toString().contains("thememanager")) return;

        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }

        // ── Suppress auto-click during HyperMTZ-initiated installs ─────────────
        // When HyperMTZ launches ThemeManager via ApplyThemeForScreenshot the
        // normal apply dialog also matches APPROVAL_TEXTS and would be clicked,
        // killing the install. FileApplyDialogFragment sends ACTION_SUPPRESS_AUTO_CLICK
        // (a directed broadcast to this service) just before startActivity, which sets
        // suppressAutoClickUntilMs for INSTALL_SUPPRESS_MS (120 s).
        //
        // NOTE: The same suppressAutoClickUntilMs flag ALSO pauses CHECK_TIME_UP
        // broadcast interception (see themeCheckReceiver). This is intentional —
        // ThemeManager requires CHECK_TIME_UP to succeed during decompression.
        //
        // WHY BROADCAST, NOT SharedPreferences:
        // This service runs in ":intercept" — a separate process. The main process
        // and :intercept each hold their own in-memory SharedPreferences cache.
        // A write from the main process is never reflected in :intercept's cache.
        // A directed broadcast is the correct, reliable cross-process signal.
        if (System.currentTimeMillis() < suppressAutoClickUntilMs) {
            Log.d(TAG, "Auto-click suppressed — HyperMTZ install in progress ("
                    + (suppressAutoClickUntilMs - System.currentTimeMillis()) / 1000 + "s left)");
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

    // ── Private helpers ───────────────────────────────────────────────────────

    private boolean tryClickApprovalButton(AccessibilityNodeInfo root) {
        for (String text : APPROVAL_TEXTS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo node : nodes) {
                if (node != null && node.isClickable() && node.isEnabled() && node.isVisibleToUser()) {
                    boolean clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.d(TAG, "performAction(CLICK) on \"" + text + "\": " + clicked);
                    return clicked;
                }
            }
        }
        return false;
    }

    private void recordIntercept() {
        // Compute timestamp once so SharedPreferences write and LogManager entry match.
        String timestamp = TIME_FMT.format(LocalDateTime.now());
        saveTimestamp(KEY_INTERCEPT_TIME, timestamp);
        broadcastStateChanged();
        LogManager.log(this, LogManager.LogType.ALARM_INTERCEPT,
                "CHECK_TIME_UP intercepted", "time=" + timestamp);
    }

    private void registerThemeReceiver() {
        if (receiverRegistered) return;

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_THEME_CHECK);
        filter.addAction(ACTION_SUPPRESS_AUTO_CLICK);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY); // 1000

        // *** CRITICAL FIX: Must use RECEIVER_EXPORTED ***
        //
        // RECEIVER_NOT_EXPORTED was the ROOT CAUSE of "解压主题包失败".
        //
        // ThemeManager (com.android.thememanager) has a DIFFERENT UID from HyperMTZ.
        // RECEIVER_NOT_EXPORTED blocks broadcasts from any process with a different UID.
        // Result: CHECK_TIME_UP sent by ThemeManager NEVER reaches this receiver.
        //
        // Without interception, CHECK_TIME_UP propagates to the system License Manager
        // receiver which responds "expired". ThemeManager reads that result and refuses
        // to decompress the .mtz — reporting "解压主题包失败" even though the file is intact.
        //
        // The original pre-API33 code used reflection to call registerReceiver() without
        // any flag, which defaulted to EXPORTED behaviour. Switching to NOT_EXPORTED
        // silently broke the entire interception mechanism.
        //
        // SECURITY: ACTION_SUPPRESS_AUTO_CLICK only toggles a 120-second auto-click
        // suppression window — not sensitive. CHECK_TIME_UP and ACTION_SCREEN_OFF are
        // sent by system/ThemeManager and are harmless to expose.
        ContextCompat.registerReceiver(this, themeCheckReceiver, filter,
                ContextCompat.RECEIVER_EXPORTED);

        receiverRegistered = true;
        Log.d(TAG, "BroadcastReceiver registered, priority=" + filter.getPriority());
    }

    private void unregisterThemeReceiver() {
        if (!receiverRegistered) return;
        try { unregisterReceiver(themeCheckReceiver); } catch (Exception ignored) {}
        receiverRegistered = false;
    }

    private void saveTimestamp(String key, String value) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(key, value).apply();
    }

    private void broadcastStateChanged() {
        sendBroadcast(new Intent(ACTION_STATE_CHANGED).setPackage(getPackageName()));
    }
}
