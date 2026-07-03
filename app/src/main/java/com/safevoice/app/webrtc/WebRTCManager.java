package com.safevoice.app.webrtc;

import android.content.Context;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

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

import java.io.IOException;
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
    }

    public void answerCall(String sessionId, String callerUid) {
        this.targetUserUid = callerUid;
        this.signalingClient.joinCallSession(sessionId);
        this.peerConnection = createPeerConnection();
        if (this.peerConnection == null) {
            Log.e(TAG, "PeerConnection creation failed.");
            return;
        }
        createAndSetLocalAudioTrack();
    }

    private PeerConnection createPeerConnection() {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        // Add Google's public STUN server
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());

        // Add custom TURN servers from EncryptedSharedPreferences if available
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            EncryptedSharedPreferences sharedPreferences = (EncryptedSharedPreferences) EncryptedSharedPreferences.create(
                    "TwilioCredentials",
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            String accountSid = sharedPreferences.getString("ACCOUNT_SID", null);
            String apiKey = sharedPreferences.getString("API_KEY", null);
            String apiSecret = sharedPreferences.getString("API_SECRET", null);

            if (accountSid != null && apiKey != null && apiSecret != null) {
                // In a real app, you would use these credentials to fetch TURN server info from Twilio's API.
                // For this example, we'll log that we have them. A real implementation is complex.
                Log.i(TAG, "Twilio credentials found. In a real app, fetch TURN servers here.");
                // Example of how you would add a TURN server if you had the URI/user/pass
                // iceServers.add(PeerConnection.IceServer.builder("turn:your-turn-server.com")
                //         .setUsername("user").setPassword("pass").createIceServer());
            }

        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Could not read EncryptedSharedPreferences", e);
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