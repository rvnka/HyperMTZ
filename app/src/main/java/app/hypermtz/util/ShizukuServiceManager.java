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
 * Follows the official Shizuku-API pattern:
 *   https://github.com/RikkaApps/Shizuku-API
 *
 * Key rules:
 *  - Only call Shizuku APIs when the binder is alive (inside onBinderReceived or onPermissionResult)
 *  - addBinderReceivedListenerSticky fires immediately if binder is already alive
 *  - onPermissionDenied(true) = permanently denied (user tapped "Don't ask again")
 *  - onPermissionDenied(false) = user explicitly tapped Deny in the dialog
 *  - Never call onPermissionDenied for transient errors or "not yet requested" states
 */
public final class ShizukuServiceManager {

    public interface Callback {
        void onServiceConnected(IPrivilegedService service);
        void onServiceDisconnected();
        void onPermissionGranted();
        /** Only called when user explicitly denied. isPermanent = true means "Don't ask again". */
        void onPermissionDenied(boolean isPermanent);
    }

    private static final String TAG          = "ShizukuServiceManager";
    private static final int    REQUEST_CODE = 0xADB;

    private final Callback callback;

    @Nullable private IPrivilegedService service;
    private boolean bound = false;

    // ── UserService config ────────────────────────────────────────────────────

    private final Shizuku.UserServiceArgs serviceArgs =
            new Shizuku.UserServiceArgs(
                    new ComponentName(BuildConfig.APPLICATION_ID,
                            PrivilegedService.class.getName()))
                    .tag("privileged_service")
                    .daemon(false)
                    .debuggable(BuildConfig.DEBUG)
                    .version(BuildConfig.VERSION_CODE);

    // ── Listeners (kept as fields so they can be removed later) ──────────────

    private final ServiceConnection connection;
    private final Shizuku.OnBinderReceivedListener onBinderReceived;
    private final Shizuku.OnBinderDeadListener onBinderDead;
    private final Shizuku.OnRequestPermissionResultListener onPermissionResult;

    // ── Constructor ───────────────────────────────────────────────────────────

    public ShizukuServiceManager(Callback callback) {
        this.callback = callback;

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

        // addBinderReceivedListenerSticky guarantees binder IS alive when this fires —
        // so it is safe to call checkSelfPermission() here directly.
        this.onBinderReceived = () -> {
            if (Shizuku.isPreV11()) {
                Log.w(TAG, "Shizuku pre-v11 is unsupported");
                return;
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "Shizuku binder received (v" + Shizuku.getVersion() + ")");
            checkPermission();
        };

        this.onBinderDead = () -> {
            if (BuildConfig.DEBUG) Log.d(TAG, "Shizuku binder died");
            service = null;
            bound = false;
            ShizukuServiceManager.this.callback.onServiceDisconnected();
        };

        // This is the ONLY place we notify of a denial —
        // because only here do we know the user has actually responded.
        this.onPermissionResult = (requestCode, grantResult) -> {
            if (requestCode != REQUEST_CODE) return;

            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Permission granted");
                ShizukuServiceManager.this.callback.onPermissionGranted();
                bindUserService();
            } else {
                // User tapped Deny. Check if it's permanent ("Don't ask again").
                boolean permanent = Shizuku.shouldShowRequestPermissionRationale();
                if (BuildConfig.DEBUG) Log.d(TAG, "Permission denied (permanent=" + permanent + ")");
                ShizukuServiceManager.this.callback.onPermissionDenied(permanent);
            }
        };
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Register all Shizuku listeners. Call from ViewModel constructor or Activity.onCreate().
     * addBinderReceivedListenerSticky fires immediately if binder is already alive.
     */
    @MainThread
    public void addListeners() {
        Shizuku.addBinderDeadListener(onBinderDead);
        Shizuku.addRequestPermissionResultListener(onPermissionResult);
        Shizuku.addBinderReceivedListenerSticky(onBinderReceived); // Must be last
    }

    /**
     * Unregister all listeners and unbind service. Call from ViewModel.onCleared() or onDestroy().
     */
    @MainThread
    public void removeListeners() {
        Shizuku.removeBinderReceivedListener(onBinderReceived);
        Shizuku.removeBinderDeadListener(onBinderDead);
        Shizuku.removeRequestPermissionResultListener(onPermissionResult);
        if (bound) {
            try {
                Shizuku.unbindUserService(serviceArgs, connection, true);
            } catch (Exception e) {
                Log.e(TAG, "Error unbinding UserService", e);
            }
            bound = false;
            service = null;
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public boolean isAvailable() {
        return bound && service != null;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Check/request permission using the exact official pattern from Shizuku-API docs.
     * Only call when the binder is alive (i.e. from inside onBinderReceived).
     */
    private void checkPermission() {
        if (Shizuku.isPreV11()) return;

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            // Already granted — go straight to binding
            if (BuildConfig.DEBUG) Log.d(TAG, "Permission already granted, binding service");
            callback.onPermissionGranted();
            bindUserService();
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            // User previously chose "Deny and don't ask again"
            Log.w(TAG, "Permission permanently denied");
            callback.onPermissionDenied(true);
        } else {
            // Ask the user — result comes via onPermissionResult
            if (BuildConfig.DEBUG) Log.d(TAG, "Requesting Shizuku permission");
            Shizuku.requestPermission(REQUEST_CODE);
        }
    }

    private void bindUserService() {
        if (bound) {
            if (BuildConfig.DEBUG) Log.d(TAG, "UserService already bound");
            return;
        }
        try {
            if (BuildConfig.DEBUG) Log.d(TAG, "Binding IPrivilegedService");
            Shizuku.bindUserService(serviceArgs, connection);
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind UserService", e);
        }
    }
}
