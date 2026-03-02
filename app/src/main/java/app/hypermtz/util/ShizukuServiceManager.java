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
 *
 * Key design rules (from reading Shizuku API source):
 *
 * 1. Shizuku.bindUserService() is safe to call multiple times — the server deduplicates
 *    by tag. No client-side "already binding" guard needed.
 *
 * 2. shouldShowRequestPermissionRationale() == true means "Deny and don't ask again".
 *    Do NOT call requestPermission() in this case (per official Shizuku demo).
 *
 * 3. NEVER call checkAndBind() recursively from within bindUserService() or from
 *    onServiceConnected/onServiceDisconnected. Shizuku callbacks may be rapid and a
 *    recursive call chain causes StackOverflowError → app crash → black screen.
 *    Recovery is handled passively: onResume() calls retryConnection() which is safe.
 */
public final class ShizukuServiceManager {

    public enum ShizukuState {
        UNAVAILABLE,        // Shizuku not installed / server not running
        PERMISSION_NEEDED,  // Shizuku running but permission not yet granted
        PERMISSION_DENIED,  // User chose "Deny and don't ask again"
        CONNECTING,         // Permission granted; waiting for UserService binder
        CONNECTED           // UserService binder alive and ready for IPC
    }

    public interface Callback {
        void onServiceConnected(IPrivilegedService service);
        void onServiceDisconnected();
        void onPermissionGranted();
        void onPermissionDenied(boolean isPermanent);
        void onStateChanged(ShizukuState newState);
    }

    private static final String TAG          = "ShizukuServiceManager";
    private static final int    REQUEST_CODE = 0xADB;

    private final Callback callback;
    private final Handler  mainHandler = new Handler(Looper.getMainLooper());

    @Nullable private IPrivilegedService service;
    private boolean bound   = false;
    /** UI-only flag: true while waiting for onServiceConnected after bindUserService(). */
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
                    Log.w(TAG, "onServiceConnected: received dead binder");
                    bound   = false;
                    service = null;
                    // Report CONNECTING and let the next retryConnection() (from onResume)
                    // attempt a rebind. Do NOT call checkAndBind() here — recursive!
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
                // UserService process died. Permission is still granted.
                // Do NOT call checkAndBind() here — it leads to recursive bind
                // calls if the UserService keeps crashing on startup.
                // Instead, post a delayed retry to the main thread so we don't
                // recurse from within a Shizuku callback.
                Log.d(TAG, "IPrivilegedService disconnected");
                binding = false;
                service = null;
                bound   = false;
                reportState(ShizukuState.CONNECTING);
                ShizukuServiceManager.this.callback.onServiceDisconnected();

                // Post a safe, non-recursive retry with a small delay.
                // If the UserService crashed, this gives the OS time to clean up
                // before we try to restart it.
                mainHandler.postDelayed(ShizukuServiceManager.this::checkAndBind, 500);
            }
        };

        this.onBinderReceived = () -> {
            Log.d(TAG, "Shizuku binder received");
            checkAndBind();
        };

        this.onBinderDead = () -> {
            Log.d(TAG, "Shizuku binder died");
            mainHandler.removeCallbacksAndMessages(null);
            binding = false;
            service = null;
            bound   = false;
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
                boolean permanent = Shizuku.shouldShowRequestPermissionRationale();
                reportState(permanent ? ShizukuState.PERMISSION_DENIED
                                      : ShizukuState.PERMISSION_NEEDED);
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
        mainHandler.removeCallbacksAndMessages(null);
        Shizuku.removeBinderReceivedListener(onBinderReceived);
        Shizuku.removeBinderDeadListener(onBinderDead);
        Shizuku.removeRequestPermissionResultListener(onPermissionResult);
        releaseService();
    }

    /**
     * Call from Activity.onResume(). Safe to call frequently — Shizuku server
     * deduplicates bindUserService() calls; this won't restart the UserService.
     */
    @MainThread
    public void retryConnection() {
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
        if (bound && isAvailable()) {
            reportState(ShizukuState.CONNECTED);
            return;
        }

        if (!Shizuku.pingBinder()) {
            Log.w(TAG, "checkAndBind: Shizuku not running");
            binding = false;
            reportState(ShizukuState.UNAVAILABLE);
            return;
        }
        if (Shizuku.isPreV11()) {
            Log.w(TAG, "checkAndBind: Shizuku pre-v11 not supported");
            binding = false;
            reportState(ShizukuState.UNAVAILABLE);
            return;
        }

        int perm;
        try {
            perm = Shizuku.checkSelfPermission();
        } catch (Exception e) {
            Log.e(TAG, "checkSelfPermission failed", e);
            reportState(binding ? ShizukuState.CONNECTING : ShizukuState.PERMISSION_NEEDED);
            return;
        }

        if (perm == PackageManager.PERMISSION_GRANTED) {
            bindUserService();
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            // "Deny and don't ask again" — do NOT call requestPermission().
            Log.w(TAG, "checkAndBind: permission permanently denied");
            binding = false;
            reportState(ShizukuState.PERMISSION_DENIED);
            callback.onPermissionDenied(true);
        } else {
            Log.d(TAG, "checkAndBind: requesting permission");
            binding = false;
            reportState(ShizukuState.PERMISSION_NEEDED);
            Shizuku.requestPermission(REQUEST_CODE);
        }
    }

    /**
     * Calls Shizuku.bindUserService(). Safe to call multiple times — Shizuku server
     * deduplicates by tag internally (UserServiceManager.addUserService).
     * On exception, does NOT call checkAndBind() to avoid recursion.
     */
    private void bindUserService() {
        binding = true;
        reportState(ShizukuState.CONNECTING);
        try {
            Log.d(TAG, "bindUserService: calling Shizuku.bindUserService");
            Shizuku.bindUserService(serviceArgs, connection);
        } catch (Exception e) {
            // RuntimeException wraps RemoteException from Shizuku.
            // Do NOT call checkAndBind() here — that would create a recursive loop
            // if Shizuku keeps throwing (e.g. during Shizuku restart).
            // Leave binding=true and state=CONNECTING; next retryConnection() recovers.
            Log.e(TAG, "bindUserService failed — will retry on next resume", e);
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
