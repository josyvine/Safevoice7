package com.safevoice.app.services;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.telephony.SmsManager;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.RemoteMessage;
import com.safevoice.app.R;
import com.safevoice.app.models.Contact;
import com.safevoice.app.utils.ContactsManager;
import com.safevoice.app.utils.DiagnosticLogger;
import com.safevoice.app.utils.LocationHelper;
import com.safevoice.app.webrtc.WebRTCCallActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service that handles the emergency sequence when triggered.
 * Resolves high-accuracy GPS coordinates and dispatches automated emergency alerts.
 * SMS warning layouts are dispatched first, and then online FCM and Realtime Database
 * signalling routes are triggered over the custom "safe_voice_circle" database structure.
 */
public class EmergencyHandlerService extends Service {

    private static final String TAG = "EmergencyHandlerService";
    private static final String SETTINGS_PREFS_NAME = "SafeVoiceSettingsPrefs";
    private static final String KEY_CALL_PREFERENCE = "call_preference";
    private static final String CALL_PREF_WEBRTC = "webrtc";

    private LocationHelper locationHelper;

    @Override
    public void onCreate() {
        super.onCreate();
        DiagnosticLogger.logInfo(TAG, "EmergencyHandlerService onCreate() invoked. Initializing helpers.");
        locationHelper = new LocationHelper(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        DiagnosticLogger.logInfo(TAG, "onStartCommand() invoked with startId: " + startId);
        Toast.makeText(this, "Emergency Triggered! Sending alerts...", Toast.LENGTH_LONG).show();

        if (!hasRequiredPermissions()) {
            DiagnosticLogger.logError(TAG, "Execution aborted. Missing critical location, call, or SMS permissions.", null);
            stopSelf();
            return START_NOT_STICKY;
        }

        locationHelper.getCurrentLocation(new LocationHelper.LocationResultCallback() {
            @Override
            public void onLocationResult(Location location) {
                if (location != null) {
                    DiagnosticLogger.logInfo(TAG, "GPS location resolved successfully: Latitude " + location.getLatitude() + ", Longitude " + location.getLongitude());
                } else {
                    DiagnosticLogger.logWarn(TAG, "GPS location returned null. Attempting emergency actions dispatcher with null location fallback.");
                }
                executeEmergencyActions(location);
            }
        });

        return START_NOT_STICKY;
    }

    private void executeEmergencyActions(Location location) {
        ContactsManager contactsManager = ContactsManager.getInstance(this);
        Contact primaryContact = contactsManager.getPrimaryContact();
        List<Contact> priorityContacts = contactsManager.getPriorityContacts();

        DiagnosticLogger.logInfo(TAG, "Emergency Actions sequence initiated.");

        // Check call preference early to manage signaling session generation
        SharedPreferences settingsPrefs = getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
        String callPreference = settingsPrefs.getString(KEY_CALL_PREFERENCE, "standard");
        DiagnosticLogger.logInfo(TAG, "Active calling preference resolved: " + callPreference);

        // Generate a secure, unique signaling session ID synchronously to prevent async latency bugs
        String sessionId = null;
        if (CALL_PREF_WEBRTC.equals(callPreference) && primaryContact != null && primaryContact.getUid() != null) {
            sessionId = UUID.randomUUID().toString();
            DiagnosticLogger.logInfo(TAG, "WebRTC preference active. Synchronously generated Session ID: " + sessionId);
        }

        // Send SMS to the primary contact
        if (primaryContact != null && primaryContact.getPhoneNumber() != null && !primaryContact.getPhoneNumber().isEmpty()) {
            sendSmsAlert(primaryContact.getPhoneNumber(), location);
        } else {
            DiagnosticLogger.logWarn(TAG, "No primary contact phone number configured. Primary SMS dispatch skipped.");
        }

        // Send SMS to all other priority contacts
        if (priorityContacts != null && !priorityContacts.isEmpty()) {
            for (Contact contact : priorityContacts) {
                if (contact.getPhoneNumber() != null && !contact.getPhoneNumber().isEmpty()) {
                    // Prevent duplicate SMS if the primary contact is also in priority list
                    if (primaryContact == null || !contact.equals(primaryContact)) {
                        sendSmsAlert(contact.getPhoneNumber(), location);
                    }
                }
            }
        } else {
            DiagnosticLogger.logWarn(TAG, "Priority contacts list is empty. Additional SMS dispatches skipped.");
        }

        if (isOnline()) {
            DiagnosticLogger.logInfo(TAG, "Network connection detected. Executing advanced online alerts strategy.");

            // Send in-app FCM alerts to priority contacts containing the actual synchronous session ID
            sendFcmAlertsToAll(priorityContacts, location, sessionId);

            if (CALL_PREF_WEBRTC.equals(callPreference) && primaryContact != null && primaryContact.getUid() != null) {
                startWebRtcCall(primaryContact.getUid(), sessionId);
                stopSelf(); // FIX FOR GLITCH 2: Cleanly stop this alert service task context after hand-off
            } else {
                DiagnosticLogger.logInfo(TAG, "Making standard fallback cellular call as per settings preference.");
                makeStandardPhoneCall(primaryContact);
                stopSelf();
            }
        } else {
            DiagnosticLogger.logWarn(TAG, "Device is OFFLINE. Executing standard fallback cellular strategy.");
            makeStandardPhoneCall(primaryContact);
            stopSelf();
        }
    }

    private void makeStandardPhoneCall(Contact primaryContact) {
        if (primaryContact != null) {
            makePhoneCall(primaryContact.getPhoneNumber());
        } else {
            DiagnosticLogger.logWarn(TAG, "No primary contact configured. Fallback cellular call aborted.");
        }
    }

    private void startWebRtcCall(String targetUid, String sessionId) {
        FirebaseApp circleApp;
        try {
            circleApp = FirebaseApp.getInstance("safe_voice_circle");
        } catch (IllegalStateException e) {
            circleApp = FirebaseApp.getInstance();
        }

        String myUid = FirebaseAuth.getInstance(circleApp).getCurrentUser().getUid();

        DiagnosticLogger.logInfo(TAG, "Launching WebRTC Calling UI for caller. Session ID: " + sessionId + ", Target UID: " + targetUid);

        // Launch the visual call screen for the caller (Phone A) immediately
        Intent callIntent = new Intent(this, WebRTCCallActivity.class);
        callIntent.putExtra("CALLER_UID", myUid);
        callIntent.putExtra("RECIPIENT_UID", targetUid);
        callIntent.putExtra("SESSION_ID", sessionId);
        callIntent.putExtra("IS_OUTGOING", true);
        
        showCallerWebRtcOverlayNotification(callIntent);
    }

    /**
     * Uses a high-priority system channel to launch the outgoing WebRTC Call interface.
     * This bypasses Android 10+ background activity restrictions securely.
     */
    private void showCallerWebRtcOverlayNotification(Intent callIntent) {
        String channelId = "EmergencyCallerChannel";
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "Emergency Call Outgoing",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Launches the outgoing WebRTC call interface");
            channel.enableVibration(true);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                101,
                callIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Emergency Call Initiated")
                .setContentText("Tap to open your live emergency voice call.")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setFullScreenIntent(pendingIntent, true) // Wakes screen and displays call UI on Android 10+
                .setContentIntent(pendingIntent);

        if (notificationManager != null) {
            notificationManager.notify(101, notificationBuilder.build());
        }

        // Direct launch using SYSTEM_ALERT_WINDOW overlay permission to guarantee instant launch without tapping
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            try {
                callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(callIntent);
                DiagnosticLogger.logInfo(TAG, "Launched WebRTCCallActivity directly via overlay permission (zero-touch).");
            } catch (Exception e) {
                DiagnosticLogger.logError(TAG, "Failed direct overlay activity start fallback.", e);
            }
        } else {
            // Direct launch fallback for older Android versions
            try {
                callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(callIntent);
                DiagnosticLogger.logInfo(TAG, "Direct launch initiated.");
            } catch (Exception e) {
                DiagnosticLogger.logWarn(TAG, "Direct call activity launch restricted by OS settings. Relying on full-screen intent notification.");
            }
        }
    }

    private void sendFcmAlertsToAll(List<Contact> contacts, Location location, String sessionId) {
        FirebaseApp circleApp;
        try {
            circleApp = FirebaseApp.getInstance("safe_voice_circle");
        } catch (IllegalStateException e) {
            DiagnosticLogger.logError(TAG, "Secondary safe_voice_circle app is not initialized yet.", e);
            return;
        }

        // Use the authenticated session on the secondary custom Firebase instance
        FirebaseUser currentUser = FirebaseAuth.getInstance(circleApp).getCurrentUser();
        if (currentUser == null) {
            DiagnosticLogger.logWarn(TAG, "Authenticated user session missing. Online dispatches aborted.");
            return;
        }

        // Retrieve Firestore connected to your custom "safe_voice_circle" named app instance
        FirebaseFirestore dynamicDb = FirebaseFirestore.getInstance(circleApp);

        dynamicDb.collection("users").document(currentUser.getUid()).get()
            .addOnCompleteListener(new OnCompleteListener<DocumentSnapshot>() {
                @Override
                public void onComplete(@NonNull Task<DocumentSnapshot> task) {
                    if (task.isSuccessful() && task.getResult() != null) {
                        String callerName = task.getResult().getString("verifiedName");
                        if (callerName == null) callerName = currentUser.getDisplayName();

                        DiagnosticLogger.logInfo(TAG, "Resolving profiles for active dispatches. Sender name: " + callerName);

                        for (Contact contact : contacts) {
                            if (contact.getUid() != null) {
                                // 1. Send the standard FCM message containing the session ID
                                sendFcmMessage(contact.getUid(), callerName, currentUser.getUid(), location, sessionId, dynamicDb);
                                
                                // 2. Send the instant real-time database signaling trigger containing the session ID
                                sendRealtimeDbAlert(circleApp, contact.getUid(), callerName, currentUser.getUid(), location, sessionId);
                            }
                        }
                    } else {
                        DiagnosticLogger.logError(TAG, "Failed to fetch sender profile document from Firestore.", task.getException());
                    }
                }
            });
    }

    private void sendFcmMessage(String recipientUid, String callerName, String callerUid, Location location, String sessionId, FirebaseFirestore dynamicDb) {
        dynamicDb.collection("users").document(recipientUid).get()
            .addOnCompleteListener(new OnCompleteListener<DocumentSnapshot>() {
                @Override
                public void onComplete(@NonNull Task<DocumentSnapshot> task) {
                    if (task.isSuccessful() && task.getResult() != null) {
                        String fcmToken = task.getResult().getString("fcmToken");
                        if (fcmToken != null) {
                            RemoteMessage.Builder messageBuilder = new RemoteMessage.Builder(fcmToken)
                                    .setMessageId(Integer.toString(java.util.UUID.randomUUID().hashCode()))
                                    .addData("type", "emergency")
                                    .addData("callerName", callerName)
                                    .addData("callerUid", callerUid);

                            if (location != null) {
                                messageBuilder.addData("location", location.getLatitude() + "," + location.getLongitude());
                            }

                            if (sessionId != null) {
                                messageBuilder.addData("sessionId", sessionId);
                            }

                            FirebaseMessaging.getInstance().send(messageBuilder.build());
                            DiagnosticLogger.logInfo(TAG, "FCM downstream dispatch queued for recipient Token: " + fcmToken);
                        } else {
                            DiagnosticLogger.logWarn(TAG, "FCM registration token missing for recipient UID: " + recipientUid + ". Downstream FCM dispatch skipped.");
                        }
                    } else {
                        DiagnosticLogger.logError(TAG, "Failed to fetch recipient profile document from Firestore.", task.getException());
                    }
                }
            });
    }

    /**
     * Writes the emergency trigger payload directly to the shared Realtime Database.
     * This bypasses local client-to-client FCM limits and wakes up the recipient's phone instantly.
     */
    private void sendRealtimeDbAlert(FirebaseApp circleApp, String recipientUid, String callerName, String callerUid, Location location, String sessionId) {
        try {
            FirebaseDatabase rtdb = FirebaseDatabase.getInstance(circleApp);
            DatabaseReference alertsRef = rtdb.getReference("alerts").child(recipientUid);

            Map<String, Object> alertPayload = new HashMap<>();
            alertPayload.put("callerUid", callerUid);
            alertPayload.put("callerName", callerName);

            if (location != null) {
                alertPayload.put("location", location.getLatitude() + "," + location.getLongitude());
            } else {
                alertPayload.put("location", "0.0,0.0");
            }

            if (sessionId != null) {
                alertPayload.put("sessionId", sessionId);
            } else {
                alertPayload.put("sessionId", "pending");
            }

            DiagnosticLogger.logInfo(TAG, "Uploading real-time database trigger payload containing Session ID: " + sessionId + " to path: " + alertsRef.getPath());

            alertsRef.setValue(alertPayload)
                    .addOnSuccessListener(aVoid -> DiagnosticLogger.logInfo(TAG, "Realtime DB Alert written successfully for recipient: " + recipientUid))
                    .addOnFailureListener(e -> Log.e(TAG, "Failed to write Realtime DB Alert.", e));

        } catch (Exception e) {
            DiagnosticLogger.logError(TAG, "Error compiling or uploading Realtime DB alert payload.", e);
        }
    }

    private void makePhoneCall(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isEmpty()) {
            DiagnosticLogger.logError(TAG, "Phone number is null or empty. Cellular call skipped.");
            return;
        }
        Intent callIntent = new Intent(Intent.ACTION_CALL);
        callIntent.setData(Uri.parse("tel:" + phoneNumber));
        callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            DiagnosticLogger.logInfo(TAG, "Initiating direct standard cellular call to: " + phoneNumber);
            startActivity(callIntent);
        } catch (SecurityException e) {
            DiagnosticLogger.logError(TAG, "CALL_PHONE permission is missing or was revoked.", e);
        }
    }

    private void sendSmsAlert(String phoneNumber, Location location) {
        if (phoneNumber == null || phoneNumber.isEmpty() || phoneNumber.equals("No number provided")) {
            DiagnosticLogger.logWarn(TAG, "Phone number is invalid or not provided. SMS dispatch skipped.");
            return;
        }
        FirebaseUser currentUser = FirebaseAuth.getInstance(FirebaseApp.getInstance("safe_voice_circle")).getCurrentUser();
        String userName = (currentUser != null && currentUser.getDisplayName() != null) ? currentUser.getDisplayName() : "a Safe Voice user";
        String message = "EMERGENCY: Automated alert from Safe Voice for " + userName + ". They may be in trouble.";
        if (location != null) {
            message += "\n\nLast known location: https://maps.google.com/?q=" + location.getLatitude() + "," + location.getLongitude();
        }
        try {
            SmsManager smsManager = SmsManager.getDefault();
            smsManager.sendMultipartTextMessage(phoneNumber, null, smsManager.divideMessage(message), null, null);
            DiagnosticLogger.logInfo(TAG, "Emergency SMS alert dispatched successfully to: " + phoneNumber);
        } catch (Exception e) {
            DiagnosticLogger.logError(TAG, "Failed to send emergency SMS dispatch to: " + phoneNumber, e);
        }
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo netInfo = cm.getActiveNetworkInfo();
            return netInfo != null && netInfo.isConnectedOrConnecting();
        }
        return false;
    }

    private boolean hasRequiredPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        DiagnosticLogger.logInfo(TAG, "EmergencyHandlerService onDestroy() invoked. Tearing down active task resources.");
        stopSelf();
    }
}