package app.hypermtz.util;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import app.hypermtz.BuildConfig;
import app.hypermtz.IPrivilegedService;
import app.hypermtz.service.PrivilegedService;
import rikka.shizuku.Shizuku;

/**
 * Manages the Shizuku binder lifecycle and the IPrivilegedService UserService connection.
 */
public final class ShizukuServiceManager {

    public enum ShizukuState {
        UNAVAILABLE,       // Shizuku not installed / server not running
        PERMISSION_NEEDED, // Shizuku running but permission not granted
        CONNECTING,        // Permission granted; UserService binder being established
        CONNECTED          // UserService binder alive and ready for IPC
    }

    public interface Callback {
        void onServiceConnected(IPrivilegedService service);
        void onServiceDisconnected();
        void onPermissionGranted();
        void onPermissionDenied(boolean isPermanent);
        void onStateChanged(ShizukuState newState);
    }

    private static final String TAG = "ShizukuServiceManager";
    private static final int    REQUEST_CODE = 0xADB;

    /**
     * If bindUserService() was called but onServiceConnected never fires within
     * this many milliseconds, the binding is considered stuck and will be reset
     * on the next retryConnection() call so we can try again cleanly.
     */
    private static final long BIND_TIMEOUT_MS = 10_000L;

    private final Callback callback;
    private final Handler  mainHandler = new Handler(Looper.getMainLooper());

    @Nullable private IPrivilegedService service;
    private boolean bound   = false;
    private boolean binding = false;

    /** Wall-clock time when the current bindUserService() call was made. */
    private long bindingStartMs = 0;

    private ShizukuState lastReportedState = null;

    private final Shizuku.UserServiceArgs serviceArgs =
            new Shizuku.UserServiceArgs(
                    new ComponentName(BuildConfig.APPLICATION_ID,
                            PrivilegedService.class.getName()))
                    .tag(BuildConfig.APPLICATION_ID + ":privileged")
                    .daemon(false)
                    .debuggable(BuildConfig.DEBUG)
                    .version(BuildConfig.VERSION_CODE);

    private final ServiceConnection       connection;
    private final Shizuku.OnBinderReceivedListener          onBinderReceived;
    private final Shizuku.OnBinderDeadListener              onBinderDead;
    private final Shizuku.OnRequestPermissionResultListener onPermissionResult;

    public ShizukuServiceManager(Callback callback) {
        this.callback = callback;

        this.connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                binding      = false;
                bindingStartMs = 0;

                if (binder == null || !binder.isBinderAlive()) {
                    Log.w(TAG, "onServiceConnected: received dead binder");
                    bound   = false;
                    service = null;
                    reportState(ShizukuState.CONNECTING);
                    return;
                }
                service = IPrivilegedService.Stub.asInterface(binder);
                bound   = true;
                Log.d(TAG, "IPrivilegedService connected");
                reportState(ShizukuState.CONNECTED);
                ShizukuServiceManager.this.callback.onServiceConnected(service);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.d(TAG, "IPrivilegedService disconnected");
                binding        = false;
                bindingStartMs = 0;
                service        = null;
                bound          = false;
                // Permission is still granted — don't report PERMISSION_NEEDED.
                // Just notify the caller and let the next retryConnection() rebind.
                ShizukuServiceManager.this.callback.onServiceDisconnected();
                // Update UI to CONNECTING so the user sees a pending state, not an
                // error. The next retryConnection() (from onResume) will rebind.
                reportState(ShizukuState.CONNECTING);
            }
        };

        this.onBinderReceived = () -> {
            Log.d(TAG, "Shizuku binder received");
            checkAndBind();
        };

        this.onBinderDead = () -> {
            Log.d(TAG, "Shizuku binder died");
            binding        = false;
            bindingStartMs = 0;
            service        = null;
            bound          = false;
            reportState(ShizukuState.UNAVAILABLE);
            ShizukuServiceManager.this.callback.onServiceDisconnected();
        };

        this.onPermissionResult = (requestCode, grantResult) -> {
            if (requestCode != REQUEST_CODE) return;
            boolean granted = (grantResult == PackageManager.PERMISSION_GRANTED);
            Log.d(TAG, "Permission result: " + (granted ? "GRANTED" : "DENIED"));
            if (granted) {
                ShizukuServiceManager.this.callback.onPermissionGranted();
                bindUserService();
            } else {
                binding        = false;
                bindingStartMs = 0;
                boolean permanent = !Shizuku.shouldShowRequestPermissionRationale();
                reportState(ShizukuState.PERMISSION_NEEDED);
                ShizukuServiceManager.this.callback.onPermissionDenied(permanent);
            }
        };
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @MainThread
    public void addListeners() {
        Shizuku.addBinderDeadListener(onBinderDead);
        Shizuku.addRequestPermissionResultListener(onPermissionResult);
        Shizuku.addBinderReceivedListenerSticky(onBinderReceived); // fires sync if binder alive
    }

    @MainThread
    public void removeListeners() {
        Shizuku.removeBinderReceivedListener(onBinderReceived);
        Shizuku.removeBinderDeadListener(onBinderDead);
        Shizuku.removeRequestPermissionResultListener(onPermissionResult);
        releaseService();
    }

    /**
     * Call from Activity.onResume() every time.
     *
     * If a bindUserService() call has been in-flight longer than BIND_TIMEOUT_MS
     * without onServiceConnected firing, we treat it as stuck and reset so we can
     * try again. This is the only place the binding flag is force-cleared — we do
     * NOT clear it unconditionally (that causes duplicate bind calls which make
     * Shizuku drop the onServiceConnected callback, causing the stuck state).
     */
    @MainThread
    public void retryConnection() {
        // Clear a stuck binding (Shizuku started the UserService process but
        // onServiceConnected never came back within the timeout window).
        if (binding && bindingStartMs > 0
                && (System.currentTimeMillis() - bindingStartMs) > BIND_TIMEOUT_MS) {
            Log.w(TAG, "retryConnection: bind timed out after "
                    + BIND_TIMEOUT_MS + " ms, resetting");
            binding        = false;
            bindingStartMs = 0;
            // Unbind cleanly before retrying so Shizuku doesn't hold a stale
            // connection entry for our ServiceConnection object.
            try {
                Shizuku.unbindUserService(serviceArgs, connection, false);
            } catch (Exception ignored) {}
        }
        checkAndBind();
    }

    // ── Public state ──────────────────────────────────────────────────────────

    public boolean isAvailable() {
        if (!bound || service == null) return false;
        try {
            return service.asBinder().isBinderAlive();
        } catch (Exception e) {
            return false;
        }
    }

    public ShizukuState getCurrentState() {
        return lastReportedState;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void checkAndBind() {
        // Already connected and binder alive — nothing to do.
        if (bound && isAvailable()) {
            reportState(ShizukuState.CONNECTED);
            return;
        }

        if (!Shizuku.pingBinder()) {
            Log.w(TAG, "checkAndBind: Shizuku not running");
            reportState(ShizukuState.UNAVAILABLE);
            return;
        }
        if (Shizuku.isPreV11()) {
            Log.w(TAG, "checkAndBind: Shizuku pre-v11 not supported");
            reportState(ShizukuState.UNAVAILABLE);
            return;
        }

        int perm;
        try {
            perm = Shizuku.checkSelfPermission();
        } catch (Exception e) {
            Log.e(TAG, "checkSelfPermission failed", e);
            reportState(ShizukuState.PERMISSION_NEEDED);
            return;
        }

        if (perm == PackageManager.PERMISSION_GRANTED) {
            // A bind is already in-flight and hasn't timed out yet — don't call
            // bindUserService() a second time. Duplicate calls with the same
            // ServiceConnection object cause Shizuku to silently drop the
            // onServiceConnected callback, leaving the state stuck at CONNECTING.
            if (binding) {
                Log.d(TAG, "checkAndBind: bind already in-flight, waiting");
                reportState(ShizukuState.CONNECTING);
                return;
            }
            bindUserService();
        } else {
            // Always call requestPermission() — even if shouldShowRationale() is true.
            // Shizuku handles the rationale UI itself; suppressing the call here only
            // leaves the user permanently stuck on "Permission required".
            Log.d(TAG, "checkAndBind: requesting permission");
            reportState(ShizukuState.PERMISSION_NEEDED);
            Shizuku.requestPermission(REQUEST_CODE);
        }
    }

    private void bindUserService() {
        if (binding || bound) {
            Log.d(TAG, "bindUserService: already binding or bound");
            return;
        }
        try {
            binding        = true;
            bindingStartMs = System.currentTimeMillis();
            reportState(ShizukuState.CONNECTING);
            Log.d(TAG, "bindUserService: calling Shizuku.bindUserService");
            Shizuku.bindUserService(serviceArgs, connection);
        } catch (Exception e) {
            binding        = false;
            bindingStartMs = 0;
            Log.e(TAG, "bindUserService failed", e);
            reportState(ShizukuState.CONNECTING);
        }
    }

    private void releaseService() {
        binding        = false;
        bindingStartMs = 0;
        if (bound || service != null) {
            try {
                Shizuku.unbindUserService(serviceArgs, connection, true);
            } catch (Exception e) {
                Log.e(TAG, "unbindUserService failed", e);
            }
        }
        bound   = false;
        service = null;
    }

    private void reportState(ShizukuState state) {
        if (state != lastReportedState) {
            lastReportedState = state;
            callback.onStateChanged(state);
        }
    }
}
