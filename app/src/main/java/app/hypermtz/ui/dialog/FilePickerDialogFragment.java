package app.hypermtz.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.hypermtz.R;

public class FilePickerDialogFragment extends DialogFragment {

    public static final String TAG = "FilePickerDialog";

    private final List<File> entries = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private TextView tvCurrentPath;
    private ProgressBar progressBar;

    /**
     * Single-thread executor for directory listing.
     * listFiles() + sort on large directories (e.g. /sdcard/Download) can take
     * 50–200 ms and must not block the main thread.
     */
    private final ExecutorService listExecutor = Executors.newSingleThreadExecutor();

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_file_picker, null);

        tvCurrentPath = view.findViewById(R.id.tv_current_path);
        ListView listView  = view.findViewById(R.id.list_files);
        Button   btnCancel = view.findViewById(R.id.btn_cancel);
        progressBar        = view.findViewById(R.id.progress_loading);

        adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_list_item_1, new ArrayList<>());
        listView.setAdapter(adapter);

        navigateTo(Environment.getExternalStorageDirectory());

        listView.setOnItemClickListener((parent, v, position, id) -> {
            File selected = entries.get(position);
            if (selected.isDirectory()) {
                navigateTo(selected);
            } else {
                openApplyDialog(selected);
            }
        });

        btnCancel.setOnClickListener(v -> dismissAllowingStateLoss());

        return new AlertDialog.Builder(requireContext())
                .setView(view)
                .create();
    }

    /**
     * Switches the picker to {@code directory}. The listing and sort run on
     * {@link #listExecutor} so that large folders don't block the main thread.
     * A ProgressBar is shown while the background work is in flight.
     */
    private void navigateTo(File directory) {
        tvCurrentPath.setText(directory.getAbsolutePath());
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);

        listExecutor.submit(() -> {
            final File parent = directory.getParentFile();
            final List<File> newEntries = new ArrayList<>();

            if (parent != null) {
                newEntries.add(parent); // ".." back-navigation entry
            }

            File[] children = directory.listFiles();
            if (children != null) {
                Arrays.sort(children, (a, b) -> {
                    if (a.isDirectory() != b.isDirectory()) {
                        return a.isDirectory() ? -1 : 1; // directories first
                    }
                    return a.getName().compareToIgnoreCase(b.getName());
                });
                for (File child : children) {
                    if (!child.getName().startsWith(".")) {
                        if (child.isDirectory()
                                || child.getName().toLowerCase(Locale.ROOT).endsWith(".mtz")) {
                            newEntries.add(child);
                        }
                    }
                }
            }

            // Build labels off the main thread so the UI post is trivial.
            final List<String> labels = new ArrayList<>(newEntries.size());
            for (int i = 0; i < newEntries.size(); i++) {
                File entry = newEntries.get(i);
                if (i == 0 && parent != null && entry.equals(parent)) {
                    labels.add(getString(R.string.file_picker_parent_dir));
                } else {
                    labels.add((entry.isDirectory() ? "📁  " : "📄  ") + entry.getName());
                }
            }

            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                entries.clear();
                entries.addAll(newEntries);
                adapter.clear();
                adapter.addAll(labels);
                adapter.notifyDataSetChanged();
                if (progressBar != null) progressBar.setVisibility(View.GONE);
            });
        });
    }

    private void openApplyDialog(File file) {
        FileApplyDialogFragment applyDialog =
                FileApplyDialogFragment.newInstance(file.getAbsolutePath());
        applyDialog.show(requireActivity().getSupportFragmentManager(),
                FileApplyDialogFragment.TAG);
        dismissAllowingStateLoss();
    }

    @Override
    public void onDestroyView() {
        listExecutor.shutdownNow();
        super.onDestroyView();
    }
}
