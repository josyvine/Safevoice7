package com.safevoice.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;

import com.safevoice.app.databinding.ActivityEmergencyPopupBinding;
import com.safevoice.app.services.FirebaseAlertService;
import com.safevoice.app.services.VoiceRecognitionService;
import com.safevoice.app.webrtc.WebRtcCallActivity;

/**
 * High-priority lock screen popup overlay that activates when an emergency is detected.
 * Plays the alarm siren and presents options to join the live voice call or call back via cell.
 */
public class EmergencyPopupActivity extends AppCompatActivity {

    public static final String EXTRA_CALLER_NAME = "com.safevoice.app.EXTRA_CALLER_NAME";
    public static final String EXTRA_CALLER_UID = "com.safevoice.app.EXTRA_CALLER_UID";
    public static final String EXTRA_SESSION_ID = "com.safevoice.app.EXTRA_SESSION_ID";
    public static final String EXTRA_LOCATION = "com.safevoice.app.EXTRA_LOCATION";

    private ActivityEmergencyPopupBinding binding;
    private String callerName;
    private String callerUid;
    private String sessionId;
    private String location;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityEmergencyPopupBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Code to make the activity appear over the lock screen and turn on the screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            );
        }

        // Retrieve data from the intent
        Intent intent = getIntent();
        callerName = intent.getStringExtra(EXTRA_CALLER_NAME);
        callerUid = intent.getStringExtra(EXTRA_CALLER_UID);
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID);
        location = intent.getStringExtra(EXTRA_LOCATION);

        // Populate the UI
        if (callerName != null) {
            String message = callerName + " is in an emergency!";
            binding.textEmergencyMessage.setText(message);
        }

        // Set up button listeners
        binding.buttonJoinCall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // FIX: Launch the visual in-app WebRTC calling activity to establish direct audio
                Intent callIntent = new Intent(EmergencyPopupActivity.this, WebRtcCallActivity.class);
                callIntent.putExtra("SESSION_ID", sessionId);
                callIntent.putExtra("CALLER_UID", callerUid);
                callIntent.putExtra("IS_OUTGOING", false);
                startActivity(callIntent);

                stopAndFinish();
            }
        });

        binding.buttonCallBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // TODO: You'll need the caller's phone number, which should be passed in the FCM message
                // For now, this is a placeholder. This requires getting the phone number for the callerUid.
                // String phoneNumber = getPhoneNumberForUid(callerUid);
                // if (phoneNumber != null) {
                //     Intent callIntent = new Intent(Intent.ACTION_CALL);
                //     callIntent.setData(Uri.parse("tel:" + phoneNumber));
                //     startActivity(callIntent);
                // }
                stopAndFinish();
            }
        });

        binding.buttonDismissPopup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopAndFinish();
            }
        });
    }

    private void stopAndFinish() {
        // Stop both types of alarm sirens to ensure absolute silence when dismissed
        FirebaseAlertService.stopAlarmSound();
        VoiceRecognitionService.stopServiceAlarm();
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Ensure the alarm stops if the activity is destroyed for any reason
        FirebaseAlertService.stopAlarmSound();
        VoiceRecognitionService.stopServiceAlarm();
    }
}