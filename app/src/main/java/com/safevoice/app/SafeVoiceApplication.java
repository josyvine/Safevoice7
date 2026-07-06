package com.safevoice.app;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;

import com.safevoice.app.firebase.FirebaseManager;
import com.safevoice.app.utils.DiagnosticLogger;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * The custom Application class for Safe Voice.
 * This is the entry point of the application process.
 * Its main responsibility is to initialize components that are needed globally,
 * such as our dynamic Firebase configuration.
 */
public class SafeVoiceApplication extends Application {

    private static final String TAG = "SafeVoiceApplication";

    @Override
    public void onCreate() {
        super.onCreate();

        // 1. Initialize our custom diagnostic logger inside the shared external storage
        DiagnosticLogger.initialize(this);
        DiagnosticLogger.logInfo("APPLICATION", "Safe Voice application process started.");

        // 2. Register the global uncaught exception crash interceptor
        setupUncaughtExceptionHandler();

        // 3. Initialize our custom FirebaseManager.
        // This manager will boot the central Firebase app [DEFAULT] using CentralConfig
        // for secure Google Auth, and dynamically mount the secondary named "safe_voice_circle"
        // instance if a private custom google-services configuration exists.
        try {
            FirebaseManager.initialize(this);
            DiagnosticLogger.logInfo("APPLICATION", "FirebaseManager successfully initialized.");
        } catch (Exception e) {
            DiagnosticLogger.logError("APPLICATION", "Failed to initialize FirebaseManager dynamically.", e);
        }
    }

    /**
     * Intercepts uncaught exceptions and application crashes across all active threads.
     * Records the exact stack trace in the local log file before letting the app close.
     */
    private void setupUncaughtExceptionHandler() {
        Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(@NonNull Thread thread, @NonNull Throwable throwable) {
                try {
                    // Extract the full stack trace as a readable string
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    throwable.printStackTrace(pw);
                    String stackTrace = sw.toString();

                    // Print crash diagnostics directly to our public debug log file
                    DiagnosticLogger.logError("CRASH", "FATAL APPLICATION CRASH on Thread: " + thread.getName());
                    DiagnosticLogger.logError("CRASH", "Stack Trace Details:\n" + stackTrace);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to write uncaught exception logs to disk.", e);
                } finally {
                    // Always forward the crash back to the Android default runtime handler to close cleanly
                    if (defaultHandler != null) {
                        defaultHandler.uncaughtException(thread, throwable);
                    }
                }
            }
        });
        DiagnosticLogger.logInfo("APPLICATION", "Global UncaughtExceptionHandler successfully registered.");
    }
}