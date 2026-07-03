package com.safevoice.app.ui.settings;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.DialogFragment;

import com.safevoice.app.databinding.DialogCircleQrGeneratorBinding;
import com.safevoice.app.utils.EncryptionHelper;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Dialog displaying a generated QR code containing the encrypted Firebase configuration payload.
 * Lets the Circle Creator easily invite family members or friends.
 */
public class CircleQrGeneratorDialog extends DialogFragment {

    private static final String TAG = "CircleQrGenerator";
    private static final String QR_ENCRYPTION_KEY = "SafeVoiceAppSuperSecretKey2026";

    private DialogCircleQrGeneratorBinding binding;
    private Bitmap generatedQrBitmap;

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            Window window = dialog.getWindow();
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = DialogCircleQrGeneratorBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        generateAndDisplayQr();

        binding.btnShareQr.setOnClickListener(v -> {
            if (generatedQrBitmap != null) {
                shareQrImage();
            } else {
                Toast.makeText(getContext(), "Failed to prepare QR code for sharing.", Toast.LENGTH_SHORT).show();
            }
        });

        binding.btnClose.setOnClickListener(v -> dismiss());
    }

    private void generateAndDisplayQr() {
        EncryptionHelper encryptionHelper = EncryptionHelper.getInstance(requireContext());

        String configJson = encryptionHelper.getFirebaseConfig();
        String companyName = encryptionHelper.getCompanyName();
        String projectId = encryptionHelper.getProjectId();

        if (configJson == null || projectId == null) {
            Toast.makeText(getContext(), "Error: Circle configuration not found. Please re-setup.", Toast.LENGTH_LONG).show();
            dismiss();
            return;
        }

        binding.tvCircleName.setText("Circle: " + companyName);

        try {
            // Build the payload structure
            JSONObject payload = new JSONObject();
            payload.put("firebaseConfig", configJson);
            payload.put("companyName", companyName);
            payload.put("projectId", projectId);
            payload.put("timestamp", System.currentTimeMillis());

            // Encrypt the payload string using AES-256
            String encryptedPayload = encryptQrPayload(payload.toString());

            if (encryptedPayload != null) {
                // Generate the QR code bitmap
                generatedQrBitmap = generateQrCode(encryptedPayload);

                if (generatedQrBitmap != null) {
                    binding.ivQrCode.setImageBitmap(generatedQrBitmap);
                    binding.ivQrCode.setVisibility(View.VISIBLE);
                } else {
                    Toast.makeText(getContext(), "Failed to render QR Code.", Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "QR Generation failed", e);
            Toast.makeText(getContext(), "Failed to generate dynamic invite", Toast.LENGTH_SHORT).show();
        }
    }

    private String encryptQrPayload(String plainText) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = SECURITY_SALT_BYTES();
            digest.update(bytes, 0, bytes.length);
            byte[] keyBytes = digest.digest();
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");

            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            byte[] encVal = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(encVal, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Encryption failed", e);
            return null;
        }
    }

    private byte[] SECURITY_SALT_BYTES() {
        return QR_ENCRYPTION_KEY.getBytes(StandardCharsets.UTF_8);
    }

    private Bitmap generateQrCode(String text) {
        try {
            com.google.zxing.MultiFormatWriter writer = new com.google.zxing.MultiFormatWriter();
            com.google.zxing.common.BitMatrix bitMatrix = writer.encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512);
            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bmp.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bmp;
        } catch (Exception e) {
            Log.e(TAG, "ZXing render failed", e);
            return null;
        }
    }

    private void shareQrImage() {
        try {
            File cachePath = new File(requireContext().getCacheDir(), "images");
            cachePath.mkdirs();
            File newFile = new File(cachePath, "circle_invite_qr.png");
            FileOutputStream stream = new FileOutputStream(newFile);

            generatedQrBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            stream.close();

            // Obtain the secure URI using your app's FileProvider authority
            Uri contentUri = FileProvider.getUriForFile(requireContext(), "com.safevoice.app.fileprovider", newFile);

            if (contentUri != null) {
                Intent shareIntent = new Intent();
                shareIntent.setAction(Intent.ACTION_SEND);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                shareIntent.setDataAndType(contentUri, requireContext().getContentResolver().getType(contentUri));
                shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Join My Safe Voice Circle");
                shareIntent.putExtra(Intent.EXTRA_TEXT, "Scan this QR code to join my private circle: " + 
                        EncryptionHelper.getInstance(getContext()).getCompanyName());

                startActivity(Intent.createChooser(shareIntent, "Share Circle Setup QR via:"));
            }
        } catch (IOException e) {
            Log.e(TAG, "Sharing failed", e);
            Toast.makeText(getContext(), "Could not share invite image.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}