package app.hypermtz;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.hypermtz.service.ThemeInterceptService;
import app.hypermtz.ui.MainViewModel;
import app.hypermtz.ui.dialog.AboutDialogFragment;
import app.hypermtz.ui.dialog.CommandDialogFragment;
import app.hypermtz.ui.dialog.FileApplyDialogFragment;
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

    private boolean setupGuideShownThisSession = false;
    private boolean storagePermissionRequested = false;

    /**
     * Single-thread executor for copying SAF content:// URI to the app's cache
     * directory before passing the local path to PrivilegedService.copyFile().
     * PrivilegedService (running as ADB shell/root) can read from the app's
     * external cache dir (/sdcard/Android/data/app.hypermtz/cache/) but cannot
     * dereference content:// URIs, which require ContentResolver in the app process.
     */
    private final ExecutorService copyExecutor = Executors.newSingleThreadExecutor();

    private final BroadcastReceiver serviceStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            viewModel.refresh();
        }
    };

    /**
     * SAF file picker — replaces the old custom FilePickerDialogFragment.
     *
     * Using ACTION_OPEN_DOCUMENT (not GET_CONTENT) so the app gets a
     * persistable URI with long-term read access, and the system file picker
     * is used directly without requiring MANAGE_EXTERNAL_STORAGE on every device.
     *
     * MIME type "*\/*" shows all files; most MIUI file managers display .mtz files.
     * We validate the extension in the result callback.
     */
    private final ActivityResultLauncher<String[]> pickThemeLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    this::onThemeFilePicked);

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

        MaterialCardView cardShizuku = findViewById(R.id.card_shizuku_status);
        cardShizuku.setOnClickListener(v -> onShizukuCardClicked());

        Button btnInstall = findViewById(R.id.btn_install_theme);
        btnInstall.setOnClickListener(v -> openFilePicker());

        registerServiceStateReceiver();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!storagePermissionRequested) {
            storagePermissionRequested = true;
            // MANAGE_EXTERNAL_STORAGE is needed by computeThemeStatus() to read
            // /sdcard/Android/data/com.android.thememanager/files/snapshot/ —
            // without it, File.listFiles() returns null on Android 11+ (scoped
            // storage) and the status card always shows "No theme installed yet"
            // even after a successful install.
            // On Android < 11 the legacy READ/WRITE_EXTERNAL_STORAGE permissions
            // (declared in the manifest with maxSdkVersion=32) are sufficient.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && !Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    // Fallback: open the generic all-files-access settings page.
                    try {
                        startActivity(new Intent(
                                Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    } catch (Exception ignored) {}
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
        try { unregisterReceiver(serviceStateReceiver); } catch (Exception ignored) {}
        copyExecutor.shutdownNow();
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

    // ── ViewModel observers ────────────────────────────────────────────────────

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

    // ── File picker ────────────────────────────────────────────────────────────

    private void openFilePicker() {
        if (!viewModel.isShizukuAvailable()) {
            Toast.makeText(this, R.string.shizuku_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        // Open system file picker — shows all files (*/*).
        // The result is handled in onThemeFilePicked().
        pickThemeLauncher.launch(new String[]{"*/*"});
    }

    /**
     * Called when the user selects a file from the system file picker.
     *
     * The selected {@code uri} is a {@code content://} URI. PrivilegedService runs
     * in a different process as ADB shell and cannot call ContentResolver, so we
     * must copy the bytes to a local cache file first, then hand the absolute path
     * to FileApplyDialogFragment which passes it to PrivilegedService.copyFile().
     *
     * We use {@code getExternalCacheDir()} (on sdcard) rather than {@code getCacheDir()}
     * (on /data/data/) because it is accessible by ADB shell on all MIUI versions
     * without relying on SELinux policy for /data/data/<package>/.
     */
    private void onThemeFilePicked(@Nullable Uri uri) {
        if (uri == null) return;

        String filename = getFilenameFromUri(uri);
        if (filename == null || !filename.toLowerCase(java.util.Locale.ROOT).endsWith(".mtz")) {
            Toast.makeText(this, R.string.error_not_mtz_file, Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, R.string.preparing_file, Toast.LENGTH_SHORT).show();

        copyExecutor.execute(() -> {
            File outFile = null;
            try {
                File cacheDir = getExternalCacheDir();
                if (cacheDir == null) cacheDir = getCacheDir();
                outFile = new File(cacheDir, filename);

                ContentResolver cr = getContentResolver();
                try (InputStream in = cr.openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(outFile)) {
                    if (in == null) throw new IOException("Cannot open URI: " + uri);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    out.flush();
                }

                final String path = outFile.getAbsolutePath();
                runOnUiThread(() -> {
                    if (!isFinishing()) showApplyDialog(path);
                });
            } catch (Exception e) {
                final File failedFile = outFile;
                runOnUiThread(() -> {
                    Toast.makeText(this, R.string.error_copy_cache, Toast.LENGTH_SHORT).show();
                });
                if (failedFile != null) failedFile.delete();
            }
        });
    }

    /**
     * Extracts the display filename from a content:// URI using the ContentResolver.
     * Falls back to the last path segment if the cursor query fails.
     */
    @Nullable
    private String getFilenameFromUri(Uri uri) {
        // Try ContentResolver first (works for most storage providers)
        try (android.database.Cursor cursor = getContentResolver().query(
                uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Exception ignored) {}

        // Fallback: last path segment of the URI
        String path = uri.getLastPathSegment();
        if (path != null) {
            int slash = path.lastIndexOf('/');
            return slash >= 0 ? path.substring(slash + 1) : path;
        }
        return null;
    }

    private void showApplyDialog(String filePath) {
        FragmentManager fm = getSupportFragmentManager();
        if (fm.findFragmentByTag(FileApplyDialogFragment.TAG) == null) {
            FileApplyDialogFragment.newInstance(filePath)
                    .show(fm, FileApplyDialogFragment.TAG);
        }
    }

    // ── Shizuku card ───────────────────────────────────────────────────────────

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

    // ── Utilities ──────────────────────────────────────────────────────────────

    private void registerServiceStateReceiver() {
        IntentFilter filter = new IntentFilter(ThemeInterceptService.ACTION_STATE_CHANGED);
        ContextCompat.registerReceiver(this, serviceStateReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void showDialog(String tag, androidx.fragment.app.DialogFragment fragment) {
        FragmentManager fm = getSupportFragmentManager();
        if (fm.findFragmentByTag(tag) == null) {
            fragment.show(fm, tag);
        }
    }
}
