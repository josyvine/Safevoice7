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

        // Event listener for creating a private safety network
        binding.btnRoleCreator.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectRole("creator");
            }
        });

        // Event listener for joining an existing private safety network
        binding.btnRoleMember.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectRole("member");
            }
        });
    }

    /**
     * Saves the chosen role and routes the user to the database setup workspace.
     *
     * @param role The role string ("creator" or "member").
     */
    private void selectRole(String role) {
        // Save the chosen role in secure preferences
        EncryptionHelper.getInstance(this).saveUserRole(role);

        // Both roles must configure their target dynamic Firebase database connection first
        Intent intent = new Intent(RoleSelectionActivity.this, CircleSetupActivity.class);
        startActivity(intent);

        // Terminate the selection activity so users cannot backtrack easily
        finish();
    }
}