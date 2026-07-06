package com.safevoice.app.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.safevoice.app.EmergencyPopupActivity;
import com.safevoice.app.R;
import com.safevoice.app.utils.DiagnosticLogger;

import java.util.Map;

/**
 * Handles incoming Firebase Cloud Messages (FCM) on-demand.
 * Listens for high-priority custom payloads, activates the device wake locks,
 * plays standard alarm siren tones, and displays the full-screen emergency overlay.
 */
public class FirebaseAlertService extends FirebaseMessagingService {

    private static final String TAG = "FirebaseAlertService";
    private static final String EMERGENCY_CHANNEL_ID = "EmergencyAlertChannel";

    private static Ringtone ringtone;

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        DiagnosticLogger.logInfo(TAG, "Incoming FCM Message received from upstream: " + remoteMessage.getFrom());

        Map<String, String> data = remoteMessage.getData();
        if (data.size() > 0) {
            DiagnosticLogger.logInfo(TAG, "FCM data payload parsed: " + data);
            String alertType = data.get("type");

            if ("emergency".equals(alertType)) {
                DiagnosticLogger.logInfo(TAG, "FCM Alert contains target type 'emergency'. Processing active hand-off.");
                handleEmergencyAlert(data);
            } else {
                DiagnosticLogger.logWarn(TAG, "FCM Alert contains unhandled payload type: " + alertType);
            }
        } else {
            DiagnosticLogger.logWarn(TAG, "Incoming FCM Message contains an empty data payload.");
        }
    }

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        DiagnosticLogger.logInfo(TAG, "Refreshed FCM token received from registration server: " + token);

        // Upload the new token to the user's dynamic custom profile in the secondary database
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser != null) {
            try {
                FirebaseApp circleApp = FirebaseApp.getInstance("safe_voice_circle");
                FirebaseFirestore.getInstance(circleApp)
                        .collection("users")
                        .document(currentUser.getUid())
                        .update("fcmToken", token)
                        .addOnSuccessListener(aVoid -> DiagnosticLogger.logInfo(TAG, "FCM Token successfully synchronized on safe_voice_circle FireStore document."))
                        .addOnFailureListener(e -> DiagnosticLogger.logError(TAG, "Failed to synchronize refreshed FCM Token on safe_voice_circle.", e));
            } catch (IllegalStateException e) {
                DiagnosticLogger.logError(TAG, "Dynamic safe_voice_circle database not initialized yet on token refresh callback.", e);
            }
        } else {
            DiagnosticLogger.logWarn(TAG, "No authenticated session. FCM Token refresh caching deferred.");
        }
    }

    private void handleEmergencyAlert(Map<String, String> data) {
        String callerName = data.get("callerName");
        String callerUid = data.get("callerUid");
        String sessionId = data.get("sessionId"); // WebRTC session ID
        String location = data.get("location");

        DiagnosticLogger.logInfo(TAG, "Active FCM emergency alerts handler launched. Sender: " + callerName + " (" + callerUid + ")");

        // Wake up the device
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK |
                    PowerManager.ACQUIRE_CAUSES_WAKEUP |
                    PowerManager.ON_AFTER_RELEASE,
                    "SafeVoice:EmergencyWakeLock"
            );
            wakeLock.acquire(10 * 60 * 1000L /*10 minutes*/);
            wakeLock.release();
            DiagnosticLogger.logInfo(TAG, "Device screen wake-lock successfully acquired and released.");
        }

        // Start playing a loud siren/alarm sound
        playAlarmSound();

        // Show the full-screen emergency pop-up
        Intent popupIntent = new Intent(this, EmergencyPopupActivity.class);
        popupIntent.putExtra(EmergencyPopupActivity.EXTRA_CALLER_NAME, callerName);
        popupIntent.putExtra(EmergencyPopupActivity.EXTRA_CALLER_UID, callerUid);
        popupIntent.putExtra(EmergencyPopupActivity.EXTRA_SESSION_ID, sessionId);
        popupIntent.putExtra(EmergencyPopupActivity.EXTRA_LOCATION, location);
        
        // Also show a high-priority notification as a fallback
        showEmergencyNotification(callerName);

        // Direct launch using SYSTEM_ALERT_WINDOW overlay permission to guarantee instant launch without tapping (zero-touch)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            try {
                popupIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(popupIntent);
                DiagnosticLogger.logInfo(TAG, "Launched EmergencyPopupActivity directly via overlay permission (zero-touch) from FCM alert.");
            } catch (Exception e) {
                DiagnosticLogger.logError(TAG, "Failed direct overlay activity start from FCM fallback.", e);
            }
        } else {
            // Direct launch fallback for older Android versions or fallback if overlay is missing
            try {
                popupIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(popupIntent);
                DiagnosticLogger.logInfo(TAG, "Direct launch initiated from FCM alert.");
            } catch (Exception e) {
                DiagnosticLogger.logWarn(TAG, "Direct launch restricted by OS settings. Relying on full-screen intent notification from FCM alert.");
            }
        }
    }

    private void showEmergencyNotification(String callerName) {
        Intent intent = new Intent(this, EmergencyPopupActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    EMERGENCY_CHANNEL_ID,
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

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, EMERGENCY_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("EMERGENCY ALERT")
                .setContentText(callerName + " has triggered a Safe Voice alert!")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setFullScreenIntent(pendingIntent, true) // Makes it a heads-up notification
                .setContentIntent(pendingIntent);

        if (notificationManager != null) {
            notificationManager.notify(1, notificationBuilder.build());
            DiagnosticLogger.logInfo(TAG, "FCM fallback high-priority notification successfully dispatched.");
        }
    }

    private void playAlarmSound() {
        try {
            if (ringtone != null && ringtone.isPlaying()) {
                ringtone.stop();
            }
            Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmSound == null) {
                alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            }
            ringtone = RingtoneManager.getRingtone(getApplicationContext(), alarmSound);
            if (ringtone != null) {
                ringtone.play();
                DiagnosticLogger.logInfo(TAG, "FCM emergency alarm siren started playing.");
            }
        } catch (Exception e) {
            DiagnosticLogger.logError(TAG, "Failed to play FCM emergency alarm siren audio.", e);
        }
    }

    public static void stopAlarmSound() {
        try {
            if (ringtone != null && ringtone.isPlaying()) {
                ringtone.stop();
                DiagnosticLogger.logInfo(TAG, "FCM emergency alarm siren stopped manually.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to manually terminate playing alarm siren audio.", e);
        }
    }
}