package app.hypermtz.ui.dialog;

import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.hypermtz.IPrivilegedService;
import app.hypermtz.R;
import app.hypermtz.ui.MainViewModel;
import app.hypermtz.util.LogManager;
import app.hypermtz.service.ThemeInterceptService;

/**
 * Installs a .mtz theme file using the ZWS path trick, then launches ThemeManager.
 *
 * ── Install flow (primary — ThemeStore ZWS method) ──────────────────────
 * 1. Stream bytes from content:// URI to ZWS path:
 *    /sdcard/Android/\u200bdata/com.android.thememanager/files/theme/安装主题.mtz
 *    No Shizuku, no MANAGE_EXTERNAL_STORAGE (bypasses scoped storage check).
 * 2. Launch com.android.thememanager/.ApplyThemeForScreenshot with REAL path.
 *
 * ── Fallback (Shizuku) ────────────────────────────────────────────────────
 * If ZWS streaming fails (SELinux edge case, older ROM):
 * 1. Copy URI bytes to app's external cache.
 * 2. Use PrivilegedService.copyFile() to place at snapshot path.
 * 3. Launch ThemeManager with snapshot path.
 *
 * ── CRITICAL FIX: context capture before dismiss ─────────────────────────
 * dismissAllowingStateLoss() detaches the fragment immediately. Any call to
 * requireContext() / requireActivity() / isAdded() AFTER that throws or
 * returns false. The original code dismissed first, then checked isAdded() in
 * the executor → always false → ThemeManager never launched!
 *
 * Fix: capture Activity + applicationContext BEFORE dismiss. Pass them as
 * parameters to all helper methods. Never call isAdded() / requireXxx() in
 * the executor after the fragment is dismissed.
 */
public class FileApplyDialogFragment extends DialogFragment {

    public static final String TAG = "FileApplyDialog";

    private static final String TAG_LOG        = "FileApplyDialog";
    private static final String ARG_SOURCE_URI = "source_uri";
    private static final String ARG_FILE_NAME  = "file_name";

    // ── ThemeStore install paths ──────────────────────────────────────────────

    /** Exact filename ThemeStore uses. Passing it in the intent matches ThemeStore behavior. */
    private static final String THEME_FILENAME = "安装主题.mtz";

    private static final String REAL_THEME_DIR =
            Environment.getExternalStorageDirectory().getPath()
            + "/Android/data/com.android.thememanager/files/theme/";

    /** Path passed in the intent extra — always the canonical real path (no ZWS). */
    private static final String REAL_THEME_PATH = REAL_THEME_DIR + THEME_FILENAME;

    /**
     * Shizuku fallback destination.
     * FIX: use Environment.getExternalStorageDirectory() instead of hardcoded /sdcard/
     * so this works on devices where /sdcard is not the primary external storage.
     */
    private static final String SNAPSHOT_PATH =
            Environment.getExternalStorageDirectory().getPath()
            + "/Android/data/com.android.thememanager/files/snapshot/snapshot.mtz";

    /**
     * Milliseconds to wait after sending ACTION_SUPPRESS_AUTO_CLICK before
     * launching ThemeManager. This ensures the suppress broadcast is received
     * by ThemeInterceptService (:intercept process) BEFORE ThemeManager's
     * first Activity lifecycle callbacks run.
     *
     * sendBroadcast() is async — on a loaded device, the broadcast delivery to
     * the :intercept process could theoretically be delayed. A 300ms pause after
     * sending the suppress broadcast is more than sufficient (Activity creation
     * takes 100–500ms on typical MIUI/HyperOS devices).
     */
    private static final long LAUNCH_DELAY_MS = 300L;

    // ── ThemeManager ──────────────────────────────────────────────────────────

    private static final String THEME_MANAGER_PKG    = "com.android.thememanager";
    private static final String THEME_APPLY_ACTIVITY =
            "com.android.thememanager.ApplyThemeForScreenshot";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // ── Factory ───────────────────────────────────────────────────────────────

    public static FileApplyDialogFragment newInstance(String sourceUri, String fileName) {
        Bundle args = new Bundle();
        args.putString(ARG_SOURCE_URI, sourceUri);
        args.putString(ARG_FILE_NAME, fileName);
        FileApplyDialogFragment f = new FileApplyDialogFragment();
        f.setArguments(args);
        return f;
    }

    // ── Dialog ────────────────────────────────────────────────────────────────

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        String uriString = requireArguments().getString(ARG_SOURCE_URI, "");
        String fileName  = requireArguments().getString(ARG_FILE_NAME, "theme.mtz");

        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_file_apply, null);

        // Destination is always REAL_THEME_PATH — name field not needed.
        View tilName = view.findViewById(R.id.til_theme_name);
        if (tilName != null) tilName.setVisibility(View.GONE);

        ((TextView) view.findViewById(R.id.tv_file_name)).setText(fileName);

        MainViewModel viewModel = new ViewModelProvider(requireActivity())
                .get(MainViewModel.class);

        Uri sourceUri = Uri.parse(uriString);

        view.findViewById(R.id.btn_import).setOnClickListener(
                v -> startInstall(viewModel, sourceUri, false));
        view.findViewById(R.id.btn_apply).setOnClickListener(
                v -> startInstall(viewModel, sourceUri, true));

        return new AlertDialog.Builder(requireContext())
                .setView(view)
                .create();
    }

    // ── Install entry point ───────────────────────────────────────────────────

    private void startInstall(MainViewModel viewModel, Uri sourceUri, boolean applyAfterCopy) {
        // ── CRITICAL: capture context references BEFORE dismissing ─────────────
        // After dismissAllowingStateLoss(), this fragment is detached:
        //   • isAdded()        → always false
        //   • requireContext() → throws IllegalStateException
        //   • requireActivity()→ throws IllegalStateException
        //
        // Capture what we need NOW, pass them into the executor lambda.
        final Activity        activity   = requireActivity();
        final Context         appContext = requireContext().getApplicationContext();
        final ContentResolver cr        = appContext.getContentResolver();

        dismissAllowingStateLoss();
        viewModel.setThemeCopyRunning(true);

        executor.submit(() -> {
            // ── Strategy 1: ZWS stream (ThemeStore method, no Shizuku) ──────────
            boolean success       = streamUriViaZwsPath(cr, sourceUri);
            String  installedPath = REAL_THEME_PATH;

            if (success) {
                Log.d(TAG_LOG, "ZWS stream succeeded → " + REAL_THEME_PATH);
            } else {
                // ── Strategy 2: Shizuku snapshot fallback ──────────────────────
                Log.w(TAG_LOG, "ZWS stream failed — trying Shizuku snapshot fallback");
                File cacheFile = copyUriToCache(cr, appContext, sourceUri);
                if (cacheFile != null) {
                    IPrivilegedService svc = viewModel.getPrivilegedService();
                    if (svc != null) {
                        try {
                            success       = svc.copyFile(cacheFile.getAbsolutePath(), SNAPSHOT_PATH);
                            installedPath = SNAPSHOT_PATH;
                            Log.d(TAG_LOG, "Shizuku copyFile "
                                    + (success ? "succeeded" : "failed") + " → " + SNAPSHOT_PATH);
                        } catch (RemoteException e) {
                            Log.e(TAG_LOG, "Shizuku IPC failed", e);
                        } finally {
                            //noinspection ResultOfMethodCallIgnored
                            cacheFile.delete();
                        }
                    } else {
                        Log.w(TAG_LOG, "Shizuku not connected — cannot fallback");
                        //noinspection ResultOfMethodCallIgnored
                        cacheFile.delete();
                    }
                }
            }

            final boolean copyOk    = success;
            final String  finalPath = installedPath;

            // Post results back on main thread using the captured Activity reference.
            // Do NOT use isAdded() / requireXxx() here — fragment is already dismissed.
            if (activity.isFinishing() || activity.isDestroyed()) {
                viewModel.setThemeCopyRunning(false);
                return;
            }

            activity.runOnUiThread(() -> {
                viewModel.setThemeCopyRunning(false);

                if (copyOk) {
                    Toast.makeText(appContext, R.string.copy_success, Toast.LENGTH_SHORT).show();
                    LogManager.log(appContext, LogManager.LogType.THEME_INSTALL,
                            "Theme installed", "path=" + finalPath);
                    if (applyAfterCopy) {
                        launchApplyThemeForScreenshot(finalPath, appContext);
                    }
                    viewModel.refresh();
                } else {
                    Toast.makeText(appContext, R.string.copy_failed, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    // ── Strategy 1: ZWS streaming ─────────────────────────────────────────────

    /**
     * Inserts U+200B after "Android/" to produce a path that passes Android's
     * scoped-storage checker (which does string comparison) while resolving to
     * the same filesystem location via FUSE on Android 11+.
     *
     * On Android < 11, returns the original file (legacy storage).
     */
    private static File getReviseFile(File file) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return file;

        String androidDir = Environment.getExternalStorageDirectory().getPath() + "/Android/";
        String canonical;
        try {
            canonical = file.getCanonicalPath();
        } catch (IOException e) {
            canonical = file.getAbsolutePath();
        }

        // Case-insensitive check in case the path casing differs on some ROMs.
        if (canonical.length() > androidDir.length()
                && canonical.toLowerCase().startsWith(androidDir.toLowerCase())) {
            // Insert ZWS immediately after "Android/" and before "data/".
            return new File(androidDir + "\u200b" + canonical.substring(androidDir.length()));
        }
        return file;
    }

    /**
     * Streams the URI bytes directly to the ZWS-modified destination path.
     *
     * Uses the pre-captured ContentResolver so this method is safe to call
     * after the fragment has been dismissed.
     */
    private static boolean streamUriViaZwsPath(ContentResolver cr, Uri sourceUri) {
        try {
            File realDir = new File(REAL_THEME_DIR);
            File zwsDir  = getReviseFile(realDir);
            File zwsFile = new File(zwsDir, THEME_FILENAME);

            if (!zwsDir.exists() && !zwsDir.mkdirs()) {
                Log.w(TAG_LOG, "ZWS mkdirs failed: " + zwsDir);
                // Attempt mkdirs on the real path as a fallback.
                if (!realDir.exists() && !realDir.mkdirs()) {
                    Log.w(TAG_LOG, "realDir mkdirs also failed: " + realDir);
                    return false;
                }
                // If real mkdirs succeeded, write to real path directly.
                zwsFile = new File(realDir, THEME_FILENAME);
            }

            if (zwsFile.exists()) //noinspection ResultOfMethodCallIgnored
                zwsFile.delete();

            try (InputStream    in  = cr.openInputStream(sourceUri);
                 FileOutputStream out = new FileOutputStream(zwsFile)) {
                if (in == null) {
                    Log.w(TAG_LOG, "Cannot open URI: " + sourceUri);
                    return false;
                }
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                out.flush();
                // fdatasync() ensures the OS kernel commits all buffers to storage
                // before ThemeManager reads the file. flush() only flushes Java-level
                // buffers; without sync the file may appear incomplete to another process.
                try { out.getFD().sync(); } catch (Exception ignored) {}
            }

            if (!zwsFile.exists() || zwsFile.length() == 0) {
                Log.w(TAG_LOG, "ZWS file empty after stream");
                return false;
            }

            // Log whether the real path is visible (useful for debugging the FUSE trick).
            Log.d(TAG_LOG, "ZWS stream OK: size=" + zwsFile.length()
                    + " | real_exists=" + new File(REAL_THEME_PATH).exists());
            return true;

        } catch (Exception e) {
            Log.e(TAG_LOG, "streamUriViaZwsPath failed", e);
            return false;
        }
    }

    // ── Strategy 2 helper: URI → external cache ───────────────────────────────

    /**
     * Copies the URI bytes to the app's external cache directory.
     *
     * External cache (/sdcard/Android/data/app.hypermtz/cache/) is readable by
     * ADB shell on all MIUI versions without SELinux issues.
     *
     * Uses pre-captured cr + appContext so this is safe after fragment dismiss.
     *
     * @return the cache File on success, null on failure.
     */
    @Nullable
    private static File copyUriToCache(ContentResolver cr, Context appContext, Uri sourceUri) {
        try {
            File cacheDir = appContext.getExternalCacheDir();
            if (cacheDir == null) cacheDir = appContext.getCacheDir();
            File outFile = new File(cacheDir, "hypermtz_temp.mtz");

            try (InputStream    in  = cr.openInputStream(sourceUri);
                 FileOutputStream out = new FileOutputStream(outFile)) {
                if (in == null) return null;
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                out.flush();
                // *** FIX: fdatasync before handing path to Shizuku (cross-process read) ***
                // flush() only clears Java's buffer into the OS page cache.
                // The Shizuku process opens this file via a separate process context.
                // Without sync(), the OS may not have committed all dirty pages, and the
                // cross-process read may see a truncated or zero-length file.
                try { out.getFD().sync(); } catch (Exception ignored) {}
            }

            return (outFile.exists() && outFile.length() > 0) ? outFile : null;

        } catch (Exception e) {
            Log.e(TAG_LOG, "copyUriToCache failed", e);
            return null;
        }
    }

    // ── ThemeManager launcher ─────────────────────────────────────────────────

    /**
     * Launches com.android.thememanager/.ApplyThemeForScreenshot.
     *
     * Sends ACTION_SUPPRESS_AUTO_CLICK to ThemeInterceptService first, then
     * delays LAUNCH_DELAY_MS before calling startActivity. This eliminates the
     * race condition where ThemeManager sends CHECK_TIME_UP before the suppress
     * broadcast arrives at the :intercept process.
     *
     * Uses appContext.startActivity() with FLAG_ACTIVITY_NEW_TASK so it can
     * be called from any context, including after the dialog is dismissed.
     *
     * Tries ThemeStore method (api_called_from="ThemeEditor") first, then
     * falls back to the snapshot/miuithemestore method (api_called_from="com.miui.themestore").
     */
    private static void launchApplyThemeForScreenshot(String themePath, Context appContext) {
        // ── Step 1: Suppress ThemeInterceptService for this install ──────────
        // ThemeInterceptService.onAccessibilityEvent() auto-clicks buttons in ANY
        // ThemeManager window. ACTION_SUPPRESS_AUTO_CLICK disables BOTH auto-click
        // AND CHECK_TIME_UP interception for INSTALL_SUPPRESS_MS (120s).
        //
        // WHY NOT SharedPreferences:
        // ThemeInterceptService runs in ":intercept". Each process holds its own
        // in-memory SharedPreferences cache. Directed broadcast is the correct
        // cross-process signal.
        Intent suppressIntent = new Intent(ThemeInterceptService.ACTION_SUPPRESS_AUTO_CLICK);
        suppressIntent.setPackage(appContext.getPackageName());
        appContext.sendBroadcast(suppressIntent);

        // ── Step 2: Delay to ensure suppress is received before launch ───────
        // sendBroadcast() is asynchronous. LAUNCH_DELAY_MS gives the broadcast
        // time to be delivered to the :intercept process before ThemeManager's
        // first lifecycle callbacks fire (which is when CHECK_TIME_UP is sent).
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            // *** FIX: Always try ThemeEditor first, for ANY path (including snapshot) ***
            //
            // The old code skipped ThemeEditor when isSnapshotPath == true, forcing the
            // com.miui.themestore path. On many MIUI/HyperOS builds, com.miui.themestore
            // triggers a stricter UID check inside ThemeManager: if Binder.getCallingUid()
            // does not match the declared api_called_from package, ThemeManager rejects the
            // install. ThemeEditor does NOT perform this UID check on most MIUI versions.
            //
            // tryLaunch() already catches all exceptions and returns false on failure, so
            // attempting ThemeEditor for the snapshot path is completely safe — if it
            // doesn't work on a specific ROM it falls through to com.miui.themestore.
            if (tryLaunch(appContext, themePath, "ThemeEditor", false)) {
                Log.d(TAG_LOG, "Launched via ThemeEditor");
                return;
            }
            // Fallback: miuithemestore method (snapshot path or older MIUI)
            if (tryLaunch(appContext, themePath, "com.miui.themestore", true)) {
                Log.d(TAG_LOG, "Launched via com.miui.themestore");
                return;
            }
            Log.e(TAG_LOG, "All launch attempts failed for: " + themePath);
            Toast.makeText(appContext, R.string.apply_theme_failed, Toast.LENGTH_SHORT).show();
        }, LAUNCH_DELAY_MS);
    }

    private static boolean tryLaunch(Context appContext, String themePath,
                                      String apiCalledFrom, boolean includeApplyFlags) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setComponent(new ComponentName(THEME_MANAGER_PKG, THEME_APPLY_ACTIVITY));
            intent.putExtra("theme_file_path", themePath);
            intent.putExtra("api_called_from", apiCalledFrom);
            intent.putExtra("ver2_step",       "ver2_step_apply");
            if (includeApplyFlags) {
                intent.putExtra("theme_apply_flags", 1);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.w(TAG_LOG, "tryLaunch [" + apiCalledFrom + "] failed: " + e.getMessage());
            return false;
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onDestroyView() {
        executor.shutdownNow();
        super.onDestroyView();
    }
}
