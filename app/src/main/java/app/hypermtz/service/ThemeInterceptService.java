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

public class ThemeInterceptService extends AccessibilityService {

    // Define the constant here so it is centrally managed
    public static final String ACTION_STATE_CHANGED = "app.hypermtz.action_Service_UP";
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
        AccessibilityManager manager = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null) return false;
        List<AccessibilityServiceInfo> enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo info : enabled) {
            ServiceInfo si = info.getResolveInfo().serviceInfo;
            if (context.getPackageName().equals(si.packageName) && ThemeInterceptService.class.getName().equals(si.name)) {
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
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        unregisterThemeReceiver();
        broadcastStateChanged();
        super.onDestroy();
    }

    private void registerThemeReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_THEME_CHECK);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);

        // MIUI ThemeManager is an external app, so this MUST be EXPORTED
        ContextCompat.registerReceiver(this, themeCheckReceiver, filter, ContextCompat.RECEIVER_EXPORTED);
        receiverRegistered = true;
    }

    private void unregisterThemeReceiver() {
        if (!receiverRegistered) return;
        unregisterReceiver(themeCheckReceiver);
        receiverRegistered = false;
    }

    private void saveTimestamp(String key, String value) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(key, value).apply();
    }

    private void broadcastStateChanged() {
        Intent intent = new Intent(ACTION_STATE_CHANGED);
        // Ensure the intent stays within your own app
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }
}
