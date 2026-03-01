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
 * Design principles (derived from reading the Shizuku API source):
 *
 * 1. Shizuku.bindUserService() is safe to call multiple times.
 *    The server (UserServiceManager) holds a synchronized record per tag and sets
 *    a `starting` flag. Duplicate calls while starting just re-register the callback
 *    and return immediately — the UserService process is NOT restarted.
 *    Therefore we do NOT use a client-side `binding` flag to gate bindUserService()
 *    calls. Doing so only adds complexity without benefit and introduced the previous
 *    stuck-at-CONNECTING bug when the flag got into a bad state.
 *
 * 2. shouldShowRequestPermissionRationale() == true means "Deny and don't ask again".
 *    Per the official Shizuku demo (DemoActivity.checkPermission), when this returns
 *    true we must NOT call requestPermission(). Doing so would show an unresponsive
 *    dialog. Instead we show a message directing the user to the Shizuku app to
 *    re-grant manually.
 *
 * 3. onServiceDisconnected → ShizukuServiceConnection removed from Shizuku's internal
 *    cache (ShizukuServiceConnections). The next bindUserService() creates a fresh
 *    connection. So in onServiceDisconnected we simply call checkAndBind() to rebind
 *    immediately, with no extra state manipulation required.
 *
 * 4. The `binding` field is kept ONLY for UI state (to show CONNECTING vs PERMISSION_NEEDED
 *    after bindUserService is called but before onServiceConnected fires). It is never
 *    used to block or skip a bindUserService() call.
 */
public final class ShizukuServiceManager {

    public enum ShizukuState {
        UNAVAILABLE,        // Shizuku not installed / server not running
        PERMISSION_NEEDED,  // Shizuku running but permission not granted
        PERMISSION_DENIED,  // User chose "Deny and don't ask again"
        CONNECTING,         // Permission granted; waiting for UserService binder
        CONNECTED           // UserService binder alive and ready for IPC
    }

    public interface Callback {
        void onServiceConnected(IPrivilegedService service);
        void onServiceDisconnected();
        /** Only fired on an actual grant transition (user tapped Allow). */
        void onPermissionGranted();
        /** @param isPermanent true if user chose "don't ask again" */
        void onPermissionDenied(boolean isPermanent);
        void onStateChanged(ShizukuState newState);
    }

    private static final String TAG          = "ShizukuServiceManager";
    private static final int    REQUEST_CODE = 0xADB;

    private final Callback callback;

    @Nullable private IPrivilegedService service;
    private boolean bound   = false;
    /**
     * UI-only flag: true between bindUserService() and onServiceConnected/Disconnected.
     * NOT used to gate bindUserService() calls — Shizuku server handles deduplication.
     * Kept only so we can report CONNECTING instead of PERMISSION_NEEDED while the
     * UserService process is being launched (which can take a few seconds).
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
                    Log.w(TAG, "onServiceConnected: received dead binder");
                    bound   = false;
                    service = null;
                    // Don't report PERMISSION_NEEDED — this is a process-level failure,
                    // not a permission problem. Attempt an immediate rebind.
                    checkAndBind();
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
                // The UserService process has died (crash, Shizuku restart, etc.).
                // Permission is still granted — do NOT report PERMISSION_NEEDED.
                // ShizukuServiceConnections has already removed the dead connection
                // from its cache, so the next bindUserService() creates a fresh one.
                Log.d(TAG, "IPrivilegedService disconnected");
                binding = false;
                service = null;
                bound   = false;
                ShizukuServiceManager.this.callback.onServiceDisconnected();
                checkAndBind();
            }
        };

        // Sticky: fires on the main thread immediately if the binder is already alive.
        this.onBinderReceived = () -> {
            Log.d(TAG, "Shizuku binder received");
            checkAndBind();
        };

        this.onBinderDead = () -> {
            Log.d(TAG, "Shizuku binder died");
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
                // Check for permanent denial (don't ask again).
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
        Shizuku.addBinderReceivedListenerSticky(onBinderReceived); // must be last
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
     * Shizuku server deduplicates bindUserService() calls internally, so calling
     * this on every resume is safe and ensures we recover from any transient failure.
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
        // Already have a live binder — nothing to do.
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
            // Transient binder error — report CONNECTING if we already started binding,
            // otherwise PERMISSION_NEEDED so the user sees something actionable.
            Log.e(TAG, "checkSelfPermission failed", e);
            reportState(binding ? ShizukuState.CONNECTING : ShizukuState.PERMISSION_NEEDED);
            return;
        }

        if (perm == PackageManager.PERMISSION_GRANTED) {
            bindUserService();
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            // Per official Shizuku demo: "Deny and don't ask again" was selected.
            // We must NOT call requestPermission() here — show guidance to the user
            // to open the Shizuku app and grant permission manually.
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

    private void bindUserService() {
        // Update UI state immediately so the user sees "Connecting..."
        // even before onServiceConnected fires (UserService process launch takes
        // a few seconds as Shizuku forks a new process via sh).
        binding = true;
        reportState(ShizukuState.CONNECTING);

        try {
            Log.d(TAG, "bindUserService: calling Shizuku.bindUserService");
            // Safe to call multiple times — Shizuku server deduplicates by tag.
            // If the UserService is already starting (starting=true on server), the
            // server just re-registers our callback and waits. No duplicate launch.
            Shizuku.bindUserService(serviceArgs, connection);
        } catch (Exception e) {
            // RuntimeException from Shizuku (wraps RemoteException).
            // The service could not be started at all — reset binding flag.
            binding = false;
            Log.e(TAG, "bindUserService failed", e);
            // Re-check the actual state rather than guessing.
            checkAndBind();
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
