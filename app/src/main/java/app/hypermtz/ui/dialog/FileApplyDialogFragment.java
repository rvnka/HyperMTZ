package app.hypermtz.ui.dialog;

import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.RemoteException;
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

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.hypermtz.IPrivilegedService;
import app.hypermtz.R;
import app.hypermtz.ui.MainViewModel;

public class FileApplyDialogFragment extends DialogFragment {

    public static final String TAG = "FileApplyDialog";

    private static final String ARG_FILE_PATH = "file_path";

    /**
     * Theme destination directories, tried in order.
     *
     * Primary:  /data/system/theme/compatibility-v12  (MIUI 12+ / HyperOS)
     * Fallback: ThemeManager snapshot dir             (older MIUI / HyperOS)
     */
    private static final String DEST_PRIMARY  = "/data/system/theme/compatibility-v12/";
    private static final String DEST_FALLBACK =
            "/sdcard/Android/data/com.android.thememanager/files/snapshot/";

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
        String filePath  = requireArguments().getString(ARG_FILE_PATH, "");
        File   sourceFile = new File(filePath);

        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_file_apply, null);

        TextView          tvFileName = view.findViewById(R.id.tv_file_name);
        TextInputLayout   tilName    = view.findViewById(R.id.til_theme_name);
        TextInputEditText etName     = view.findViewById(R.id.et_theme_name);
        Button            btnImport  = view.findViewById(R.id.btn_import);
        Button            btnApply   = view.findViewById(R.id.btn_apply);

        tvFileName.setText(sourceFile.getName());
        etName.setText(stripExtension(sourceFile.getName()));

        MainViewModel viewModel = new ViewModelProvider(requireActivity())
                .get(MainViewModel.class);

        btnImport.setOnClickListener(v -> {
            String name = getValidatedName(tilName, etName);
            if (name != null) copyAndInstall(viewModel, sourceFile, name, false);
        });

        btnApply.setOnClickListener(v -> {
            String name = getValidatedName(tilName, etName);
            if (name != null) copyAndInstall(viewModel, sourceFile, name, true);
        });

        return new AlertDialog.Builder(requireContext())
                .setView(view)
                .create();
    }

    @Nullable
    private String getValidatedName(TextInputLayout tilName, TextInputEditText etName) {
        String name = etName.getText() != null
                ? etName.getText().toString().trim()
                : "";
        if (name.isEmpty()) {
            tilName.setError(getString(R.string.error_name_required));
            return null;
        }
        tilName.setError(null);
        return name;
    }

    private void copyAndInstall(MainViewModel viewModel, File sourceFile,
            String themeName, boolean applyAfterCopy) {
        IPrivilegedService service = viewModel.getPrivilegedService();
        if (service == null) {
            Toast.makeText(requireContext(), R.string.shizuku_not_connected,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        dismissAllowingStateLoss();
        viewModel.setThemeCopyRunning(true);

        executor.submit(() -> {
            // BUG FIX: chooseDest() previously called new File(path).isDirectory() from
            // the app process, which always returned false for /data/system/theme/ because
            // the app lacks read permission there — so it always fell back to sdcard.
            // Now we delegate the directory check to the privileged service (IPC call),
            // which runs as ADB/root and can actually stat the system path.
            String destination = chooseDest(service, themeName);
            boolean success;
            try {
                success = service.copyFile(sourceFile.getAbsolutePath(), destination);
            } catch (RemoteException e) {
                success = false;
            }

            final boolean copySucceeded = success;
            final String  destPath      = destination;
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
                if (copySucceeded) {
                    Toast.makeText(requireContext(), R.string.copy_success,
                            Toast.LENGTH_SHORT).show();
                    if (applyAfterCopy) {
                        triggerMiuiThemeApply(destPath);
                    }
                } else {
                    Toast.makeText(requireContext(), R.string.copy_failed,
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    /**
     * Determines the destination path.
     *
     * BUG FIX: The original version called new File(DEST_PRIMARY).isDirectory() from
     * the unprivileged app process. Since the app cannot read /data/system/theme/,
     * isDirectory() always returned false, and themes were always installed to the
     * sdcard fallback path even on devices where the primary path exists.
     *
     * Now delegates to service.isDirectory() which runs with ADB/root privileges.
     */
    private static String chooseDest(IPrivilegedService service, String themeName) {
        try {
            if (service.isDirectory(DEST_PRIMARY)) {
                return DEST_PRIMARY + themeName + ".mtz";
            }
        } catch (RemoteException ignored) {
            // If the IPC fails, fall through to sdcard fallback
        }
        return DEST_FALLBACK + themeName + ".mtz";
    }

    private void triggerMiuiThemeApply(String themePath) {
        try {
            Intent intent = new Intent();
            intent.setClassName(
                    "com.android.thememanager",
                    "com.android.thememanager.ApplyThemeForScreenshot");
            intent.putExtra("theme_file_path", themePath);
            intent.putExtra("ver2_step", "ver2_step_apply");
            intent.putExtra("api_called_from", "com.miui.themestore");
            intent.putExtra("theme_apply_flags", 1);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.apply_theme_failed,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    @Override
    public void onDestroyView() {
        executor.shutdownNow();
        super.onDestroyView();
    }
}
