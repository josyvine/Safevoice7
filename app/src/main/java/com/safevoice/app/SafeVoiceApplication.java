package com.safevoice.app;

import android.app.Application;

import com.safevoice.app.firebase.FirebaseManager;

/**
 * The custom Application class for Safe Voice.
 * This is the entry point of the application process.
 * Its main responsibility is to initialize components that are needed globally,
 * such as our dynamic Firebase configuration.
 */
public class SafeVoiceApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // Initialize our custom FirebaseManager.
        // This manager will boot the central Firebase app [DEFAULT] using CentralConfig
        // for secure Google Auth, and dynamically mount the secondary named "safe_voice_circle"
        // instance if a private custom google-services configuration exists.
        FirebaseManager.initialize(this);
    }
}