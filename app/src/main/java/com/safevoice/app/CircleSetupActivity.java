package com.safevoice.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.safevoice.app.databinding.ActivityCircleSetupBinding;
import com.safevoice.app.firebase.FirebaseManager;
import com.safevoice.app.utils.EncryptionHelper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Handles the manual or text-based loading of custom dynamic Firebase configs.
 * Once parsed and stored, it initializes the secondary app context and moves to the login stage.
 */
public class CircleSetupActivity extends AppCompatActivity {

    private static final String TAG = "CircleSetupActivity";
    private ActivityCircleSetupBinding binding;
    private String jsonContent = null;
    private String parsedProjectId = null;

    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            processSelectedFile(uri);
                        }
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCircleSetupBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.btnSelectJson.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openFilePicker();
            }
        });

        binding.btnSaveContinue.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                validateAndSave();
            }
        });
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        String[] mimeTypes = {"application/json", "text/plain", "application/octet-stream"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        filePickerLauncher.launch(intent);
    }

    private void processSelectedFile(Uri uri) {
        try {
            jsonContent = readTextFromUri(uri);
            if (jsonContent != null) {
                // Perform quick validation to confirm the file is a google-services configuration
                JSONObject root = new JSONObject(jsonContent);
                JSONObject projectInfo = root.getJSONObject("project_info");
                parsedProjectId = projectInfo.getString("project_id");

                binding.tvFileName.setText("Loaded: " + parsedProjectId);
                binding.tvFileName.setVisibility(View.VISIBLE);

                Toast.makeText(this, "JSON Loaded Successfully", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing file", e);
            jsonContent = null;
            parsedProjectId = null;
            binding.tvFileName.setText("Error: Invalid google-services.json");
            binding.tvFileName.setVisibility(View.VISIBLE);
            Toast.makeText(this, "Invalid JSON File", Toast.LENGTH_LONG).show();
        }
    }

    private String readTextFromUri(Uri uri) throws IOException {
        InputStream inputStream = getContentResolver().openInputStream(uri);
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        StringBuilder stringBuilder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            stringBuilder.append(line);
        }
        inputStream.close();
        return stringBuilder.toString();
    }

    private void validateAndSave() {
        String circleName = binding.etCircleName.getText().toString().trim();

        if (TextUtils.isEmpty(circleName)) {
            binding.etCircleName.setError("Circle/Group Name is required");
            return;
        }

        String pastedJson = binding.etPasteJson.getText().toString().trim();
        String finalJson = null;
        String finalProjectId = null;

        // Give priority to pasted JSON text
        if (!TextUtils.isEmpty(pastedJson)) {
            try {
                JSONObject root = new JSONObject(pastedJson);
                JSONObject projectInfo = root.getJSONObject("project_info");
                finalProjectId = projectInfo.getString("project_id");
                finalJson = pastedJson;
            } catch (Exception e) {
                Log.e(TAG, "Error parsing pasted JSON content", e);
                Toast.makeText(this, "Invalid Pasted JSON Structure", Toast.LENGTH_LONG).show();
                return;
            }
        } 
        // Fallback to picked file JSON text
        else if (jsonContent != null && parsedProjectId != null) {
            finalJson = jsonContent;
            finalProjectId = parsedProjectId;
        }

        if (finalJson == null || finalProjectId == null) {
            Toast.makeText(this, "Please upload a google-services.json or paste its content", Toast.LENGTH_LONG).show();
            return;
        }

        // Configure the Firebase secondary app dynamically with the utility helper
        boolean success = FirebaseManager.setConfiguration(this, finalJson, circleName, finalProjectId);

        if (success) {
            // Initialize both the default app and the custom secondary app immediately
            FirebaseManager.initialize(this);

            Toast.makeText(this, "Setup Complete. Proceeding to Login...", Toast.LENGTH_SHORT).show();

            // Direct user to Google login activity to complete authentication
            Intent intent = new Intent(CircleSetupActivity.this, LoginActivity.class);
            startActivity(intent);
            finish();
        } else {
            Toast.makeText(this, "Failed to save configuration.", Toast.LENGTH_SHORT).show();
        }
    }
}