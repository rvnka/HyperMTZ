package app.hypermtz.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Centralized SharedPreferences wrapper.
 *
 * Ported from ThemeStore's PreferenceUtil.kt.
 *
 * Must be initialized once via {@link #init(Context)} in Application.onCreate()
 * before any other code calls get/set methods. After initialization the instance
 * is process-global and thread-safe for reads (SharedPreferences is thread-safe
 * for getters; apply() is also asynchronous and thread-safe).
 *
 * NOTE: KeepAliveService and ThemeInterceptService run in the ":intercept"
 * process. That process has its own memory space, so PreferenceUtil must be
 * initialized separately there (ThemeInterceptService.onCreate calls
 * PreferenceUtil.init via HyperMTZApplication). SharedPreferences files on
 * disk are shared across processes via the same app UID — reads are safe,
 * but concurrent writes from two processes are not guaranteed to be atomic.
 * The stat counters are incremented only from the :intercept process, so
 * in practice there is no write conflict.
 */
public final class PreferenceUtil {

    private static final String PREFS_NAME = "hypermtz_prefs";

    private static SharedPreferences prefs;

    private PreferenceUtil() {}

    /** Call once from Application.onCreate() in every process. */
    public static void init(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ── int ───────────────────────────────────────────────────────────────────

    public static int getInt(String key, int defaultValue) {
        return prefs.getInt(key, defaultValue);
    }

    public static void setInt(String key, int value) {
        prefs.edit().putInt(key, value).apply();
    }

    // ── boolean ───────────────────────────────────────────────────────────────

    public static boolean getBoolean(String key, boolean defaultValue) {
        return prefs.getBoolean(key, defaultValue);
    }

    public static void setBoolean(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
    }

    // ── String ────────────────────────────────────────────────────────────────

    public static String getString(String key, String defaultValue) {
        return prefs.getString(key, defaultValue);
    }

    public static void setString(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }

    // ── Listener support ──────────────────────────────────────────────────────

    public static void registerListener(
            SharedPreferences.OnSharedPreferenceChangeListener listener) {
        prefs.registerOnSharedPreferenceChangeListener(listener);
    }

    public static void unregisterListener(
            SharedPreferences.OnSharedPreferenceChangeListener listener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener);
    }

    public static SharedPreferences getSharedPreferences() {
        return prefs;
    }
}
