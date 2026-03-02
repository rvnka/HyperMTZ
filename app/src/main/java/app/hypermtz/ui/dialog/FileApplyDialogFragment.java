package app.hypermtz.ui.dialog;

import android.app.Dialog;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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

/**
 * Installs a .mtz theme file using ThemeStore's Zero Width Space path trick,
 * then launches MIUI ThemeManager via ApplyThemeForScreenshot.
 *
 * ── Install flow (ThemeStore method — primary) ────────────────────────────
 *
 *  1. Stream bytes from content:// URI directly to the ZWS path:
 *       /sdcard/Android/\u200bdata/com.android.thememanager/files/theme/安装主题.mtz
 *     No Shizuku, no intermediate cache, no special permissions needed.
 *     On Android < 11 the real path is used (legacy storage).
 *
 *  2. Launch com.android.thememanager/.ApplyThemeForScreenshot with the
 *     REAL (non-ZWS) path:
 *       /sdcard/Android/data/com.android.thememanager/files/theme/安装主题.mtz
 *     ThemeManager cannot resolve the ZWS path from an intent extra, so we
 *     always pass the canonical real path regardless of how we wrote the file.
 *
 * ── Fallback flow (Shizuku method — when ZWS fails) ─────────────────────
 *
 *  If ZWS streaming fails (SELinux edge case, older ROM quirk, etc.):
 *  - Copy the URI bytes to external cache (app process can resolve content://)
 *  - Hand the cache path to PrivilegedService.copyFile() via Shizuku IPC
 *  - Target: /sdcard/Android/data/com.android.thememanager/files/snapshot/snapshot.mtz
 *  - Launch ThemeManager with the snapshot path
 *
 * ── Intent extras (ThemeStore method — exact copy) ────────────────────────
 *
 *  action            = Intent.ACTION_MAIN      (required)
 *  theme_file_path   = REAL_THEME_PATH         (canonical path, no ZWS)
 *  api_called_from   = "ThemeEditor"           (ThemeStore — wider ROM compat)
 *  ver2_step         = "ver2_step_apply"
 *  FLAG_ACTIVITY_NEW_TASK
 *
 *  Fallback api_called_from = "com.miui.themestore" (io.vi.hypertheme method)
 *  theme_apply_flags = 1 added only in the Shizuku/snapshot fallback path
 *  (ThemeStore does not include this extra).
 *
 * ── Why NOT_EXPORTED for Shizuku? ────────────────────────────────────────
 *
 *  Shizuku is not used for broadcast interception, only for file copy.
 *  The broadcast receiver in ThemeInterceptService uses NOT_EXPORTED (API 33+)
 *  because MIUI (system/privileged) can still deliver to NOT_EXPORTED receivers.
 */
public class FileApplyDialogFragment extends DialogFragment {

    public static final String TAG = "FileApplyDialog";

    private static final String TAG_LOG        = "FileApplyDialog";
    private static final String ARG_SOURCE_URI = "source_uri";
    private static final String ARG_FILE_NAME  = "file_name";

    // ── ThemeStore method — exact paths ──────────────────────────────────────

    /**
     * ThemeStore uses "安装主题.mtz" — this exact filename is passed in
     * theme_file_path. MIUI doesn't care about the name, but matching
     * ThemeStore reduces any chance of future ROM-side filtering.
     */
    private static final String THEME_FILENAME  = "安装主题.mtz";

    private static final String REAL_THEME_DIR  =
            Environment.getExternalStorageDirectory().getPath()
            + "/Android/data/com.android.thememanager/files/theme/";

    /** Exact path passed in the intent extra (canonical, no ZWS). */
    private static final String REAL_THEME_PATH = REAL_THEME_DIR + THEME_FILENAME;

    // ── Shizuku snapshot fallback ─────────────────────────────────────────────

    private static final String SNAPSHOT_PATH =
            "/sdcard/Android/data/com.android.thememanager/files/snapshot/snapshot.mtz";

    // ── ThemeManager activity ─────────────────────────────────────────────────

    private static final String THEME_MANAGER_PKG    = "com.android.thememanager";
    private static final String THEME_APPLY_ACTIVITY =
            "com.android.thememanager.ApplyThemeForScreenshot";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // ──────────────────────────────────────────────────────────────────────────

    /**
     * @param sourceUri  content:// or file:// URI of the .mtz file the user selected
     * @param fileName   display name (e.g. "CoolTheme.mtz") for the dialog title
     */
    public static FileApplyDialogFragment newInstance(String sourceUri, String fileName) {
        Bundle args = new Bundle();
        args.putString(ARG_SOURCE_URI, sourceUri);
        args.putString(ARG_FILE_NAME, fileName);
        FileApplyDialogFragment f = new FileApplyDialogFragment();
        f.setArguments(args);
        return f;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        String uriString = requireArguments().getString(ARG_SOURCE_URI, "");
        String fileName  = requireArguments().getString(ARG_FILE_NAME, "theme.mtz");

        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_file_apply, null);

        TextView tvFileName = view.findViewById(R.id.tv_file_name);
        Button   btnImport  = view.findViewById(R.id.btn_import);
        Button   btnApply   = view.findViewById(R.id.btn_apply);

        // Theme-name input not needed — destination is always REAL_THEME_PATH.
        View tilName = view.findViewById(R.id.til_theme_name);
        if (tilName != null) tilName.setVisibility(View.GONE);

        tvFileName.setText(fileName);

        MainViewModel viewModel = new ViewModelProvider(requireActivity())
                .get(MainViewModel.class);

        Uri sourceUri = Uri.parse(uriString);

        btnImport.setOnClickListener(v -> startInstall(viewModel, sourceUri, false));
        btnApply.setOnClickListener(v  -> startInstall(viewModel, sourceUri, true));

        return new AlertDialog.Builder(requireContext())
                .setView(view)
                .create();
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    private void startInstall(MainViewModel viewModel, Uri sourceUri, boolean applyAfterCopy) {
        dismissAllowingStateLoss();
        viewModel.setThemeCopyRunning(true);

        executor.submit(() -> {
            // ── Strategy 1 (ThemeStore): stream URI → ZWS path ───────────────
            // No Shizuku, no intermediate cache, no MANAGE_EXTERNAL_STORAGE.
            boolean success = streamUriViaZwsPath(sourceUri);
            String  installedPath = REAL_THEME_PATH;

            if (success) {
                Log.d(TAG_LOG, "ZWS stream succeeded → " + REAL_THEME_PATH);
            } else {
                // ── Strategy 2 (Shizuku fallback) ─────────────────────────────
                Log.w(TAG_LOG, "ZWS stream failed — trying Shizuku snapshot fallback");

                // Step 2a: copy URI bytes to external cache (app process resolves URI).
                File cacheFile = copyUriToCache(sourceUri);

                if (cacheFile != null) {
                    IPrivilegedService service = viewModel.getPrivilegedService();
                    if (service != null) {
                        try {
                            success = service.copyFile(
                                    cacheFile.getAbsolutePath(), SNAPSHOT_PATH);
                            installedPath = SNAPSHOT_PATH;
                            Log.d(TAG_LOG, "Shizuku copyFile "
                                    + (success ? "succeeded" : "failed")
                                    + " → " + SNAPSHOT_PATH);
                        } catch (RemoteException e) {
                            Log.e(TAG_LOG, "Shizuku IPC failed", e);
                        } finally {
                            //noinspection ResultOfMethodCallIgnored
                            cacheFile.delete(); // clean up temp file
                        }
                    } else {
                        Log.w(TAG_LOG, "Shizuku not connected, can't fallback");
                        cacheFile.delete();
                    }
                }
            }

            final boolean copyOk    = success;
            final String  finalPath = installedPath;

            if (!isAdded()) {
                viewModel.setThemeCopyRunning(false);
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) {
                    viewModel.setThemeCopyRunning(false);
                    return;
                }
                viewModel.setThemeCopyRunning(false);
                if (copyOk) {
                    Toast.makeText(requireContext(), R.string.copy_success,
                            Toast.LENGTH_SHORT).show();
                    // Log the install via LogManager for statistics tracking.
                    LogManager.log(requireContext(),
                            LogManager.LogType.THEME_INSTALL,
                            "Theme installed",
                            "path=" + finalPath);
                    if (applyAfterCopy) {
                        launchApplyThemeForScreenshot(finalPath);
                    }
                    viewModel.refresh();
                } else {
                    Toast.makeText(requireContext(), R.string.copy_failed,
                            Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    // ── Strategy 1: ZWS streaming directly from URI ───────────────────────────

    /**
     * Computes the ZWS-modified path for Android 11+ scoped storage bypass.
     *
     * Inserting U+200B (Zero Width Space) immediately after "Android/" produces
     * a path the filesystem treats identically, but Android's restricted-path
     * checker does not recognize as a protected location.
     *
     * On Android < 11 returns the original file unchanged (legacy storage).
     */
    private static File getReviseFile(File file) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return file;

        String androidPath = Environment.getExternalStorageDirectory().getPath() + "/Android/";
        String canonical;
        try {
            canonical = file.getCanonicalPath();
        } catch (IOException e) {
            canonical = file.getAbsolutePath();
        }

        if (canonical.length() > androidPath.length()
                && canonical.toLowerCase().startsWith(androidPath.toLowerCase())) {
            return new File(androidPath + "\u200b" + canonical.substring(androidPath.length()));
        }
        return file;
    }

    /**
     * Streams bytes from a content:// or file:// URI directly to the ZWS path.
     *
     * This is the ThemeStore method: no Shizuku, no intermediate cache file,
     * no MANAGE_EXTERNAL_STORAGE. Works because:
     *   - ContentResolver.openInputStream() resolves the URI in the app process
     *   - The ZWS path is writable on Android 11+ without special permissions
     *   - ThemeManager is launched with the REAL (non-ZWS) canonical path
     */
    private boolean streamUriViaZwsPath(Uri sourceUri) {
        try {
            File realDir = new File(REAL_THEME_DIR);
            File zwsDir  = getReviseFile(realDir);
            File zwsFile = new File(zwsDir, THEME_FILENAME);

            if (!zwsDir.exists() && !zwsDir.mkdirs()) {
                Log.w(TAG_LOG, "ZWS mkdirs failed: " + zwsDir);
                return false;
            }

            if (zwsFile.exists()) zwsFile.delete();

            ContentResolver cr = requireContext().getContentResolver();
            try (InputStream in  = cr.openInputStream(sourceUri);
                 FileOutputStream out = new FileOutputStream(zwsFile)) {
                if (in == null) {
                    Log.w(TAG_LOG, "Cannot open URI: " + sourceUri);
                    return false;
                }
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                out.flush();
            }

            if (!zwsFile.exists() || zwsFile.length() == 0) {
                Log.w(TAG_LOG, "ZWS file empty after stream");
                return false;
            }

            Log.d(TAG_LOG, "ZWS stream: size=" + zwsFile.length()
                    + " real_exists=" + new File(REAL_THEME_PATH).exists());
            return true;

        } catch (Exception e) {
            Log.e(TAG_LOG, "streamUriViaZwsPath failed", e);
            return false;
        }
    }

    // ── Strategy 2 helper: copy URI to external cache ─────────────────────────

    /**
     * Copies the URI bytes to the app's external cache directory.
     *
     * Used only as a Shizuku fallback when ZWS streaming fails.
     * External cache (/sdcard/Android/data/app.hypermtz/cache/) is readable
     * by ADB shell on all MIUI versions without SELinux issues.
     *
     * @return the cache File on success, null on failure.
     */
    @Nullable
    private File copyUriToCache(Uri sourceUri) {
        try {
            File cacheDir = requireContext().getExternalCacheDir();
            if (cacheDir == null) cacheDir = requireContext().getCacheDir();
            File outFile = new File(cacheDir, "hypermtz_temp.mtz");

            ContentResolver cr = requireContext().getContentResolver();
            try (InputStream in  = cr.openInputStream(sourceUri);
                 FileOutputStream out = new FileOutputStream(outFile)) {
                if (in == null) return null;
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                out.flush();
            }

            return (outFile.exists() && outFile.length() > 0) ? outFile : null;
        } catch (Exception e) {
            Log.e(TAG_LOG, "copyUriToCache failed", e);
            return null;
        }
    }

    // ── ApplyThemeForScreenshot launcher ─────────────────────────────────────

    /**
     * Launches com.android.thememanager/.ApplyThemeForScreenshot.
     *
     * ThemeStore method (primary):
     *   action = ACTION_MAIN, api_called_from = "ThemeEditor"
     *   No theme_apply_flags (ThemeStore does not include this extra).
     *
     * Fallback (io.vi.hypertheme / snapshot path):
     *   api_called_from = "com.miui.themestore", theme_apply_flags = 1
     *
     * The hardcoded REAL_THEME_PATH is always used for the ZWS path — ThemeManager
     * cannot resolve ZWS paths from intent extras. For the snapshot path,
     * finalPath will be SNAPSHOT_PATH.
     */
    private void launchApplyThemeForScreenshot(String themePath) {
        boolean isSnapshotPath = themePath.equals(SNAPSHOT_PATH);

        // Primary: exact ThemeStore method
        if (!isSnapshotPath && tryLaunch(themePath, "ThemeEditor", false)) {
            Log.d(TAG_LOG, "Launched via ThemeEditor");
            return;
        }
        // Fallback with theme_apply_flags (compatible with snapshot path too)
        if (tryLaunch(themePath, "com.miui.themestore", true)) {
            Log.d(TAG_LOG, "Launched via com.miui.themestore");
            return;
        }
        Log.e(TAG_LOG, "All launch attempts failed for path: " + themePath);
        if (isAdded()) {
            Toast.makeText(requireContext(), R.string.apply_theme_failed,
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * @param includeApplyFlags whether to add theme_apply_flags=1 (Shizuku/snapshot path)
     */
    private boolean tryLaunch(String themePath, String apiCalledFrom,
                               boolean includeApplyFlags) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setComponent(
                    new ComponentName(THEME_MANAGER_PKG, THEME_APPLY_ACTIVITY));
            intent.putExtra("theme_file_path", themePath);
            intent.putExtra("api_called_from", apiCalledFrom);
            intent.putExtra("ver2_step", "ver2_step_apply");
            if (includeApplyFlags) {
                intent.putExtra("theme_apply_flags", 1);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            requireContext().startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.w(TAG_LOG, "tryLaunch [" + apiCalledFrom + "] failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void onDestroyView() {
        executor.shutdownNow();
        super.onDestroyView();
    }
}
