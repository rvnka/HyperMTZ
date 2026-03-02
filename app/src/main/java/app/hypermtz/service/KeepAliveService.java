package app.hypermtz.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import app.hypermtz.MainActivity;
import app.hypermtz.R;
import app.hypermtz.util.LogManager;
import app.hypermtz.util.PreferenceUtil;

/**
 * Foreground service that keeps the :intercept process alive on MIUI/HyperOS.
 *
 * Enhanced with methods and features ported from ThemeStore's KeepAliveService.kt:
 *
 *  1. Statistics in notification — shows total intercept count and theme install
 *     count fetched from {@link LogManager#getStatistics(Context)} so the user
 *     can see the service's track record at a glance.
 *
 *  2. keep_alive_enabled preference toggle — {@link #start(Context)} checks the
 *     "keep_alive_enabled" boolean in {@link PreferenceUtil} before starting.
 *     If the preference is false the service stops itself.
 *
 *  3. Optimization mode — if "optimization_mode_enabled" is true, an extra
 *     action button is added to the notification. Tapping it sends a broadcast
 *     that MainActivity handles to disable optimization mode and relaunch the UI.
 *
 *  4. {@link #requestRefresh(Context)} — mirrors ThemeStore's helper; equivalent
 *     to {@link #refresh(Context)} but respects the keep_alive_enabled flag.
 */
public class KeepAliveService extends android.app.Service {

    private static final String TAG = "KeepAliveService";

    private static final String CHANNEL_ID      = "hypermtz_keep_alive";
    private static final int    NOTIFICATION_ID  = 1001;

    // Intent actions.
    public static final String ACTION_START   = "app.hypermtz.keepalive.START";
    public static final String ACTION_STOP    = "app.hypermtz.keepalive.STOP";
    public static final String ACTION_REFRESH = "app.hypermtz.keepalive.REFRESH";

    /** Sent by the notification's "Disable optimization" action button. */
    public static final String ACTION_EXIT_OPTIMIZATION = "app.hypermtz.ACTION_EXIT_OPTIMIZATION";

    private boolean isForeground = false;

    // ── Service lifecycle ─────────────────────────────────────────────────────

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        Log.d(TAG, "onStartCommand action=" + action);

        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // Respect keep_alive_enabled preference (default true for backward compat).
        if (!PreferenceUtil.getBoolean("keep_alive_enabled", true)) {
            Log.d(TAG, "keep_alive_enabled=false, stopping");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!hasNotificationPermission()) {
            Log.w(TAG, "Notification permission missing — stopping");
            stopSelf();
            return START_NOT_STICKY;
        }

        showForegroundNotification();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        if (isForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                //noinspection deprecation
                stopForeground(true);
            }
            isForeground = false;
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    /**
     * Starts the foreground service, respecting keep_alive_enabled.
     * If preference is false, stops the service instead.
     */
    public static void start(Context context) {
        if (!PreferenceUtil.getBoolean("keep_alive_enabled", true)) {
            stop(context);
            return;
        }
        Intent intent = new Intent(context, KeepAliveService.class);
        intent.setAction(ACTION_START);
        startCompat(context, intent);
    }

    /**
     * Refreshes the notification. Ported from ThemeStore's requestRefresh().
     * Respects keep_alive_enabled.
     */
    public static void requestRefresh(Context context) {
        if (!PreferenceUtil.getBoolean("keep_alive_enabled", true)) {
            stop(context);
            return;
        }
        Intent intent = new Intent(context, KeepAliveService.class);
        intent.setAction(ACTION_REFRESH);
        startCompat(context, intent);
    }

    /** Alias kept for existing callers in ThemeInterceptService. */
    public static void refresh(Context context) {
        requestRefresh(context);
    }

    /** Stops the foreground service. */
    public static void stop(Context context) {
        context.startService(new Intent(context, KeepAliveService.class)
                .setAction(ACTION_STOP));
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private void showForegroundNotification() {
        createChannelIfNeeded();

        // Fetch statistics — fast, reads only from SharedPreferences.
        LogManager.Statistics stats;
        try {
            stats = LogManager.getStatistics(this);
        } catch (Exception e) {
            Log.e(TAG, "getStatistics failed", e);
            stats = new LogManager.Statistics(0, 0, 0L, 0L, 0L);
        }

        PendingIntent openApp = PendingIntent.getActivity(
                this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // Notification body: statistics if available, else last-intercept time.
        String contentText;
        if (stats.alarmInterceptCount > 0 || stats.themeInstallCount > 0) {
            contentText = getString(R.string.keep_alive_stats,
                    stats.alarmInterceptCount, stats.themeInstallCount);
        } else {
            String interceptTime = getSharedPreferences(
                    ThemeInterceptService.PREFS_NAME, MODE_PRIVATE)
                    .getString(ThemeInterceptService.KEY_INTERCEPT_TIME, null);
            contentText = interceptTime != null
                    ? getString(R.string.keep_alive_last_intercept, interceptTime)
                    : getString(R.string.keep_alive_waiting);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.keep_alive_notification_title))
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_accessibility)
                .setContentIntent(openApp)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setShowWhen(false);

        // Optimization mode: add button to exit from notification.
        // Ported from ThemeStore KeepAliveService.buildNotification().
        if (PreferenceUtil.getBoolean("optimization_mode_enabled", false)) {
            Intent exitIntent = new Intent(ACTION_EXIT_OPTIMIZATION)
                    .setPackage(getPackageName());
            PendingIntent exitPi = PendingIntent.getBroadcast(
                    this, 1, exitIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(0,
                    getString(R.string.optimization_mode_notification_action),
                    exitPi);
        }

        Notification notification = builder.build();

        if (!isForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            isForeground = true;
        } else {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION_ID, notification);
        }
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return NotificationManagerCompat.from(this).areNotificationsEnabled();
    }

    private void createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.keep_alive_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.keep_alive_channel_desc));
        channel.setShowBadge(false);
        channel.enableLights(false);
        channel.enableVibration(false);
        nm.createNotificationChannel(channel);
    }

    private static void startCompat(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }
}
