package com.safevoice.app.ui.settings;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.safevoice.app.R;
import com.safevoice.app.databinding.FragmentSettingsBinding;
import com.safevoice.app.firebase.FirebaseManager;
import com.safevoice.app.utils.EncryptionHelper;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;

/**
 * The fragment for the "Settings" screen.
 * It allows the user to manage Firebase configuration and other app preferences.
 * Integrates copyable security rules and encrypted Twilio credentials.
 */
public class SettingsFragment extends Fragment {

    private static final String TAG = "SettingsFragment";
    private static final String PREFS_NAME = "SafeVoiceSettingsPrefs";
    private static final String KEY_CALL_PREFERENCE = "call_preference";
    private static final String CALL_PREF_STANDARD = "standard";
    private static final String CALL_PREF_WEBRTC = "webrtc";

    private FragmentSettingsBinding binding;
    private ActivityResultLauncher<Intent> filePickerLauncher;
    private SharedPreferences settingsPrefs;

    private FirebaseFirestore db;
    private FirebaseAuth mAuth;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        settingsPrefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Safely map dynamic database elements to protect cloud sync configurations
        FirebaseApp circleApp;
        try {
            circleApp = FirebaseApp.getInstance("safe_voice_circle");
            db = FirebaseFirestore.getInstance(circleApp);
            mAuth = FirebaseAuth.getInstance(circleApp);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Secondary safe_voice_circle app is not initialized yet. Falling back.", e);
            circleApp = FirebaseApp.getInstance();
            db = FirebaseFirestore.getInstance();
            mAuth = FirebaseAuth.getInstance();
        }

        initializeLaunchers();
        setupClickListeners();
        loadSettings();
        setupCreatorQrFeature();
    }

    private void initializeLaunchers() {
        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                new ActivityResultCallback<ActivityResult>() {
                    @Override
                    public void onActivityResult(ActivityResult result) {
                        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                            Uri uri = result.getData().getData();
                            if (uri != null) {
                                saveFirebaseConfigFromUri(uri);
                            }
                        }
                    }
                });
    }

    private void setupClickListeners() {
        binding.buttonUploadFirebaseJson.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openFilePicker();
            }
        });

        // Show copyable security rules via our custom dialog
        binding.buttonShowRules.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FirestoreRulesDialog dialog = new FirestoreRulesDialog();
                dialog.show(getChildFragmentManager(), "FirestoreRulesDialog");
            }
        });

        binding.buttonCopyRule.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyRulesToClipboard();
            }
        });

        binding.radioGroupCallPreference.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                SharedPreferences.Editor editor = settingsPrefs.edit();
                if (checkedId == R.id.radioStandardCall) {
                    editor.putString(KEY_CALL_PREFERENCE, CALL_PREF_STANDARD);
                } else if (checkedId == R.id.radioWebrtcCall) {
                    editor.putString(KEY_CALL_PREFERENCE, CALL_PREF_WEBRTC);
                }
                editor.apply();
            }
        });

        binding.buttonSaveTwilio.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveTwilioCredentials();
            }
        });
    }

    private void setupCreatorQrFeature() {
        String userRole = EncryptionHelper.getInstance(requireContext()).getUserRole();
        
        // Show the generated dynamic invite QR code ONLY if the user is a Circle Creator
        if ("creator".equals(userRole)) {
            binding.buttonShowQr.setVisibility(View.VISIBLE);
            binding.buttonShowQr.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    CircleQrGeneratorDialog dialog = new CircleQrGeneratorDialog();
                    dialog.show(getChildFragmentManager(), "CircleQrGeneratorDialog");
                }
            });
        } else {
            // Hide for simple family members
            binding.buttonShowQr.setVisibility(View.GONE);
        }
    }

    private void loadSettings() {
        // Load call preference
        String callPreference = settingsPrefs.getString(KEY_CALL_PREFERENCE, CALL_PREF_STANDARD);
        if (CALL_PREF_WEBRTC.equals(callPreference)) {
            binding.radioWebrtcCall.setChecked(true);
        } else {
            binding.radioStandardCall.setChecked(true);
        }

        // Load Twilio credentials from EncryptedSharedPreferences
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            SharedPreferences encryptedPrefs = EncryptedSharedPreferences.create(
                    "TwilioCredentials",
                    masterKeyAlias,
                    requireContext(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            binding.editTextTwilioSid.setText(encryptedPrefs.getString("ACCOUNT_SID", ""));
            binding.editTextTwilioApiKey.setText(encryptedPrefs.getString("API_KEY", ""));
            binding.editTextTwilioApiSecret.setText(encryptedPrefs.getString("API_SECRET", ""));
        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Could not load Twilio credentials from local cache.", e);
        }
    }

    private void saveTwilioCredentials() {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            SharedPreferences encryptedPrefs = EncryptedSharedPreferences.create(
                    "TwilioCredentials",
                    masterKeyAlias,
                    requireContext(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );

            String accountSid = binding.editTextTwilioSid.getText().toString().trim();
            String apiKey = binding.editTextTwilioApiKey.getText().toString().trim();
            String apiSecret = binding.editTextTwilioApiSecret.getText().toString().trim();

            // Save settings locally in encrypted storage container
            encryptedPrefs.edit()
                    .putString("ACCOUNT_SID", accountSid)
                    .putString("API_KEY", apiKey)
                    .putString("API_SECRET", apiSecret)
                    .apply();

            // RESTORE LOGIC FOR GLITCH 2: Safely back up credentials to the secure Firestore subcollection
            FirebaseUser currentUser = mAuth.getCurrentUser();
            if (currentUser != null) {
                Map<String, Object> twilioData = new HashMap<>();
                twilioData.put("ACCOUNT_SID", accountSid);
                twilioData.put("API_KEY", apiKey);
                twilioData.put("API_SECRET", apiSecret);

                db.collection("users").document(currentUser.getUid())
                        .collection("private_config").document("twilio")
                        .set(twilioData)
                        .addOnSuccessListener(aVoid -> Log.d(TAG, "Twilio configuration successfully backed up to secure Firestore path."))
                        .addOnFailureListener(e -> Log.e(TAG, "Failed to back up Twilio credentials to Firestore path.", e));
            }

            Toast.makeText(getContext(), R.string.twilio_credentials_saved, Toast.LENGTH_SHORT).show();

        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Could not save Twilio credentials", e);
            Toast.makeText(getContext(), R.string.twilio_credentials_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        filePickerLauncher.launch(intent);
    }

    private void saveFirebaseConfigFromUri(Uri uri) {
        try {
            InputStream inputStream = requireContext().getContentResolver().openInputStream(uri);
            if (inputStream != null) {
                boolean success = FirebaseManager.saveUserFirebaseConfig(requireContext(), inputStream);
                if (success) {
                    showRestartDialog();
                } else {
                    Toast.makeText(getContext(), "Failed to save Firebase configuration.", Toast.LENGTH_LONG).show();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(getContext(), "Error reading the selected file.", Toast.LENGTH_LONG).show();
        }
    }

    private void copyRulesToClipboard() {
        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        String ruleText = getString(R.string.settings_firebase_rules_helper_text);
        ClipData clip = ClipData.newPlainText("Firestore Rule", ruleText);
        if (clipboard != null) {
            clipboard.setPrimaryClip(clip);
            Toast.makeText(getContext(), "Rule copied to clipboard!", Toast.LENGTH_SHORT).show();
        }
    }

    private void showRestartDialog() {
        new AlertDialog.Builder(requireContext())
                .setTitle("Configuration Saved")
                .setMessage("Your new Firebase configuration has been saved. Please close and restart the app for the changes to take effect.")
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .setCancelable(false)
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}