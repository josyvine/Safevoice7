package com.safevoice.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/**
 * Handles security operations and local secure storage.
 * Leverages EncryptedSharedPreferences to prevent unauthorized local configuration tampering.
 */
public class EncryptionHelper {

    private static final String TAG = "EncryptionHelper";
    private static final String PREFS_FILENAME = "secure_safevoice_prefs";

    // Keys for secure SharedPreferences storage
    private static final String KEY_USER_ROLE = "key_user_role"; // "creator" or "member"
    private static final String KEY_FIREBASE_CONFIG = "key_firebase_config";
    private static final String KEY_COMPANY_NAME = "key_company_name";
    private static final String KEY_PROJECT_ID = "key_project_id";
    private static final String KEY_IS_SETUP_DONE = "key_is_setup_done";

    // Unique security salt used specifically for computing silent secondary passwords
    private static final String SECURITY_SALT = "SafeVoiceAppSecurePasswordSalt2026";

    private final SharedPreferences sharedPreferences;
    private static EncryptionHelper instance;

    private EncryptionHelper(Context context) {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            sharedPreferences = EncryptedSharedPreferences.create(
                    PREFS_FILENAME,
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | java.io.IOException e) {
            throw new RuntimeException("Failed to initialize encrypted storage", e);
        }
    }

    /**
     * Retrieves the single synchronized instance of the EncryptionHelper.
     */
    public static synchronized EncryptionHelper getInstance(Context context) {
        if (instance == null) {
            instance = new EncryptionHelper(context.getApplicationContext());
        }
        return instance;
    }

    public void saveUserRole(String role) {
        sharedPreferences.edit().putString(KEY_USER_ROLE, role).apply();
    }

    public String getUserRole() {
        return sharedPreferences.getString(KEY_USER_ROLE, null);
    }

    public void clearUserRole() {
        sharedPreferences.edit().remove(KEY_USER_ROLE).apply();
    }

    public void saveFirebaseConfig(String jsonConfig, String companyName, String projectId) {
        sharedPreferences.edit()
                .putString(KEY_FIREBASE_CONFIG, jsonConfig)
                .putString(KEY_COMPANY_NAME, companyName)
                .putString(KEY_PROJECT_ID, projectId)
                .putBoolean(KEY_IS_SETUP_DONE, true)
                .apply();
    }

    public String getFirebaseConfig() {
        return sharedPreferences.getString(KEY_FIREBASE_CONFIG, null);
    }

    public String getCompanyName() {
        return sharedPreferences.getString(KEY_COMPANY_NAME, "Unknown Circle");
    }

    public String getProjectId() {
        return sharedPreferences.getString(KEY_PROJECT_ID, null);
    }

    public boolean isSetupDone() {
        return sharedPreferences.getBoolean(KEY_IS_SETUP_DONE, false);
    }

    public void clearAllData() {
        sharedPreferences.edit().clear().apply();
    }

    /**
     * Generates a predictable, secure, and unique 16-character password
     * based on a cryptographic SHA-256 hash of the user's central credentials.
     * Used for programmatic authentication on the secondary named Firebase instances.
     *
     * @param email     The verified Gmail address.
     * @param googleUid The unique Google authentication user ID.
     * @return A secure 16-character password string.
     */
    public String calculateSecurePassword(String email, String googleUid) {
        try {
            String input = email + googleUid + SECURITY_SALT;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString().substring(0, 16);
        } catch (Exception e) {
            Log.e(TAG, "Failed to compute secure password hash.", e);
            return "FallbackPass123!";
        }
    }
}