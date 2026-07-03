package com.safevoice.app;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.safevoice.app.databinding.ActivityLoginBinding;
import com.safevoice.app.utils.CentralConfig;
import com.safevoice.app.utils.EncryptionHelper;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles Gmail sign-in via the central Auth Gateway.
 * Automatically synchronizes and authenticates the user on the secondary circle database
 * using their verified identity and a cryptographically computed secure password.
 */
public class LoginActivity extends AppCompatActivity {

    private static final String TAG = "LoginActivity";
    private ActivityLoginBinding binding;
    private GoogleSignInClient mGoogleSignInClient;
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;

    private final ActivityResultLauncher<Intent> signInLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(result.getData());
                    try {
                        GoogleSignInAccount account = task.getResult(ApiException.class);
                        firebaseAuthWithGoogle(account);
                    } catch (ApiException e) {
                        Log.w(TAG, "Google sign in failed", e);
                        Toast.makeText(this, "Google Sign-In Failed.", Toast.LENGTH_SHORT).show();
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Default mAuth operates on the central [DEFAULT] app instance
        mAuth = FirebaseAuth.getInstance();

        // Redirect Firestore operations to the custom secondary named database instance
        try {
            db = FirebaseFirestore.getInstance(FirebaseApp.getInstance("safe_voice_circle"));
        } catch (IllegalStateException e) {
            Log.e(TAG, "Secondary safe_voice_circle not initialized yet. Falling back to default Firestore.", e);
            db = FirebaseFirestore.getInstance();
        }

        // Configure Google Sign-In using CentralConfig's Web Client ID
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(CentralConfig.WEB_CLIENT_ID)
                .requestEmail()
                .build();

        mGoogleSignInClient = GoogleSignIn.getClient(this, gso);

        binding.signInButton.setOnClickListener(v -> signIn());
    }

    private void signIn() {
        Intent signInIntent = mGoogleSignInClient.getSignInIntent();
        signInLauncher.launch(signInIntent);
    }

    private void firebaseAuthWithGoogle(GoogleSignInAccount account) {
        AuthCredential credential = GoogleAuthProvider.getCredential(account.getIdToken(), null);
        mAuth.signInWithCredential(credential)
                .addOnCompleteListener(this, new OnCompleteListener<AuthResult>() {
                    @Override
                    public void onComplete(@NonNull Task<AuthResult> task) {
                        if (task.isSuccessful()) {
                            Log.d(TAG, "signInWithCredential:success");
                            FirebaseUser user = mAuth.getCurrentUser();
                            if (user != null) {
                                // Execute programmatic silent secondary authentication
                                authenticateOnSecondaryApp(user);
                            } else {
                                finish();
                            }
                        } else {
                            Log.w(TAG, "signInWithCredential:failure", task.getException());
                            Toast.makeText(LoginActivity.this, "Firebase Authentication Failed.", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    /**
     * Silently authenticates the user on the secondary circle database using a mathematically
     * computed secure password based on their central credentials.
     */
    private void authenticateOnSecondaryApp(FirebaseUser centralUser) {
        try {
            FirebaseApp circleApp = FirebaseApp.getInstance("safe_voice_circle");
            FirebaseAuth circleAuth = FirebaseAuth.getInstance(circleApp);

            String email = centralUser.getEmail();
            String googleUid = centralUser.getUid();

            if (email == null || email.isEmpty()) {
                Log.e(TAG, "Email is missing from central Google account.");
                Toast.makeText(this, "Authentication failed. Google Email is required.", Toast.LENGTH_LONG).show();
                mAuth.signOut();
                return;
            }

            // Derive the secure secondary password cryptographically
            String securePassword = EncryptionHelper.getInstance(this).calculateSecurePassword(email, googleUid);

            // Attempt silent login on the secondary Firebase project
            circleAuth.signInWithEmailAndPassword(email, securePassword)
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful()) {
                            Log.d(TAG, "Secondary silent login successful.");
                            checkSecondaryFirestoreUser(circleAuth.getCurrentUser(), centralUser.getDisplayName());
                        } else {
                            // If user does not exist on the secondary database yet, register them silently
                            Log.d(TAG, "Secondary account does not exist. Attempting silent registration.");
                            circleAuth.createUserWithEmailAndPassword(email, securePassword)
                                    .addOnCompleteListener(regTask -> {
                                        if (regTask.isSuccessful()) {
                                            Log.d(TAG, "Secondary silent registration successful.");
                                            checkSecondaryFirestoreUser(circleAuth.getCurrentUser(), centralUser.getDisplayName());
                                        } else {
                                            Log.e(TAG, "Secondary registration failed.", regTask.getException());
                                            Toast.makeText(LoginActivity.this, "Failed to connect to dynamic database.", Toast.LENGTH_SHORT).show();
                                            mAuth.signOut();
                                        }
                                    });
                        }
                    });

        } catch (Exception e) {
            Log.e(TAG, "Error executing secondary authentication.", e);
            Toast.makeText(this, "Configuration mismatch. Please setup configuration again.", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Verifies the presence of the user document inside the secondary circle Firestore database.
     */
    private void checkSecondaryFirestoreUser(FirebaseUser secondaryUser, String displayName) {
        if (secondaryUser == null) return;

        DocumentReference userDocRef = db.collection("users").document(secondaryUser.getUid());

        userDocRef.get().addOnCompleteListener(new OnCompleteListener<DocumentSnapshot>() {
            @Override
            public void onComplete(@NonNull Task<DocumentSnapshot> snapshotTask) {
                if (snapshotTask.isSuccessful()) {
                    DocumentSnapshot document = snapshotTask.getResult();
                    if (!document.exists()) {
                        Log.d(TAG, "New user on secondary DB. Creating profile document in Firestore.");
                        Map<String, Object> userData = new HashMap<>();
                        userData.put("email", secondaryUser.getEmail());
                        userData.put("uid", secondaryUser.getUid());
                        if (displayName != null && !displayName.isEmpty()) {
                            userData.put("verifiedName", displayName);
                        }

                        userDocRef.set(userData)
                                .addOnSuccessListener(aVoid -> {
                                    Log.d(TAG, "User profile successfully created on secondary DB.");
                                    Toast.makeText(LoginActivity.this, "Sign-In Successful.", Toast.LENGTH_SHORT).show();
                                    finish();
                                })
                                .addOnFailureListener(e -> {
                                    Log.w(TAG, "Error creating user profile on secondary DB.", e);
                                    Toast.makeText(LoginActivity.this, "Sign-In Successful, profile creation delayed.", Toast.LENGTH_SHORT).show();
                                    finish();
                                });
                    } else {
                        Log.d(TAG, "Existing user on secondary DB. Profile already exists.");
                        Toast.makeText(LoginActivity.this, "Sign-In Successful.", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                } else {
                    Log.w(TAG, "Failed to check for user document on secondary DB.", snapshotTask.getException());
                    Toast.makeText(LoginActivity.this, "Sign-In Successful.", Toast.LENGTH_SHORT).show();
                    finish();
                }
            }
        });
    }
}