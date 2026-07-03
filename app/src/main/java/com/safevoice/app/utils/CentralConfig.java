package com.safevoice.app.utils;

/**
 * Global configuration parameters for the Central Firebase Auth Gateway.
 * These credentials belong to the master project owned by the app builder.
 * All global Gmail authentication flows run against this central console.
 */
public final class CentralConfig {

    private CentralConfig() {
        // Prevent instantiation
    }

    /**
     * The Google Web Client ID from your central project's OAuth 2.0 client IDs (Client Type 3).
     * This is required to request the ID token during the Google Sign-In handshake.
     */
    public static final String WEB_CLIENT_ID = "1013718891067-ogca31bvt1f2rvcf0jed41gccienmdg8.apps.googleusercontent.com";

    /**
     * The API Key from your central Firebase project configuration.
     */
    public static final String API_KEY = "AIzaSyBIQwGtpgZw-uVvJcFndrlCb6hllGMBbs0";

    /**
     * The Application ID (mobilesdk_app_id) for your Android app in your central Firebase project.
     */
    public static final String APPLICATION_ID = "1:1013718891067:android:14fafe142776740fe57a1e";

    /**
     * The Project ID of your central developer Firebase project.
     */
    public static final String PROJECT_ID = "safevoice-a09cf";

    /**
     * The Storage Bucket URL of your central developer Firebase project.
     */
    public static final String STORAGE_BUCKET = "safevoice-a09cf.firebasestorage.app";
}