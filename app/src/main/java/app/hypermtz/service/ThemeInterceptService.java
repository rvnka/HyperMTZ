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

import androidx.core.content.ContextCompat;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public class ThemeInterceptService extends AccessibilityService {

    public static final String ACTION_STATE_CHANGED = "app.hypermtz.action_Service_UP";
    private static final String ACTION_THEME_CHECK = "miui.intent.action.CHECK_TIME_UP";

    public static final String PREFS_NAME         = "hypermtz_state";
    public static final String KEY_CONNECTED_TIME = "connected_time";
    public static final String KEY_INTERCEPT_TIME = "intercept_time";

    private static final String TAG = "ThemeInterceptService";

    /**
     * Candidate button texts to click when ThemeManager shows an approval dialog.
     *
     * Priority order matters: Chinese UI strings first (most MIUI devices are CN),
     * then English fallbacks.
     *
     * Context for each string:
     *  应用  — "Apply" (apply theme button)
     *  确定  — "Confirm / OK" (generic approval)
     *  安装  — "Install" (install theme)
     *  继续  — "Continue" (continue despite warning)
     *  允许  — "Allow" (allow operation)
     *  跳过  — "Skip" (skip restriction check)
     *  忽略  — "Ignore" (ignore the restriction warning)
     *  取消限制 — "Cancel restriction" (MIUI-specific theme trial dialog)
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
            Log.d(TAG, "BroadcastReceiver: " + action);
            if (ACTION_THEME_CHECK.equals(action)) {
                // MIUI sends CHECK_TIME_UP as an ordered broadcast to trigger theme
                // cleanup. abortBroadcast() prevents ThemeManager from receiving it,
                // so the theme persists indefinitely.
                //
                // SAFETY GUARD: abortBroadcast() throws UnsupportedOperationException
                // if called on a non-ordered broadcast. Some MIUI/HyperOS versions may
                // send this as a regular broadcast depending on the ROM build. Without
                // the guard the exception would silently kill onReceive() and leave
                // the receiver in a broken state for future deliveries.
                if (isOrderedBroadcast()) {
                    abortBroadcast();
                }
                recordIntercept();
            }
            // SCREEN_OFF: no action needed — receiver is registered to keep the
            // process alive when the screen turns off (MIUI aggressively kills
            // services after screen-off; holding a broadcast wakelock helps).
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
        saveTimestamp(KEY_CONNECTED_TIME, TIME_FMT.format(LocalDateTime.now()));
        registerThemeReceiver();
        broadcastStateChanged();
    }

    @Override
    public void onServiceConnected() {
        Log.d(TAG, "onServiceConnected");
        broadcastStateChanged();
        super.onServiceConnected();
    }

    @Override
    public void onDestroy() {
        unregisterThemeReceiver();
        broadcastStateChanged();
        super.onDestroy();
    }

    // ── AccessibilityService ───────────────────────────────────────────────────

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // packageNames filter in accessibility_service_config.xml already restricts
        // events to com.android.thememanager / com.miui.thememanager, but double-check
        // here so a future config change can't cause unintended behaviour.
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !pkg.toString().contains("thememanager")) return;

        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }

        // canRetrieveWindowContent="true" is required in the accessibility config
        // for getRootInActiveWindow() to return non-null.
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            if (tryClickApprovalButton(root)) {
                Log.d(TAG, "Clicked approval button in ThemeManager");
                recordIntercept();
            }
        } finally {
            root.recycle();
        }
    }

    @Override
    public void onInterrupt() {}

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Walks the accessibility node tree looking for a visible, enabled, clickable
     * button whose text matches one of the APPROVAL_TEXTS entries.
     *
     * Uses {@link AccessibilityNodeInfo#findAccessibilityNodeInfosByText} which does
     * a case-insensitive substring match — so "Apply theme" matches "Apply".
     *
     * @return true if a button was found and clicked.
     */
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
    }

    private void registerThemeReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_THEME_CHECK);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        // MIUI ThemeManager is an external app → RECEIVER_EXPORTED required.
        ContextCompat.registerReceiver(this, themeCheckReceiver, filter,
                ContextCompat.RECEIVER_EXPORTED);
        receiverRegistered = true;
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
