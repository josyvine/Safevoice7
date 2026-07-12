package com.safevoice.app.ui.home; 

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.safevoice.app.LoginActivity;
import com.safevoice.app.R;
import com.safevoice.app.databinding.FragmentHomeBinding;
import com.safevoice.app.services.VoiceRecognitionService;

/**
 * The fragment for the "Home" screen.
 * It provides controls to start/stop the listening service and to verify the user's profile status.
 */
public class HomeFragment extends Fragment {

    private static final String TAG = "HomeFragment";
    private FragmentHomeBinding binding;
    private FirebaseAuth mAuth;

    // FIX FOR GLITCH 4: BroadcastReceiver to listen for active service running status updates dynamically
    private final BroadcastReceiver serviceStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (VoiceRecognitionService.ACTION_SERVICE_STATUS_CHANGED.equals(intent.getAction())) {
                Log.d(TAG, "Service status changed broadcast received. Updating button status UI.");
                updateServiceStatusUI();
            }
        }
    };

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        // Inflate the layout for this fragment using view binding.
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        mAuth = FirebaseAuth.getInstance();
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Set up the click listener for the service toggle button.
        binding.buttonToggleService.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isServiceRunning()) {
                    stopVoiceService();
                } else {
                    startVoiceService();
                }
                // Update the UI immediately after the button is clicked.
                updateServiceStatusUI();
            }
        });

        // Set up the click listener for the profile status button.
        binding.buttonVerifyIdentity.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // First, check if a user is already signed in.
                FirebaseUser currentUser = mAuth.getCurrentUser();
                if (currentUser != null) {
                    // Guide them to set their phone number in the Profile tab instead of KYC
                    Toast.makeText(getContext(), "Please navigate to the Profile tab to set your contact details.", Toast.LENGTH_LONG).show();
                } else {
                    // If no user is signed in, force them to log in first.
                    if (getActivity() != null) {
                        Toast.makeText(getContext(), "Please sign in to access your profile.", Toast.LENGTH_SHORT).show();
                        Intent intent = new Intent(getActivity(), LoginActivity.class);
                        startActivity(intent);
                    }
                }
            }
        });
    }

    // FIX FOR GLITCH 4: Register broadcast receiver to receive dynamic service running status alerts
    @Override
    public void onStart() {
        super.onStart();
        if (getContext() != null) {
            IntentFilter filter = new IntentFilter(VoiceRecognitionService.ACTION_SERVICE_STATUS_CHANGED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getContext().registerReceiver(serviceStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                getContext().registerReceiver(serviceStatusReceiver, filter);
            }
            Log.d(TAG, "Service status BroadcastReceiver registered successfully.");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Update the UI every time the fragment becomes visible to ensure it's up-to-date.
        updateServiceStatusUI();
        updateVerificationStatusUI();
    }

    // FIX FOR GLITCH 4: Safely unregister broadcast receiver on fragment stop to avoid system memory leaks
    @Override
    public void onStop() {
        super.onStop();
        if (getContext() != null) {
            try {
                getContext().unregisterReceiver(serviceStatusReceiver);
                Log.d(TAG, "Service status BroadcastReceiver unregistered cleanly.");
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Receiver was not registered or failed to unregister.", e);
            }
        }
    }

    /**
     * Checks the status of the VoiceRecognitionService and updates the UI elements accordingly.
     */
    private void updateServiceStatusUI() {
        if (isServiceRunning()) {
            binding.textServiceStatus.setText(R.string.home_status_listening);
            binding.buttonToggleService.setText(R.string.home_stop_service_button);
        } else {
            binding.textServiceStatus.setText(R.string.home_status_stopped);
            binding.buttonToggleService.setText(R.string.home_start_service_button);
        }
    }

    /**
     * Checks the user's profile status from the dynamic secondary Firestore database and updates the UI.
     */
    private void updateVerificationStatusUI() {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser != null) {
            // User is logged in, check if name and phone setup are completed on the circle database
            try {
                FirebaseFirestore.getInstance(FirebaseApp.getInstance("safe_voice_circle"))
                        .collection("users")
                        .document(currentUser.getUid())
                        .get()
                        .addOnCompleteListener(new OnCompleteListener<DocumentSnapshot>() {
                            @Override
                            public void onComplete(@NonNull Task<DocumentSnapshot> task) {
                                // FIX: Safety null guard check to avoid crash if fragment view is destroyed
                                if (binding == null) {
                                    return;
                                }

                                if (task.isSuccessful() && task.getResult() != null) {
                                    DocumentSnapshot document = task.getResult();
                                    String verifiedName = document.getString("verifiedName");
                                    String phoneNumber = document.getString("phoneNumber");

                                    boolean isProfileComplete = (verifiedName != null && !verifiedName.isEmpty()) &&
                                            (phoneNumber != null && !phoneNumber.isEmpty());

                                    if (isProfileComplete) {
                                        // Profile is complete, show the user's name and hide the action button
                                        String statusText = "Profile Complete: " + verifiedName;
                                        binding.textVerificationStatus.setText(statusText);
                                        binding.buttonVerifyIdentity.setVisibility(View.GONE);
                                    } else {
                                        // Profile is incomplete on the dynamic database
                                        binding.textVerificationStatus.setText("Profile Incomplete. Please set your Phone Number.");
                                        binding.buttonVerifyIdentity.setVisibility(View.VISIBLE);
                                        binding.buttonVerifyIdentity.setText("Complete Profile");
                                    }
                                } else {
                                    // Error fetching document or document doesn't exist
                                    Log.w(TAG, "Failed to fetch user document.", task.getException());
                                    binding.textVerificationStatus.setText("Profile Incomplete. Please set your Phone Number.");
                                    binding.buttonVerifyIdentity.setVisibility(View.VISIBLE);
                                    binding.buttonVerifyIdentity.setText("Complete Profile");
                                }
                            }
                        });
            } catch (IllegalStateException e) {
                Log.e(TAG, "Secondary safe_voice_circle app is not initialized yet.", e);
                binding.textVerificationStatus.setText("Database Configuration Required.");
                binding.buttonVerifyIdentity.setVisibility(View.GONE);
            }
        } else {
            // No user is logged in. Show the default incomplete/not signed in state
            binding.textVerificationStatus.setText("Not Signed In");
            binding.buttonVerifyIdentity.setVisibility(View.VISIBLE);
            binding.buttonVerifyIdentity.setText("Sign In");
        }
    }

    /**
     * Starts the background voice recognition service.
     */
    private void startVoiceService() {
        if (getActivity() != null) {
            Intent serviceIntent = new Intent(getActivity(), VoiceRecognitionService.class);
            getActivity().startService(serviceIntent);
        }
    }

    /**
     * Stops the background voice recognition service.
     */
    private void stopVoiceService() {
        if (getActivity() != null) {
            Intent serviceIntent = new Intent(getActivity(), VoiceRecognitionService.class);
            getActivity().stopService(serviceIntent);
        }
    }

    /**
     * A simple method to check if the service is running.
     * This relies on a static variable in the service class itself.
     *
     * @return true if the service is currently running, false otherwise.
     */
    private boolean isServiceRunning() {
        return VoiceRecognitionService.isServiceRunning;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Clean up the binding object to prevent memory leaks.
        binding = null;
    }
}