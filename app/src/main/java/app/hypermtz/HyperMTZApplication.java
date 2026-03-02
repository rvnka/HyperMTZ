package app.hypermtz;

import android.app.Application;

import app.hypermtz.util.PreferenceUtil;

/**
 * Application class.
 *
 * Ported from ThemeStore's ThemeStoreApplication.kt.
 *
 * Initializes {@link PreferenceUtil} before any Activity or Service starts.
 * This runs in every process — including the ":intercept" process — so
 * PreferenceUtil is always ready when ThemeInterceptService or KeepAliveService
 * access it.
 */
public class HyperMTZApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        PreferenceUtil.init(this);
    }
}
