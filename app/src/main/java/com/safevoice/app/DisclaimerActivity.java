package com.safevoice.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.safevoice.app.databinding.ActivityDisclaimerBinding;
import com.safevoice.app.utils.EncryptionHelper;

/**
 * This is the first screen the user sees.
 * It forces the user to accept the terms of service before using the app.
 * The user's acceptance is stored in SharedPreferences. If already accepted,
 * this activity immediately forwards the user to the correct onboarding state or MainActivity.
 */
public class DisclaimerActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "SafeVoicePrefs";
    private static final String KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted";

    private ActivityDisclaimerBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Check if the disclaimer has already been accepted on a previous launch.
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean hasAccepted = prefs.getBoolean(KEY_DISCLAIMER_ACCEPTED, false);

        if (hasAccepted) {
            // If accepted, evaluate onboarding setup states first
            navigateToNextScreen();
            return; // Important to return here to stop further execution of onCreate.
        }

        // If not accepted, set up the view for the user to read and decide.
        binding = ActivityDisclaimerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Set up the click listener for the "Accept" button.
        binding.buttonAccept.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // When the user accepts, save this choice to SharedPreferences.
                SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
                editor.putBoolean(KEY_DISCLAIMER_ACCEPTED, true);
                editor.apply();

                // Proceed to onboarding flow
                navigateToNextScreen();
            }
        });

        // Set up the click listener for the "Decline" button.
        binding.buttonDecline.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // If the user declines, simply close the application.
                finish();
            }
        });
    }

    /**
     * Evaluates the local user session to route them securely.
     * Routes users to role selection, appropriate setup page, or the main application hub.
     */
    private void navigateToNextScreen() {
        EncryptionHelper encryptionHelper = EncryptionHelper.getInstance(this);
        String userRole = encryptionHelper.getUserRole();
        boolean isSetupDone = encryptionHelper.isSetupDone();

        if (userRole == null) {
            // No role selected yet -> Redirect to role selection onboarding
            Intent intent = new Intent(DisclaimerActivity.this, RoleSelectionActivity.class);
            startActivity(intent);
        } else if (!isSetupDone) {
            // Role is selected but dynamic database configuration is pending
            Intent intent;
            if ("creator".equals(userRole)) {
                // Creators set up their custom private dynamic Firebase DB by uploading JSON
                intent = new Intent(DisclaimerActivity.this, CircleSetupActivity.class);
            } else {
                // Members bypass JSON setup entirely and scan the Creator's setup QR code
                intent = new Intent(DisclaimerActivity.this, CircleJoinQrScanActivity.class);
            }
            startActivity(intent);
        } else {
            // Setup is complete -> Direct user safely to MainActivity
            Intent intent = new Intent(DisclaimerActivity.this, MainActivity.class);
            startActivity(intent);
        }
        finish();
    }
}