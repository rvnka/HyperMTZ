package app.hypermtz.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Accessibility service that intercepts MIUI theme authorization broadcasts.
 */
public class ThemeInterceptService extends AccessibilityService {

    /** Broadcast sent to MainActivity when service state changes. */
    public static final String ACTION_STATE_CHANGED = "app.hypermtz.ACTION_STATE_CHANGED";

    /** MIUI theme license expiry trigger. */
    private static final String ACTION_THEME_CHECK = "miui.intent.action.CHECK_TIME_UP";

    public static final String PREFS_NAME          = "hypermtz_state";
    public static final String KEY_CONNECTED_TIME  = "connected_time";
    public static final String KEY_INTERCEPT_TIME  = "intercept_time";

    private boolean receiverRegistered = false;

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.getDefault());

    private final BroadcastReceiver themeCheckReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_THEME_CHECK.equals(intent.getAction())) {
                saveTimestamp(KEY_INTERCEPT_TIME, TIME_FMT.format(LocalDateTime.now()));
            }
            broadcastStateChanged();
        }
    };

    public static boolean isRunning(Context context) {
        AccessibilityManager manager =
                (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null) {
            return false;
        }
        List<AccessibilityServiceInfo> enabled =
                manager.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        String targetClass   = ThemeInterceptService.class.getName();
        String targetPackage = context.getPackageName();
        for (AccessibilityServiceInfo info : enabled) {
            ServiceInfo si = info.getResolveInfo().serviceInfo;
            if (targetPackage.equals(si.packageName) && targetClass.equals(si.name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        saveTimestamp(KEY_CONNECTED_TIME, TIME_FMT.format(LocalDateTime.now()));
        registerThemeReceiver();
        broadcastStateChanged();
    }

    @Override
    public void onServiceConnected() {
        broadcastStateChanged();
        super.onServiceConnected();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No event handling needed
    }

    @Override
    public void onInterrupt() {
        // No active operations to cancel
    }

    @Override
    public void onDestroy() {
        unregisterThemeReceiver();
        broadcastStateChanged();
        super.onDestroy();
    }

    private void registerThemeReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_THEME_CHECK);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);

        if (Build.VERSION.SDK_INT >= 33) {
            // RECEIVER_EXPORTED allows external MIUI ThemeManager to deliver broadcasts
            registerReceiver(themeCheckReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(themeCheckReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void unregisterThemeReceiver() {
        if (!receiverRegistered) {
            return;
        }
        unregisterReceiver(themeCheckReceiver);
        receiverRegistered = false;
    }

    private void saveTimestamp(String key, String value) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putString(key, value).apply();
    }

    private void broadcastStateChanged() {
        Intent intent = new Intent(ACTION_STATE_CHANGED);
        intent.setPackage(getPackageName()); // Targets only this app's MainActivity
        sendBroadcast(intent);
    }
}
