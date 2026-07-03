package com.safevoice.app.firebase;

import android.content.Context;
import android.util.Log;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.safevoice.app.utils.CentralConfig;
import com.safevoice.app.utils.EncryptionHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Manages the dynamic initialization of the Firebase backend.
 * This class allows the app to switch its Firebase project at runtime.
 *
 * Programmatic Dual-App Design:
 * - [DEFAULT] App: Initialized permanently with developer credentials for global Gmail logins.
 * - "safe_voice_circle" App: Mounted dynamically to handle isolated, user-hosted database files.
 */
public class FirebaseManager {

    private static final String TAG = "FirebaseManager";
    private static final String USER_CONFIG_FILENAME = "user_google_services.json";

    /**
     * Initializes Firebase for the entire application.
     * Mounts the global Auth app first, and then initializes the custom secondary circle app if configured.
     *
     * @param context The application context.
     */
    public static void initialize(Context context) {
        // 1. Programmatically initialize the Central developer auth project as the [DEFAULT] instance
        try {
            FirebaseOptions defaultOptions = new FirebaseOptions.Builder()
                    .setApiKey(CentralConfig.API_KEY)
                    .setApplicationId(CentralConfig.APPLICATION_ID)
                    .setProjectId(CentralConfig.PROJECT_ID)
                    .setStorageBucket(CentralConfig.STORAGE_BUCKET)
                    .build();

            boolean defaultAppExists = false;
            for (FirebaseApp app : FirebaseApp.getApps(context)) {
                if (FirebaseApp.DEFAULT_APP_NAME.equals(app.getName())) {
                    defaultAppExists = true;
                    break;
                }
            }

            if (!defaultAppExists) {
                FirebaseApp.initializeApp(context, defaultOptions);
                Log.d(TAG, "Central Firebase [DEFAULT] initialized successfully.");
            } else {
                Log.d(TAG, "Central Firebase [DEFAULT] already exists.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize central default Firebase app.", e);
        }

        // 2. Programmatically mount the user's secondary private project as the "safe_voice_circle" instance
        String jsonConfig = EncryptionHelper.getInstance(context).getFirebaseConfig();

        if (jsonConfig == null || jsonConfig.isEmpty()) {
            // Check if there is a legacy local file fallback to maintain absolute compatibility
            File userConfigFile = new File(context.getFilesDir(), USER_CONFIG_FILENAME);
            if (userConfigFile.exists()) {
                try {
                    FileInputStream fis = new FileInputStream(userConfigFile);
                    int size = fis.available();
                    byte[] buffer = new byte[size];
                    fis.read(buffer);
                    fis.close();
                    jsonConfig = new String(buffer, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    Log.e(TAG, "Failed to load dynamic file fallback", e);
                }
            }
        }

        if (jsonConfig != null && !jsonConfig.isEmpty()) {
            try {
                FirebaseOptions options = buildOptionsFromJson(jsonConfig);
                boolean circleAppExists = false;
                for (FirebaseApp app : FirebaseApp.getApps(context)) {
                    if ("safe_voice_circle".equals(app.getName())) {
                        circleAppExists = true;
                        break;
                    }
                }

                if (circleAppExists) {
                    FirebaseApp app = FirebaseApp.getInstance("safe_voice_circle");
                    if (!app.getOptions().getProjectId().equals(options.getProjectId())) {
                         Log.w(TAG, "safe_voice_circle project ID mismatch. Re-initializing secondary app.");
                         app.delete();
                         FirebaseApp.initializeApp(context, options, "safe_voice_circle");
                    } else {
                         Log.d(TAG, "safe_voice_circle already initialized and matches current configuration.");
                    }
                } else {
                    FirebaseApp.initializeApp(context, options, "safe_voice_circle");
                    Log.d(TAG, "Secondary Firebase 'safe_voice_circle' initialized successfully.");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse or initialize secondary dynamic Firebase config.", e);
            }
        } else {
            Log.d(TAG, "No secondary safety circle Firebase config found. Waiting for user setup.");
        }
    }

    /**
     * Re-creates the named "safe_voice_circle" Firebase instance at runtime.
     * Overrides and saves the credentials inside the local secure preferences securely.
     */
    public static boolean setConfiguration(Context context, String jsonConfig, String companyName, String projectId) {
        try {
            FirebaseOptions options = buildOptionsFromJson(jsonConfig);
            EncryptionHelper.getInstance(context).saveFirebaseConfig(jsonConfig, companyName, projectId);

            boolean circleAppExists = false;
            for (FirebaseApp app : FirebaseApp.getApps(context)) {
                if ("safe_voice_circle".equals(app.getName())) {
                    circleAppExists = true;
                    break;
                }
            }

            if (circleAppExists) {
                FirebaseApp app = FirebaseApp.getInstance("safe_voice_circle");
                app.delete();
            }

            FirebaseApp.initializeApp(context, options, "safe_voice_circle");
            Log.d(TAG, "New Circle Firebase configuration saved and secondary app mounted.");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Invalid Firebase JSON provided.", e);
            return false;
        }
    }

    /**
     * Parses the JSON configuration string and returns built FirebaseOptions.
     */
    public static FirebaseOptions buildOptionsFromJson(String jsonString) throws Exception {
        JSONObject root = new JSONObject(jsonString);
        JSONObject projectInfo = root.getJSONObject("project_info");
        JSONArray clientArray = root.getJSONArray("client");
        JSONObject client = clientArray.getJSONObject(0);
        JSONObject clientInfo = client.getJSONObject("client_info");
        JSONArray apiKeyArray = client.getJSONArray("api_key");
        JSONObject apiKey = apiKeyArray.getJSONObject(0);

        return new FirebaseOptions.Builder()
                .setApiKey(apiKey.getString("current_key"))
                .setApplicationId(clientInfo.getString("mobilesdk_app_id"))
                .setProjectId(projectInfo.getString("project_id"))
                .setStorageBucket(projectInfo.getString("storage_bucket"))
                .build();
    }

    /**
     * Helper method to parse a JSON InputStream and build FirebaseOptions.
     */
    private static FirebaseOptions buildOptionsFromJson(InputStream inputStream) throws IOException, org.json.JSONException {
        int size = inputStream.available();
        byte[] buffer = new byte[size];
        inputStream.read(buffer);
        inputStream.close();
        String json = new String(buffer, StandardCharsets.UTF_8);
        try {
            return buildOptionsFromJson(json);
        } catch (org.json.JSONException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Parsing options failed", e);
        }
    }

    /**
     * Fallback default setup method retained to preserve backward compatibility.
     */
    private static void initializeAppWithDefault(Context context) {
        try {
            FirebaseApp.initializeApp(context);
            Log.d(TAG, "Firebase initialized successfully with DEFAULT config.");
        } catch (Exception e) {
            Log.e(TAG, "FATAL: Could not initialize Firebase with default config.", e);
        }
    }

    /**
     * Saves a new google-services.json file provided by the user to the app's private storage.
     * Mirrors the config values inside our secure SharedPreferences automatically.
     *
     * @param context The application context.
     * @param inputStream The InputStream from the user-selected file.
     * @return true if the file was saved successfully, false otherwise.
     */
    public static boolean saveUserFirebaseConfig(Context context, InputStream inputStream) {
        File userConfigFile = new File(context.getFilesDir(), USER_CONFIG_FILENAME);
        OutputStream outputStream = null;
        try {
            ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                byteBuffer.write(buffer, 0, length);
            }
            byte[] fileBytes = byteBuffer.toByteArray();

            // Write to files directory fallback
            outputStream = new FileOutputStream(userConfigFile);
            outputStream.write(fileBytes);
            outputStream.close();

            // Mirror file content inside local secure preferences
            String jsonString = new String(fileBytes, StandardCharsets.UTF_8);
            FirebaseOptions options = buildOptionsFromJson(jsonString);
            
            // Mirror to secure shared preferences securely
            EncryptionHelper.getInstance(context).saveFirebaseConfig(jsonString, "My Safety Circle", options.getProjectId());

            Log.d(TAG, "Successfully saved user Firebase config to files and secure SharedPreferences.");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error saving user Firebase config.", e);
            return false;
        } finally {
            try {
                if (outputStream != null) {
                    outputStream.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error closing streams after saving config.", e);
            }
        }
    }
}