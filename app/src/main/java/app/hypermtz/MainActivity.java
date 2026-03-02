package app.hypermtz;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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

import app.hypermtz.service.KeepAliveService;
import app.hypermtz.service.ThemeInterceptService;
import app.hypermtz.ui.MainViewModel;
import app.hypermtz.ui.dialog.AboutDialogFragment;
import app.hypermtz.ui.dialog.CommandDialogFragment;
import app.hypermtz.ui.dialog.FileApplyDialogFragment;
import app.hypermtz.ui.dialog.SetupGuideDialogFragment;
import app.hypermtz.util.PreferenceUtil;
import app.hypermtz.util.ShizukuServiceManager;

/**
 * Main UI entry point.
 *
 * Additions ported from ThemeStore's MainActivity.kt:
 *
 *  1. Optimization mode check — if "optimization_mode_enabled" is true AND the
 *     accessibility service is running, the main process exits immediately to
 *     reduce RAM. When the user taps the notification action to exit optimization
 *     mode, a broadcast is received here that re-enables normal startup.
 *
 *  2. ACTION_EXIT_OPTIMIZATION broadcast receiver — receives the intent sent by
 *     the notification action button (KeepAliveService.ACTION_EXIT_OPTIMIZATION).
 *     Sets "optimization_mode_enabled"=false and shows a confirmation toast.
 *
 *  3. Deferred notification-permission request moved to onResume() to avoid
 *     showing the dialog before the activity is fully visible.
 */
public class MainActivity extends AppCompatActivity {

    private MainViewModel viewModel;
    private TextView tvServiceStatus;
    private TextView tvConnectedTime;
    private TextView tvInterceptTime;
    private TextView tvThemeTime;
    private TextView tvShizukuStatus;
    private TextView tvShizukuDetail;

    private boolean setupGuideShownThisSession  = false;
    private boolean storagePermissionRequested  = false;

    // ── Broadcast receivers ───────────────────────────────────────────────────

    /** Refreshes UI when ThemeInterceptService state changes. */
    private final BroadcastReceiver serviceStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            viewModel.refresh();
        }
    };

    /**
     * Receives ACTION_EXIT_OPTIMIZATION from KeepAliveService notification action.
     *
     * Ported from ThemeStore's MainActivity: disables optimization mode and shows
     * a toast, then restarts MainActivity (this) so the UI is visible again.
     */
    private final BroadcastReceiver exitOptimizationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!KeepAliveService.ACTION_EXIT_OPTIMIZATION.equals(intent.getAction())) return;
            PreferenceUtil.setBoolean("optimization_mode_enabled", false);
            PreferenceUtil.setBoolean("optimization_mode_just_exited", true);
            // Restart this activity so the UI appears.
            Intent relaunch = new Intent(MainActivity.this, MainActivity.class);
            relaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(relaunch);
        }
    };

    // ── SAF file picker ───────────────────────────────────────────────────────

    private final ActivityResultLauncher<String[]> pickThemeLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    this::onThemeFilePicked);

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DynamicColors.applyToActivityIfAvailable(this);

        // ── Optimization mode check (ported from ThemeStore) ──────────────────
        //
        // If the user previously tapped "Disable optimization" in the notification,
        // optimization_mode_just_exited will be true — show a toast and continue
        // normally.
        //
        // Otherwise: if optimization_mode_enabled is true AND the accessibility
        // service is already running, exit the main process to save RAM. The
        // service keeps running in :intercept — only the UI process exits.
        boolean justExited = PreferenceUtil.getBoolean("optimization_mode_just_exited", false);
        if (justExited) {
            PreferenceUtil.setBoolean("optimization_mode_just_exited", false);
            Toast.makeText(this,
                    getString(R.string.optimization_mode_disabled_toast),
                    Toast.LENGTH_SHORT).show();
        } else {
            boolean optimizationMode = PreferenceUtil.getBoolean("optimization_mode_enabled", false);
            if (optimizationMode) {
                boolean accessibilityRunning = ThemeInterceptService.isRunning(this);
                if (accessibilityRunning) {
                    // Accessibility service is active — no need to keep the UI process.
                    finish();
                    android.os.Process.killProcess(android.os.Process.myPid());
                    return;
                } else {
                    // Accessibility not running — auto-disable optimization mode so
                    // the user sees the setup guide and can re-enable the service.
                    PreferenceUtil.setBoolean("optimization_mode_enabled", false);
                }
            }
        }

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

        MaterialCardView cardShizuku = findViewById(R.id.card_shizuku_status);
        cardShizuku.setOnClickListener(v -> onShizukuCardClicked());

        Button btnInstall = findViewById(R.id.btn_install_theme);
        btnInstall.setOnClickListener(v -> openFilePicker());

        registerServiceStateReceiver();
        registerExitOptimizationReceiver();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!storagePermissionRequested) {
            storagePermissionRequested = true;
            // Android 13+: request notification permission for KeepAliveService.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this,
                        android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                            new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                            1003);
                }
            }
        }

        viewModel.refresh();
        viewModel.retryShizuku();

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
        try { unregisterReceiver(serviceStateReceiver);     } catch (Exception ignored) {}
        try { unregisterReceiver(exitOptimizationReceiver); } catch (Exception ignored) {}
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
        if (id == R.id.action_run_command) {
            showDialog(CommandDialogFragment.TAG, new CommandDialogFragment());
        } else if (id == R.id.action_about) {
            showDialog(AboutDialogFragment.TAG, new AboutDialogFragment());
        }
        return super.onOptionsItemSelected(item);
    }

    // ── ViewModel observers ───────────────────────────────────────────────────

    private void observeViewModel() {
        viewModel.serviceRunning.observe(this, running ->
                tvServiceStatus.setText(running
                        ? R.string.service_connected
                        : R.string.service_disconnected));

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
                case PERMISSION_DENIED:
                    tvShizukuStatus.setText(R.string.shizuku_permission_denied);
                    tvShizukuDetail.setText(R.string.shizuku_detail_permission_denied);
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

    // ── File picker ───────────────────────────────────────────────────────────

    private void openFilePicker() {
        pickThemeLauncher.launch(new String[]{"*/*"});
    }

    private void onThemeFilePicked(@Nullable Uri uri) {
        if (uri == null) return;
        String filename = getFilenameFromUri(uri);
        if (filename == null || !filename.toLowerCase(java.util.Locale.ROOT).endsWith(".mtz")) {
            Toast.makeText(this, R.string.error_not_mtz_file, Toast.LENGTH_SHORT).show();
            return;
        }
        showApplyDialog(uri.toString(), filename);
    }

    @Nullable
    private String getFilenameFromUri(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(
                uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Exception ignored) {}
        String path = uri.getLastPathSegment();
        if (path != null) {
            int slash = path.lastIndexOf('/');
            return slash >= 0 ? path.substring(slash + 1) : path;
        }
        return null;
    }

    private void showApplyDialog(String uriString, String fileName) {
        FragmentManager fm = getSupportFragmentManager();
        if (fm.findFragmentByTag(FileApplyDialogFragment.TAG) == null) {
            FileApplyDialogFragment.newInstance(uriString, fileName)
                    .show(fm, FileApplyDialogFragment.TAG);
        }
    }

    // ── Shizuku card ──────────────────────────────────────────────────────────

    private void onShizukuCardClicked() {
        ShizukuServiceManager.ShizukuState state = viewModel.shizukuState.getValue();
        if (state == ShizukuServiceManager.ShizukuState.UNAVAILABLE) {
            Intent launch = getPackageManager()
                    .getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (launch != null) {
                startActivity(launch);
            } else {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://shizuku.rikka.app/")));
                } catch (Exception ignored) {}
            }
        } else if (state == ShizukuServiceManager.ShizukuState.PERMISSION_DENIED) {
            Intent launch = getPackageManager()
                    .getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (launch != null) startActivity(launch);
        } else if (state == ShizukuServiceManager.ShizukuState.PERMISSION_NEEDED) {
            viewModel.retryShizuku();
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private void registerServiceStateReceiver() {
        IntentFilter filter = new IntentFilter(ThemeInterceptService.ACTION_STATE_CHANGED);
        ContextCompat.registerReceiver(this, serviceStateReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    /**
     * Registers the receiver for ACTION_EXIT_OPTIMIZATION.
     * Ported from ThemeStore MainActivity.
     */
    private void registerExitOptimizationReceiver() {
        IntentFilter filter = new IntentFilter(KeepAliveService.ACTION_EXIT_OPTIMIZATION);
        ContextCompat.registerReceiver(this, exitOptimizationReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void showDialog(String tag, androidx.fragment.app.DialogFragment fragment) {
        FragmentManager fm = getSupportFragmentManager();
        if (fm.findFragmentByTag(tag) == null) {
            fragment.show(fm, tag);
        }
    }
}
