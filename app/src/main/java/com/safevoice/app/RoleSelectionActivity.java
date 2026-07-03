package com.safevoice.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.safevoice.app.databinding.ActivityRoleSelectionBinding;
import com.safevoice.app.utils.EncryptionHelper;

/**
 * Handles the selection of the user's role on the first-time application launch.
 * The selected role is saved securely using local encrypted preferences.
 */
public class RoleSelectionActivity extends AppCompatActivity {

    private ActivityRoleSelectionBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityRoleSelectionBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Event listener for creating a private safety network (Circle Creator)
        binding.btnRoleCreator.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectRole("creator");
            }
        });

        // Event listener for joining an existing private safety network (Circle Member)
        binding.btnRoleMember.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectRole("member");
            }
        });
    }

    /**
     * Saves the chosen role and routes the user based on their access permissions.
     * Creators upload a JSON file, while Members scan a setup QR code directly.
     *
     * @param role The role string ("creator" or "member").
     */
    private void selectRole(String role) {
        // Save the chosen role in secure preferences
        EncryptionHelper.getInstance(this).saveUserRole(role);

        Intent intent;
        if ("creator".equals(role)) {
            // Circle Creators must configure their dynamic Firebase project via JSON upload
            intent = new Intent(RoleSelectionActivity.this, CircleSetupActivity.class);
        } else {
            // Circle Members bypass JSON handling and scan the Creator's invite QR code directly
            intent = new Intent(RoleSelectionActivity.this, CircleJoinQrScanActivity.class);
        }
        
        startActivity(intent);

        // Terminate the selection activity so users cannot backtrack easily
        finish();
    }
}