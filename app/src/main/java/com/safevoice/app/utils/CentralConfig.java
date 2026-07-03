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
     * The Google Web Client ID from your central project's OAuth 2.0 client IDs.
     * This is required to request the ID token during the Google Sign-In handshake.
     */
    public static final String WEB_CLIENT_ID = "YOUR_CENTRAL_WEB_CLIENT_ID_HERE.apps.googleusercontent.com";

    /**
     * The API Key from your central Firebase project configuration.
     */
    public static final String API_KEY = "YOUR_CENTRAL_API_KEY_HERE";

    /**
     * The Application ID (mobilesdk_app_id) for your Android app in your central Firebase project.
     */
    public static final String APPLICATION_ID = "YOUR_CENTRAL_APPLICATION_ID_HERE";

    /**
     * The Project ID of your central developer Firebase project.
     */
    public static final String PROJECT_ID = "YOUR_CENTRAL_PROJECT_ID_HERE";

    /**
     * The Storage Bucket URL of your central developer Firebase project.
     */
    public static final String STORAGE_BUCKET = "YOUR_CENTRAL_STORAGE_BUCKET_HERE";
}