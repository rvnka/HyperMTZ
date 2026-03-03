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
 * Optimization mode: if "optimization_mode_enabled"=true AND the accessibility
 * service is running, the main process exits to save RAM (service keeps running
 * in :intercept). Re-enabled by tapping the notification action button, which
 * relaunches MainActivity with EXTRA_EXIT_OPTIMIZATION=true.
 */
public class MainActivity extends AppCompatActivity {

    private MainViewModel viewModel;
    private TextView tvServiceStatus;
    private TextView tvConnectedTime;
    private TextView tvInterceptTime;
    private TextView tvThemeTime;
    private TextView tvShizukuStatus;
    private TextView tvShizukuDetail;

    private boolean setupGuideShownThisSession = false;
    private boolean permissionRequested        = false;

    // Refreshes UI when ThemeInterceptService state changes.
    private final BroadcastReceiver serviceStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (viewModel != null) viewModel.refresh();
        }
    };

    // SAF file picker — picks any file; .mtz validated in callback.
    private final ActivityResultLauncher<String[]> pickThemeLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    this::onThemeFilePicked);

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DynamicColors.applyToActivityIfAvailable(this);

        // ── Handle EXTRA_EXIT_OPTIMIZATION ────────────────────────────────────
        // The "Disable Optimization" notification button launches MainActivity with
        // this extra. Check it first — before the optimization-mode kill — so we
        // never accidentally re-kill ourselves after the user tapped the button.
        if (getIntent() != null && getIntent().getBooleanExtra(
                KeepAliveService.EXTRA_EXIT_OPTIMIZATION, false)) {
            PreferenceUtil.setBoolean("optimization_mode_enabled", false);
            Toast.makeText(this, R.string.optimization_mode_disabled_toast,
                    Toast.LENGTH_SHORT).show();
            // Clear the extra so a config-change recreate doesn't re-show the toast.
            getIntent().removeExtra(KeepAliveService.EXTRA_EXIT_OPTIMIZATION);
        }

        // ── Optimization mode kill check ──────────────────────────────────────
        // When enabled, exit the main UI process to conserve RAM. The accessibility
        // service keeps running in the :intercept process.
        if (PreferenceUtil.getBoolean("optimization_mode_enabled", false)) {
            if (ThemeInterceptService.isRunning(this)) {
                finish();
                android.os.Process.killProcess(android.os.Process.myPid());
                return; // unreachable, but keeps the compiler happy
            } else {
                // Accessibility not running — auto-disable so user can re-set it up.
                PreferenceUtil.setBoolean("optimization_mode_enabled", false);
            }
        }

        // ── Normal startup ────────────────────────────────────────────────────

        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        tvServiceStatus = findViewById(R.id.tv_service_status);
        tvConnectedTime = findViewById(R.id.tv_service_connected_time);
        tvInterceptTime = findViewById(R.id.tv_last_intercept_time);
        tvThemeTime     = findViewById(R.id.tv_theme_install_time);
        tvShizukuStatus = findViewById(R.id.tv_shizuku_status);
        tvShizukuDetail = findViewById(R.id.tv_shizuku_detail);

        viewModel = new ViewModelProvider(this).get(MainViewModel.class);
        observeViewModel();

        // FIX: service card click no longer calls ThemeInterceptService.isRunning()
        // on the main thread (synchronous binder IPC → ANR risk). Use the cached
        // serviceRunning LiveData value instead.
        MaterialCardView cardService = findViewById(R.id.card_service_status);
        cardService.setOnClickListener(v -> {
            if (!Boolean.TRUE.equals(viewModel.serviceRunning.getValue())) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });

        MaterialCardView cardShizuku = findViewById(R.id.card_shizuku_status);
        cardShizuku.setOnClickListener(v -> onShizukuCardClicked());

        Button btnInstall = findViewById(R.id.btn_install_theme);
        btnInstall.setOnClickListener(v -> openFilePicker());

        registerServiceStateReceiver();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Handle EXTRA_EXIT_OPTIMIZATION when activity is already running (singleTop).
        if (intent != null && intent.getBooleanExtra(
                KeepAliveService.EXTRA_EXIT_OPTIMIZATION, false)) {
            PreferenceUtil.setBoolean("optimization_mode_enabled", false);
            Toast.makeText(this, R.string.optimization_mode_disabled_toast,
                    Toast.LENGTH_SHORT).show();
            intent.removeExtra(KeepAliveService.EXTRA_EXIT_OPTIMIZATION);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Request notification permission once (Android 13+).
        if (!permissionRequested) {
            permissionRequested = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && ContextCompat.checkSelfPermission(this,
                            android.Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1003);
            }
        }

        viewModel.refresh();
        viewModel.retryShizuku();

        // NOTE: Setup guide is now shown from the serviceRunning observer in
        // observeViewModel(). This avoids calling ThemeInterceptService.isRunning()
        // directly on the main thread (binder IPC → ANR risk).
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(serviceStateReceiver); } catch (Exception ignored) {}
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
            showDialogIfNotShown(CommandDialogFragment.TAG, new CommandDialogFragment());
        } else if (id == R.id.action_about) {
            showDialogIfNotShown(AboutDialogFragment.TAG, new AboutDialogFragment());
        }
        return super.onOptionsItemSelected(item);
    }

    // ── ViewModel observers ───────────────────────────────────────────────────

    private void observeViewModel() {
        viewModel.serviceRunning.observe(this, running -> {
            tvServiceStatus.setText(running
                    ? R.string.service_connected
                    : R.string.service_disconnected);

            // FIX: setup guide driven by the serviceRunning LiveData (which is set
            // from a background thread in MainViewModel) instead of calling
            // ThemeInterceptService.isRunning() on the main thread. The first false
            // emission after launch triggers the guide exactly once per session.
            if (!running && !setupGuideShownThisSession) {
                setupGuideShownThisSession = true;
                FragmentManager fm = getSupportFragmentManager();
                if (fm.findFragmentByTag(SetupGuideDialogFragment.TAG) == null) {
                    new SetupGuideDialogFragment().show(fm, SetupGuideDialogFragment.TAG);
                }
            }
        });

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
            if (Boolean.TRUE.equals(copying)) tvThemeTime.setText(R.string.copying_theme_files);
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
        showDialogIfNotShown(FileApplyDialogFragment.TAG,
                FileApplyDialogFragment.newInstance(uri.toString(), filename));
    }

    @Nullable
    private String getFilenameFromUri(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Exception ignored) {}
        String seg = uri.getLastPathSegment();
        if (seg != null) {
            int slash = seg.lastIndexOf('/');
            return slash >= 0 ? seg.substring(slash + 1) : seg;
        }
        return null;
    }

    // ── Shizuku card ──────────────────────────────────────────────────────────

    private void onShizukuCardClicked() {
        ShizukuServiceManager.ShizukuState state = viewModel.shizukuState.getValue();
        if (state == null) return;

        switch (state) {
            case UNAVAILABLE:
                Intent launchShizuku = getPackageManager()
                        .getLaunchIntentForPackage("moe.shizuku.privileged.api");
                if (launchShizuku != null) {
                    startActivity(launchShizuku);
                } else {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://shizuku.rikka.app/")));
                    } catch (Exception ignored) {}
                }
                break;
            case PERMISSION_DENIED:
                Intent openShizuku = getPackageManager()
                        .getLaunchIntentForPackage("moe.shizuku.privileged.api");
                if (openShizuku != null) startActivity(openShizuku);
                break;
            case PERMISSION_NEEDED:
                viewModel.retryShizuku();
                break;
            default:
                break;
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private void registerServiceStateReceiver() {
        IntentFilter filter = new IntentFilter(ThemeInterceptService.ACTION_STATE_CHANGED);
        ContextCompat.registerReceiver(this, serviceStateReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void showDialogIfNotShown(String tag, androidx.fragment.app.DialogFragment fragment) {
        FragmentManager fm = getSupportFragmentManager();
        if (fm.findFragmentByTag(tag) == null) {
            fragment.show(fm, tag);
        }
    }
}
