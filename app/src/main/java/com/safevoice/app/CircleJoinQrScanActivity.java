package com.safevoice.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.safevoice.app.databinding.ActivityCircleJoinQrScanBinding;
import com.safevoice.app.firebase.FirebaseManager;
import com.safevoice.app.utils.EncryptionHelper;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Scanning screen allowing non-technical family members to join an existing circle
 * by simply scanning the invite QR code displayed by the Circle Creator.
 */
@androidx.camera.core.ExperimentalGetImage
public class CircleJoinQrScanActivity extends AppCompatActivity {

    private static final String TAG = "CircleJoinQrScan";
    private static final int PERMISSION_REQUEST_CAMERA = 1001;
    private static final String QR_DECRYPTION_KEY = "SafeVoiceAppSuperSecretKey2026";

    private ActivityCircleJoinQrScanBinding binding;
    private ExecutorService cameraExecutor;
    private BarcodeScanner scanner;
    private boolean isProcessing = false;

    private final ActivityResultLauncher<Intent> galleryLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri imageUri = result.getData().getData();
                    if (imageUri != null) {
                        processGalleryImage(imageUri);
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCircleJoinQrScanBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        cameraExecutor = Executors.newSingleThreadExecutor();

        // Configure the Barcode Scanner options specifically for QR Codes
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();
        scanner = BarcodeScanning.getClient(options);

        // Check for runtime camera permission and start standard CameraX
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, PERMISSION_REQUEST_CAMERA);
        }

        // Support uploading QR setup images directly from the local gallery
        binding.btnUploadQr.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            galleryLauncher.launch(intent);
        });

        binding.btnBack.setOnClickListener(v -> finish());
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera initialization failed.", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases(@NonNull ProcessCameraProvider cameraProvider) {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(binding.viewFinder.getSurfaceProvider());

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> processImageProxy(imageProxy));

        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

        try {
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
        } catch (Exception e) {
            Log.e(TAG, "Use case binding failed", e);
        }
    }

    private void processImageProxy(ImageProxy imageProxy) {
        if (isProcessing || imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }

        InputImage image = InputImage.fromMediaImage(imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());

        scanner.process(image)
                .addOnSuccessListener(barcodes -> {
                    if (!barcodes.isEmpty()) {
                        String rawValue = barcodes.get(0).getRawValue();
                        if (rawValue != null) {
                            handleScannedQr(rawValue);
                        }
                    }
                })
                .addOnFailureListener(e -> Log.e(TAG, "Camera QR analysis failed", e))
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void processGalleryImage(Uri uri) {
        if (isProcessing) return;

        try {
            InputImage image = InputImage.fromFilePath(this, uri);
            binding.progressBar.setVisibility(View.VISIBLE);

            scanner.process(image)
                    .addOnSuccessListener(barcodes -> {
                        if (!barcodes.isEmpty()) {
                            String rawValue = barcodes.get(0).getRawValue();
                            if (rawValue != null) {
                                handleScannedQr(rawValue);
                            }
                        } else {
                            binding.progressBar.setVisibility(View.GONE);
                            Toast.makeText(this, "No QR code found in this image.", Toast.LENGTH_LONG).show();
                        }
                    })
                    .addOnFailureListener(e -> {
                        binding.progressBar.setVisibility(View.GONE);
                        Toast.makeText(this, "Failed to read image.", Toast.LENGTH_SHORT).show();
                    });
        } catch (IOException e) {
            Log.e(TAG, "Gallery image load failed", e);
        }
    }

    private void handleScannedQr(String encryptedPayload) {
        if (isProcessing) return;
        isProcessing = true;

        runOnUiThread(() -> {
            binding.progressBar.setVisibility(View.VISIBLE);
            binding.tvStatus.setText("Processing circle registration...");
        });

        // Decrypt the scanned payload securely using local AES-256 routine
        String decryptedJson = decryptQrPayload(encryptedPayload);

        if (decryptedJson == null) {
            resetScan("Invalid QR Code. Decryption failed.");
            return;
        }

        try {
            JSONObject wrapper = new JSONObject(decryptedJson);

            String firebaseConfigStr = wrapper.getString("firebaseConfig");
            String companyName = wrapper.getString("companyName");
            String projectId = wrapper.getString("projectId");

            // Save the extracted configuration dynamically in local encrypted preferences
            boolean success = FirebaseManager.setConfiguration(this, firebaseConfigStr, companyName, projectId);

            if (success) {
                // Instantly mount the secondary database "safe_voice_circle"
                FirebaseManager.initialize(this);

                runOnUiThread(() -> {
                    Toast.makeText(CircleJoinQrScanActivity.this, "Connected to " + companyName, Toast.LENGTH_LONG).show();
                    // Push the family member straight to login activity
                    startActivity(new Intent(CircleJoinQrScanActivity.this, LoginActivity.class));
                    finish();
                });
            } else {
                resetScan("Configuration setup failed.");
            }

        } catch (Exception e) {
            Log.e(TAG, "JSON Parsing error", e);
            resetScan("Unsupported QR format.");
        }
    }

    private String decryptQrPayload(String encryptedText) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = QR_DECRYPTION_KEY.getBytes(StandardCharsets.UTF_8);
            digest.update(bytes, 0, bytes.length);
            byte[] keyBytes = digest.digest();
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");

            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            byte[] decodedValue = Base64.decode(encryptedText, Base64.NO_WRAP);
            byte[] decValue = cipher.doFinal(decodedValue);
            return new String(decValue, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "QR Decryption failed", e);
            return null;
        }
    }

    private void resetScan(String errorMsg) {
        runOnUiThread(() -> {
            Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show();
            binding.progressBar.setVisibility(View.GONE);
            binding.tvStatus.setText("Scan your admin's circle setup QR");
            isProcessing = false;
        });
    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CAMERA) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Camera access is needed for live scanning.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}