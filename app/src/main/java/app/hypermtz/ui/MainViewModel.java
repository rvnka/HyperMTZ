package app.hypermtz.ui;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

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

import app.hypermtz.IPrivilegedService;
import app.hypermtz.R;
import app.hypermtz.service.ThemeInterceptService;
import app.hypermtz.util.Event;
import app.hypermtz.util.ShizukuServiceManager;

/**
 * Survives configuration changes and owns all mutable app state.
 */
public class MainViewModel extends AndroidViewModel
        implements ShizukuServiceManager.Callback {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.getDefault());

    // ── Backing LiveData ──────────────────────────────────────────────────────

    private final MutableLiveData<Boolean>       _serviceRunning   = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean>       _shizukuConnected = new MutableLiveData<>(false);
    private final MutableLiveData<ShizukuServiceManager.ShizukuState>
                                                 _shizukuState     = new MutableLiveData<>(
                                                         ShizukuServiceManager.ShizukuState.UNAVAILABLE);
    private final MutableLiveData<String>        _connectedTime    = new MutableLiveData<>("");
    private final MutableLiveData<String>        _interceptTime    = new MutableLiveData<>("");
    private final MutableLiveData<String>        _themeStatus      = new MutableLiveData<>("");
    private final MutableLiveData<Boolean>       _themeCopyRunning = new MutableLiveData<>(false);
    private final MutableLiveData<Event<String>> _toastEvent       = new MutableLiveData<>();

    // ── Public read-only LiveData ─────────────────────────────────────────────

    public final LiveData<Boolean>                             serviceRunning   = _serviceRunning;
    public final LiveData<Boolean>                             shizukuConnected = _shizukuConnected;
    /** Granular Shizuku state (UNAVAILABLE / PERMISSION_NEEDED / CONNECTING / CONNECTED). */
    public final LiveData<ShizukuServiceManager.ShizukuState> shizukuState     = _shizukuState;
    public final LiveData<String>                              connectedTime    = _connectedTime;
    public final LiveData<String>                              interceptTime    = _interceptTime;
    public final LiveData<String>                              themeStatus      = _themeStatus;
    public final LiveData<Boolean>                             themeCopyRunning = _themeCopyRunning;
    public final LiveData<Event<String>>                       toastEvent       = _toastEvent;

    // ── Internal state ────────────────────────────────────────────────────────

    @Nullable private IPrivilegedService privilegedService;
    private final ShizukuServiceManager shizukuManager;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public MainViewModel(@NonNull Application application) {
        super(application);
        shizukuManager = new ShizukuServiceManager(this);
        shizukuManager.addListeners();
        refresh();
    }

    @Override
    protected void onCleared() {
        shizukuManager.removeListeners();
        ioExecutor.shutdownNow();
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
        // ShizukuServiceManager handles binding automatically after grant.
    }

    @Override
    public void onPermissionDenied(boolean isPermanent) {
        _shizukuConnected.postValue(false);
        String msg = getApplication().getString(isPermanent
                ? R.string.shizuku_permission_permanent_denial
                : R.string.shizuku_permission_denied);
        _toastEvent.postValue(new Event<>(msg));
    }

    @Override
    public void onStateChanged(ShizukuServiceManager.ShizukuState newState) {
        _shizukuState.postValue(newState);
        if (newState == ShizukuServiceManager.ShizukuState.CONNECTED) {
            _shizukuConnected.postValue(true);
        } else if (newState != ShizukuServiceManager.ShizukuState.CONNECTING) {
            _shizukuConnected.postValue(false);
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    @Nullable
    public IPrivilegedService getPrivilegedService() {
        return privilegedService;
    }

    public boolean isShizukuAvailable() {
        return shizukuManager.isAvailable();
    }

    public void retryShizuku() {
        shizukuManager.retryConnection();
    }

    public void setThemeCopyRunning(boolean running) {
        _themeCopyRunning.postValue(running);
        if (!running) refreshThemeStatus();
    }

    /**
     * Re-reads all state from ThemeInterceptService and SharedPreferences.
     * isRunning() (binder IPC) runs on ioExecutor — not the main thread.
     */
    public void refresh() {
        ioExecutor.submit(() -> {
            boolean running = ThemeInterceptService.isRunning(getApplication());
            _serviceRunning.postValue(running);
        });
        refreshTimestamps();
        refreshThemeStatus();
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
        // FIX: use Environment.getExternalStorageDirectory() — not hardcoded /sdcard/.
        // FIX: first candidate updated to files/snapshot/snapshot.mtz — the actual
        //      install destination used by FileApplyDialogFragment (both ZWS and
        //      Shizuku strategies write here). The old files/theme/安装主题.mtz path
        //      is no longer written and would always return "not installed".
        String extRoot = Environment.getExternalStorageDirectory().getPath();
        File[] candidates = {
            new File(extRoot + "/Android/data/com.android.thememanager/files/snapshot/snapshot.mtz"),
            new File(extRoot + "/Android/data/com.android.thememanager/files/snapshot"),
            new File("/data/system/theme/compatibility-v12"),
        };
        for (File f : candidates) {
            if (f.exists() && f.lastModified() > 0) {
                String modTime = TIME_FMT.format(
                        Instant.ofEpochMilli(f.lastModified())
                               .atZone(ZoneId.systemDefault())
                               .toLocalDateTime());
                return getApplication().getString(R.string.theme_installed_at, modTime);
            }
        }
        return getApplication().getString(R.string.theme_not_installed);
    }
}
