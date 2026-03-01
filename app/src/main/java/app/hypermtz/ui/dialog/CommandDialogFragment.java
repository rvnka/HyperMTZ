package app.hypermtz.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;
import android.os.RemoteException;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.hypermtz.IPrivilegedService;
import app.hypermtz.R;
import app.hypermtz.ui.MainViewModel;

public class CommandDialogFragment extends DialogFragment {

    public static final String TAG = "CommandDialog";

    private static final int  MAX_LINES  = 500;
    private static final long TIMEOUT_MS = 10_000L;

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault());

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_command, null);

        EditText etInput  = view.findViewById(R.id.et_command_input);
        Button   btnRun   = view.findViewById(R.id.btn_command_run);
        TextView tvStatus = view.findViewById(R.id.tv_command_status);
        TextView tvTime   = view.findViewById(R.id.tv_command_timestamp);
        TextView tvOutput = view.findViewById(R.id.tv_command_output);

        // Obtain the shared ViewModel so state survives dialog recreation.
        MainViewModel viewModel = new ViewModelProvider(requireActivity())
                .get(MainViewModel.class);

        Runnable runCommand = () -> {
            String raw = etInput.getText().toString().trim();
            if (TextUtils.isEmpty(raw)) return;

            IPrivilegedService svc = viewModel.getPrivilegedService();
            if (svc == null) {
                tvStatus.setText(R.string.shizuku_not_connected);
                return;
            }

            String[] args = raw.split("\\s+");
            tvStatus.setText(R.string.command_running);
            tvOutput.setText("");
            tvTime.setText("");

            executor.submit(() -> {
                String result;
                try {
                    result = svc.executeWithOutput(MAX_LINES, TIMEOUT_MS, true, args);
                } catch (RemoteException e) {
                    result = getString(R.string.command_error, e.getMessage());
                }
                String ts     = TIME_FMT.format(LocalDateTime.now());
                String output = result != null ? result : getString(R.string.command_no_output);
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    tvStatus.setText(R.string.command_done);
                    tvTime.setText(ts);
                    tvOutput.setText(output);
                });
            });
        };

        btnRun.setOnClickListener(v -> runCommand.run());
        etInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                runCommand.run();
                return true;
            }
            return false;
        });

        return new AlertDialog.Builder(requireContext())
                .setView(view)
                .create();
    }

    @Override
    public void onDestroyView() {
        executor.shutdownNow();
        super.onDestroyView();
    }
}
