package app.hypermtz.util;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Application-wide log manager.
 *
 * Ported from ThemeStore's LogManager.kt.
 *
 * Writes structured log entries to a private file ({@code files/app_logs.txt}).
 * Tracks statistics (intercept count, install count) via {@link PreferenceUtil}.
 * All disk I/O runs on a dedicated single-thread executor — never on the main
 * thread. The executor is a process-global singleton; callers do not need to
 * manage its lifecycle.
 *
 * Cross-process usage: KeepAliveService reads statistics via
 * {@link #getStatistics(Context)} to populate the foreground notification.
 * Because it runs in the ":intercept" process, it opens the same filesDir
 * (same UID → same path) independently.
 */
public final class LogManager {

    private static final String TAG           = "LogManager";
    private static final String LOG_FILE_NAME = "app_logs.txt";
    private static final long   MAX_LOG_BYTES = 1024 * 1024L; // 1 MB
    private static final int    MAX_LOG_LINES = 1000;

    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    // Shared executor — keeps disk I/O off the main thread.
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private LogManager() {}

    // ── Log types ─────────────────────────────────────────────────────────────

    public enum LogType {
        INFO,
        THEME_INSTALL,
        ALARM_INTERCEPT,
        ERROR,
        WARNING,
        DEBUG
    }

    // ── LogEntry ──────────────────────────────────────────────────────────────

    public static final class LogEntry {
        public final long    timestamp;
        public final LogType type;
        public final String  message;
        /** Optional extra detail line (may be null). */
        public final String  details;

        public LogEntry(long timestamp, LogType type, String message, String details) {
            this.timestamp = timestamp;
            this.type      = type;
            this.message   = message;
            this.details   = details;
        }

        /** Serialized form written to disk. */
        public String toFormattedString() {
            SimpleDateFormat fmt = new SimpleDateFormat(DATE_PATTERN, Locale.getDefault());
            String time    = fmt.format(new Date(timestamp));
            String typeTag = "[" + type.name() + "]";
            if (details != null && !details.isEmpty()) {
                return time + " " + typeTag + " " + message + "\n  \u2192 " + details;
            }
            return time + " " + typeTag + " " + message;
        }
    }

    // ── Statistics ────────────────────────────────────────────────────────────

    public static final class Statistics {
        public final int  themeInstallCount;
        public final int  alarmInterceptCount;
        public final long lastThemeInstallTime;   // epoch ms (0 = never)
        public final long lastAlarmInterceptTime; // epoch ms (0 = never)
        public final long totalLogSizeBytes;

        public Statistics(int themeInstallCount, int alarmInterceptCount,
                          long lastThemeInstallTime, long lastAlarmInterceptTime,
                          long totalLogSizeBytes) {
            this.themeInstallCount    = themeInstallCount;
            this.alarmInterceptCount  = alarmInterceptCount;
            this.lastThemeInstallTime = lastThemeInstallTime;
            this.lastAlarmInterceptTime = lastAlarmInterceptTime;
            this.totalLogSizeBytes    = totalLogSizeBytes;
        }
    }

    // ── Public write API ──────────────────────────────────────────────────────

    /**
     * Appends a log entry asynchronously.
     *
     * @param context Any valid Context (application context used internally).
     * @param type    Log type for categorization.
     * @param message Short human-readable message.
     * @param details Optional additional details (may be null).
     */
    public static void log(Context context, LogType type, String message, String details) {
        Context appCtx = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                LogEntry entry = new LogEntry(System.currentTimeMillis(), type, message, details);
                File logFile = getLogFile(appCtx);

                // Trim before appending if the file is too large.
                if (logFile.exists() && logFile.length() > MAX_LOG_BYTES) {
                    trimLog(logFile);
                }

                try (FileWriter fw = new FileWriter(logFile, /* append= */ true);
                     PrintWriter pw = new PrintWriter(fw)) {
                    pw.println(entry.toFormattedString());
                }

                updateStatistics(type);
            } catch (IOException e) {
                Log.e(TAG, "log() write failed", e);
            }
        });
    }

    /** Convenience overload without details. */
    public static void log(Context context, LogType type, String message) {
        log(context, type, message, null);
    }

    // ── Public read API ───────────────────────────────────────────────────────

    /**
     * Reads all persisted log entries (newest first) synchronously.
     *
     * Must NOT be called on the main thread. Use EXECUTOR or a background thread.
     */
    public static List<LogEntry> getAllLogs(Context context) {
        File logFile = getLogFile(context.getApplicationContext());
        if (!logFile.exists()) return Collections.emptyList();

        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
        } catch (IOException e) {
            Log.e(TAG, "getAllLogs() read failed", e);
            return Collections.emptyList();
        }

        SimpleDateFormat fmt = new SimpleDateFormat(DATE_PATTERN, Locale.getDefault());
        List<LogEntry> entries = new ArrayList<>();

        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) { i++; continue; }

            try {
                // Format: "yyyy-MM-dd HH:mm:ss [TYPE] message"
                String[] parts = line.split(" ", 4);
                if (parts.length >= 4) {
                    String dateTime  = parts[0] + " " + parts[1];
                    long   timestamp = 0;
                    try { timestamp = fmt.parse(dateTime).getTime(); } catch (ParseException ignored) {}
                    String typeStr = parts[2].replace("[", "").replace("]", "");
                    LogType type;
                    try { type = LogType.valueOf(typeStr); } catch (IllegalArgumentException e2) { type = LogType.INFO; }
                    String message = parts[3];

                    String details = null;
                    if (i + 1 < lines.size() && lines.get(i + 1).startsWith("  \u2192 ")) {
                        details = lines.get(i + 1).substring(4);
                        i++;
                    }
                    entries.add(new LogEntry(timestamp, type, message, details));
                }
            } catch (Exception ignored) {}
            i++;
        }

        // Newest first
        Collections.reverse(entries);
        return entries;
    }

    /**
     * Returns aggregate statistics (always fast — reads only from SharedPreferences
     * plus one File.length() call). Safe to call from any process.
     */
    public static Statistics getStatistics(Context context) {
        File logFile = getLogFile(context.getApplicationContext());

        // Count from in-memory log if needed; use PreferenceUtil counters for speed.
        int interceptCount = PreferenceUtil.getInt("stat_alarm_intercept_count", 0);
        int installCount   = PreferenceUtil.getInt("stat_theme_install_count",   0);
        long lastIntercept = (long) PreferenceUtil.getInt("stat_last_alarm_intercept", 0) * 1000L;
        long lastInstall   = (long) PreferenceUtil.getInt("stat_last_theme_install",   0) * 1000L;
        long logSize       = logFile.exists() ? logFile.length() : 0L;

        return new Statistics(installCount, interceptCount, lastInstall, lastIntercept, logSize);
    }

    /** Clears the log file and resets all statistics counters. */
    public static void clearAllLogs(Context context) {
        Context appCtx = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            File logFile = getLogFile(appCtx);
            if (logFile.exists()) logFile.delete();
            PreferenceUtil.setInt("stat_alarm_intercept_count", 0);
            PreferenceUtil.setInt("stat_theme_install_count",   0);
            PreferenceUtil.setInt("stat_last_alarm_intercept",  0);
            PreferenceUtil.setInt("stat_last_theme_install",    0);
        });
    }

    /**
     * Copies the log file to a destination file.
     *
     * @return true on success, false if the log file doesn't exist or copy fails.
     */
    public static boolean exportLogs(Context context, File targetFile) {
        File logFile = getLogFile(context.getApplicationContext());
        if (!logFile.exists()) return false;
        try {
            copyFile(logFile, targetFile);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "exportLogs() failed", e);
            return false;
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static File getLogFile(Context appCtx) {
        return new File(appCtx.getFilesDir(), LOG_FILE_NAME);
    }

    private static void trimLog(File logFile) {
        try {
            List<String> lines = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
                String line;
                while ((line = br.readLine()) != null) lines.add(line);
            }
            if (lines.size() > MAX_LOG_LINES) {
                List<String> keep = lines.subList(lines.size() - MAX_LOG_LINES, lines.size());
                try (FileWriter fw = new FileWriter(logFile, /* append= */ false);
                     PrintWriter pw = new PrintWriter(fw)) {
                    for (String l : keep) pw.println(l);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "trimLog() failed", e);
        }
    }

    private static void updateStatistics(LogType type) {
        int nowSeconds = (int) (System.currentTimeMillis() / 1000L);
        switch (type) {
            case THEME_INSTALL:
                PreferenceUtil.setInt("stat_last_theme_install",
                        nowSeconds);
                PreferenceUtil.setInt("stat_theme_install_count",
                        PreferenceUtil.getInt("stat_theme_install_count", 0) + 1);
                break;
            case ALARM_INTERCEPT:
                PreferenceUtil.setInt("stat_last_alarm_intercept",
                        nowSeconds);
                PreferenceUtil.setInt("stat_alarm_intercept_count",
                        PreferenceUtil.getInt("stat_alarm_intercept_count", 0) + 1);
                break;
            default:
                break;
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        byte[] buf = new byte[8192];
        try (java.io.FileInputStream in  = new java.io.FileInputStream(src);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }
}
