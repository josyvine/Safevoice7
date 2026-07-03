package com.safevoice.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.google.firebase.auth.FirebaseAuth;
import com.safevoice.app.databinding.ActivityMainBinding;
import com.safevoice.app.services.VoiceRecognitionService;
import com.safevoice.app.utils.EncryptionHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The main screen of the application, which hosts the bottom navigation and various fragments.
 * It is responsible for requesting all necessary runtime permissions upon creation.
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private NavController navController;

    private ActivityResultLauncher<String[]> permissionLauncher;

    private final String[] requiredPermissions = new String[]{
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CAMERA,
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ? Manifest.permission.POST_NOTIFICATIONS : null
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Verify that the dynamic safety circle configuration is complete
        if (!EncryptionHelper.getInstance(this).isSetupDone()) {
            Intent intent = new Intent(this, CircleSetupActivity.class);
            startActivity(intent);
            finish();
            return;
        }

        // 2. Ensure that a valid user session is authenticated before displaying main UI components
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            Intent intent = new Intent(this, LoginActivity.class);
            startActivity(intent);
            finish();
            return;
        }

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment_activity_main);
        if (navHostFragment != null) {
            navController = navHostFragment.getNavController();
            NavigationUI.setupWithNavController(binding.navView, navController);
        }

        permissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                new ActivityResultCallback<Map<String, Boolean>>() {
                    @Override
                    public void onActivityResult(Map<String, Boolean> results) {
                        boolean allGranted = true;
                        for (Boolean granted : results.values()) {
                            if (!granted) {
                                allGranted = false;
                                break;
                            }
                        }

                        if (allGranted) {
                            Toast.makeText(MainActivity.this, "All permissions granted. Safe Voice is ready.", Toast.LENGTH_SHORT).show();
                            // Automatically start the listening service once permissions are granted.
                            startVoiceService();
                        } else {
                            Toast.makeText(MainActivity.this, "Some permissions were denied. Core features may not work.", Toast.LENGTH_LONG).show();
                        }
                    }
                });

        checkAndRequestPermissions();
    }

    /**
     * Checks which of the required permissions are not yet granted and requests them.
     */
    private void checkAndRequestPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();
        boolean allPermissionsAlreadyGranted = true;
        for (String permission : requiredPermissions) {
            if (permission != null && ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
                allPermissionsAlreadyGranted = false;
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toArray(new String[0]));
        }

        // If all permissions were already granted from a previous launch,
        // start the service immediately without asking again.
        if (allPermissionsAlreadyGranted) {
            startVoiceService();
        }
    }

    /**
     * Helper method to start the background voice recognition service.
     */
    private void startVoiceService() {
        // We only start the service if it's not already running.
        if (!VoiceRecognitionService.isServiceRunning) {
            Intent serviceIntent = new Intent(this, VoiceRecognitionService.class);
            startService(serviceIntent);
        }
    }
}