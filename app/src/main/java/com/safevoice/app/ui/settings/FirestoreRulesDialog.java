package com.safevoice.app.ui.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.safevoice.app.databinding.DialogFirestoreRulesBinding;

/**
 * Dialog displaying copy-pasteable Cloud Firestore and Realtime Database safety rules.
 * This helps circle creators secure their own private databases properly.
 */
public class FirestoreRulesDialog extends DialogFragment {

    private DialogFirestoreRulesBinding binding;

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() != null && getDialog().getWindow() != null) {
            Window window = getDialog().getWindow();
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = DialogFirestoreRulesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Populate visual layouts
        binding.tvFirestoreRule.setText(getFirestoreRulesText());
        binding.tvDatabaseRule.setText(getRealtimeDatabaseRulesText());

        binding.btnCopyFirestore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyToClipboard("Cloud Firestore Rules", getFirestoreRulesText());
            }
        });

        binding.btnCopyDatabase.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyToClipboard("Realtime Database Rules", getRealtimeDatabaseRulesText());
            }
        });

        binding.btnCloseRules.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
    }

    private String getFirestoreRulesText() {
        return "rules_version = '2';\n" +
                "service cloud.firestore {\n" +
                "  match /databases/{database}/documents {\n" +
                "    match /{document=**} {\n" +
                "      allow read, write: if request.auth != null;\n" +
                "    }\n" +
                "    match /users/{userId} {\n" +
                "      allow read, write: if request.auth != null && request.auth.uid == userId;\n" +
                "    }\n" +
                "  }\n" +
                "}";
    }

    private String getRealtimeDatabaseRulesText() {
        return "{\n" +
                "  \"rules\": {\n" +
                "    \"call_sessions\": {\n" +
                "      \".read\": \"auth != null\",\n" +
                "      \".write\": \"auth != null\"\n" +
                "    }\n" +
                "  }\n" +
                "}";
    }

    private void copyToClipboard(String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText(label, text);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(getContext(), label + " copied to clipboard!", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}