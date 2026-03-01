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
 * Bug fixes in this version:
 *
 *  1. onServiceDisconnected no longer reports PERMISSION_NEEDED.
 *     The old behaviour was wrong: if the UserService process dies (crash, Shizuku
 *     restart, version upgrade) we still have permission — reporting PERMISSION_NEEDED
 *     showed "Permission required" in the UI and confused the user into thinking they
 *     needed to re-grant. Now it reports CONNECTING and immediately attempts a rebind.
 *
 *  2. binding flag is reset in retryConnection() (called from Activity.onResume).
 *     Without this, if onServiceConnected was never fired after bindUserService()
 *     (e.g. UserService crashed before fully starting), binding stayed true forever
 *     and every retry was silently skipped — leaving the UI stuck at CONNECTING or
 *     PERMISSION_NEEDED indefinitely.
 *
 *  3. shouldShowRequestPermissionRationale() path now ALWAYS calls requestPermission().
 *     The old code called onPermissionDenied(true) but never showed the dialog, so
 *     the user was permanently stuck: tapping the Shizuku card did nothing and the
 *     "Permission required" label never went away. Now we show the dialog every time
 *     (including when the user explicitly retries), letting them re-grant.
 *
 *  4. isAvailable() checks isBinderAlive() so a dead remote binder is detected
 *     immediately instead of returning a stale true when the service has crashed.
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
     * True from the moment bindUserService() is called until onServiceConnected()
     * or onServiceDisconnected() fires. Prevents duplicate bind calls from rapid
     * onBinderReceived / checkAndBind invocations.
     *
     * IMPORTANT: retryConnection() (called from onResume) resets this flag before
     * retrying — see bug fix #2 above.
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
                if (binder == null || !binder.isBinderAlive()) {
                    // Shizuku returned a dead binder — clear state and retry on next resume.
                    Log.w(TAG, "onServiceConnected: received dead binder, will retry");
                    binding = false;
                    bound   = false;
                    service = null;
                    // Don't report PERMISSION_NEEDED — permission is fine, the process just
                    // crashed. Show CONNECTING so the user sees "binding..." not "denied".
                    reportState(ShizukuState.CONNECTING);
                    return;
                }
                binding = false;
                service = IPrivilegedService.Stub.asInterface(binder);
                bound   = true;
                Log.d(TAG, "IPrivilegedService connected");
                reportState(ShizukuState.CONNECTED);
                ShizukuServiceManager.this.callback.onServiceConnected(service);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                // FIX #1: The UserService process died. We still have permission.
                // Do NOT report PERMISSION_NEEDED here — that would show
                // "Permission required" in the UI which is factually wrong and
                // prompts the user to re-grant something they never revoked.
                Log.d(TAG, "IPrivilegedService disconnected — will attempt rebind");
                binding = false;
                service = null;
                bound   = false;
                ShizukuServiceManager.this.callback.onServiceDisconnected();
                // Immediately try to rebind. Shizuku will restart the UserService
                // process. If Shizuku itself is gone, pingBinder() will be false
                // and checkAndBind() will set UNAVAILABLE instead.
                checkAndBind(false);
            }
        };

        // Sticky: fires immediately if the Shizuku binder is already alive.
        this.onBinderReceived = () -> {
            Log.d(TAG, "Shizuku binder received");
            checkAndBind(false);
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
                // Check permanent denial for informational purposes only.
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
        Shizuku.addBinderReceivedListenerSticky(onBinderReceived); // must be last — fires sync
    }

    @MainThread
    public void removeListeners() {
        Shizuku.removeBinderReceivedListener(onBinderReceived);
        Shizuku.removeBinderDeadListener(onBinderDead);
        Shizuku.removeRequestPermissionResultListener(onPermissionResult);
        releaseService();
    }

    /**
     * Proactive retry — call from Activity.onResume() every time.
     *
     * FIX #2: Resets the {@code binding} flag before retrying. This recovers from
     * the case where bindUserService() was called but onServiceConnected never
     * fired (UserService crashed before fully starting). Without this reset, the
     * binding flag would be permanently true and every subsequent retry silently
     * skipped, leaving the connection stuck forever.
     */
    @MainThread
    public void retryConnection() {
        // Reset the in-flight binding flag so we actually attempt the bind.
        // If we were genuinely in the middle of a valid connect, Shizuku will
        // deliver onServiceConnected momentarily and the duplicate bind call is
        // harmless (Shizuku deduplicates same ServiceConnection objects).
        binding = false;
        checkAndBind(false);
    }

    // ── Public state ──────────────────────────────────────────────────────────

    /**
     * Returns true only when the remote UserService binder is alive.
     *
     * FIX #4: Checks isBinderAlive() so a dead binder is detected immediately
     * rather than returning a stale true that leads to "Shizuku is not connected"
     * toasts on the next IPC call.
     */
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
            // Binder call failed transiently — treat as permission needed so
            // the UI shows something actionable (not a blank/stuck state).
            Log.e(TAG, "checkSelfPermission failed — treating as permission needed", e);
            reportState(ShizukuState.PERMISSION_NEEDED);
            return;
        }

        if (perm == PackageManager.PERMISSION_GRANTED) {
            if (bound && isAvailable()) {
                // Already connected and binder is alive — nothing to do.
                Log.d(TAG, "checkAndBind: already connected");
                reportState(ShizukuState.CONNECTED);
                return;
            }
            if (binding) {
                // A bind is already in flight (from onBinderReceived or a prior
                // onResume call that hasn't delivered onServiceConnected yet).
                // retryConnection() clears this flag, so getting here means we
                // were called from the sticky listener during an active bind.
                Log.d(TAG, "checkAndBind: already binding — waiting for onServiceConnected");
                reportState(ShizukuState.CONNECTING);
                return;
            }
            Log.d(TAG, "checkAndBind: permission granted, binding service");
            bindUserService();
        } else {
            // FIX #3: ALWAYS call requestPermission() regardless of
            // shouldShowRequestPermissionRationale(). The old code skipped
            // the dialog when shouldShowRationale() was true, leaving the user
            // permanently stuck with "Permission required" and no way to fix it.
            // Shizuku's permission dialog handles the "show rationale" case
            // itself; we don't need to suppress the request on our side.
            Log.d(TAG, "checkAndBind: requesting permission (rationale="
                    + Shizuku.shouldShowRequestPermissionRationale() + ")");
            reportState(ShizukuState.PERMISSION_NEEDED);
            Shizuku.requestPermission(REQUEST_CODE);
        }
    }

    private void bindUserService() {
        if (binding) {
            Log.d(TAG, "bindUserService: bind already in flight");
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
            // Don't report PERMISSION_NEEDED here — the failure might be a
            // transient binder error, not a permission problem. Report CONNECTING
            // so the user sees a pending state; the next onResume retry will
            // determine the real state via checkSelfPermission().
            reportState(ShizukuState.CONNECTING);
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

    /** Fires onStateChanged only when the state actually changes. */
    private void reportState(ShizukuState state) {
        if (state != lastReportedState) {
            lastReportedState = state;
            callback.onStateChanged(state);
        }
    }
}
