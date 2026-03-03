package app.hypermtz.util;

import android.content.Context;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Application-wide structured log manager — ported from ThemeStore's LogManager.kt.
 *
 * Writes log entries to {filesDir}/app_logs.txt and tracks statistics (intercept count,
 * install count) in {@link PreferenceUtil}. All disk I/O runs on a background executor.
 *
 * Cross-process: KeepAliveService (in :intercept) calls {@link #getStatistics(Context)}
 * to populate the foreground notification. Since stats are stored in SharedPreferences,
 * they are readable from any process with the same UID.
 */
public final class LogManager {

    private static final String TAG           = "LogManager";
    private static final String LOG_FILE_NAME = "app_logs.txt";
    private static final long   MAX_LOG_BYTES = 1024 * 1024L; // 1 MB
    private static final int    MAX_LOG_LINES = 1000;

    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    // Single-thread executor — serializes all file writes.
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
        /** Optional extra detail line. Null if absent. */
        public final String  details;

        public LogEntry(long timestamp, LogType type, String message, String details) {
            this.timestamp = timestamp;
            this.type      = type;
            this.message   = message;
            this.details   = details;
        }

        /** Format written to disk: "2024-01-01 12:00:00 [TYPE] message\n  → details" */
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
        public final long lastThemeInstallTime;    // epoch ms, 0 = never
        public final long lastAlarmInterceptTime;  // epoch ms, 0 = never
        public final long totalLogSizeBytes;

        public Statistics(int themeInstallCount, int alarmInterceptCount,
                          long lastThemeInstallTime, long lastAlarmInterceptTime,
                          long totalLogSizeBytes) {
            this.themeInstallCount     = themeInstallCount;
            this.alarmInterceptCount   = alarmInterceptCount;
            this.lastThemeInstallTime  = lastThemeInstallTime;
            this.lastAlarmInterceptTime = lastAlarmInterceptTime;
            this.totalLogSizeBytes     = totalLogSizeBytes;
        }
    }

    // ── Write API ─────────────────────────────────────────────────────────────

    /**
     * Appends a log entry asynchronously (never blocks the calling thread).
     */
    public static void log(Context context, LogType type, String message, String details) {
        Context appCtx = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                LogEntry entry = new LogEntry(System.currentTimeMillis(), type, message, details);
                File logFile = logFile(appCtx);

                if (logFile.exists() && logFile.length() > MAX_LOG_BYTES) {
                    trimLog(logFile);
                }

                try (PrintWriter pw = new PrintWriter(new FileWriter(logFile, true))) {
                    pw.println(entry.toFormattedString());
                }

                updateStats(type);
            } catch (IOException e) {
                Log.e(TAG, "log() write failed", e);
            }
        });
    }

    /** Convenience overload without details. */
    public static void log(Context context, LogType type, String message) {
        log(context, type, message, null);
    }

    // ── Read API ──────────────────────────────────────────────────────────────

    /**
     * Returns aggregate statistics — fast, reads only from SharedPreferences.
     * Safe to call on any thread, including the main thread.
     */
    public static Statistics getStatistics(Context context) {
        File logFile    = logFile(context.getApplicationContext());
        int  intercepts = PreferenceUtil.getInt("stat_alarm_intercept_count", 0);
        int  installs   = PreferenceUtil.getInt("stat_theme_install_count",   0);
        long lastInt    = (long) PreferenceUtil.getInt("stat_last_alarm_intercept", 0) * 1000L;
        long lastInst   = (long) PreferenceUtil.getInt("stat_last_theme_install",   0) * 1000L;
        long logSize    = logFile.exists() ? logFile.length() : 0L;
        return new Statistics(installs, intercepts, lastInst, lastInt, logSize);
    }

    /**
     * Reads all log entries from disk (newest first).
     * Must NOT be called on the main thread.
     */
    public static List<LogEntry> getAllLogs(Context context) {
        File logFile = logFile(context.getApplicationContext());
        if (!logFile.exists()) return Collections.emptyList();

        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
        } catch (IOException e) {
            Log.e(TAG, "getAllLogs() read failed", e);
            return Collections.emptyList();
        }

        SimpleDateFormat fmt     = new SimpleDateFormat(DATE_PATTERN, Locale.getDefault());
        List<LogEntry>   entries = new ArrayList<>();

        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) { i++; continue; }

            try {
                // "yyyy-MM-dd HH:mm:ss [TYPE] message"
                String[] parts = line.split(" ", 4);
                if (parts.length >= 4) {
                    String dateTime  = parts[0] + " " + parts[1];
                    long   timestamp = 0L;
                    try {
                        Date parsed = fmt.parse(dateTime); // may return null
                        if (parsed != null) timestamp = parsed.getTime();
                    } catch (ParseException ignored) {}

                    String  typeStr = parts[2].replace("[", "").replace("]", "");
                    LogType type;
                    try { type = LogType.valueOf(typeStr); }
                    catch (IllegalArgumentException e) { type = LogType.INFO; }

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

        Collections.reverse(entries); // newest first
        return entries;
    }

    /** Deletes the log file and resets all statistic counters. */
    public static void clearAllLogs(Context context) {
        Context appCtx = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            File f = logFile(appCtx);
            if (f.exists()) //noinspection ResultOfMethodCallIgnored
                f.delete();
            PreferenceUtil.setInt("stat_alarm_intercept_count", 0);
            PreferenceUtil.setInt("stat_theme_install_count",   0);
            PreferenceUtil.setInt("stat_last_alarm_intercept",  0);
            PreferenceUtil.setInt("stat_last_theme_install",    0);
        });
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static File logFile(Context appCtx) {
        return new File(appCtx.getFilesDir(), LOG_FILE_NAME);
    }

    private static void trimLog(File logFile) {
        try {
            List<String> all = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
                String line;
                while ((line = br.readLine()) != null) all.add(line);
            }
            if (all.size() > MAX_LOG_LINES) {
                List<String> keep = all.subList(all.size() - MAX_LOG_LINES, all.size());
                try (PrintWriter pw = new PrintWriter(new FileWriter(logFile, false))) {
                    for (String l : keep) pw.println(l);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "trimLog() failed", e);
        }
    }

    private static void updateStats(LogType type) {
        int now = (int) (System.currentTimeMillis() / 1000L);
        switch (type) {
            case THEME_INSTALL:
                PreferenceUtil.setInt("stat_last_theme_install", now);
                PreferenceUtil.setInt("stat_theme_install_count",
                        PreferenceUtil.getInt("stat_theme_install_count", 0) + 1);
                break;
            case ALARM_INTERCEPT:
                PreferenceUtil.setInt("stat_last_alarm_intercept", now);
                PreferenceUtil.setInt("stat_alarm_intercept_count",
                        PreferenceUtil.getInt("stat_alarm_intercept_count", 0) + 1);
                break;
            default:
                break;
        }
    }
}
