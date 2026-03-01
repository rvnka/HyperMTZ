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
 * All callbacks are delivered on an arbitrary thread. Callers are responsible
 * for dispatching UI updates to the main thread.
 *
 * Lifecycle:
 *   1. Construct and call {@link #addListeners()} in the ViewModel constructor.
 *   2. Remove with {@link #removeListeners()} in {@code ViewModel.onCleared()}.
 *   3. Implement {@link Callback} to react to connect / disconnect / permission events.
 *
 * Permission flow (per the official Shizuku-API guide):
 *   isPreV11()                              → unsupported, warn and return
 *   checkSelfPermission() == GRANTED        → bind directly
 *   shouldShowRequestPermissionRationale()  → permanently denied; guide user to Shizuku app
 *   else                                    → call requestPermission()
 */
public final class ShizukuServiceManager {

    public interface Callback {
        void onServiceConnected(IPrivilegedService service);
        void onServiceDisconnected();
        void onPermissionGranted();
        void onPermissionDenied(boolean isPermanent);
    }

    private static final String TAG          = "ShizukuServiceManager";
    private static final int    REQUEST_CODE = 0xADB;

    private final Callback callback;

    @Nullable
    private IPrivilegedService service;
    private boolean bound = false;

    private final Shizuku.UserServiceArgs serviceArgs =
            new Shizuku.UserServiceArgs(
                    new ComponentName(BuildConfig.APPLICATION_ID,
                            PrivilegedService.class.getName()))
                    .tag("privileged_service") // stable tag — required since class name is mangled by R8
                    .daemon(false)
                    .debuggable(BuildConfig.DEBUG)
                    .version(BuildConfig.VERSION_CODE);

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (binder != null && binder.isBinderAlive()) {
                service = IPrivilegedService.Stub.asInterface(binder);
                bound = true;
                if (BuildConfig.DEBUG) Log.d(TAG, "IPrivilegedService connected");
                callback.onServiceConnected(service);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
            if (BuildConfig.DEBUG) Log.d(TAG, "IPrivilegedService disconnected");
            callback.onServiceDisconnected();
        }
    };

    private final Shizuku.OnBinderReceivedListener onBinderReceived = () -> {
        if (Shizuku.isPreV11()) {
            Log.w(TAG, "Shizuku version is pre-v11 (unsupported)");
            return;
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Shizuku binder received (v" + Shizuku.getVersion() + ")");
        checkPermissionAndBind();
    };

    private final Shizuku.OnBinderDeadListener onBinderDead = () -> {
        if (BuildConfig.DEBUG) Log.d(TAG, "Shizuku binder died");
        service = null;
        bound = false;
        callback.onServiceDisconnected();
    };

    private final Shizuku.OnRequestPermissionResultListener onPermissionResult =
            (requestCode, grantResult) -> {
                if (requestCode != REQUEST_CODE) {
                    return;
                }
                boolean granted = grantResult == PackageManager.PERMISSION_GRANTED;
                if (BuildConfig.DEBUG) Log.d(TAG, "Permission result: " + (granted ? "granted" : "denied"));
                if (granted) {
                    callback.onPermissionGranted();
                    bindService();
                } else {
                    callback.onPermissionDenied(false);
                }
            };

    public ShizukuServiceManager(Callback callback) {
        this.callback = callback;
    }

    /**
     * Registers all Shizuku listeners. The sticky binder listener fires immediately
     * if the binder is already alive, so it is registered last to avoid ordering issues.
     */
    @MainThread
    public void addListeners() {
        Shizuku.addBinderDeadListener(onBinderDead);
        Shizuku.addRequestPermissionResultListener(onPermissionResult);
        Shizuku.addBinderReceivedListenerSticky(onBinderReceived);
    }

    /**
     * Unregisters all Shizuku listeners and releases the UserService binding.
     */
    @MainThread
    public void removeListeners() {
        Shizuku.removeBinderReceivedListener(onBinderReceived);
        Shizuku.removeBinderDeadListener(onBinderDead);
        Shizuku.removeRequestPermissionResultListener(onPermissionResult);
        if (bound) {
            Shizuku.unbindUserService(serviceArgs, connection, true);
            bound = false;
            service = null;
        }
    }

    /** Returns true if the UserService binder is bound and alive. */
    public boolean isAvailable() {
        return bound && service != null;
    }

    private void checkPermissionAndBind() {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            bindService();
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            // Permanently denied — the user must grant the permission in the Shizuku app.
            Log.w(TAG, "Permission permanently denied — user must grant in Shizuku app");
            callback.onPermissionDenied(true);
        } else {
            Shizuku.requestPermission(REQUEST_CODE);
        }
    }

    private void bindService() {
        if (bound) {
            return;
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Binding IPrivilegedService");
        Shizuku.bindUserService(serviceArgs, connection);
    }
}
