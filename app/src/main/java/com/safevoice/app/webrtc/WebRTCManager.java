package com.safevoice.app.webrtc;

import android.content.Context;
import android.os.Build;
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

    private String targetUserUid;
    private final WebRTCListener listener;

    // Cache list to store Twilio TURN servers dynamically fetched from the REST API
    private final List<PeerConnection.IceServer> twilioIceServers = new ArrayList<>();

    public interface WebRTCListener {
        void onWebRTCCallEstablished();
        void onWebRTCCallEnded();
    }

    public WebRTCManager(Context context, WebRTCListener listener) {
        this.context = context;
        this.listener = listener;
        this.signalingClient = new FirebaseSignalingClient();
        this.signalingClient.setListener(this);
        initializePeerConnectionFactory();
    }

    // --- THIS IS THE FIX ---
    // This public method allows other classes like EmergencyHandlerService to get the session ID.
    public FirebaseSignalingClient getSignalingClient() {
        return this.signalingClient;
    }

    private void initializePeerConnectionFactory() {
        PeerConnectionFactory.InitializationOptions initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions();
        PeerConnectionFactory.initialize(initializationOptions);

        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .createPeerConnectionFactory();
    }

    public void startCall(String targetUserUid) {
        this.targetUserUid = targetUserUid;

        // Fetch Twilio TURN servers asynchronously first before building the PeerConnection
        fetchTwilioTokens(() -> {
            this.peerConnection = createPeerConnection();
            if (this.peerConnection == null) {
                Log.e(TAG, "PeerConnection creation failed.");
                return;
            }

            createAndSetLocalAudioTrack();

            peerConnection.createOffer(new SdpObserver() {
                @Override
                public void onCreateSuccess(SessionDescription sessionDescription) {
                    Log.d(TAG, "Offer created successfully.");
                    peerConnection.setLocalDescription(new SdpObserver() {
                        @Override
                        public void onSetSuccess() {
                            Log.d(TAG, "Local description set successfully for offer.");
                            signalingClient.sendOffer(sessionDescription, targetUserUid);
                        }
                        @Override
                        public void onCreateSuccess(SessionDescription sdp) {}
                        @Override
                        public void onSetFailure(String s) { Log.e(TAG, "Failed to set local description for offer: " + s); }
                        @Override
                        public void onCreateFailure(String s) {}
                    }, sessionDescription);
                }
                @Override
                public void onSetSuccess() {}
                @Override
                public void onCreateFailure(String s) { Log.e(TAG, "Failed to create offer: " + s); }
                @Override
                public void onSetFailure(String s) {}
            }, new MediaConstraints());
        });
    }

    public void answerCall(String sessionId, String callerUid) {
        this.targetUserUid = callerUid;
        this.signalingClient.joinCallSession(sessionId);

        // Fetch Twilio TURN servers asynchronously first before building the PeerConnection
        fetchTwilioTokens(() -> {
            this.peerConnection = createPeerConnection();
            if (this.peerConnection == null) {
                Log.e(TAG, "PeerConnection creation failed.");
                return;
            }
            createAndSetLocalAudioTrack();
        });
    }

    /**
     * Authenticates with Twilio over HTTP and retrieves dynamic network traversal tokens on a worker thread.
     * Always triggers the completion callback on the Main UI thread.
     */
    private void fetchTwilioTokens(Runnable onComplete) {
        synchronized (twilioIceServers) {
            if (!twilioIceServers.isEmpty()) {
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
            Log.e(TAG, "Could not load saved Twilio credentials", e);
        }

        if (accountSid == null || accountSid.isEmpty() ||
            apiKey == null || apiKey.isEmpty() ||
            apiSecret == null || apiSecret.isEmpty()) {
            Log.w(TAG, "Twilio credentials not found. Proceeding with standard STUN fallback.");
            onComplete.run();
            return;
        }

        final String finalAccountSid = accountSid;
        final String finalApiKey = apiKey;
        final String finalApiSecret = apiSecret;

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
                    Log.e(TAG, "Twilio Token API error response code: " + responseCode);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to connect to Twilio Network Traversal API", e);
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
                Log.d(TAG, "Dynamic Twilio ICE Server Configuration populated. Total servers: " + parsedList.size());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing Twilio API response payload", e);
        }
    }

    private PeerConnection createPeerConnection() {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        // Add Google's public STUN server as default
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());

        // Add dynamically-fetched Twilio TURN/STUN servers
        synchronized (twilioIceServers) {
            if (!twilioIceServers.isEmpty()) {
                iceServers.addAll(twilioIceServers);
                Log.d(TAG, "Added Twilio dynamic relay endpoints to client config.");
            } else {
                Log.w(TAG, "Twilio configuration list empty. Falling back to default P2P STUN.");
            }
        }

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        return peerConnectionFactory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {
                Log.d(TAG, "onSignalingChange: " + signalingState);
            }
            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                Log.d(TAG, "onIceConnectionChange: " + iceConnectionState);
                if (iceConnectionState == PeerConnection.IceConnectionState.CONNECTED) {
                    listener.onWebRTCCallEstablished();
                } else if (iceConnectionState == PeerConnection.IceConnectionState.FAILED || iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED) {
                    endCall();
                }
            }
            @Override
            public void onIceConnectionReceivingChange(boolean b) {}
            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                Log.d(TAG, "onIceGatheringChange: " + iceGatheringState);
            }
            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                Log.d(TAG, "onIceCandidate: sending candidate");
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
                Log.d(TAG, "Remote audio track received.");
            }
        });
    }

    private void createAndSetLocalAudioTrack() {
        audioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());
        localAudioTrack = peerConnectionFactory.createAudioTrack(AUDIO_TRACK_ID, audioSource);
        localAudioTrack.setEnabled(true);
        peerConnection.addTrack(localAudioTrack, Collections.singletonList("stream1"));
    }

    public void endCall() {
        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
        }
        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
        }
        signalingClient.endCall();
    }

    // FirebaseSignalingClient.SignalingListener methods
    @Override
    public void onOfferReceived(SessionDescription sessionDescription) {
        Log.d(TAG, "Offer received.");
        if (peerConnection != null) {
            peerConnection.setRemoteDescription(new SdpObserver() {
                @Override
                public void onSetSuccess() {
                    Log.d(TAG, "Remote description set for offer.");
                    peerConnection.createAnswer(new SdpObserver() {
                        @Override
                        public void onCreateSuccess(SessionDescription answerSdp) {
                            Log.d(TAG, "Answer created successfully.");
                            peerConnection.setLocalDescription(new SdpObserver() {
                                @Override
                                public void onSetSuccess() {
                                    Log.d(TAG, "Local description set for answer.");
                                    signalingClient.sendAnswer(answerSdp, targetUserUid);
                                }
                                @Override
                                public void onCreateSuccess(SessionDescription sdp) {}
                                @Override
                                public void onSetFailure(String s) { Log.e(TAG, "Failed to set local description for answer: " + s); }
                                @Override
                                public void onCreateFailure(String s) {}
                            }, answerSdp);
                        }
                        @Override
                        public void onSetSuccess() {}
                        @Override
                        public void onCreateFailure(String s) { Log.e(TAG, "Failed to create answer: " + s); }
                        @Override
                        public void onSetFailure(String s) {}
                    }, new MediaConstraints());
                }
                @Override
                public void onCreateSuccess(SessionDescription sdp) {}
                @Override
                public void onSetFailure(String s) { Log.e(TAG, "Failed to set remote description for offer: " + s); }
                @Override
                public void onCreateFailure(String s) {}
            }, sessionDescription);
        }
    }

    @Override
    public void onAnswerReceived(SessionDescription sessionDescription) {
        Log.d(TAG, "Answer received.");
        if (peerConnection != null) {
            peerConnection.setRemoteDescription(new SdpObserver() {
                @Override
                public void onSetSuccess() {
                    Log.d(TAG, "Remote description set for answer.");
                }
                @Override
                public void onCreateSuccess(SessionDescription sdp) {}
                @Override
                public void onSetFailure(String s) { Log.e(TAG, "Failed to set remote description for answer: " + s); }
                @Override
                public void onCreateFailure(String s) {}
            }, sessionDescription);
        }
    }

    @Override
    public void onIceCandidateReceived(IceCandidate iceCandidate) {
        Log.d(TAG, "ICE candidate received.");
        if (peerConnection != null) {
            peerConnection.addIceCandidate(iceCandidate);
        }
    }

    @Override
    public void onCallEnded() {
        Log.d(TAG, "Call ended signal received.");
        if (listener != null) {
            listener.onWebRTCCallEnded();
        }
    }

    public void cleanup() {
        endCall();
        if (peerConnectionFactory != null) {
            peerConnectionFactory.dispose();
            peerConnectionFactory = null;
        }
        PeerConnectionFactory.shutdownInternalTracer();
    }
}