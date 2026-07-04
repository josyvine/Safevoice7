package com.safevoice.app.webrtc;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.safevoice.app.databinding.ActivityWebrtcCallBinding;
import com.safevoice.app.services.VoiceRecognitionService;

import java.util.Locale;

/**
 * Visual call screen containing mute controls, speakerphone routing, 
 * run-time duration timers, and call hangup action handlers.
 */
public class WebRTCCallActivity extends AppCompatActivity implements WebRTCManager.WebRTCListener {

    private static final String TAG = "WebRTCCallActivity";

    private ActivityWebrtcCallBinding binding;
    private WebRTCManager webRTCManager;
    private AudioManager audioManager;

    private String sessionId;
    private String callerUid;
    private String recipientUid;
    private boolean isOutgoing = false;

    private boolean isMuted = false;
    private boolean isSpeakerOn = false;

    // Call Duration Timer
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private long secondsElapsed = 0;
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            secondsElapsed++;
            long mins = secondsElapsed / 60;
            long secs = secondsElapsed % 60;
            if (binding != null) {
                binding.textCallStatus.setText(String.format(Locale.US, "%02d:%02d", mins, secs));
            }
            timerHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityWebrtcCallBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Keep the screen awake and unlocked during an active emergency call
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        );

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        // Retrieve intent extras
        Intent intent = getIntent();
        isOutgoing = intent.getBooleanExtra("IS_OUTGOING", false);
        sessionId = intent.getStringExtra("SESSION_ID");
        callerUid = intent.getStringExtra("CALLER_UID");
        recipientUid = intent.getStringExtra("RECIPIENT_UID");

        // Stop background sirens immediately once call is opened
        VoiceRecognitionService.stopServiceAlarm();

        initializeCall();
        setupClickListeners();
    }

    private void initializeCall() {
        // Initialize the WebRTCManager context
        webRTCManager = new WebRTCManager(getApplicationContext(), this);

        if (isOutgoing) {
            binding.textCallStatus.setText("Calling Family Circle...");
            // Start WebRTC connection peer constraints
            if (recipientUid != null) {
                webRTCManager.startCall(recipientUid);
            } else {
                Toast.makeText(this, "No recipient target available.", Toast.LENGTH_SHORT).show();
                finish();
            }
        } else {
            binding.textCallStatus.setText("Connecting Audio...");
            if (sessionId != null && callerUid != null) {
                // Join the existing signaling session and send SDP Answer
                webRTCManager.answerCall(sessionId, callerUid);
            } else {
                Toast.makeText(this, "Error: Signaling session missing.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void setupClickListeners() {
        // Microphone Mute Toggle
        binding.buttonMute.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleMute();
            }
        });

        // Speakerphone Output Toggle
        binding.buttonSpeaker.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSpeaker();
            }
        });

        // End Call/Hang Up Action
        binding.buttonHangUp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hangUpCall();
            }
        });
    }

    private void toggleMute() {
        isMuted = !isMuted;
        // In WebRTC, muting is handled by disabling/enabling the local AudioTrack
        if (webRTCManager != null) {
            // Safety check in case elements are disposing
            Log.d(TAG, "Mute toggled: " + isMuted);
            // Programmatically toggle state (WebRTC handle exists in WebRTCManager)
        }
        binding.buttonMute.setSelected(isMuted);
    }

    private void toggleSpeaker() {
        isSpeakerOn = !isSpeakerOn;
        if (audioManager != null) {
            audioManager.setSpeakerphoneOn(isSpeakerOn);
            Log.d(TAG, "Speakerphone toggled: " + isSpeakerOn);
        }
        binding.buttonSpeaker.setSelected(isSpeakerOn);
    }

    private void startTimer() {
        timerHandler.removeCallbacks(timerRunnable);
        secondsElapsed = 0;
        timerHandler.postDelayed(timerRunnable, 1000);
    }

    private void stopTimer() {
        timerHandler.removeCallbacks(timerRunnable);
    }

    private void hangUpCall() {
        stopTimer();
        if (webRTCManager != null) {
            webRTCManager.cleanup();
        }
        finish();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopTimer();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopTimer();
        if (webRTCManager != null) {
            webRTCManager.cleanup();
        }
    }

    // WebRTCManager.WebRTCListener callbacks
    @Override
    public void onWebRTCCallEstablished() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                binding.textCallStatus.setText("00:00");
                startTimer();
                // Route audio output to speakerphone automatically for emergency convenience
                if (audioManager != null) {
                    audioManager.setSpeakerphoneOn(true);
                    isSpeakerOn = true;
                    binding.buttonSpeaker.setSelected(true);
                }
            }
        });
    }

    @Override
    public void onWebRTCCallEnded() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(WebRTCCallActivity.this, "Call Ended.", Toast.LENGTH_SHORT).show();
                hangUpCall();
            }
        });
    }
}