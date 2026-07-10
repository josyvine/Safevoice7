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
import android.provider.Settings;
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
import com.safevoice.app.utils.DiagnosticLogger;

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

    // FIX FOR GLITCH 4: Broadcast action to notify the UI when service running state changes
    public static final String ACTION_SERVICE_STATUS_CHANGED = "com.safevoice.app.ACTION_SERVICE_STATUS_CHANGED";

    private SpeechRecognizer speechRecognizer;
    private Intent speechRecognizerIntent;

    // Track active service instance context to allow static helper control calls
    private static VoiceRecognitionService instance = null;

    // A public static flag to allow UI components (like HomeFragment) to check if the service is active [3].
    public static boolean isServiceRunning = false;
    public static boolean isPaused = false;

    // Real-Time signaling references
    private DatabaseReference alertsReference;
    private ValueEventListener alertsListener;
    private static Ringtone serviceRingtone;

    @Override
    public void onCreate() {
        super.onCreate();
        isServiceRunning = true;
        instance = this;
        sendStatusBroadcast(); // FIX FOR GLITCH 4: Broadcast live running status state to HomeFragment
        DiagnosticLogger.logInfo(TAG, "Service onCreate() invoked. Continuous listening loop starting.");

        try {
            // Initialize the SpeechRecognizer
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new VoiceRecognitionListener());

            // Set up the intent for the speech recognizer
            speechRecognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        } catch (Exception e) {
            DiagnosticLogger.logError(TAG, "Failed to initialize standard system SpeechRecognizer hardware.", e);
        }

        // Start listening to private Realtime Database signaling alerts in real-time
        listenForIncomingAlerts();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        DiagnosticLogger.logInfo(TAG, "Service onStartCommand() invoked with startId: " + startId);

        // Start the service in the foreground [3]
        startForeground(NOTIFICATION_ID, createNotification());
        DiagnosticLogger.logInfo(TAG, "Service transitioned cleanly to Foreground Mode with notification ID: " + NOTIFICATION_ID);

        // Start listening
        startListening();

        // If the service is killed, it will be automatically restarted [3].
        return START_STICKY;
    }

    /**
     * Instructs the voice recognition loop to stop capturing microphone audio and pause its execution.
     */
    public static void pauseListening() {
        isPaused = true;
        DiagnosticLogger.logInfo(TAG, "pauseListening() invoked. Halting continuous SpeechRecognizer to release audio hardware.");
        if (instance != null) {
            instance.stopSpeechRecognizer();
        }
    }

    /**
     * Commands the continuous voice recognition loop to resume.
     */
    public static void resumeListening() {
        isPaused = false;
        DiagnosticLogger.logInfo(TAG, "resumeListening() invoked. Restarting continuous voice loop.");
        if (instance != null) {
            instance.startListening();
        }
    }

    /**
     * Safely cancels system-level background recordings to free the hardware resource.
     */
    private void stopSpeechRecognizer() {
        if (speechRecognizer != null) {
            try {
                speechRecognizer.cancel();
                DiagnosticLogger.logInfo(TAG, "SpeechRecognizer cancel() executed successfully.");
            } catch (Exception e) {
                DiagnosticLogger.logError(TAG, "Exception encountered during SpeechRecognizer.cancel()", e);
            }
        }
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
                DiagnosticLogger.logWarn(TAG, "No authenticated user session found on safe_voice_circle app. Alert listener postponed.");
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

                        DiagnosticLogger.logInfo(TAG, "Incoming Realtime Alert change detected. Raw snapshot: " + snapshot.getValue());

                        if (callerUid != null) {
                            DiagnosticLogger.logInfo(TAG, "Active Realtime Alert processed. Source caller: " + callerName + " (" + callerUid + ")");

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
                                DiagnosticLogger.logInfo(TAG, "Screen wakeup wake-lock acquired and released successfully.");
                            }

                            // 2. Play the loud siren alarm tone
                            playServiceAlarmSound();

                            // 3. Launch the full-screen EmergencyPopupActivity via secure Notification Intent
                            showEmergencyNotificationAndPopup(callerName, callerUid, sessionId, location);

                            // 4. Delete the database node immediately to consume the alert and prevent looping
                            DiagnosticLogger.logInfo(TAG, "Consuming alert node to prevent notification looping.");
                            snapshot.getRef().removeValue()
                                    .addOnSuccessListener(aVoid -> DiagnosticLogger.logInfo(TAG, "Alert node consumed and deleted from Realtime Database."))
                                    .addOnFailureListener(e -> DiagnosticLogger.logError(TAG, "Failed to delete consumed alert node from Realtime Database.", e));
                        }
                    }
                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    DiagnosticLogger.logError(TAG, "Realtime Alerts listener cancelled by database server.", error.toException());
                }
            };

            alertsReference.addValueEventListener(alertsListener);
            DiagnosticLogger.logInfo(TAG, "Attached Realtime Alert listener for UID: " + currentUser.getUid());

        } catch (Exception e) {
            DiagnosticLogger.logError(TAG, "Fatal error initializing Realtime Database Alert listener.", e);
        }
    }

    /**
     * Uses a high-priority system channel to launch the incoming Emergency overlay.
     * This bypasses Android 10+ background activity restrictions securely.
     */
    private void showEmergencyNotificationAndPopup(String callerName, String callerUid, String sessionId, String location) {
        String emergencyChannelId = "EmergencyAlertChannel";
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    emergencyChannelId,
                    "Emergency Alerts",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("High-priority alerts for Safe Voice emergencies");
            channel.enableVibration(true);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }

        Intent popupIntent = new Intent(this, EmergencyPopupActivity.class);
        popupIntent.putExtra(EmergencyPopupActivity.EXTRA_CALLER_NAME, callerName);
        popupIntent.putExtra(EmergencyPopupActivity.EXTRA_CALLER_UID, callerUid);
        popupIntent.putExtra(EmergencyPopupActivity.EXTRA_SESSION_ID, sessionId);
        popupIntent.putExtra(EmergencyPopupActivity.EXTRA_LOCATION, location);
        
        int uniqueId = (callerUid != null) ? callerUid.hashCode() : (int) System.currentTimeMillis();

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                uniqueId,
                popupIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, emergencyChannelId)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("EMERGENCY ALERT")
                .setContentText(callerName + " has triggered a Safe Voice alert!")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setFullScreenIntent(pendingIntent, true) // This bypasses OS-level background locks
                .setContentIntent(pendingIntent);

        if (notificationManager != null) {
            notificationManager.notify(2, notificationBuilder.build());
            DiagnosticLogger.logInfo(TAG, "Dispatched full-screen intent high-priority notification.");
        }

        // Direct launch using SYSTEM_ALERT_WINDOW overlay permission to guarantee instant launch without tapping
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            try {
                popupIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(popupIntent);
                DiagnosticLogger.logInfo(TAG, "Launched EmergencyPopupActivity directly via overlay permission (zero-touch).");
            } catch (Exception e) {
                DiagnosticLogger.logError(TAG, "Failed direct overlay activity start fallback.", e);
            }
        } else {
            // Direct launch fallback for older Android versions or fallback if overlay is missing
            try {
                popupIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(popupIntent);
                DiagnosticLogger.logInfo(TAG, "Direct launch initiated.");
            } catch (Exception e) {
                DiagnosticLogger.logWarn(TAG, "Direct launch restricted by OS settings. Relying on full-screen intent notification.");
            }
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
                DiagnosticLogger.logInfo(TAG, "Loud system emergency alarm siren started playing.");
            }
        } catch (Exception e) {
            DiagnosticLogger.logError(TAG, "Failed to play default system alarm/siren audio.", e);
        }
    }

    /**
     * Public static accessor allowing EmergencyPopupActivity to terminate the siren on stop.
     */
    public static void stopServiceAlarm() {
        try {
            if (serviceRingtone != null && serviceRingtone.isPlaying()) {
                serviceRingtone.stop();
                DiagnosticLogger.logInfo(TAG, "Loud system emergency alarm siren stopped manually.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to manually terminate playing alarm siren audio.", e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isServiceRunning = false;
        instance = null;
        sendStatusBroadcast(); // FIX FOR GLITCH 4: Broadcast updated stopped state to update UI elements
        DiagnosticLogger.logInfo(TAG, "Service onDestroy() invoked. Stopping all background listening routines.");
        
        // Remove the real-time database listener to prevent memory leaks on destroy
        if (alertsReference != null && alertsListener != null) {
            alertsReference.removeEventListener(alertsListener);
            DiagnosticLogger.logInfo(TAG, "Detached Realtime Database alerts listener successfully.");
        }
        
        stopServiceAlarm();

        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
            speechRecognizer.destroy();
            DiagnosticLogger.logInfo(TAG, "System SpeechRecognizer destroyed cleanly.");
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
        if (isPaused) {
            DiagnosticLogger.logInfo(TAG, "startListening skipped because continuous voice recognition is paused.");
            return;
        }

        if (speechRecognizer != null) {
            try {
                speechRecognizer.startListening(speechRecognizerIntent);
                DiagnosticLogger.logInfo(TAG, "SpeechRecognizer started listening on context.");
            } catch (Exception e) {
                DiagnosticLogger.logError(TAG, "Failed to invoke startListening on SpeechRecognizer hardware.", e);
            }
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

    // FIX FOR GLITCH 4: Broadcast helper method to dispatch service state updates
    private void sendStatusBroadcast() {
        try {
            Intent intent = new Intent(ACTION_SERVICE_STATUS_CHANGED);
            sendBroadcast(intent);
            Log.d(TAG, "Service running status broadcast dispatched successfully.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to dispatch service status broadcast.", e);
        }
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
                    DiagnosticLogger.logInfo(TAG, "Recognizer captured utterance matching text: '" + result + "'");
                    // Check if the recognized text contains the trigger phrase (case-insensitive) [3]
                    if (result.toLowerCase().contains("help help")) {
                        DiagnosticLogger.logInfo(TAG, "TRIGGER PHRASE 'HELP HELP' POSITIVELY DETECTED! INITIATING EMERGENCY SEQUENCE.");

                        // Launch the EmergencyHandlerService to handle the alert
                        Intent emergencyIntent = new Intent(VoiceRecognitionService.this, EmergencyHandlerService.class);
                        startService(emergencyIntent);

                        // FIX FOR GLITCH 3: Do not stop the entire service context (remove stopSelf()).
                        // Call pauseListening() to suspend continuous recording and release the hardware microphone,
                        // allowing the caller activity to cleanly resume the loop later.
                        pauseListening();
                        return; // Exit the loop and method
                    }
                }
            }
            // If the trigger phrase was not detected, restart listening for the next utterance unless paused.
            if (!isPaused) {
                startListening();
            }
        }

        @Override
        public void onError(int error) {
            // Most errors are normal (e.g., no speech detected). We just restart the listener unless paused.
            Log.d(TAG, "Speech recognizer error: " + error);
            if (!isPaused) {
                startListening();
            }
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