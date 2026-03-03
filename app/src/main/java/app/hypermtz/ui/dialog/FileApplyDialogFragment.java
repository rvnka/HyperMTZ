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
import android.os.RemoteException;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
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
import java.util.Locale;
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
 *    /sdcard/Android/\u200bdata/com.android.thememanager/files/snapshot/snapshot.mtz
 *    No Shizuku, no MANAGE_EXTERNAL_STORAGE (bypasses scoped storage check).
 * 2. Launch com.android.thememanager/.ApplyThemeForScreenshot with SNAPSHOT_PATH.
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

    private static final String ARG_SOURCE_URI = "source_uri";
    private static final String ARG_FILE_NAME  = "file_name";

    // ── Install destination ───────────────────────────────────────────────────
    //
    // Confirmed working command:
    //   am start -n "com.android.thememanager/.ApplyThemeForScreenshot"
    //     --es theme_file_path "/sdcard/Android/data/com.android.thememanager/files/snapshot/snapshot.mtz"
    //     --es ver2_step "ver2_step_apply"
    //     --es api_called_from "com.miui.themestore"
    //     --ei theme_apply_flags 1

    private static final String SNAPSHOT_DIR  =
            Environment.getExternalStorageDirectory().getPath()
            + "/Android/data/com.android.thememanager/files/snapshot/";

    private static final String SNAPSHOT_PATH = SNAPSHOT_DIR + "snapshot.mtz";

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

        // Theme name field not needed — destination path is fixed (SNAPSHOT_PATH).
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
            // ── Strategy 1: ZWS stream (no Shizuku needed) ──────────────────
            // Inserts U+200B after "Android/" to bypass scoped storage check,
            // writing directly to SNAPSHOT_PATH without MANAGE_EXTERNAL_STORAGE.
            boolean success = streamUriViaZwsPath(cr, sourceUri);

            if (success) {
                Log.d(TAG, "ZWS stream succeeded → " + SNAPSHOT_PATH);
            } else {
                // ── Strategy 2: Shizuku copy to SNAPSHOT_PATH ────────────────
                Log.w(TAG, "ZWS stream failed — trying Shizuku copy");
                File cacheFile = copyUriToCache(cr, appContext, sourceUri);
                if (cacheFile != null) {
                    IPrivilegedService svc = viewModel.getPrivilegedService();
                    if (svc != null) {
                        try {
                            success = svc.copyFile(cacheFile.getAbsolutePath(), SNAPSHOT_PATH);
                            Log.d(TAG, "Shizuku copyFile " + (success ? "succeeded" : "failed")
                                    + " → " + SNAPSHOT_PATH);
                        } catch (RemoteException e) {
                            Log.e(TAG, "Shizuku IPC failed", e);
                        } finally {
                            //noinspection ResultOfMethodCallIgnored
                            cacheFile.delete();
                        }
                    } else {
                        Log.w(TAG, "Shizuku not connected — both strategies failed");
                        //noinspection ResultOfMethodCallIgnored
                        cacheFile.delete();
                    }
                }
            }

            final boolean copyOk = success;

            // Post results back on main thread. Fragment is already dismissed —
            // use captured Activity reference, never isAdded() / requireXxx().
            if (activity.isFinishing() || activity.isDestroyed()) {
                viewModel.setThemeCopyRunning(false);
                return;
            }

            activity.runOnUiThread(() -> {
                viewModel.setThemeCopyRunning(false);

                if (copyOk) {
                    Toast.makeText(appContext, R.string.copy_success, Toast.LENGTH_SHORT).show();
                    LogManager.log(appContext, LogManager.LogType.THEME_INSTALL,
                            "Theme installed", "path=" + SNAPSHOT_PATH);
                    if (applyAfterCopy) {
                        launchApplyThemeForScreenshot(appContext);
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
                && canonical.toLowerCase(Locale.ROOT).startsWith(androidDir.toLowerCase(Locale.ROOT))) {
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
            File realDir = new File(SNAPSHOT_DIR);
            File zwsDir  = getReviseFile(realDir);
            File zwsFile = new File(zwsDir, "snapshot.mtz");

            if (!zwsDir.exists() && !zwsDir.mkdirs()) {
                Log.w(TAG, "ZWS mkdirs failed: " + zwsDir);
                if (!realDir.exists() && !realDir.mkdirs()) {
                    Log.w(TAG, "realDir mkdirs also failed: " + realDir);
                    return false;
                }
                zwsFile = new File(realDir, "snapshot.mtz");
            }

            if (zwsFile.exists()) //noinspection ResultOfMethodCallIgnored
                zwsFile.delete();

            try (InputStream     in  = cr.openInputStream(sourceUri);
                 FileOutputStream out = new FileOutputStream(zwsFile)) {
                if (in == null) {
                    Log.w(TAG, "Cannot open URI: " + sourceUri);
                    return false;
                }
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                out.flush();
            }

            if (!zwsFile.exists() || zwsFile.length() == 0) {
                Log.w(TAG, "ZWS file empty after stream");
                return false;
            }

            Log.d(TAG, "ZWS stream OK: size=" + zwsFile.length()
                    + " | real_exists=" + new File(SNAPSHOT_PATH).exists());
            return true;

        } catch (Exception e) {
            Log.e(TAG, "streamUriViaZwsPath failed", e);
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
            }

            return (outFile.exists() && outFile.length() > 0) ? outFile : null;

        } catch (Exception e) {
            Log.e(TAG, "copyUriToCache failed", e);
            return null;
        }
    }

    // ── ThemeManager launcher ─────────────────────────────────────────────────

    /**
     * Launches ThemeManager using the exact confirmed working command:
     *
     *   am start -n "com.android.thememanager/.ApplyThemeForScreenshot"
     *     --es theme_file_path "/sdcard/Android/data/com.android.thememanager/files/snapshot/snapshot.mtz"
     *     --es ver2_step "ver2_step_apply"
     *     --es api_called_from "com.miui.themestore"
     *     --ei theme_apply_flags 1
     */
    private static void launchApplyThemeForScreenshot(Context appContext) {
        // Suppress ThemeInterceptService auto-click for 120s (cross-process broadcast).
        Intent suppressIntent = new Intent(ThemeInterceptService.ACTION_SUPPRESS_AUTO_CLICK);
        suppressIntent.setPackage(appContext.getPackageName());
        appContext.sendBroadcast(suppressIntent);

        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setComponent(new ComponentName(THEME_MANAGER_PKG, THEME_APPLY_ACTIVITY));
            intent.putExtra("theme_file_path", SNAPSHOT_PATH);
            intent.putExtra("ver2_step",       "ver2_step_apply");
            intent.putExtra("api_called_from", "com.miui.themestore");
            intent.putExtra("theme_apply_flags", 1);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(intent);
            Log.d(TAG, "Launched ThemeManager → " + SNAPSHOT_PATH);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch ThemeManager: " + e.getMessage());
            Toast.makeText(appContext, R.string.apply_theme_failed, Toast.LENGTH_SHORT).show();
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onDestroyView() {
        executor.shutdownNow();
        super.onDestroyView();
    }
}
