package com.safevoice.app.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.safevoice.app.EmergencyPopupActivity;
import com.safevoice.app.MainActivity;
import com.safevoice.app.R;

import java.util.ArrayList;
import java.util.Locale;

/**
 * A foreground service that continuously listens for the voice trigger "Help Help".
 * It uses Android's built-in SpeechRecognizer. To achieve continuous listening,
 * it restarts the recognizer every time it stops (either on a result or an error) [3].
 *
 * Real-Time Alert Extension:
 * - Dynamically listens to the private database's "/alerts/{uid}" path.
 * - Handles screen wakeup, alarm tone activation, and launching emergency popup overlays.
 */
public class VoiceRecognitionService extends Service {

    private static final String TAG = "VoiceRecognitionService";
    private static final String CHANNEL_ID = "VoiceRecognitionChannel";
    private static final int NOTIFICATION_ID = 1;

    private SpeechRecognizer speechRecognizer;
    private Intent speechRecognizerIntent;

    // A public static flag to allow UI components (like HomeFragment) to check if the service is active [3].
    public static boolean isServiceRunning = false;

    // Real-Time signaling references
    private DatabaseReference alertsReference;
    private ValueEventListener alertsListener;
    private static Ringtone serviceRingtone;

    @Override
    public void onCreate() {
        super.onCreate();
        isServiceRunning = true;

        // Initialize the SpeechRecognizer
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new VoiceRecognitionListener());

        // Set up the intent for the speech recognizer
        speechRecognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);

        // Start listening to private Realtime Database signaling alerts in real-time
        listenForIncomingAlerts();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Start the service in the foreground [3]
        startForeground(NOTIFICATION_ID, createNotification());
        Log.d(TAG, "Service started and is now in the foreground.");

        // Start listening
        startListening();

        // If the service is killed, it will be automatically restarted [3].
        return START_STICKY;
    }

    /**
     * Attaches an active Realtime Database listener to the user's specific alert node.
     * Triggers popup screens and sirens instantly upon receiving a custom alert.
     */
    private void listenForIncomingAlerts() {
        try {
            FirebaseApp circleApp = FirebaseApp.getInstance("safe_voice_circle");
            FirebaseUser currentUser = FirebaseAuth.getInstance(circleApp).getCurrentUser();
            
            if (currentUser == null) {
                Log.w(TAG, "No authenticated user session on safe_voice_circle. Alert listener postponed.");
                return;
            }

            FirebaseDatabase rtdb = FirebaseDatabase.getInstance(circleApp);
            alertsReference = rtdb.getReference("alerts").child(currentUser.getUid());

            alertsListener = new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    if (snapshot.exists()) {
                        String callerName = snapshot.child("callerName").getValue(String.class);
                        String callerUid = snapshot.child("callerUid").getValue(String.class);
                        String sessionId = snapshot.child("sessionId").getValue(String.class);
                        String location = snapshot.child("location").getValue(String.class);

                        if (callerUid != null) {
                            Log.i(TAG, "Incoming Realtime Alert detected from: " + callerName);

                            // 1. Wake up the device screen securely
                            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                            if (pm != null) {
                                PowerManager.WakeLock wakeLock = pm.newWakeLock(
                                        PowerManager.FULL_WAKE_LOCK |
                                        PowerManager.ACQUIRE_CAUSES_WAKEUP |
                                        PowerManager.ON_AFTER_RELEASE,
                                        "SafeVoice:EmergencyWakeLock"
                                );
                                wakeLock.acquire(10 * 60 * 1000L /* 10 minutes */);
                                wakeLock.release();
                            }

                            // 2. Play the loud siren alarm tone
                            playServiceAlarmSound();

                            // 3. Launch the full-screen EmergencyPopupActivity
                            Intent popupIntent = new Intent(VoiceRecognitionService.this, EmergencyPopupActivity.class);
                            popupIntent.putExtra(EmergencyPopupActivity.EXTRA_CALLER_NAME, callerName);
                            popupIntent.putExtra(EmergencyPopupActivity.EXTRA_CALLER_UID, callerUid);
                            popupIntent.putExtra(EmergencyPopupActivity.EXTRA_SESSION_ID, sessionId);
                            popupIntent.putExtra(EmergencyPopupActivity.EXTRA_LOCATION, location);
                            popupIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(popupIntent);

                            // 4. Delete the database node immediately to consume the alert and prevent looping
                            snapshot.getRef().removeValue();
                        }
                    }
                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    Log.e(TAG, "Realtime Alerts listener cancelled.", error.toException());
                }
            };

            alertsReference.addValueEventListener(alertsListener);
            Log.d(TAG, "Attached Realtime Alert listener for UID: " + currentUser.getUid());

        } catch (Exception e) {
            Log.e(TAG, "Error initializing Realtime Alert listener", e);
        }
    }

    /**
     * Plays the default system alarm/siren tone.
     */
    private void playServiceAlarmSound() {
        try {
            if (serviceRingtone != null && serviceRingtone.isPlaying()) {
                serviceRingtone.stop();
            }
            Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmSound == null) {
                alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            }
            serviceRingtone = RingtoneManager.getRingtone(getApplicationContext(), alarmSound);
            if (serviceRingtone != null) {
                serviceRingtone.play();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error playing service alarm sound", e);
        }
    }

    /**
     * Public static accessor allowing EmergencyPopupActivity to terminate the siren on stop.
     */
    public static void stopServiceAlarm() {
        if (serviceRingtone != null && serviceRingtone.isPlaying()) {
            serviceRingtone.stop();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isServiceRunning = false;
        
        // Remove the real-time database listener to prevent memory leaks on destroy
        if (alertsReference != null && alertsListener != null) {
            alertsReference.removeEventListener(alertsListener);
        }
        
        stopServiceAlarm();

        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
            speechRecognizer.destroy();
        }
        Log.d(TAG, "Service destroyed.");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        // This is a started service, not a bound one, so we return null.
        return null;
    }

    private void startListening() {
        if (speechRecognizer != null) {
            speechRecognizer.startListening(speechRecognizerIntent);
            Log.d(TAG, "Speech recognizer started listening...");
        }
    }

    /**
     * Creates the persistent notification required for a foreground service [3].
     *
     * @return The Notification object.
     */
    private Notification createNotification() {
        // Create a notification channel for Android Oreo and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Safe Voice Active Service",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }

        // Create an intent that will open MainActivity when the notification is tapped [3]
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        // Build the notification [3]
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Safe Voice is Active")
                .setContentText("Listening for your voice trigger...")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .build();
    }

    /**
     * The core RecognitionListener that handles speech-to-text results and errors.
     */
    private class VoiceRecognitionListener implements RecognitionListener {

        @Override
        public void onResults(Bundle results) {
            ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches != null) {
                for (String result : matches) {
                    Log.d(TAG, "Heard: " + result);
                    // Check if the recognized text contains the trigger phrase (case-insensitive) [3]
                    if (result.toLowerCase().contains("help help")) {
                        Log.i(TAG, "TRIGGER PHRASE DETECTED!");

                        // Launch the EmergencyHandlerService to handle the alert
                        Intent emergencyIntent = new Intent(VoiceRecognitionService.this, EmergencyHandlerService.class);
                        startService(emergencyIntent);

                        // Stop listening after a successful trigger to prevent multiple alerts [3]
                        stopSelf();
                        return; // Exit the loop and method
                    }
                }
            }
            // If the trigger phrase was not detected, restart listening for the next utterance.
            startListening();
        }

        @Override
        public void onError(int error) {
            // Most errors are normal (e.g., no speech detected). We just restart the listener.
            Log.d(TAG, "Speech recognizer error: " + error);
            // Restart listening after any error to ensure continuity [3].
            startListening();
        }

        // --- Other listener methods (can be left empty for this implementation) ---
        @Override
        public void onReadyForSpeech(Bundle params) { Log.d(TAG, "Ready for speech..."); }
        @Override
        public void onBeginningOfSpeech() { Log.d(TAG, "Beginning of speech..."); }
        @Override
        public void onRmsChanged(float rmsdB) { /* Do nothing */ }
        @Override
        public void onBufferReceived(byte[] buffer) { /* Do nothing */ }
        @Override
        public void onEndOfSpeech() { Log.d(TAG, "End of speech."); }
        @Override
        public void onPartialResults(Bundle partialResults) { /* Do nothing */ }
        @Override
        public void onEvent(int eventType, Bundle params) { /* Do nothing */ }
    }
}