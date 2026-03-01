package app.hypermtz;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;

import app.hypermtz.service.ThemeInterceptService;
import app.hypermtz.ui.MainViewModel;
import app.hypermtz.ui.dialog.AboutDialogFragment;
import app.hypermtz.ui.dialog.CommandDialogFragment;
import app.hypermtz.ui.dialog.FilePickerDialogFragment;
import app.hypermtz.ui.dialog.SetupGuideDialogFragment;
import app.hypermtz.util.ShizukuServiceManager;

public class MainActivity extends AppCompatActivity {

    private MainViewModel viewModel;
    private TextView tvServiceStatus;
    private TextView tvConnectedTime;
    private TextView tvInterceptTime;
    private TextView tvThemeTime;
    private TextView tvShizukuStatus;
    private TextView tvShizukuDetail;

    /**
     * FIX: Track whether the setup guide has been shown this session.
     * Original bug: the dialog was shown on EVERY onResume() call while the
     * accessibility service was not running — so it would re-appear every time
     * the user returned from Accessibility Settings without enabling the service.
     */
    private boolean setupGuideShownThisSession = false;

    private final BroadcastReceiver serviceStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            viewModel.refresh();
        }
    };

    private final ActivityResultLauncher<Intent> allFilesPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                                && !Environment.isExternalStorageManager()) {
                            showPermissionDeniedToast();
                        }
                    });

    private final ActivityResultLauncher<String[]> storagePermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    results -> {
                        Boolean granted = results.getOrDefault(
                                Manifest.permission.WRITE_EXTERNAL_STORAGE, false);
                        if (Boolean.FALSE.equals(granted)) {
                            showPermissionDeniedToast();
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DynamicColors.applyToActivityIfAvailable(this);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        tvServiceStatus  = findViewById(R.id.tv_service_status);
        tvConnectedTime  = findViewById(R.id.tv_service_connected_time);
        tvInterceptTime  = findViewById(R.id.tv_last_intercept_time);
        tvThemeTime      = findViewById(R.id.tv_theme_install_time);
        tvShizukuStatus  = findViewById(R.id.tv_shizuku_status);
        tvShizukuDetail  = findViewById(R.id.tv_shizuku_detail);

        viewModel = new ViewModelProvider(this).get(MainViewModel.class);
        observeViewModel();

        MaterialCardView cardService = findViewById(R.id.card_service_status);
        cardService.setOnClickListener(v -> {
            if (!ThemeInterceptService.isRunning(this)) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });

        // Shizuku card: tap to open Shizuku app if unavailable, or retry connection
        MaterialCardView cardShizuku = findViewById(R.id.card_shizuku_status);
        cardShizuku.setOnClickListener(v -> onShizukuCardClicked());

        Button btnInstall = findViewById(R.id.btn_install_theme);
        btnInstall.setOnClickListener(v -> openFilePicker());

        registerServiceStateReceiver();
        requestStoragePermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.refresh();
        viewModel.retryShizuku();

        // FIX: only show SetupGuideDialog once per session, not every onResume.
        // Original: the dialog re-appeared every time the user came back from
        // Accessibility Settings without enabling the service — very annoying.
        if (!setupGuideShownThisSession && !ThemeInterceptService.isRunning(this)) {
            setupGuideShownThisSession = true;
            FragmentManager fm = getSupportFragmentManager();
            if (fm.findFragmentByTag(SetupGuideDialogFragment.TAG) == null) {
                new SetupGuideDialogFragment().show(fm, SetupGuideDialogFragment.TAG);
            }
        }
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(serviceStateReceiver);
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_restart_system_ui) {
            viewModel.restartSystemUi();
        } else if (id == R.id.action_run_command) {
            showDialog(CommandDialogFragment.TAG, new CommandDialogFragment());
        } else if (id == R.id.action_about) {
            showDialog(AboutDialogFragment.TAG, new AboutDialogFragment());
        }
        return super.onOptionsItemSelected(item);
    }

    private void observeViewModel() {
        viewModel.serviceRunning.observe(this, running ->
                tvServiceStatus.setText(running
                        ? R.string.service_connected
                        : R.string.service_disconnected));

        // Legacy boolean observer (kept for backward compat with other observers)
        viewModel.shizukuConnected.observe(this, connected -> {
            // Detail text is driven by shizukuState — nothing extra needed here
        });

        // FIX: use granular ShizukuState for the Shizuku card label + detail
        viewModel.shizukuState.observe(this, state -> {
            if (state == null) return;
            switch (state) {
                case CONNECTED:
                    tvShizukuStatus.setText(R.string.shizuku_connected);
                    tvShizukuDetail.setText(R.string.shizuku_detail_connected);
                    break;
                case CONNECTING:
                    tvShizukuStatus.setText(R.string.shizuku_connecting);
                    tvShizukuDetail.setText(R.string.shizuku_detail_connecting);
                    break;
                case PERMISSION_NEEDED:
                    tvShizukuStatus.setText(R.string.shizuku_permission_needed);
                    tvShizukuDetail.setText(R.string.shizuku_detail_permission_needed);
                    break;
                case UNAVAILABLE:
                default:
                    tvShizukuStatus.setText(R.string.shizuku_disconnected);
                    tvShizukuDetail.setText(R.string.shizuku_detail_unavailable);
                    break;
            }
        });

        viewModel.connectedTime.observe(this, tvConnectedTime::setText);

        viewModel.interceptTime.observe(this, time ->
                tvInterceptTime.setText(time == null || time.isEmpty()
                        ? getString(R.string.intercept_none_yet)
                        : getString(R.string.intercept_last, time)));

        viewModel.themeStatus.observe(this, status -> {
            if (status != null) tvThemeTime.setText(status);
        });

        viewModel.themeCopyRunning.observe(this, copying -> {
            if (Boolean.TRUE.equals(copying)) {
                tvThemeTime.setText(R.string.copying_theme_files);
            }
        });

        viewModel.toastEvent.observe(this, event -> {
            @Nullable String msg = event.getIfNotConsumed();
            if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        });
    }

    private void onShizukuCardClicked() {
        ShizukuServiceManager.ShizukuState state = viewModel.shizukuState.getValue();
        if (state == ShizukuServiceManager.ShizukuState.UNAVAILABLE) {
            // Try to open Shizuku app so user can start it
            try {
                startActivity(getPackageManager()
                        .getLaunchIntentForPackage("moe.shizuku.privileged.api"));
            } catch (Exception e) {
                // Shizuku not installed — open Play Store / download page
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://shizuku.rikka.app/")));
                } catch (Exception ignored) {}
            }
        } else if (state == ShizukuServiceManager.ShizukuState.PERMISSION_NEEDED) {
            // Retry to trigger the permission dialog
            viewModel.retryShizuku();
        }
        // CONNECTED / CONNECTING → no-op
    }

    private void openFilePicker() {
        if (!viewModel.isShizukuAvailable()) {
            Toast.makeText(this, R.string.shizuku_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        showDialog(FilePickerDialogFragment.TAG, new FilePickerDialogFragment());
    }

    private void registerServiceStateReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ThemeInterceptService.ACTION_STATE_CHANGED);
        ContextCompat.registerReceiver(this, serviceStateReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                allFilesPermissionLauncher.launch(intent);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                storagePermissionLauncher.launch(new String[]{
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                });
            }
        }
    }

    private void showPermissionDeniedToast() {
        Toast.makeText(this, R.string.permission_storage_denied, Toast.LENGTH_SHORT).show();
    }

    private void showDialog(String tag, androidx.fragment.app.DialogFragment fragment) {
        FragmentManager fm = getSupportFragmentManager();
        if (fm.findFragmentByTag(tag) == null) {
            fragment.show(fm, tag);
        }
    }
}
