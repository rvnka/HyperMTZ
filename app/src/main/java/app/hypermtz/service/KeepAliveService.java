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
 * Enhanced with features ported from ThemeStore's KeepAliveService.kt:
 *
 *  1. Statistics in notification — intercept count and install count from
 *     {@link LogManager#getStatistics(Context)}, updated after each event.
 *
 *  2. keep_alive_enabled preference — {@link #start(Context)} checks the
 *     "keep_alive_enabled" preference; if false, stops the service.
 *
 *  3. Optimization mode button — adds "Disable Optimization" action to the
 *     notification. Uses PendingIntent.getActivity() (NOT getBroadcast) so
 *     that tapping the button relaunches MainActivity even when the main
 *     process was killed by optimization mode.
 *
 * Design: runs in ":intercept" process together with ThemeInterceptService.
 * The foreground notification prevents MIUI from killing the process.
 */
public class KeepAliveService extends android.app.Service {

    private static final String TAG            = "KeepAliveService";
    private static final String CHANNEL_ID     = "hypermtz_keep_alive";
    private static final int    NOTIFICATION_ID = 1001;

    public static final String ACTION_START   = "app.hypermtz.keepalive.START";
    public static final String ACTION_STOP    = "app.hypermtz.keepalive.STOP";
    public static final String ACTION_REFRESH = "app.hypermtz.keepalive.REFRESH";

    /**
     * Intent extra key placed on the MainActivity launch intent when the user taps
     * "Disable Optimization" in the notification. MainActivity checks this in onCreate().
     */
    public static final String EXTRA_EXIT_OPTIMIZATION = "exit_optimization";

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

        // Respect keep_alive_enabled preference (default true = backward compatible).
        if (!PreferenceUtil.getBoolean("keep_alive_enabled", true)) {
            Log.d(TAG, "keep_alive_enabled=false, stopping");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!hasNotificationPermission()) {
            Log.w(TAG, "No notification permission — stopping");
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

    /** Starts the foreground service. Stops it if keep_alive_enabled=false. */
    public static void start(Context context) {
        if (!PreferenceUtil.getBoolean("keep_alive_enabled", true)) {
            stop(context);
            return;
        }
        startCompat(context, new Intent(context, KeepAliveService.class).setAction(ACTION_START));
    }

    /**
     * Refreshes the notification content with updated statistics.
     * Ported from ThemeStore's requestRefresh(). Stops service if keep_alive_enabled=false.
     */
    public static void requestRefresh(Context context) {
        if (!PreferenceUtil.getBoolean("keep_alive_enabled", true)) {
            stop(context);
            return;
        }
        startCompat(context, new Intent(context, KeepAliveService.class).setAction(ACTION_REFRESH));
    }

    /** Alias for {@link #requestRefresh(Context)} kept for existing callers. */
    public static void refresh(Context context) {
        requestRefresh(context);
    }

    /** Stops the foreground service. */
    public static void stop(Context context) {
        // Use startService (not startForegroundService) — the service just calls stopSelf().
        context.startService(new Intent(context, KeepAliveService.class).setAction(ACTION_STOP));
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private void showForegroundNotification() {
        createChannelIfNeeded();

        // Statistics — fast, reads only from SharedPreferences.
        LogManager.Statistics stats;
        try {
            stats = LogManager.getStatistics(this);
        } catch (Exception e) {
            Log.e(TAG, "getStatistics failed", e);
            stats = new LogManager.Statistics(0, 0, 0L, 0L, 0L);
        }

        // Tap notification → open MainActivity.
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // Notification body: show stats when available, else waiting message.
        String body;
        if (stats.alarmInterceptCount > 0 || stats.themeInstallCount > 0) {
            body = getString(R.string.keep_alive_stats,
                    stats.alarmInterceptCount, stats.themeInstallCount);
        } else {
            String lastTime = getSharedPreferences(ThemeInterceptService.PREFS_NAME, MODE_PRIVATE)
                    .getString(ThemeInterceptService.KEY_INTERCEPT_TIME, null);
            body = lastTime != null
                    ? getString(R.string.keep_alive_last_intercept, lastTime)
                    : getString(R.string.keep_alive_waiting);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.keep_alive_notification_title))
                .setContentText(body)
                .setSmallIcon(R.drawable.ic_accessibility)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setShowWhen(false);

        // Optimization mode button — ported from ThemeStore.
        //
        // IMPORTANT: Uses PendingIntent.getActivity() (not getBroadcast) so that tapping
        // this button relaunches MainActivity even when the main process was killed by
        // optimization mode. getBroadcast cannot wake a dead process.
        if (PreferenceUtil.getBoolean("optimization_mode_enabled", false)) {
            Intent exitIntent = new Intent(this, MainActivity.class);
            exitIntent.putExtra(EXTRA_EXIT_OPTIMIZATION, true);
            exitIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent exitPi = PendingIntent.getActivity(
                    this, 1, exitIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(0, getString(R.string.optimization_mode_notification_action), exitPi);
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

        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.keep_alive_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.keep_alive_channel_desc));
        ch.setShowBadge(false);
        ch.enableLights(false);
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
    }

    private static void startCompat(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }
}
