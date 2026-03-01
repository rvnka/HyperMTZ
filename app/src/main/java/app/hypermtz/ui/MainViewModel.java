package app.hypermtz.ui;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.hypermtz.BuildConfig;
import app.hypermtz.IPrivilegedService;
import app.hypermtz.R;
import app.hypermtz.service.ThemeInterceptService;
import app.hypermtz.util.Event;
import app.hypermtz.util.ShizukuServiceManager;

/**
 * Survives configuration changes and owns all mutable app state.
 *
 * State flow:
 *   ShizukuServiceManager  →  MainViewModel (LiveData)  →  MainActivity / Dialogs (UI)
 *
 * Implements {@link ShizukuServiceManager.Callback} so it receives Shizuku
 * connect/disconnect events without leaking an Activity reference into the
 * service manager.
 */
public class MainViewModel extends AndroidViewModel
        implements ShizukuServiceManager.Callback {

    private static final String TAG = "MainViewModel";

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.getDefault());

    // ── Backing LiveData ──────────────────────────────────────────────────────

    private final MutableLiveData<Boolean>       _serviceRunning   = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean>       _shizukuConnected = new MutableLiveData<>(false);
    private final MutableLiveData<String>        _connectedTime    = new MutableLiveData<>("");
    private final MutableLiveData<String>        _interceptTime    = new MutableLiveData<>("");
    private final MutableLiveData<String>        _themeStatus      = new MutableLiveData<>("");
    private final MutableLiveData<Boolean>       _themeCopyRunning = new MutableLiveData<>(false);
    private final MutableLiveData<Event<String>> _toastEvent       = new MutableLiveData<>();

    // ── Public read-only LiveData ─────────────────────────────────────────────

    /** True when ThemeInterceptService is enabled in Accessibility Settings. */
    public final LiveData<Boolean>       serviceRunning   = _serviceRunning;

    /** True while the Shizuku UserService binder is alive. */
    public final LiveData<Boolean>       shizukuConnected = _shizukuConnected;

    /** Timestamp string written by ThemeInterceptService on first connect. */
    public final LiveData<String>        connectedTime    = _connectedTime;

    /** Timestamp string written on each theme-check broadcast intercept. */
    public final LiveData<String>        interceptTime    = _interceptTime;

    /** Human-readable theme directory status (computed on a background thread). */
    public final LiveData<String>        themeStatus      = _themeStatus;

    /** True while a theme file copy is in progress. */
    public final LiveData<Boolean>       themeCopyRunning = _themeCopyRunning;

    /**
     * Single-use Toast string events. Observe with {@link Event#getIfNotConsumed()}
     * to avoid replaying on config changes.
     */
    public final LiveData<Event<String>> toastEvent       = _toastEvent;

    // ── Internal state ────────────────────────────────────────────────────────

    @Nullable
    private IPrivilegedService privilegedService;

    private final ShizukuServiceManager shizukuManager;

    /** Single-thread executor for filesystem I/O (theme directory stat). */
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    /**
     * Executor for UI-driven actions (e.g. restart SystemUI).
     * Kept separate from ioExecutor so neither can starve the other.
     */
    private final ExecutorService actionExecutor = Executors.newSingleThreadExecutor();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public MainViewModel(@NonNull Application application) {
        super(application);
        shizukuManager = new ShizukuServiceManager(this);
        // Sticky listener fires immediately if the Shizuku binder is already alive.
        shizukuManager.addListeners();
        refresh();
    }

    @Override
    protected void onCleared() {
        shizukuManager.removeListeners();
        ioExecutor.shutdownNow();
        actionExecutor.shutdownNow();
        super.onCleared();
    }

    // ── ShizukuServiceManager.Callback ────────────────────────────────────────

    @Override
    public void onServiceConnected(IPrivilegedService service) {
        privilegedService = service;
        _shizukuConnected.postValue(true);
    }

    @Override
    public void onServiceDisconnected() {
        privilegedService = null;
        _shizukuConnected.postValue(false);
    }

    @Override
    public void onPermissionGranted() {
        // ShizukuServiceManager binds automatically after permission is granted.
    }

    @Override
    public void onPermissionDenied(boolean isPermanent) {
        _shizukuConnected.postValue(false);
        String msg = getApplication().getString(isPermanent
                ? R.string.shizuku_permission_permanent_denial
                : R.string.shizuku_permission_denied);
        _toastEvent.postValue(new Event<>(msg));
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns the live Shizuku privileged service, or null if not connected. */
    @Nullable
    public IPrivilegedService getPrivilegedService() {
        return privilegedService;
    }

    /** Returns true if the Shizuku UserService binder is bound and alive. */
    public boolean isShizukuAvailable() {
        return shizukuManager.isAvailable();
    }

    /**
     * Called by dialogs when a theme copy starts or finishes.
     * Posts the new state and triggers a theme-status refresh when done.
     */
    public void setThemeCopyRunning(boolean running) {
        _themeCopyRunning.postValue(running);
        if (!running) {
            refreshThemeStatus();
        }
    }

    /**
     * Re-reads all state from ThemeInterceptService and SharedPreferences.
     * Called by the BroadcastReceiver in MainActivity and from onResume.
     */
    public void refresh() {
        _serviceRunning.postValue(ThemeInterceptService.isRunning(getApplication()));
        refreshTimestamps();
        refreshThemeStatus();
    }

    /**
     * Restarts SystemUI via the privileged service. Tries the
     * InstrumentationActivityInvoker bootstrap activity first; falls back to
     * {@code killall com.android.systemui}.
     */
    public void restartSystemUi() {
        IPrivilegedService svc = privilegedService;
        if (svc == null) {
            _toastEvent.postValue(new Event<>(
                    getApplication().getString(R.string.shizuku_not_connected)));
            return;
        }
        actionExecutor.submit(() -> {
            try {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(
                        "com.android.systemui",
                        "androidx.test.core.app.InstrumentationActivityInvoker$BootstrapActivity"));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getApplication().startActivity(intent);
            } catch (Exception primary) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "SystemUI bootstrap activity unavailable, falling back to killall",
                            primary);
                }
                try {
                    svc.execute(new String[]{"killall", "com.android.systemui"});
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to restart SystemUI", e);
                    _toastEvent.postValue(new Event<>(
                            getApplication().getString(R.string.error_restart_system_ui)));
                }
            }
        });
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void refreshTimestamps() {
        SharedPreferences prefs = getApplication()
                .getSharedPreferences(ThemeInterceptService.PREFS_NAME, Context.MODE_PRIVATE);
        _connectedTime.postValue(prefs.getString(ThemeInterceptService.KEY_CONNECTED_TIME, ""));
        _interceptTime.postValue(prefs.getString(ThemeInterceptService.KEY_INTERCEPT_TIME, ""));
    }

    private void refreshThemeStatus() {
        if (Boolean.TRUE.equals(_themeCopyRunning.getValue())) {
            _themeStatus.postValue(getApplication().getString(R.string.copying_theme_files));
            return;
        }
        ioExecutor.submit(() -> _themeStatus.postValue(computeThemeStatus()));
    }

    private String computeThemeStatus() {
        File themeDir = new File("/data/system/theme/compatibility-v12");
        if (!themeDir.isDirectory() || themeDir.listFiles() == null) {
            themeDir = new File("/sdcard/Android/data/com.android.thememanager/files/snapshot");
        }
        if (themeDir.isDirectory() && themeDir.listFiles() != null) {
            String modTime = TIME_FMT.format(
                    Instant.ofEpochMilli(themeDir.lastModified())
                           .atZone(ZoneId.systemDefault())
                           .toLocalDateTime());
            return getApplication().getString(R.string.theme_installed_at, modTime);
        }
        return getApplication().getString(R.string.theme_not_installed);
    }
}
