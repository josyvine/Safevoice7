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
import com.safevoice.app.utils.LocationHelper;
import com.safevoice.app.webrtc.WebRTCCallActivity;
import com.safevoice.app.webrtc.WebRTCManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service that handles the emergency sequence when triggered.
 * Resolves high-accuracy GPS coordinates and dispatches automated emergency alerts.
 * SMS warning layouts are dispatched first, and then online FCM and Realtime Database
 * signalling routes are triggered over the custom "safe_voice_circle" database structure.
 */
public class EmergencyHandlerService extends Service implements WebRTCManager.WebRTCListener {

    private static final String TAG = "EmergencyHandlerService";
    private static final String SETTINGS_PREFS_NAME = "SafeVoiceSettingsPrefs";
    private static final String KEY_CALL_PREFERENCE = "call_preference";
    private static final String CALL_PREF_WEBRTC = "webrtc";

    private LocationHelper locationHelper;
    private WebRTCManager webRTCManager;

    @Override
    public void onCreate() {
        super.onCreate();
        locationHelper = new LocationHelper(this);
        webRTCManager = new WebRTCManager(getApplicationContext(), this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Emergency sequence initiated.");
        Toast.makeText(this, "Emergency Triggered! Sending alerts...", Toast.LENGTH_LONG).show();

        if (!hasRequiredPermissions()) {
            Log.e(TAG, "Cannot proceed with emergency alerts. Missing permissions.");
            stopSelf();
            return START_NOT_STICKY;
        }

        locationHelper.getCurrentLocation(new LocationHelper.LocationResultCallback() {
            @Override
            public void onLocationResult(Location location) {
                executeEmergencyActions(location);
                // The service will stop itself after actions are complete
            }
        });

        return START_NOT_STICKY;
    }

    private void executeEmergencyActions(Location location) {
        ContactsManager contactsManager = ContactsManager.getInstance(this);
        Contact primaryContact = contactsManager.getPrimaryContact();
        List<Contact> priorityContacts = contactsManager.getPriorityContacts();

        // Check call preference early to manage signaling session generation
        SharedPreferences settingsPrefs = getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE);
        String callPreference = settingsPrefs.getString(KEY_CALL_PREFERENCE, "standard");

        // Generate the signaling session ID first, so it gets injected into the FCM/Realtime Alerts correctly
        if (CALL_PREF_WEBRTC.equals(callPreference) && primaryContact != null && primaryContact.getUid() != null) {
            if (webRTCManager != null) {
                webRTCManager.startCall(primaryContact.getUid());
            }
        }

        // Send SMS to the primary contact
        if (primaryContact != null && primaryContact.getPhoneNumber() != null && !primaryContact.getPhoneNumber().isEmpty()) {
            sendSmsAlert(primaryContact.getPhoneNumber(), location);
        } else {
            Log.w(TAG, "No primary contact with a phone number set. Cannot send primary SMS.");
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
            Log.w(TAG, "No additional priority contacts to send SMS to.");
        }

        if (isOnline()) {
            Log.d(TAG, "Device is ONLINE. Executing advanced plan.");

            // Send in-app FCM alerts to priority contacts (they will now receive the generated session ID)
            sendFcmAlertsToAll(priorityContacts, location);

            if (CALL_PREF_WEBRTC.equals(callPreference) && primaryContact != null && primaryContact.getUid() != null) {
                Log.d(TAG, "Starting WebRTC call.");
                startWebRtcCall(primaryContact.getUid());
            } else {
                Log.d(TAG, "Making standard phone call as per preference or fallback.");
                makeStandardPhoneCall(primaryContact);
                stopSelf();
            }
        } else {
            Log.d(TAG, "Device is OFFLINE. Executing fallback plan.");
            makeStandardPhoneCall(primaryContact);
            stopSelf();
        }
    }

    private void makeStandardPhoneCall(Contact primaryContact) {
        if (primaryContact != null) {
            makePhoneCall(primaryContact.getPhoneNumber());
        } else {
            Log.w(TAG, "No primary contact set. Cannot make emergency call.");
        }
    }

    private void startWebRtcCall(String targetUid) {
        // Fetch dynamic secondary app reference cleanly
        FirebaseApp circleApp;
        try {
            circleApp = FirebaseApp.getInstance("safe_voice_circle");
        } catch (IllegalStateException e) {
            circleApp = FirebaseApp.getInstance();
        }

        String myUid = FirebaseAuth.getInstance(circleApp).getCurrentUser().getUid();
        String sessionId = (webRTCManager != null && webRTCManager.getSignalingClient() != null) ? 
                webRTCManager.getSignalingClient().getSessionId() : null;

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

        // Direct launch fallback for older Android versions
        try {
            startActivity(callIntent);
        } catch (Exception e) {
            Log.w(TAG, "Direct call activity launch restricted. Relying on full-screen intent notification.");
        }
    }

    private void sendFcmAlertsToAll(List<Contact> contacts, Location location) {
        FirebaseApp circleApp;
        try {
            circleApp = FirebaseApp.getInstance("safe_voice_circle");
        } catch (IllegalStateException e) {
            Log.e(TAG, "Secondary safe_voice_circle app is not initialized yet.", e);
            return;
        }

        // Use the authenticated session on the secondary custom Firebase instance
        FirebaseUser currentUser = FirebaseAuth.getInstance(circleApp).getCurrentUser();
        if (currentUser == null) return;

        // Retrieve Firestore connected to your custom "safe_voice_circle" named app instance
        FirebaseFirestore dynamicDb = FirebaseFirestore.getInstance(circleApp);

        dynamicDb.collection("users").document(currentUser.getUid()).get()
            .addOnCompleteListener(new OnCompleteListener<DocumentSnapshot>() {
                @Override
                public void onComplete(@NonNull Task<DocumentSnapshot> task) {
                    if (task.isSuccessful() && task.getResult() != null) {
                        String callerName = task.getResult().getString("verifiedName");
                        if (callerName == null) callerName = currentUser.getDisplayName();

                        for (Contact contact : contacts) {
                            if (contact.getUid() != null) {
                                // 1. Send the standard FCM message
                                sendFcmMessage(contact.getUid(), callerName, currentUser.getUid(), location, dynamicDb);
                                
                                // 2. Send the instant real-time database signaling trigger
                                sendRealtimeDbAlert(circleApp, contact.getUid(), callerName, currentUser.getUid(), location);
                            }
                        }
                    }
                }
            });
    }

    private void sendFcmMessage(String recipientUid, String callerName, String callerUid, Location location, FirebaseFirestore dynamicDb) {
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

                            if (webRTCManager != null && webRTCManager.getSignalingClient().getSessionId() != null) {
                                messageBuilder.addData("sessionId", webRTCManager.getSignalingClient().getSessionId());
                            }

                            FirebaseMessaging.getInstance().send(messageBuilder.build());
                            Log.d(TAG, "Sent FCM alert to " + recipientUid);
                        }
                    }
                }
            });
    }

    /**
     * Writes the emergency trigger payload directly to the shared Realtime Database.
     * This bypasses local client-to-client FCM limits and wakes up the recipient's phone instantly.
     */
    private void sendRealtimeDbAlert(FirebaseApp circleApp, String recipientUid, String callerName, String callerUid, Location location) {
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

            if (webRTCManager != null && webRTCManager.getSignalingClient().getSessionId() != null) {
                alertPayload.put("sessionId", webRTCManager.getSignalingClient().getSessionId());
            } else {
                alertPayload.put("sessionId", "pending");
            }

            alertsRef.setValue(alertPayload)
                    .addOnSuccessListener(aVoid -> Log.d(TAG, "Realtime DB Alert written successfully for recipient: " + recipientUid))
                    .addOnFailureListener(e -> Log.e(TAG, "Failed to write Realtime DB Alert.", e));

        } catch (Exception e) {
            Log.e(TAG, "Error compiling Realtime DB alert", e);
        }
    }

    private void makePhoneCall(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isEmpty()) {
            Log.e(TAG, "Phone number is invalid. Cannot make call.");
            return;
        }
        Intent callIntent = new Intent(Intent.ACTION_CALL);
        callIntent.setData(Uri.parse("tel:" + phoneNumber));
        callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(callIntent);
        } catch (SecurityException e) {
            Log.e(TAG, "CALL_PHONE permission missing or denied.", e);
        }
    }

    private void sendSmsAlert(String phoneNumber, Location location) {
        if (phoneNumber == null || phoneNumber.isEmpty() || phoneNumber.equals("No number provided")) {
            Log.e(TAG, "Phone number is invalid for SMS.");
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
            Log.i(TAG, "SMS alert sent to " + phoneNumber);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send SMS to " + phoneNumber, e);
        }
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        return netInfo != null && netInfo.isConnectedOrConnecting();
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
        if (webRTCManager != null) {
            webRTCManager.cleanup();
        }
        Log.d(TAG, "EmergencyHandlerService destroyed.");
    }

    // WebRTCManager.WebRTCListener callbacks
    @Override
    public void onWebRTCCallEstablished() {
        Log.i(TAG, "WebRTC call established. Stopping service.");
        stopSelf();
    }

    @Override
    public void onWebRTCCallEnded() {
        Log.i(TAG, "WebRTC call ended or failed. Stopping service.");
        stopSelf();
    }
}