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
 * Pattern is derived from production apps that use Shizuku (e.g. HyperTheme, Canta):
 *
 *  1. Register listeners (binder received/dead, permission result) once.
 *  2. addBinderReceivedListenerSticky fires immediately if binder is already alive.
 *  3. ALSO call retryConnection() from Activity.onResume() every time —
 *     this is the crucial retry that the sticky listener alone cannot cover
 *     (e.g. if the UserService crashed, or bindUserService was called but
 *     onServiceConnected never fired).
 *  4. Never show permission-denied UI for internal/transient states.
 */
public final class ShizukuServiceManager {

    public interface Callback {
        void onServiceConnected(IPrivilegedService service);
        void onServiceDisconnected();
        void onPermissionGranted();
        /**
         * Called ONLY when the user explicitly denied the permission dialog,
         * or when shouldShowRequestPermissionRationale() is true (permanent denial).
         * isPermanent = true  →  user tapped "Don't ask again" → send to Shizuku app settings.
         * isPermanent = false →  user tapped plain Deny this time.
         */
        void onPermissionDenied(boolean isPermanent);
        /** Called when Shizuku itself is not installed / not running. */
        void onShizukuUnavailable();
    }

    private static final String TAG          = "ShizukuServiceManager";
    private static final int    REQUEST_CODE = 0xADB;

    private final Callback callback;

    @Nullable private IPrivilegedService service;
    private boolean bound = false;

    // ── UserService config ────────────────────────────────────────────────────
    // version(VERSION_CODE) tells Shizuku to restart the service when the app updates.
    // daemon(false) means the service is killed when all connections are released.
    // No process name override — let Shizuku use its default.

    private final Shizuku.UserServiceArgs serviceArgs =
            new Shizuku.UserServiceArgs(
                    new ComponentName(BuildConfig.APPLICATION_ID,
                            PrivilegedService.class.getName()))
                    .tag(BuildConfig.APPLICATION_ID + ":privileged")
                    .daemon(false)
                    .debuggable(BuildConfig.DEBUG)
                    .version(BuildConfig.VERSION_CODE);

    // ── Listeners ─────────────────────────────────────────────────────────────

    private final ServiceConnection connection;
    private final Shizuku.OnBinderReceivedListener onBinderReceived;
    private final Shizuku.OnBinderDeadListener     onBinderDead;
    private final Shizuku.OnRequestPermissionResultListener onPermissionResult;

    // ── Constructor ───────────────────────────────────────────────────────────

    public ShizukuServiceManager(Callback callback) {
        this.callback = callback;

        this.connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                if (binder == null || !binder.isBinderAlive()) {
                    Log.w(TAG, "onServiceConnected: null or dead binder — ignoring");
                    bound = false;
                    service = null;
                    return;
                }
                service = IPrivilegedService.Stub.asInterface(binder);
                bound = true;
                Log.d(TAG, "IPrivilegedService connected");
                ShizukuServiceManager.this.callback.onServiceConnected(service);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.d(TAG, "IPrivilegedService disconnected");
                service = null;
                bound = false;
                ShizukuServiceManager.this.callback.onServiceDisconnected();
                // Do NOT try to rebind here — wait for the next retryConnection()
                // call from onResume, or for onBinderReceived to fire again.
            }
        };

        // Sticky: fires immediately if binder is already alive when registered.
        this.onBinderReceived = () -> {
            Log.d(TAG, "Shizuku binder received");
            checkAndBind(false /* not a user-initiated action */);
        };

        this.onBinderDead = () -> {
            Log.d(TAG, "Shizuku binder died");
            service = null;
            bound = false;
            ShizukuServiceManager.this.callback.onServiceDisconnected();
        };

        // Result of Shizuku.requestPermission() — user explicitly responded.
        this.onPermissionResult = (requestCode, grantResult) -> {
            if (requestCode != REQUEST_CODE) return;

            boolean granted = (grantResult == PackageManager.PERMISSION_GRANTED);
            Log.d(TAG, "Permission result: " + (granted ? "GRANTED" : "DENIED"));

            if (granted) {
                ShizukuServiceManager.this.callback.onPermissionGranted();
                bindUserService();
            } else {
                // User explicitly tapped Deny (or "Don't ask again").
                // shouldShowRequestPermissionRationale() is false when permanent.
                boolean permanent = !Shizuku.shouldShowRequestPermissionRationale();
                ShizukuServiceManager.this.callback.onPermissionDenied(permanent);
            }
        };
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Register listeners. Call once (e.g. in ViewModel constructor).
     * The sticky binder-received listener fires immediately if Shizuku is already running.
     */
    @MainThread
    public void addListeners() {
        Shizuku.addBinderDeadListener(onBinderDead);
        Shizuku.addRequestPermissionResultListener(onPermissionResult);
        Shizuku.addBinderReceivedListenerSticky(onBinderReceived); // must be last — may fire sync
    }

    /**
     * Unregister listeners and release the UserService.
     * Call from ViewModel.onCleared().
     */
    @MainThread
    public void removeListeners() {
        Shizuku.removeBinderReceivedListener(onBinderReceived);
        Shizuku.removeBinderDeadListener(onBinderDead);
        Shizuku.removeRequestPermissionResultListener(onPermissionResult);
        releaseService();
    }

    /**
     * Proactive retry — call this from Activity.onResume() every time.
     *
     * This covers the case where:
     *  - Shizuku was not running when the app started but is now.
     *  - bindUserService was called but the UserService crashed before onServiceConnected.
     *  - The user just granted permission in the Shizuku app and returned here.
     *
     * This is the same pattern used by the reference app (onResume checks
     * e.e() + e.c() == 0 and calls h.a() to bind directly).
     */
    @MainThread
    public void retryConnection() {
        checkAndBind(false);
    }

    // ── Public state ──────────────────────────────────────────────────────────

    public boolean isAvailable() {
        return bound && service != null;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Core logic: decide what to do based on current Shizuku state.
     * Safe to call at any time; guards every Shizuku API call.
     *
     * @param userInitiated true when the user explicitly tapped a "connect" button
     *                      (reserved for future use — currently always false).
     */
    private void checkAndBind(boolean userInitiated) {
        // 1. Is Shizuku running at all?
        if (!Shizuku.pingBinder()) {
            Log.w(TAG, "checkAndBind: Shizuku not running");
            callback.onShizukuUnavailable();
            return;
        }

        // 2. Pre-v11 is not supported (permission model is different).
        if (Shizuku.isPreV11()) {
            Log.w(TAG, "checkAndBind: Shizuku pre-v11 not supported");
            return;
        }

        // 3. Check permission.
        int perm;
        try {
            perm = Shizuku.checkSelfPermission();
        } catch (Exception e) {
            // Binder call failed transiently — do not treat as denial, just wait.
            Log.e(TAG, "checkSelfPermission failed (transient)", e);
            return;
        }

        if (perm == PackageManager.PERMISSION_GRANTED) {
            // Permission already granted — bind if not already bound.
            if (!bound) {
                Log.d(TAG, "checkAndBind: permission granted, binding service");
                callback.onPermissionGranted();
                bindUserService();
            } else {
                Log.d(TAG, "checkAndBind: already bound, nothing to do");
            }
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            // User permanently denied before — don't spam the dialog again.
            Log.w(TAG, "checkAndBind: permission permanently denied");
            callback.onPermissionDenied(true);
        } else {
            // Not yet granted and not permanently denied — show the dialog.
            Log.d(TAG, "checkAndBind: requesting permission");
            Shizuku.requestPermission(REQUEST_CODE);
        }
    }

    private void bindUserService() {
        if (bound) {
            Log.d(TAG, "bindUserService: already bound");
            return;
        }
        try {
            Log.d(TAG, "bindUserService: calling Shizuku.bindUserService");
            Shizuku.bindUserService(serviceArgs, connection);
        } catch (Exception e) {
            Log.e(TAG, "bindUserService failed", e);
        }
    }

    private void releaseService() {
        if (bound) {
            try {
                Shizuku.unbindUserService(serviceArgs, connection, true);
            } catch (Exception e) {
                Log.e(TAG, "unbindUserService failed", e);
            }
        }
        bound = false;
        service = null;
    }
}
