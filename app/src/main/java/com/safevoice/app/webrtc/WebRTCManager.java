package com.safevoice.app.webrtc;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioDeviceInfo;
import android.os.Build;
import android.os.Looper;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import com.safevoice.app.utils.DiagnosticLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages the lifetime of a WebRTC PeerConnection session.
 * Handles local audio acquisition and interfaces with the signaling client.
 */
public class WebRTCManager implements FirebaseSignalingClient.SignalingListener {

    private static final String TAG = "WebRTCManager";
    private static final String AUDIO_TRACK_ID = "ARDAMSa0";

    private final Context context;
    private final FirebaseSignalingClient signalingClient;
    private PeerConnectionFactory peerConnectionFactory;
    private PeerConnection peerConnection;
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;
    private final AudioManager audioManager;

    private String targetUserUid;
    private final WebRTCListener listener;

    // Cache list to store Twilio TURN servers dynamically fetched from the REST API
    private final List<PeerConnection.IceServer> twilioIceServers = new ArrayList<>();

    // Variables to queue early incoming ICE candidates before the remote description is applied
    private boolean isRemoteDescriptionSet = false;
    private final List<IceCandidate> queuedRemoteCandidates = new ArrayList<>();

    public interface WebRTCListener {
        void onWebRTCCallEstablished();
        void onWebRTCCallEnded();
    }

    public WebRTCManager(Context context, WebRTCListener listener) {
        this.context = context;
        this.listener = listener;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.signalingClient = new FirebaseSignalingClient();
        this.signalingClient.setListener(this);
        initializePeerConnectionFactory();
        DiagnosticLogger.logInfo(TAG, "WebRTCManager initialized. System AudioManager fetched successfully.");
    }

    // --- THIS IS THE FIX ---
    // This public method allows other classes like EmergencyHandlerService to get the session ID.
    public FirebaseSignalingClient getSignalingClient() {
        return this.signalingClient;
    }

    private void initializePeerConnectionFactory() {
        try {
            PeerConnectionFactory.InitializationOptions initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
                    .createInitializationOptions();
            PeerConnectionFactory.initialize(initializationOptions);

            PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
            peerConnectionFactory = PeerConnectionFactory.builder()
                    .setOptions(options)
                    .createPeerConnectionFactory();
            DiagnosticConnectionLog("Native PeerConnectionFactory initialized successfully.");
        } catch (Exception e) {
            DiagnosticLogger.logError(TAG, "Fatal error initializing Native PeerConnectionFactory libraries.", e);
        }
    }

    /**
     * Starts an outgoing WebRTC call. Binds the signaling client to the pre-generated session ID
     * so that the caller and callee successfully enter the exact same signaling room.
     */
    public void startCall(String targetUserUid, String sessionId) {
        this.targetUserUid = targetUserUid;
        DiagnosticLogger.logInfo(TAG, "startCall() triggered with Session ID: " + sessionId + ". Initiating Twilio REST handshake.");

        // Explicitly prepare and bind the call session node to the pre-generated session ID
        signalingClient.prepareCallSession(sessionId, true);

        // Fetch Twilio TURN servers asynchronously first before building the PeerConnection
        fetchTwilioTokens(() -> {
            // FIX: Ensure peerConnectionFactory is still active before attempting to initialize PeerConnection
            if (peerConnectionFactory == null) {
                DiagnosticLogger.logWarn(TAG, "startCall fetchTwilioTokens callback aborted: peerConnectionFactory has been disposed.");
                return;
            }

            DiagnosticLogger.logInfo(TAG, "Twilio handshake completed. Building caller PeerConnection constraints.");
            this.peerConnection = createPeerConnection();
            if (this.peerConnection == null) {
                DiagnosticLogger.logError(TAG, "Aborting startCall(). PeerConnection creation returned null.", null);
                return;
            }

            createAndSetLocalAudioTrack();

            peerConnection.createOffer(new SdpObserver() {
                @Override
                public void onCreateSuccess(SessionDescription sessionDescription) {
                    DiagnosticLogger.logInfo(TAG, "WebRTC SDP Offer created successfully. Applying local description.");
                    peerConnection.setLocalDescription(new SdpObserver() {
                        @Override
                        public void onSetSuccess() {
                            DiagnosticLogger.logInfo(TAG, "Local description set successfully for SDP Offer. Dispatched to signaling database.");
                            signalingClient.sendOffer(sessionDescription, targetUserUid);
                        }
                        @Override
                        public void onCreateSuccess(SessionDescription sdp) {}
                        @Override
                        public void onSetFailure(String s) { DiagnosticLogger.logError(TAG, "Failed to apply local description for offer: " + s, null); }
                        @Override
                        public void onCreateFailure(String s) {}
                    }, sessionDescription);
                }
                @Override
                public void onSetSuccess() {}
                @Override
                public void onCreateFailure(String s) { DiagnosticLogger.logError(TAG, "Failed to create active SDP Offer: " + s, null); }
                @Override
                public void onSetFailure(String s) {}
            }, new MediaConstraints());
        });
    }

    public void answerCall(String sessionId, String callerUid) {
        this.targetUserUid = callerUid;
        DiagnosticLogger.logInfo(TAG, "answerCall() triggered. Deferring signaling subscription until PeerConnection is ready.");

        // Fetch Twilio TURN servers asynchronously first before building the PeerConnection
        fetchTwilioTokens(() -> {
            // FIX: Ensure peerConnectionFactory is still active before attempting to initialize PeerConnection
            if (peerConnectionFactory == null) {
                DiagnosticLogger.logWarn(TAG, "answerCall fetchTwilioTokens callback aborted: peerConnectionFactory has been disposed.");
                return;
            }

            DiagnosticLogger.logInfo(TAG, "Twilio handshake completed. Building callee PeerConnection constraints.");
            this.peerConnection = createPeerConnection();
            if (this.peerConnection == null) {
                DiagnosticLogger.logError(TAG, "Aborting answerCall(). PeerConnection creation returned null.", null);
                return;
            }
            createAndSetLocalAudioTrack();

            // FIX: Join signaling session only AFTER PeerConnection is fully built to prevent race conditions
            DiagnosticLogger.logInfo(TAG, "Callee PeerConnection is fully built. Joining signaling session: " + sessionId);
            this.signalingClient.joinCallSession(sessionId);
        });
    }

    /**
     * Authenticates with Twilio over HTTP and retrieves dynamic network traversal tokens on a worker thread.
     * Always triggers the completion callback on the Main UI thread.
     */
    private void fetchTwilioTokens(Runnable onComplete) {
        synchronized (twilioIceServers) {
            if (!twilioIceServers.isEmpty()) {
                DiagnosticLogger.logInfo(TAG, "Using cached Twilio ICE Server configuration list.");
                onComplete.run();
                return;
            }
        }

        String accountSid = null;
        String apiKey = null;
        String apiSecret = null;

        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            EncryptedSharedPreferences sharedPreferences = (EncryptedSharedPreferences) EncryptedSharedPreferences.create(
                    "TwilioCredentials",
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            accountSid = sharedPreferences.getString("ACCOUNT_SID", null);
            apiKey = sharedPreferences.getString("API_KEY", null);
            apiSecret = sharedPreferences.getString("API_SECRET", null);
        } catch (GeneralSecurityException | IOException e) {
            DiagnosticLogger.logError(TAG, "Failed to read Twilio credentials from EncryptedSharedPreferences.", e);
        }

        if (accountSid == null || accountSid.isEmpty() ||
            apiKey == null || apiKey.isEmpty() ||
            apiSecret == null || apiSecret.isEmpty()) {
            DiagnosticLogger.logWarn(TAG, "Twilio credentials are not configured in settings. WebRTC falling back to standard STUN routing.");
            onComplete.run();
            return;
        }

        final String finalAccountSid = accountSid;
        final String finalApiKey = apiKey;
        final String finalApiSecret = apiSecret;

        DiagnosticLogger.logInfo(TAG, "Initiating dynamic REST handshake with Twilio Endpoint.");

        new Thread(() -> {
            try {
                String auth = finalApiKey + ":" + finalApiSecret;
                String base64Auth = android.util.Base64.encodeToString(auth.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);

                URL url = new URL("https://api.twilio.com/2010-04-01/Accounts/" + finalAccountSid + "/Tokens.json");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Basic " + base64Auth);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.flush();
                }

                int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_CREATED || responseCode == HttpURLConnection.HTTP_OK) {
                    try (InputStream is = conn.getInputStream();
                         BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                        parseTwilioResponse(sb.toString());
                    }
                } else {
                    DiagnosticLogger.logError(TAG, "Twilio Token API handshake failed. Server response code: " + responseCode, null);
                }
            } catch (Exception e) {
                DiagnosticLogger.logError(TAG, "Exception encountered during Twilio network handshake.", e);
            } finally {
                // Ensure callback execution returns safely to the Main UI Thread
                new android.os.Handler(android.os.Looper.getMainLooper()).post(onComplete);
            }
        }).start();
    }

    /**
     * Parses the REST API JSON response payload to configure local IceServer structures.
     */
    private void parseTwilioResponse(String jsonString) {
        try {
            JSONObject responseObj = new JSONObject(jsonString);
            if (responseObj.has("ice_servers")) {
                JSONArray iceServersArray = responseObj.getJSONArray("ice_servers");
                List<PeerConnection.IceServer> parsedList = new ArrayList<>();

                for (int i = 0; i < iceServersArray.length(); i++) {
                    JSONObject serverObj = iceServersArray.getJSONObject(i);

                    List<String> urls = new ArrayList<>();
                    if (serverObj.has("url")) {
                        urls.add(serverObj.getString("url"));
                    } else if (serverObj.has("urls")) {
                        Object urlsVal = serverObj.get("urls");
                        if (urlsVal instanceof JSONArray) {
                            JSONArray urlsArray = (JSONArray) urlsVal;
                            for (int j = 0; j < urlsArray.length(); j++) {
                                urls.add(urlsArray.getString(j));
                            }
                        } else if (urlsVal instanceof String) {
                            urls.add((String) urlsVal);
                        }
                    }

                    String username = serverObj.optString("username", null);
                    String credential = serverObj.optString("credential", null);

                    for (String url : urls) {
                        PeerConnection.IceServer.Builder builder = PeerConnection.IceServer.builder(url);
                        if (username != null && !username.isEmpty() && credential != null && !credential.isEmpty()) {
                            builder.setUsername(username);
                            builder.setPassword(credential);
                        }
                        parsedList.add(builder.createIceServer());
                    }
                }

                synchronized (twilioIceServers) {
                    twilioIceServers.clear();
                    twilioIceServers.addAll(parsedList);
                }
                DiagnosticLogger.logInfo(TAG, "Dynamic Twilio ICE Server Configuration populated. Total servers: " + parsedList.size());
            }
        } catch (Exception e) {
            DiagnosticLogger.logError(TAG, "Error parsing Twilio API response payload.", e);
        }
    }

    private PeerConnection createPeerConnection() {
        // FIX: If peerConnectionFactory has been disposed or cleaned up already, return null immediately
        if (peerConnectionFactory == null) {
            DiagnosticLogger.logWarn(TAG, "createPeerConnection aborted: peerConnectionFactory is null.");
            return null;
        }

        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        // Add Google's public STUN server as default
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());

        // Add dynamically-fetched Twilio TURN/STUN servers
        synchronized (twilioIceServers) {
            if (!twilioIceServers.isEmpty()) {
                iceServers.addAll(twilioIceServers);
                DiagnosticLogger.logInfo(TAG, "Added " + twilioIceServers.size() + " Twilio dynamic ICE relay endpoints to configuration.");
            } else {
                DiagnosticLogger.logWarn(TAG, "Twilio configuration list empty. Falling back to default P2P STUN routing.");
            }
        }

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        return peerConnectionFactory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {
                DiagnosticLogger.logInfo(TAG, "onSignalingChange: " + signalingState);
            }
            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                DiagnosticLogger.logInfo(TAG, "onIceConnectionChange: " + iceConnectionState);
                if (iceConnectionState == PeerConnection.IceConnectionState.CONNECTED) {
                    DiagnosticLogger.logInfo(TAG, "ICE Link Established. Routing VoIP audio to system speakerphone.");
                    setupAudioRouting();
                    listener.onWebRTCCallEstablished();
                } else if (iceConnectionState == PeerConnection.IceConnectionState.FAILED || iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED) {
                    DiagnosticLogger.logWarn(TAG, "ICE Link Disconnected or Failed. Terminating call session.");
                    endCall();
                }
            }
            @Override
            public void onIceConnectionReceivingChange(boolean b) {}
            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                DiagnosticLogger.logInfo(TAG, "onIceGatheringChange: " + iceGatheringState);
            }
            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                DiagnosticLogger.logInfo(TAG, "Local ICE Candidate gathered. Forwarding candidate to remote peer.");
                signalingClient.sendIceCandidate(iceCandidate, targetUserUid);
            }
            @Override
            public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {}
            @Override
            public void onAddStream(MediaStream mediaStream) {}
            @Override
            public void onRemoveStream(MediaStream mediaStream) {}
            @Override
            public void onDataChannel(DataChannel dataChannel) {}
            @Override
            public void onRenegotiationNeeded() {}
            @Override
            public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
                DiagnosticLogger.logInfo(TAG, "Remote audio track stream successfully matched and received.");
            }
        });
    }

    private void setupAudioRouting() {
        try {
            if (audioManager != null) {
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                
                // FIX FOR GLITCH 1: Correctly route audio output using setCommunicationDevice on API 31+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    List<AudioDeviceInfo> devices = audioManager.getAvailableCommunicationDevices();
                    AudioDeviceInfo speakerDevice = null;
                    for (AudioDeviceInfo device : devices) {
                        if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                            speakerDevice = device;
                            break;
                        }
                    }
                    if (speakerDevice != null) {
                        boolean result = audioManager.setCommunicationDevice(speakerDevice);
                        DiagnosticLogger.logInfo(TAG, "Android 12+ audio routed to built-in speaker. Success: " + result);
                    } else {
                        DiagnosticLogger.logWarn(TAG, "Android 12+ built-in speaker device was not found.");
                        audioManager.setSpeakerphoneOn(true); // Fallback
                    }
                } else {
                    audioManager.setSpeakerphoneOn(true);
                }
                
                audioManager.setMicrophoneMute(false);
                DiagnosticLogger.logInfo(TAG, "System AudioManager set to MODE_IN_COMMUNICATION. Speakerphone turned ON.");
            }
        } catch (Exception e) {
            DiagnosticLogger.logError(TAG, "Failed to apply system AudioManager VoIP parameters.", e);
        }
    }

    private void resetAudioRouting() {
        try {
            if (audioManager != null) {
                audioManager.setMode(AudioManager.MODE_NORMAL);
                
                // FIX FOR GLITCH 1: Clear custom speakerphone device routing on API 31+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice();
                    DiagnosticLogger.logInfo(TAG, "Android 12+ cleared custom communication device audio routing.");
                } else {
                    audioManager.setSpeakerphoneOn(false);
                }
                
                DiagnosticLogger.logInfo(TAG, "System AudioManager successfully reset to MODE_NORMAL.");
            }
        } catch (Exception e) {
            DiagnosticLogger.logError(TAG, "Failed to reset system AudioManager audio routing parameters.", e);
        }
    }

    private void createAndSetLocalAudioTrack() {
        try {
            // FIX: Verify peerConnectionFactory is not null before using it
            if (peerConnectionFactory == null) {
                DiagnosticLogger.logError(TAG, "createAndSetLocalAudioTrack aborted: peerConnectionFactory is null.", null);
                return;
            }
            audioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());
            localAudioTrack = peerConnectionFactory.createAudioTrack(AUDIO_TRACK_ID, audioSource);
            localAudioTrack.setEnabled(true);
            peerConnection.addTrack(localAudioTrack, Collections.singletonList("stream1"));
            DiagnosticConnectionLog("Local audio track successfully acquired, enabled, and bound to stream.");
        } catch (Exception e) {
            DiagnosticLogger.logError(TAG, "Failed to allocate local audio capture hardware.", e);
        }
    }

    public void endCall() {
        DiagnosticLogger.logInfo(TAG, "endCall() triggered. Tearing down PeerConnection and resetting audio states.");
        
        resetAudioRouting();

        // Reset variables and clear queued remote candidate collection
        isRemoteDescriptionSet = false;
        synchronized (queuedRemoteCandidates) {
            queuedRemoteCandidates.clear();
        }

        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
            DiagnosticLogger.logInfo(TAG, "PeerConnection successfully closed.");
        }
        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
            DiagnosticLogger.logInfo(TAG, "AudioSource successfully disposed.");
        }
        signalingClient.endCall();
    }

    // Helper to safely drain incoming remote candidates that were collected early
    private void drainQueuedCandidates() {
        synchronized (queuedRemoteCandidates) {
            isRemoteDescriptionSet = true;
            DiagnosticLogger.logInfo(TAG, "Draining " + queuedRemoteCandidates.size() + " queued remote ICE candidates.");
            for (IceCandidate candidate : queuedRemoteCandidates) {
                if (peerConnection != null) {
                    peerConnection.addIceCandidate(candidate);
                    DiagnosticLogger.logInfo(TAG, "Drained and applied queued remote candidate.");
                }
            }
            queuedRemoteCandidates.clear();
        }
    }

    // FirebaseSignalingClient.SignalingListener methods
    @Override
    public void onOfferReceived(SessionDescription sessionDescription) {
        DiagnosticLogger.logInfo(TAG, "onOfferReceived() callback. Posting to Main Thread.");
        new android.os.Handler(Looper.getMainLooper()).post(() -> {
            if (peerConnection != null) {
                peerConnection.setRemoteDescription(new SdpObserver() {
                    @Override
                    public void onSetSuccess() {
                        DiagnosticLogger.logInfo(TAG, "Remote description successfully set for Offer on Main Thread. Creating SDP Answer.");
                        
                        // Drain queued candidates immediately after successfully setting the remote description
                        drainQueuedCandidates();

                        peerConnection.createAnswer(new SdpObserver() {
                            @Override
                            public void onCreateSuccess(SessionDescription answerSdp) {
                                DiagnosticLogger.logInfo(TAG, "WebRTC SDP Answer created successfully. Applying local description.");
                                peerConnection.setLocalDescription(new SdpObserver() {
                                    @Override
                                    public void onSetSuccess() {
                                        DiagnosticLogger.logInfo(TAG, "Local description set successfully for Answer. Dispatched to signaling database.");
                                        signalingClient.sendAnswer(answerSdp, targetUserUid);
                                    }
                                    @Override
                                    public void onCreateSuccess(SessionDescription sdp) {}
                                    @Override
                                    public void onSetFailure(String s) { DiagnosticLogger.logError(TAG, "Failed to apply local description for answer: " + s, null); }
                                    @Override
                                    public void onCreateFailure(String s) {}
                                }, answerSdp);
                            }
                            @Override
                            public void onSetSuccess() {}
                            @Override
                            public void onCreateFailure(String s) { DiagnosticLogger.logError(TAG, "Failed to create dynamic SDP Answer: " + s, null); }
                            @Override
                            public void onSetFailure(String s) {}
                        }, new MediaConstraints());
                    }
                    @Override
                    public void onCreateSuccess(SessionDescription sdp) {}
                    @Override
                    public void onSetFailure(String s) { DiagnosticLogger.logError(TAG, "Failed to apply remote description for offer: " + s, null); }
                    @Override
                    public void onCreateFailure(String s) {}
                }, sessionDescription);
            }
        });
    }

    @Override
    public void onAnswerReceived(SessionDescription sessionDescription) {
        DiagnosticLogger.logInfo(TAG, "onAnswerReceived() callback. Posting to Main Thread.");
        new android.os.Handler(Looper.getMainLooper()).post(() -> {
            if (peerConnection != null) {
                peerConnection.setRemoteDescription(new SdpObserver() {
                    @Override
                    public void onSetSuccess() {
                        DiagnosticLogger.logInfo(TAG, "Remote description successfully set for Answer on Main Thread. P2P channel ready.");
                        
                        // Drain queued candidates immediately after successfully setting the remote description
                        drainQueuedCandidates();
                    }
                    @Override
                    public void onCreateSuccess(SessionDescription sdp) {}
                    @Override
                    public void onSetFailure(String s) { DiagnosticLogger.logError(TAG, "Failed to apply remote description for answer: " + s, null); }
                    @Override
                    public void onCreateFailure(String s) {}
                }, sessionDescription);
            }
        });
    }

    @Override
    public void onIceCandidateReceived(IceCandidate iceCandidate) {
        DiagnosticLogger.logInfo(TAG, "onIceCandidateReceived() callback. Posting to Main Thread.");
        new android.os.Handler(Looper.getMainLooper()).post(() -> {
            if (peerConnection != null) {
                synchronized (queuedRemoteCandidates) {
                    if (isRemoteDescriptionSet) {
                        peerConnection.addIceCandidate(iceCandidate);
                        DiagnosticLogger.logInfo(TAG, "Remote candidate applied successfully on Main Thread.");
                    } else {
                        queuedRemoteCandidates.add(iceCandidate);
                        DiagnosticLogger.logInfo(TAG, "Remote description not set yet. Remote candidate queued on Main Thread.");
                    }
                }
            }
        });
    }

    @Override
    public void onCallEnded() {
        DiagnosticLogger.logInfo(TAG, "onCallEnded() signaling event received. Propagating callback to active listener.");
        if (listener != null) {
            listener.onWebRTCCallEnded();
        }
    }

    /**
     * Systematically closes peer connection and disposes factory contexts on a dedicated worker thread.
     * This avoids deadlocks or native crashes on the main thread (essential for Glitch 2).
     */
    public void cleanup() {
        new Thread(() -> {
            try {
                DiagnosticLogger.logInfo(TAG, "Native resource cleanup initiated on worker thread.");
                endCall();
                if (peerConnectionFactory != null) {
                    peerConnectionFactory.dispose();
                    peerConnectionFactory = null;
                    DiagnosticLogger.logInfo(TAG, "PeerConnectionFactory successfully disposed on worker thread.");
                }
                PeerConnectionFactory.shutdownInternalTracer();
                DiagnosticLogger.logInfo(TAG, "Native resource cleanup successfully finalized.");
            } catch (Exception e) {
                DiagnosticLogger.logError(TAG, "Exception encountered during background native resource cleanup.", e);
            }
        }).start();
    }

    private void DiagnosticConnectionLog(String message) {
        DiagnosticLogger.logInfo(TAG, message);
    }
}