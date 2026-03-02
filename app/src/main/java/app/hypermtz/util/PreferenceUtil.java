package app.hypermtz.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Centralized SharedPreferences wrapper — ported from ThemeStore's PreferenceUtil.kt.
 *
 * Must be initialized once via {@link #init(Context)} in Application.onCreate()
 * before any call to get/set methods. Runs in every process (main + :intercept)
 * because HyperMTZApplication declares android:name in the manifest.
 */
public final class PreferenceUtil {

    private static final String PREFS_NAME = "hypermtz_prefs";

    private static volatile SharedPreferences sPrefs;

    private PreferenceUtil() {}

    /** Call once from Application.onCreate() in every process. */
    public static void init(Context context) {
        sPrefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static SharedPreferences prefs() {
        SharedPreferences p = sPrefs;
        if (p == null) throw new IllegalStateException(
                "PreferenceUtil not initialized — call PreferenceUtil.init() in Application.onCreate()");
        return p;
    }

    // ── int ───────────────────────────────────────────────────────────────────

    public static int getInt(String key, int defaultValue) {
        return prefs().getInt(key, defaultValue);
    }

    public static void setInt(String key, int value) {
        prefs().edit().putInt(key, value).apply();
    }

    // ── boolean ───────────────────────────────────────────────────────────────

    public static boolean getBoolean(String key, boolean defaultValue) {
        return prefs().getBoolean(key, defaultValue);
    }

    public static void setBoolean(String key, boolean value) {
        prefs().edit().putBoolean(key, value).apply();
    }

    // ── String ────────────────────────────────────────────────────────────────

    public static String getString(String key, String defaultValue) {
        return prefs().getString(key, defaultValue);
    }

    public static void setString(String key, String value) {
        prefs().edit().putString(key, value).apply();
    }

    // ── Listener support ──────────────────────────────────────────────────────

    public static void registerListener(
            SharedPreferences.OnSharedPreferenceChangeListener listener) {
        prefs().registerOnSharedPreferenceChangeListener(listener);
    }

    public static void unregisterListener(
            SharedPreferences.OnSharedPreferenceChangeListener listener) {
        prefs().unregisterOnSharedPreferenceChangeListener(listener);
    }
}
