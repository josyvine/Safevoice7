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
import com.safevoice.app.utils.DiagnosticLogger;

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
        DiagnosticLogger.logInfo(TAG, "Activity onCreate() invoked. Preparing fullscreen WebRTC dashboard.");
        
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

        DiagnosticLogger.logInfo(TAG, "Call Intent Metadata -> Is Outgoing: " + isOutgoing + 
                ", Session ID: " + sessionId + ", Caller UID: " + callerUid + ", Recipient UID: " + recipientUid);

        // Stop background sirens immediately once call is opened
        VoiceRecognitionService.stopServiceAlarm();

        initializeCall();
        setupClickListeners();
    }

    private void initializeCall() {
        DiagnosticLogger.logInfo(TAG, "Initializing active WebRTC session manager context.");
        webRTCManager = new WebRTCManager(getApplicationContext(), this);

        if (isOutgoing) {
            binding.textCallStatus.setText("Calling Family Circle...");
            DiagnosticLogger.logInfo(TAG, "Outgoing connection request initiated for target UID: " + recipientUid);
            // Start WebRTC connection peer constraints
            if (recipientUid != null) {
                webRTCManager.startCall(recipientUid);
            } else {
                DiagnosticLogger.logError(TAG, "Failed to start call. Recipient target UID is null.", null);
                Toast.makeText(this, "No recipient target available.", Toast.LENGTH_SHORT).show();
                finish();
            }
        } else {
            binding.textCallStatus.setText("Connecting Audio...");
            DiagnosticLogger.logInfo(TAG, "Incoming connection request handshake initiated for Session ID: " + sessionId);
            if (sessionId != null && callerUid != null) {
                // Join the existing signaling session and send SDP Answer
                webRTCManager.answerCall(sessionId, callerUid);
            } else {
                DiagnosticLogger.logError(TAG, "Failed to answer call. Session metadata parameters are null.", null);
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
                // Disable calling controls immediately to prevent native thread deadlocks (Glitch 2)
                binding.buttonHangUp.setEnabled(false);
                binding.buttonMute.setEnabled(false);
                binding.buttonSpeaker.setEnabled(false);
                
                DiagnosticLogger.logInfo(TAG, "Hang Up clicked. Controls disabled. Tearing down connection.");
                hangUpCall();
            }
        });
    }

    private void toggleMute() {
        isMuted = !isMuted;
        if (audioManager != null) {
            try {
                // Apply direct system-level hardware mute/unmute
                audioManager.setMicrophoneMute(isMuted);
                DiagnosticLogger.logInfo(TAG, "Microphone toggled. Hardware mute state: " + isMuted);
            } catch (Exception e) {
                DiagnosticLogger.logError(TAG, "Failed to apply hardware microphone mute state.", e);
            }
        }
        binding.buttonMute.setSelected(isMuted);
    }

    private void toggleSpeaker() {
        isSpeakerOn = !isSpeakerOn;
        if (audioManager != null) {
            try {
                audioManager.setSpeakerphoneOn(isSpeakerOn);
                DiagnosticLogger.logInfo(TAG, "Speakerphone toggled. Hardware speakerphone state: " + isSpeakerOn);
            } catch (Exception e) {
                DiagnosticLogger.logError(TAG, "Failed to apply hardware speakerphone state.", e);
            }
        }
        binding.buttonSpeaker.setSelected(isSpeakerOn);
    }

    private void startTimer() {
        DiagnosticLogger.logInfo(TAG, "Starting call run-time duration counter.");
        timerHandler.removeCallbacks(timerRunnable);
        secondsElapsed = 0;
        timerHandler.postDelayed(timerRunnable, 1000);
    }

    private void stopTimer() {
        DiagnosticLogger.logInfo(TAG, "Stopping call run-time duration counter.");
        timerHandler.removeCallbacks(timerRunnable);
    }

    private void hangUpCall() {
        stopTimer();
        if (webRTCManager != null) {
            webRTCManager.cleanup();
            webRTCManager = null;
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
        DiagnosticLogger.logInfo(TAG, "Activity onDestroy() invoked. Safely releasing remaining active resources.");
        stopTimer();
        if (webRTCManager != null) {
            webRTCManager.cleanup();
            webRTCManager = null;
        }
    }

    // WebRTCManager.WebRTCListener callbacks
    @Override
    public void onWebRTCCallEstablished() {
        DiagnosticLogger.logInfo(TAG, "onWebRTCCallEstablished() invoked on activity context. Initializing visual UI timers.");
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                binding.textCallStatus.setText("00:00");
                startTimer();
                // Route audio output to speakerphone automatically for emergency convenience
                if (audioManager != null) {
                    try {
                        audioManager.setSpeakerphoneOn(true);
                        isSpeakerOn = true;
                        binding.buttonSpeaker.setSelected(true);
                        DiagnosticLogger.logInfo(TAG, "VoIP speakerphone auto-activated for hands-free emergency convenience.");
                    } catch (Exception e) {
                        DiagnosticLogger.logError(TAG, "Failed to auto-route audio stream to speakerphone.", e);
                    }
                }
            }
        });
    }

    @Override
    public void onWebRTCCallEnded() {
        DiagnosticLogger.logInfo(TAG, "onWebRTCCallEnded() remote signaling event received. Terminating UI.");
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(WebRTCCallActivity.this, "Call Ended.", Toast.LENGTH_SHORT).show();
                hangUpCall();
            }
        });
    }
}