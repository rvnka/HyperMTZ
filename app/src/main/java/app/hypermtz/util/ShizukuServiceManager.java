package app.hypermtz.util;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import app.hypermtz.BuildConfig;
import app.hypermtz.IPrivilegedService;
import app.hypermtz.service.PrivilegedService;
import rikka.shizuku.Shizuku;

/**
 * Manages the Shizuku binder lifecycle and the IPrivilegedService UserService connection.
 *
 * Fixed bugs vs original:
 *  1. Added {@code binding} flag → prevents double-bindUserService() during the window
 *     between bindUserService() and onServiceConnected(). Old code: bound=false until
 *     onServiceConnected, so every onResume retryConnection() would re-call bind.
 *  2. {@code onPermissionGranted()} only fires on a real grant transition, not every
 *     retryConnection() that finds permission already held.
 *  3. Exposes {@link ShizukuState} enum for granular UI feedback.
 */
public final class ShizukuServiceManager {

    /** Granular Shizuku connection state for the UI layer. */
    public enum ShizukuState {
        UNAVAILABLE,       // Shizuku not installed / server not running
        PERMISSION_NEEDED, // Shizuku running but permission not granted to this app
        CONNECTING,        // Permission granted; UserService binder being established
        CONNECTED          // UserService binder alive and ready for IPC
    }

    public interface Callback {
        void onServiceConnected(IPrivilegedService service);
        void onServiceDisconnected();
        /** Only fired on an actual grant transition (user tapped Allow). */
        void onPermissionGranted();
        void onPermissionDenied(boolean isPermanent);
        void onStateChanged(ShizukuState newState);
    }

    private static final String TAG          = "ShizukuServiceManager";
    private static final int    REQUEST_CODE = 0xADB;

    private final Callback callback;

    @Nullable private IPrivilegedService service;
    private boolean bound   = false;
    /**
     * BUG FIX: guards against double-bind.
     * True from Shizuku.bindUserService() until onServiceConnected() fires.
     * Without this, retryConnection() (called every onResume) would call
     * bindUserService() a second time while bound is still false.
     */
    private boolean binding = false;

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
                binding = false;
                if (binder == null || !binder.isBinderAlive()) {
                    Log.w(TAG, "onServiceConnected: null or dead binder — ignoring");
                    bound = false; service = null;
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
                binding = false; service = null; bound = false;
                reportState(ShizukuState.PERMISSION_NEEDED);
                ShizukuServiceManager.this.callback.onServiceDisconnected();
            }
        };

        this.onBinderReceived = () -> {
            Log.d(TAG, "Shizuku binder received");
            checkAndBind(false);
        };

        this.onBinderDead = () -> {
            Log.d(TAG, "Shizuku binder died");
            binding = false; service = null; bound = false;
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
                binding = false;
                boolean permanent = !Shizuku.shouldShowRequestPermissionRationale();
                reportState(ShizukuState.PERMISSION_NEEDED);
                ShizukuServiceManager.this.callback.onPermissionDenied(permanent);
            }
        };
    }

    @MainThread
    public void addListeners() {
        Shizuku.addBinderDeadListener(onBinderDead);
        Shizuku.addRequestPermissionResultListener(onPermissionResult);
        Shizuku.addBinderReceivedListenerSticky(onBinderReceived);
    }

    @MainThread
    public void removeListeners() {
        Shizuku.removeBinderReceivedListener(onBinderReceived);
        Shizuku.removeBinderDeadListener(onBinderDead);
        Shizuku.removeRequestPermissionResultListener(onPermissionResult);
        releaseService();
    }

    @MainThread
    public void retryConnection() {
        checkAndBind(false);
    }

    public boolean isAvailable() {
        return bound && service != null;
    }

    public ShizukuState getCurrentState() {
        return lastReportedState;
    }

    private void checkAndBind(boolean userInitiated) {
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
            Log.e(TAG, "checkSelfPermission failed (transient)", e);
            return;
        }

        if (perm == PackageManager.PERMISSION_GRANTED) {
            if (bound) {
                reportState(ShizukuState.CONNECTED);
                return;
            }
            // FIX: don't re-bind if already connecting
            if (binding) {
                Log.d(TAG, "checkAndBind: already binding — waiting for onServiceConnected");
                reportState(ShizukuState.CONNECTING);
                return;
            }
            Log.d(TAG, "checkAndBind: permission granted, binding service");
            // NOTE: no onPermissionGranted() here — permission was already granted
            // before this retry. Only the onPermissionResult listener fires that event.
            bindUserService();
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            Log.w(TAG, "checkAndBind: permission permanently denied");
            reportState(ShizukuState.PERMISSION_NEEDED);
            callback.onPermissionDenied(true);
        } else {
            Log.d(TAG, "checkAndBind: requesting permission");
            reportState(ShizukuState.PERMISSION_NEEDED);
            Shizuku.requestPermission(REQUEST_CODE);
        }
    }

    private void bindUserService() {
        if (bound || binding) {
            Log.d(TAG, "bindUserService: already bound or binding");
            return;
        }
        try {
            binding = true;
            reportState(ShizukuState.CONNECTING);
            Log.d(TAG, "bindUserService: calling Shizuku.bindUserService");
            Shizuku.bindUserService(serviceArgs, connection);
        } catch (Exception e) {
            binding = false;
            Log.e(TAG, "bindUserService failed", e);
            reportState(ShizukuState.PERMISSION_NEEDED);
        }
    }

    private void releaseService() {
        binding = false;
        if (bound || service != null) {
            try {
                Shizuku.unbindUserService(serviceArgs, connection, true);
            } catch (Exception e) {
                Log.e(TAG, "unbindUserService failed", e);
            }
        }
        bound = false; service = null;
    }

    private void reportState(ShizukuState state) {
        if (state != lastReportedState) {
            lastReportedState = state;
            callback.onStateChanged(state);
        }
    }
}
