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
                    .tag("privileged_service")
                    .daemon(false)
                    .debuggable(BuildConfig.DEBUG)
                    .version(BuildConfig.VERSION_CODE);

    // Fields declared as final but initialized in the constructor
    private final ServiceConnection connection;
    private final Shizuku.OnBinderReceivedListener onBinderReceived;
    private final Shizuku.OnBinderDeadListener onBinderDead;
    private final Shizuku.OnRequestPermissionResultListener onPermissionResult;

    public ShizukuServiceManager(Callback callback) {
        this.callback = callback;

        // Initialize connection after callback is assigned
        this.connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                if (binder != null && binder.isBinderAlive()) {
                    service = IPrivilegedService.Stub.asInterface(binder);
                    bound = true;
                    if (BuildConfig.DEBUG) Log.d(TAG, "IPrivilegedService connected");
                    ShizukuServiceManager.this.callback.onServiceConnected(service);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                service = null;
                bound = false;
                if (BuildConfig.DEBUG) Log.d(TAG, "IPrivilegedService disconnected");
                ShizukuServiceManager.this.callback.onServiceDisconnected();
            }
        };

        this.onBinderReceived = () -> {
            if (Shizuku.isPreV11()) {
                Log.w(TAG, "Shizuku version is pre-v11 (unsupported)");
                return;
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "Shizuku binder received (v" + Shizuku.getVersion() + ")");
            checkPermissionAndBind();
        };

        this.onBinderDead = () -> {
            if (BuildConfig.DEBUG) Log.d(TAG, "Shizuku binder died");
            service = null;
            bound = false;
            this.callback.onServiceDisconnected();
        };

        this.onPermissionResult = (requestCode, grantResult) -> {
            if (requestCode != REQUEST_CODE) {
                return;
            }
            boolean granted = grantResult == PackageManager.PERMISSION_GRANTED;
            if (BuildConfig.DEBUG) Log.d(TAG, "Permission result: " + (granted ? "granted" : "denied"));
            if (granted) {
                this.callback.onPermissionGranted();
                bindService();
            } else {
                this.callback.onPermissionDenied(false);
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
        if (bound) {
            Shizuku.unbindUserService(serviceArgs, connection, true);
            bound = false;
            service = null;
        }
    }

    public boolean isAvailable() {
        return bound && service != null;
    }

    private void checkPermissionAndBind() {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            bindService();
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
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
