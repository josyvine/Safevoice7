package com.safevoice.app.ui.profile;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.safevoice.app.databinding.FragmentProfileBinding;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles the display and updates of the user's phone number.
 * All changes are saved directly into the user's secondary safety circle database.
 */
public class ProfileFragment extends Fragment {

    private FragmentProfileBinding binding;
    private FirebaseFirestore db;
    private FirebaseAuth mAuth;
    private DocumentReference userProfileRef;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentProfileBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Retrieve Firestore connected directly to your secondary custom Firebase project
        try {
            db = FirebaseFirestore.getInstance(FirebaseApp.getInstance("safe_voice_circle"));
        } catch (IllegalStateException e) {
            db = FirebaseFirestore.getInstance();
        }

        mAuth = FirebaseAuth.getInstance();

        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser != null) {
            // Set the reference to this user's document in the "users" collection
            userProfileRef = db.collection("users").document(currentUser.getUid());
            loadProfileData();
        } else {
            // Optionally, handle the case where the user is not logged in
            Toast.makeText(getContext(), "You must be logged in to view your profile.", Toast.LENGTH_SHORT).show();
        }

        binding.buttonSaveProfile.setOnClickListener(v -> saveProfileData());
    }

    private void loadProfileData() {
        if (userProfileRef == null) return;

        // Fetch the user's document from Firestore
        userProfileRef.get().addOnSuccessListener(documentSnapshot -> {
            if (documentSnapshot.exists()) {
                // Get the phone number from the document and set it in the text field
                String phoneNumber = documentSnapshot.getString("phoneNumber");
                binding.editTextProfilePhone.setText(phoneNumber);
            }
        }).addOnFailureListener(e -> {
            Toast.makeText(getContext(), "Failed to load profile.", Toast.LENGTH_SHORT).show();
        });
    }

    private void saveProfileData() {
        if (userProfileRef == null) {
            Toast.makeText(getContext(), "Cannot save profile. Not logged in.", Toast.LENGTH_SHORT).show();
            return;
        }

        String phoneNumber = binding.editTextProfilePhone.getText().toString().trim();
        if (phoneNumber.isEmpty()) {
            Toast.makeText(getContext(), "Phone number cannot be empty.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Create a Map to hold the data you want to save
        Map<String, Object> profileData = new HashMap<>();
        profileData.put("phoneNumber", phoneNumber);

        // Save the data to Firestore.
        // SetOptions.merge() is crucial: it updates the phoneNumber field
        // without deleting other existing fields like 'verifiedName' or 'email'.
        userProfileRef.set(profileData, SetOptions.merge())
            .addOnSuccessListener(aVoid -> Toast.makeText(getContext(), "Profile saved successfully!", Toast.LENGTH_SHORT).show())
            .addOnFailureListener(e -> Toast.makeText(getContext(), "Failed to save profile.", Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}