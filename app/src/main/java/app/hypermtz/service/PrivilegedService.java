package app.hypermtz.service;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import app.hypermtz.IPrivilegedService;

/**
 * Runs inside the Shizuku server process (:shizuku) with elevated privileges.
 *
 * Shizuku v13+ will try the Context constructor first; older versions fall back
 * to the no-arg constructor. The Context available here is a stripped-down
 * server-side instance — do NOT call registerReceiver or getContentResolver.
 *
 * The destroy() method is reserved by Shizuku (transaction 16777115). It is
 * called before a newer version of this service is bound and must not block.
 */
public class PrivilegedService extends IPrivilegedService.Stub {

    private static final String TAG = "PrivilegedService";

    /**
     * Bounded pool for command execution tasks.
     * CallerRunsPolicy provides natural back-pressure when the queue is full.
     */
    private final ExecutorService executor = new ThreadPoolExecutor(
            1, 4,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(32),
            new ThreadPoolExecutor.CallerRunsPolicy());

    /**
     * Dedicated 2-thread pool for concurrent stdout/stderr draining inside
     * captureOutput(). Kept separate from the command executor so that a
     * single executeWithOutput() call cannot exhaust the main pool's slots
     * with its own drain tasks.
     */
    private final ExecutorService drainExecutor = Executors.newFixedThreadPool(2);

    /** No-arg constructor required by older Shizuku versions. */
    public PrivilegedService() {}

    /** Context constructor used by Shizuku v13+. */
    public PrivilegedService(Context context) {}

    @Override
    public String listDirectory(String path) {
        File dir = new File(path);
        if (!dir.isDirectory()) {
            return "Not a directory: " + path;
        }
        File[] entries = dir.listFiles();
        if (entries == null || entries.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (File entry : entries) {
            sb.append(entry.getName())
              .append(" : ")
              .append(entry.isDirectory() ? "DIR" : "FILE")
              .append('\n');
        }
        return sb.toString();
    }

    @Override
    public boolean copyFile(String sourcePath, String destinationPath) {
        File source = new File(sourcePath);
        if (!source.isFile()) {
            Log.e(TAG, "copyFile: source not found or not a file: " + sourcePath);
            return false;
        }
        File destination = new File(destinationPath);
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.e(TAG, "copyFile: failed to create parent directories: " + parent);
            return false;
        }
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) > 0) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();
            // fdatasync() ensures data is physically written before the caller
            // launches ThemeManager. Without this, the kernel buffer may not
            // be flushed, causing ThemeManager to read a partial/empty file.
            try { out.getFD().sync(); } catch (Exception ignored) {}
            // Make the copied file world-readable so ThemeManager (which runs as its own
            // UID) can read it even though this service runs as shell (ADB) or root.
            //noinspection ResultOfMethodCallIgnored
            destination.setReadable(true, false);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "copyFile failed: " + sourcePath + " -> " + destinationPath, e);
            return false;
        }
    }

    @Override
    public boolean deleteFile(String path) {
        File file = new File(path);
        if (!file.exists()) {
            return false;
        }
        return file.delete();
    }

    @Override
    public String readFile(String path) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            Log.e(TAG, "readFile failed: " + path, e);
            return null;
        }
    }

    @Override
    public boolean createFile(String path) {
        File file = new File(path);
        if (file.exists()) {
            return true;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return false;
        }
        try {
            return file.createNewFile();
        } catch (IOException e) {
            Log.e(TAG, "createFile failed: " + path, e);
            return false;
        }
    }

    @Override
    public boolean writeFile(String content, String path) {
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return false;
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "writeFile failed: " + path, e);
            return false;
        }
    }

    @Override
    public boolean saveBitmapFromFile(String sourcePath, String destPath) {
        Bitmap bitmap = BitmapFactory.decodeFile(sourcePath);
        if (bitmap == null) {
            Log.e(TAG, "saveBitmapFromFile: failed to decode bitmap from: " + sourcePath);
            return false;
        }
        File dest = new File(destPath);
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.e(TAG, "saveBitmapFromFile: failed to create parent directories: " + parent);
            bitmap.recycle();
            return false;
        }
        try (FileOutputStream out = new FileOutputStream(dest)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "saveBitmapFromFile failed: " + sourcePath + " -> " + destPath, e);
            return false;
        } finally {
            bitmap.recycle();
        }
    }

    @Override
    public boolean execute(String[] command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            // Drain output to prevent the process from blocking on a full pipe.
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                //noinspection StatementWithEmptyBody
                while (reader.readLine() != null) { /* intentionally empty */ }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                Log.w(TAG, "execute: exit code " + exitCode + " for " + Arrays.toString(command));
            }
            return exitCode == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "execute interrupted", e);
            return false;
        } catch (IOException e) {
            Log.e(TAG, "execute failed", e);
            return false;
        }
    }

    @Override
    public String executeWithOutput(int maxLines, long timeoutMs,
            boolean returnError, String[] command) {
        final long futureTimeoutMs = timeoutMs + 2_000L;
        try {
            return CompletableFuture
                    .supplyAsync(() -> captureOutput(command, maxLines, timeoutMs, returnError),
                            executor)
                    .get(futureTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return returnError ? "Timed out after " + timeoutMs + " ms" : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return returnError ? "Interrupted" : null;
        } catch (Exception e) {
            Log.e(TAG, "executeWithOutput failed", e);
            return returnError ? e.getMessage() : null;
        }
    }

    private String captureOutput(String[] command, int maxLines,
            long timeoutMs, boolean returnError) {
        try {
            Process process = new ProcessBuilder(command).start();

            CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(
                    () -> drainStream(process.getInputStream(), maxLines), drainExecutor);
            CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(
                    () -> drainStream(process.getErrorStream(), maxLines), drainExecutor);

            boolean exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                return returnError ? "Process timed out" : null;
            }

            int exitCode = process.exitValue();
            String stdout = stdoutFuture.getNow("");
            String stderr = stderrFuture.getNow("");

            if (exitCode != 0) {
                return returnError ? stderr : null;
            }
            return stdout;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "captureOutput interrupted", e);
            return returnError ? "Interrupted" : null;
        } catch (IOException e) {
            Log.e(TAG, "captureOutput failed", e);
            return returnError ? e.getMessage() : null;
        }
    }

    private String drainStream(InputStream stream, int maxLines) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null && count < maxLines) {
                sb.append(line).append('\n');
                count++;
            }
            if (count >= maxLines) {
                sb.append("[output truncated at ").append(maxLines).append(" lines]");
            }
        } catch (IOException ignored) {}
        return sb.toString();
    }

    @Override
    public boolean enableAccessibilityService(String componentName) {
        try {
            String current = settingsGet("enabled_accessibility_services");
            if (!TextUtils.isEmpty(current) && !"null".equals(current)) {
                ArrayList<String> services = new ArrayList<>(Arrays.asList(current.split(":")));
                if (services.contains(componentName)) {
                    return true;
                }
                services.add(componentName);
                return settingsPut("enabled_accessibility_services",
                        TextUtils.join(":", services));
            }
            return settingsPut("enabled_accessibility_services", componentName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "enableAccessibilityService interrupted for: " + componentName, e);
            return false;
        } catch (IOException e) {
            Log.e(TAG, "enableAccessibilityService failed for: " + componentName, e);
            return false;
        }
    }

    /**
     * Returns true if path is an existing directory.
     *
     * BUG FIX: The original FileApplyDialogFragment called new File(path).isDirectory()
     * from the app process, which always returned false for system paths like
     * /data/system/theme/ because the app lacks read permission there.
     * Routing this check through the privileged service fixes chooseDest() so that
     * themes are correctly installed to the primary MIUI 12+ system path when available.
     */
    @Override
    public boolean isDirectory(String path) {
        return new File(path).isDirectory();
    }

    private String settingsGet(String key) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("settings", "get", "secure", key).start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String value = reader.readLine();
            process.waitFor();
            return value != null ? value.trim() : "";
        }
    }

    private boolean settingsPut(String key, String value) throws IOException, InterruptedException {
        return new ProcessBuilder("settings", "put", "secure", key, value)
                .start()
                .waitFor() == 0;
    }

    @Override
    public void destroy() {
        drainExecutor.shutdownNow();
        executor.shutdownNow();
        System.exit(0);
    }
}
