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
 * ──────────────────────────────────────────────────────────────────────────────
 * ROOT CAUSE ANALYSIS (based on Shizuku-API server source)
 * ──────────────────────────────────────────────────────────────────────────────
 *
 * The permanent "stuck at Connecting" bug is caused by a server-side timeout path:
 *
 *   1. bindUserService() → server creates UserServiceRecord with starting=true
 *      and schedules a 30-second timeout (UserServiceRecord.setStartingTimeout).
 *   2. The UserService app_process is launched via sh.
 *   3. On MIUI/HyperOS, the background process killer (or Shizuku GUI interference,
 *      see issue #475) kills the app_process before it connects back.
 *   4. After 30 seconds, the timeout fires → record.removeSelf() → record.destroy()
 *      → callbacks.kill() on the RemoteCallbackList.
 *   5. RemoteCallbackList.kill() drops the server's binder reference to our
 *      ShizukuServiceConnection WITHOUT calling IShizukuServiceConnection.died().
 *   6. Our client-side ShizukuServiceConnection.died() is never invoked.
 *   7. Therefore onServiceDisconnected() is never called, onServiceConnected()
 *      is never called, and we remain stuck at CONNECTING forever.
 *
 * Contrast with the normal UserService death path (after successful startup):
 *   • UserService binder dies → server's linkToDeath fires → record.broadcastBinderDied()
 *     → explicitly calls connection.died() on each callback → onServiceDisconnected fires.
 *
 * The timeout path skips broadcastBinderDied() and goes straight to callbacks.kill().
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * FIX: CLIENT-SIDE TIMEOUT
 * ──────────────────────────────────────────────────────────────────────────────
 *
 * We post a 15-second watchdog on the main handler whenever bindUserService() is
 * called. If onServiceConnected() fires before the watchdog, we cancel it. If the
 * watchdog fires (meaning the server silently gave up), we:
 *   1. Call unbindUserService(remove=false) to clean up the dead ShizukuServiceConnection
 *      from Shizuku server's record (avoids duplicating callbacks next time).
 *   2. Reset binding state on the client.
 *   3. Schedule a fresh bindUserService() after 500 ms to allow Shizuku to settle.
 *
 * Watchdog is 15 s (well under Shizuku's 30 s server-side timeout) so we detect
 * the failure and retry before the server timer even fires.
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * OTHER FIXES
 * ──────────────────────────────────────────────────────────────────────────────
 *
 * • catch (Throwable) instead of catch (Exception) in bindUserService():
 *   Shizuku wraps RemoteException in RuntimeException; Binder internals can also
 *   throw TransactionTooLargeException (RuntimeException) and other unchecked types.
 *   Using catch (Exception) is almost enough, but Throwable is strictly safer and
 *   prevents an uncaught exception from propagating into the ViewModel constructor
 *   (which would cause a crash → black screen on first launch).
 *
 * • processNameSuffix("privileged") added to UserServiceArgs:
 *   Makes the Shizuku UserService process appear in logcat and ps as
 *   "app.hypermtz:privileged" instead of "app.hypermtz:null", which significantly
 *   aids debugging on MIUI where background kill logs reference the process name.
 *
 * • shouldShowRequestPermissionRationale() == true → PERMISSION_DENIED, no dialog:
 *   Per the official Shizuku demo (DemoActivity.checkPermission), this flag means
 *   "Deny and don't ask again". Calling requestPermission() here shows an empty
 *   dialog that does nothing. Correct behaviour is to direct user to Shizuku app.
 *
 * • No recursive checkAndBind() from within Shizuku callbacks:
 *   Shizuku callbacks (onServiceConnected, onServiceDisconnected) run on the main
 *   thread. Calling checkAndBind() → bindUserService() synchronously from within
 *   the ShizukuServiceConnection.died() Runnable (before connections.clear() and
 *   ShizukuServiceConnections.remove() have run) causes the re-bind to register our
 *   ServiceConnection with the OLD dying ShizukuServiceConnection, which then
 *   immediately clears it. The connection is lost and onServiceConnected never fires.
 *   All retry paths now use mainHandler.post/postDelayed to run after the current
 *   Runnable completes.
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
        /** Fired only on an actual permission grant transition (user tapped Allow). */
        void onPermissionGranted();
        /** @param isPermanent true when shouldShowRequestPermissionRationale() is true */
        void onPermissionDenied(boolean isPermanent);
        void onStateChanged(ShizukuState newState);
    }

    private static final String TAG = "ShizukuServiceManager";

    /** Shizuku permission request code — arbitrary non-zero value. */
    private static final int REQUEST_CODE = 0xADB;

    /**
     * Client-side watchdog timeout for bindUserService().
     *
     * Shizuku's server-side timeout is 30 seconds (DateUtils.SECOND_IN_MILLIS * 30,
     * see UserServiceRecord.setStartingTimeout). We use 15 s to detect failure and
     * retry before Shizuku's own timer fires.
     *
     * On devices where MIUI/HyperOS kills the UserService process before it connects,
     * Shizuku silently drops our callback (callbacks.kill()) without calling died().
     * This watchdog fires, cleans up, and retries.
     */
    private static final long BIND_WATCHDOG_MS = 15_000L;

    /** Delay before retrying after a watchdog or disconnect, in milliseconds. */
    private static final long RETRY_DELAY_MS = 500L;

    private final Callback callback;
    private final Handler  mainHandler = new Handler(Looper.getMainLooper());

    @Nullable private IPrivilegedService service;
    private boolean bound   = false;
    /** True between bindUserService() and onServiceConnected/Disconnected. UI only. */
    private boolean binding = false;

    private ShizukuState lastReportedState = null;

    /**
     * UserServiceArgs is stateless and immutable after construction. It is safe
     * to reuse the same instance across multiple bindUserService() calls.
     *
     * Key decisions:
     *   .tag()               — stable key for the server's userServiceRecords map.
     *                          Using APPLICATION_ID + ":privileged" is unique and
     *                          survives APK updates (unlike class name after ProGuard).
     *   .processNameSuffix() — the UserService process appears in ps/logcat as
     *                          "app.hypermtz:privileged" instead of "app.hypermtz:null".
     *   .daemon(false)       — Shizuku stops the UserService when no app has it bound.
     *                          Saves RAM; we rebind as needed.
     *   .version(VERSION_CODE) — causes Shizuku to kill and restart the UserService
     *                           when the app is updated, ensuring fresh code is used.
     */
    private final Shizuku.UserServiceArgs serviceArgs =
            new Shizuku.UserServiceArgs(
                    new ComponentName(BuildConfig.APPLICATION_ID,
                            PrivilegedService.class.getName()))
                    .tag("privileged")                        // simple, stable key
                    .processNameSuffix("privileged")          // visible in ps/logcat
                    .daemon(false)
                    .debuggable(BuildConfig.DEBUG)
                    .version(BuildConfig.VERSION_CODE);

    /** Watchdog Runnable — posted on bindUserService(), cancelled on success. */
    private final Runnable bindWatchdog = this::onBindWatchdogFired;

    private final ServiceConnection       connection;
    private final Shizuku.OnBinderReceivedListener          onBinderReceived;
    private final Shizuku.OnBinderDeadListener              onBinderDead;
    private final Shizuku.OnRequestPermissionResultListener onPermissionResult;

    public ShizukuServiceManager(Callback callback) {
        this.callback = callback;

        this.connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                // Cancel the watchdog — connection succeeded within the timeout window.
                mainHandler.removeCallbacks(bindWatchdog);
                binding = false;

                if (binder == null || !binder.isBinderAlive()) {
                    // Shizuku delivered a dead binder (e.g. UserService crashed
                    // immediately on startup). Don't report PERMISSION_NEEDED — permission
                    // is fine. Report CONNECTING and let retryConnection() recover on the
                    // next onResume(). Do NOT call checkAndBind() here directly — it
                    // would register our ServiceConnection with the OLD dying
                    // ShizukuServiceConnection before its cleanup Runnable has run,
                    // causing the registration to be silently dropped (see class javadoc).
                    Log.w(TAG, "onServiceConnected: dead binder — waiting for retry");
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
                // UserService process died (crash, Shizuku restart, MIUI kill, etc.).
                // Permission is still granted — do NOT report PERMISSION_NEEDED.
                // Do NOT call bindUserService() synchronously here — see class javadoc
                // for why this causes the registration to be silently dropped.
                // Instead, post a delayed retry so the ShizukuServiceConnection cleanup
                // Runnable (connections.clear + remove from cache) runs first.
                Log.d(TAG, "IPrivilegedService disconnected");
                mainHandler.removeCallbacks(bindWatchdog);
                binding = false;
                service = null;
                bound   = false;
                reportState(ShizukuState.CONNECTING);
                ShizukuServiceManager.this.callback.onServiceDisconnected();
                mainHandler.postDelayed(() -> checkAndBind(), RETRY_DELAY_MS);
            }
        };

        // addBinderReceivedListenerSticky fires on the calling thread synchronously
        // if binderReady==true (Shizuku already connected). Must be added last so the
        // dead and permission listeners are registered before the first checkAndBind().
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
                // shouldShowRequestPermissionRationale() == true means
                // "Deny and don't ask again" (per official Shizuku demo).
                boolean permanent = Shizuku.shouldShowRequestPermissionRationale();
                reportState(permanent ? ShizukuState.PERMISSION_DENIED
                                      : ShizukuState.PERMISSION_NEEDED);
                ShizukuServiceManager.this.callback.onPermissionDenied(permanent);
            }
        };
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /**
     * Call from ViewModel constructor (main thread).
     * addBinderReceivedListenerSticky must be registered last so that onBinderDead
     * and onPermissionResult are in place before the initial checkAndBind().
     */
    @MainThread
    public void addListeners() {
        Shizuku.addBinderDeadListener(onBinderDead);
        Shizuku.addRequestPermissionResultListener(onPermissionResult);
        Shizuku.addBinderReceivedListenerSticky(onBinderReceived); // fires sync if ready
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
     * Proactive retry — call from Activity.onResume() on every resume.
     *
     * Because Shizuku.bindUserService() is idempotent (server deduplicates by tag),
     * calling this on every onResume() is safe and recovers from:
     *   • Race condition at first launch (Shizuku GUI hasn't fully settled)
     *   • MIUI background kill of the UserService process
     *   • Server-side timeout with silent callback.kill() (see class javadoc)
     */
    @MainThread
    public void retryConnection() {
        checkAndBind();
    }

    // ── Public state ───────────────────────────────────────────────────────────

    /** Returns true only when the remote binder is confirmed alive. */
    public boolean isAvailable() {
        if (!bound || service == null) return false;
        try {
            return service.asBinder().isBinderAlive();
        } catch (Throwable t) {
            return false;
        }
    }

    public ShizukuState getCurrentState() {
        return lastReportedState;
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Watchdog fired: onServiceConnected() did not arrive within BIND_WATCHDOG_MS.
     *
     * This means Shizuku's server-side timeout fired (or will fire shortly), removed
     * the UserServiceRecord, and called callbacks.kill() WITHOUT calling died() on our
     * ShizukuServiceConnection. We are permanently stuck in CONNECTING.
     *
     * Recovery:
     *   1. unbindUserService(remove=false) — tells Shizuku server to drop our stale
     *      ShizukuServiceConnection from the (possibly already removed) record. Using
     *      remove=false avoids killing the UserService if it somehow started late.
     *   2. Delay 500 ms to let Shizuku settle, then call bindUserService() again.
     *      ShizukuServiceConnections.get() will create a fresh ShizukuServiceConnection
     *      (since the old one was removed by unbind), giving us a clean registration.
     */
    private void onBindWatchdogFired() {
        Log.w(TAG, "bindUserService() watchdog fired after " + BIND_WATCHDOG_MS
                + " ms — server likely timed out silently, retrying");
        binding = false;

        // Clean up the stale ShizukuServiceConnection from Shizuku's server record.
        try {
            Shizuku.unbindUserService(serviceArgs, connection, false);
        } catch (Throwable t) {
            Log.w(TAG, "unbindUserService in watchdog failed: " + t.getMessage());
        }

        // Give Shizuku 500 ms to process the unbind before we rebind.
        mainHandler.postDelayed(() -> checkAndBind(), RETRY_DELAY_MS);
    }

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
        } catch (Throwable t) {
            Log.e(TAG, "checkSelfPermission failed: " + t.getMessage());
            // Transient binder error — show current state, recover on next retry.
            reportState(binding ? ShizukuState.CONNECTING : ShizukuState.PERMISSION_NEEDED);
            return;
        }

        if (perm == PackageManager.PERMISSION_GRANTED) {
            bindUserService();
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            // Per the official Shizuku demo (DemoActivity.checkPermission):
            // "Users choose 'Deny and don't ask again'" — do NOT call requestPermission().
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
     * Calls Shizuku.bindUserService() and starts the client-side watchdog.
     *
     * The watchdog fires after BIND_WATCHDOG_MS if onServiceConnected() hasn't been
     * delivered — indicating Shizuku's server-side timeout fired silently (see class
     * javadoc). On watchdog fire we clean up and schedule a fresh retry.
     *
     * Uses catch (Throwable) rather than catch (Exception) because:
     *   • Shizuku wraps RemoteException in RuntimeException (unchecked).
     *   • Binder.transact() can throw TransactionTooLargeException (RuntimeException).
     *   • Some Binder edge cases on MIUI throw Error subclasses.
     *   Catching only Exception would propagate an Error up through the ViewModel
     *   constructor call chain, causing an uncaught crash → black screen.
     */
    private void bindUserService() {
        binding = true;
        reportState(ShizukuState.CONNECTING);

        // Arm the watchdog. Cancelled in onServiceConnected() on success.
        mainHandler.removeCallbacks(bindWatchdog);
        mainHandler.postDelayed(bindWatchdog, BIND_WATCHDOG_MS);

        try {
            Log.d(TAG, "bindUserService: calling Shizuku.bindUserService");
            // Safe to call multiple times — server deduplicates by tag in
            // UserServiceManager.createUserServiceRecordIfNeededLocked().
            Shizuku.bindUserService(serviceArgs, connection);
        } catch (Throwable t) {
            // Disarm the watchdog on immediate failure.
            mainHandler.removeCallbacks(bindWatchdog);
            binding = false;
            Log.e(TAG, "bindUserService failed: " + t.getMessage());
            // Leave state as CONNECTING — retryConnection() from the next onResume()
            // will recover. Do NOT call checkAndBind() here: if Shizuku threw because
            // the binder is transiently dead, a recursive call would throw again.
            // The sticky listener will re-fire via onBinderReceived when Shizuku recovers.
        }
    }

    private void releaseService() {
        mainHandler.removeCallbacks(bindWatchdog);
        binding = false;
        if (bound || service != null) {
            try {
                Shizuku.unbindUserService(serviceArgs, connection, true);
            } catch (Throwable t) {
                Log.e(TAG, "unbindUserService failed: " + t.getMessage());
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
