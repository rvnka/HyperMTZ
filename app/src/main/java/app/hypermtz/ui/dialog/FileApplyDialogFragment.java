package app.hypermtz.ui.dialog;

import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.hypermtz.IPrivilegedService;
import app.hypermtz.R;
import app.hypermtz.ui.MainViewModel;

/**
 * Dialog that copies a user-selected .mtz file to the MIUI ThemeManager
 * snapshot location and then triggers installation via ApplyThemeForScreenshot.
 *
 * Installation method (mirrors theme_keeper.sh + io.vi.hypertheme MainActivity.t()):
 *
 *   1. Shizuku (PrivilegedService.copyFile) copies the .mtz to:
 *        /sdcard/Android/data/com.android.thememanager/files/snapshot/snapshot.mtz
 *      This path is writable only from ADB shell level — hence the privileged service.
 *
 *   2. startActivity() launches:
 *        com.android.thememanager/.ApplyThemeForScreenshot
 *      with the standard MIUI theme-apply extras. ThemeManager reads the snapshot
 *      and presents its own installation UI to the user.
 *
 *   The ThemeInterceptService AccessibilityService auto-clicks any approval button
 *   that ThemeManager shows during the process.
 */
public class FileApplyDialogFragment extends DialogFragment {

    public static final String TAG = "FileApplyDialog";

    private static final String ARG_FILE_PATH = "file_path";
    private static final String TAG_LOG = "FileApplyDialog";

    /**
     * MIUI ThemeManager snapshot path — the fixed location where ThemeManager
     * expects the .mtz file before applying via ApplyThemeForScreenshot.
     * Equivalent to $SNAPSHOT_FILE in theme_keeper.sh.
     */
    private static final String SNAPSHOT_PATH =
            "/sdcard/Android/data/com.android.thememanager/files/snapshot/snapshot.mtz";

    /**
     * MIUI ThemeManager activity that applies a theme from the snapshot path.
     * Equivalent to $THEME_ACTIVITY in theme_keeper.sh / MainActivity.t() in
     * the reference decompiled app.
     */
    private static final String THEME_MANAGER_PKG = "com.android.thememanager";
    private static final String THEME_APPLY_ACTIVITY =
            "com.android.thememanager.ApplyThemeForScreenshot";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static FileApplyDialogFragment newInstance(String filePath) {
        Bundle args = new Bundle();
        args.putString(ARG_FILE_PATH, filePath);
        FileApplyDialogFragment fragment = new FileApplyDialogFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        String filePath   = requireArguments().getString(ARG_FILE_PATH, "");
        File   sourceFile = new File(filePath);

        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_file_apply, null);

        TextView tvFileName = view.findViewById(R.id.tv_file_name);
        Button   btnImport  = view.findViewById(R.id.btn_import);
        Button   btnApply   = view.findViewById(R.id.btn_apply);

        // Hide the (now unused) theme-name input — destination is always snapshot.mtz.
        View tilName = view.findViewById(R.id.til_theme_name);
        if (tilName != null) tilName.setVisibility(View.GONE);

        tvFileName.setText(sourceFile.getName());

        MainViewModel viewModel = new ViewModelProvider(requireActivity())
                .get(MainViewModel.class);

        // Import only: copy to snapshot path, do not launch ThemeManager.
        btnImport.setOnClickListener(v -> copyToSnapshot(viewModel, sourceFile, false));

        // Import & Apply: copy + launch ApplyThemeForScreenshot.
        btnApply.setOnClickListener(v -> copyToSnapshot(viewModel, sourceFile, true));

        return new AlertDialog.Builder(requireContext())
                .setView(view)
                .create();
    }

    // ── Core logic ─────────────────────────────────────────────────────────────

    /**
     * Uses Shizuku (PrivilegedService) to copy the source .mtz file to the
     * ThemeManager snapshot path, then optionally launches ApplyThemeForScreenshot.
     *
     * @param applyAfterCopy  if true, launch ThemeManager to apply the theme
     *                        immediately after a successful copy.
     */
    private void copyToSnapshot(MainViewModel viewModel, File sourceFile,
            boolean applyAfterCopy) {
        IPrivilegedService service = viewModel.getPrivilegedService();
        if (service == null) {
            Toast.makeText(requireContext(), R.string.shizuku_not_connected,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        dismissAllowingStateLoss();
        viewModel.setThemeCopyRunning(true);

        executor.submit(() -> {
            boolean success;
            try {
                success = service.copyFile(sourceFile.getAbsolutePath(), SNAPSHOT_PATH);
            } catch (RemoteException e) {
                Log.e(TAG_LOG, "copyFile IPC failed", e);
                success = false;
            }

            // Clean up the temporary cache copy created by MainActivity to bridge
            // the content:// URI and the Shizuku process.
            String srcPath = sourceFile.getAbsolutePath();
            if (srcPath.contains("/cache/")) {
                //noinspection ResultOfMethodCallIgnored
                sourceFile.delete();
            }

            final boolean copyOk = success;
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
                    if (applyAfterCopy) {
                        launchApplyThemeForScreenshot();
                    }
                    viewModel.refresh();
                } else {
                    Toast.makeText(requireContext(), R.string.copy_failed,
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    /**
     * Launches {@code com.android.thememanager/.ApplyThemeForScreenshot} with the
     * standard MIUI theme-apply extras.
     *
     * This is the exact method used by the reference app (io.vi.hypertheme,
     * MainActivity.t()) and reproduced in theme_keeper.sh (restore_theme / install_theme).
     *
     * Extras breakdown:
     *   theme_file_path    — absolute path to the .mtz snapshot file
     *   ver2_step          — tells ThemeManager this is a ver2 apply step
     *   api_called_from    — spoofs the caller as MiUI ThemeStore (bypasses auth checks)
     *   theme_apply_flags  — 1 = apply all theme components
     *
     * FLAG_ACTIVITY_NEW_TASK is required because we may be starting from a
     * DialogFragment that is no longer the foreground task.
     */
    private void launchApplyThemeForScreenshot() {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(THEME_MANAGER_PKG, THEME_APPLY_ACTIVITY));
            intent.putExtra("theme_file_path", SNAPSHOT_PATH);
            intent.putExtra("ver2_step", "ver2_step_apply");
            intent.putExtra("api_called_from", "com.miui.themestore");
            intent.putExtra("theme_apply_flags", 1);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            requireContext().startActivity(intent);
            Log.d(TAG_LOG, "ApplyThemeForScreenshot launched");
        } catch (Exception e) {
            Log.e(TAG_LOG, "Failed to launch ApplyThemeForScreenshot", e);
            if (isAdded()) {
                Toast.makeText(requireContext(), R.string.apply_theme_failed,
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onDestroyView() {
        executor.shutdownNow();
        super.onDestroyView();
    }
}
