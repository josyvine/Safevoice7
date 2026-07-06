package com.safevoice.app.utils;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Thread-safe logging utility that writes diagnostic logs and system crash traces
 * to a public-facing file in the app's external files directory.
 * Accessible at: /Android/data/com.safevoice.app/files/SafeVoice Logs/safevoice_diagnostic_log.txt
 */
public class DiagnosticLogger {

    private static final String TAG = "DiagnosticLogger";
    private static final String DIRECTORY_NAME = "SafeVoice Logs";
    private static final String FILE_NAME = "safevoice_diagnostic_log.txt";

    private static File logFile = null;
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    /**
     * Initializes the logging manager. Creates directories and the target output file.
     */
    public static synchronized void initialize(Context context) {
        if (logFile != null) return;

        try {
            // Get the app-specific external files directory (bypasses runtime storage permission prompts)
            File externalFilesDir = context.getExternalFilesDir(null);
            if (externalFilesDir != null) {
                File logDirectory = new File(externalFilesDir, DIRECTORY_NAME);
                if (!logDirectory.exists()) {
                    boolean created = logDirectory.mkdirs();
                    Log.d(TAG, "Log directory created: " + created);
                }
                logFile = new File(logDirectory, FILE_NAME);
                Log.d(TAG, "DiagnosticLogger initialized at path: " + logFile.getAbsolutePath());
                
                // Appends a clear header indicating a new startup sequence
                writeLog("SYSTEM", "INFO", "========================= NEW SESSION INITIATED =========================");
            } else {
                Log.e(TAG, "External files directory is not available. Logging disabled.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize DiagnosticLogger directories.", e);
        }
    }

    public static void logInfo(String tag, String message) {
        writeLog(tag, "INFO", message);
    }

    public static void logWarn(String tag, String message) {
        writeLog(tag, "WARN", message);
    }

    public static void logError(String tag, String message) {
        writeLog(tag, "ERROR", message);
    }

    public static void logError(String tag, String message, Throwable tr) {
        if (tr != null) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            tr.printStackTrace(pw);
            writeLog(tag, "ERROR", message + "\nException Stack Trace:\n" + sw.toString());
        } else {
            writeLog(tag, "ERROR", message);
        }
    }

    /**
     * Internal thread-safe file writer.
     */
    private static synchronized void writeLog(String tag, String level, String message) {
        // Log to system Logcat console
        switch (level) {
            case "INFO":
                Log.i(tag, message);
                break;
            case "WARN":
                Log.w(tag, message);
                break;
            case "ERROR":
                Log.e(tag, message);
                break;
            default:
                Log.d(TAG, "[" + level + "] [" + tag + "] " + message);
                break;
        }

        if (logFile == null) return;

        try (FileWriter writer = new FileWriter(logFile, true)) {
            String timestamp = dateFormat.format(new Date());
            String logLine = String.format(Locale.US, "%s [%s] [%s]: %s\n", timestamp, level, tag, message);
            writer.write(logLine);
            writer.flush();
        } catch (Exception e) {
            Log.e(TAG, "Failed to append log line to diagnostic log file.", e);
        }
    }
}