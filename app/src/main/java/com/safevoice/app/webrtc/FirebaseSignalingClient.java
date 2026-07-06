package com.safevoice.app.webrtc;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.safevoice.app.utils.DiagnosticLogger;

import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Handles WebRTC call signaling via Firebase Realtime Database.
 * Overhauled to point signaling listener channels, session keys, and authenticated
 * user contexts directly to the custom secondary safety circle app instance.
 */
public class FirebaseSignalingClient {

    private static final String TAG = "FirebaseSignalingClient";
    private static final String CALL_SESSIONS_NODE = "call_sessions";

    private final FirebaseDatabase database;
    private final String currentUserUid;
    private DatabaseReference callSessionRef;
    private SignalingListener listener;

    private ValueEventListener offerListener;
    private ValueEventListener iceCandidateListener;
    private ValueEventListener sessionListener;

    // Track roles to implement robust, unidirectional signaling routes and prevent self-subscription bugs
    private boolean isCaller = false;

    public interface SignalingListener {
        void onOfferReceived(SessionDescription sessionDescription);
        void onAnswerReceived(SessionDescription sessionDescription);
        void onIceCandidateReceived(IceCandidate iceCandidate);
        void onCallEnded();
    }

    public FirebaseSignalingClient() {
        FirebaseApp circleApp;
        try {
            // Retrieve the custom secondary safety circle Firebase project reference
            circleApp = FirebaseApp.getInstance("safe_voice_circle");
            DiagnosticLogger.logInfo(TAG, "Signaling client connecting to secondary 'safe_voice_circle' instance.");
        } catch (IllegalStateException e) {
            Log.e(TAG, "Secondary safe_voice_circle not initialized yet. Falling back to default app.", e);
            DiagnosticLogger.logWarn(TAG, "Secondary 'safe_voice_circle' database missing. Falling back to default Firebase App.");
            circleApp = FirebaseApp.getInstance();
        }

        this.database = FirebaseDatabase.getInstance(circleApp);
        this.currentUserUid = Objects.requireNonNull(FirebaseAuth.getInstance(circleApp).getCurrentUser()).getUid();
        DiagnosticLogger.logInfo(TAG, "FirebaseSignalingClient initialized successfully for UID: " + currentUserUid);
    }

    public void setListener(SignalingListener listener) {
        this.listener = listener;
    }

    public void createCallSession(String targetUserUid) {
        this.isCaller = true;
        // A unique session ID is created by the caller
        this.callSessionRef = database.getReference(CALL_SESSIONS_NODE).push();
        DiagnosticLogger.logInfo(TAG, "Created new Call Session node in RTDB at path: " + callSessionRef.getPath());
        listenForSignals(targetUserUid);
    }

    public void joinCallSession(String sessionId) {
        this.isCaller = false;
        this.callSessionRef = database.getReference(CALL_SESSIONS_NODE).child(sessionId);
        DiagnosticLogger.logInfo(TAG, "Joining existing Call Session ID: " + sessionId + " at path: " + callSessionRef.getPath());
        
        // The callee needs to know who the caller is to send signals back
        callSessionRef.child("callerUid").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                String callerUid = snapshot.getValue(String.class);
                DiagnosticLogger.logInfo(TAG, "Retrieved Caller UID for session: " + callerUid);
                if (callerUid != null) {
                    listenForSignals(callerUid);
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                DiagnosticLogger.logError(TAG, "Failed to retrieve caller UID from session.", error.toException());
            }
        });
    }

    private void listenForSignals(String remoteUserUid) {
        DiagnosticLogger.logInfo(TAG, "Subscribing signaling listeners. Current role isCaller: " + isCaller + ", Remote user: " + remoteUserUid);

        // 1. Listener for session lifecycle changes (essential to solve Glitch 2)
        sessionListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    String type = snapshot.child("type").getValue(String.class);
                    if ("end_call".equalsIgnoreCase(type)) {
                        DiagnosticLogger.logInfo(TAG, "Received remote 'end_call' signal state on session root. Closing call.");
                        if (listener != null) {
                            listener.onCallEnded();
                        }
                    }
                } else {
                    // Node deletion indicates the remote side terminated the session
                    DiagnosticLogger.logInfo(TAG, "Session node deleted by remote peer. Closing call.");
                    if (listener != null) {
                        listener.onCallEnded();
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                DiagnosticLogger.logError(TAG, "Session lifecycle listener cancelled.", error.toException());
            }
        };

        // 2. Listener for SDP signals
        offerListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    String type = snapshot.child("type").getValue(String.class);
                    String sdp = snapshot.child("sdp").getValue(String.class);
                    DiagnosticLogger.logInfo(TAG, "Received SDP data change. Type: " + type);
                    if (type != null && sdp != null) {
                        SessionDescription sessionDescription = new SessionDescription(
                                SessionDescription.Type.fromCanonicalForm(type.toLowerCase()), sdp);
                        if ("offer".equalsIgnoreCase(type)) {
                            DiagnosticLogger.logInfo(TAG, "Forwarding parsed SDP Offer payload to PeerConnection.");
                            listener.onOfferReceived(sessionDescription);
                        } else if ("answer".equalsIgnoreCase(type)) {
                            DiagnosticLogger.logInfo(TAG, "Forwarding parsed SDP Answer payload to PeerConnection.");
                            listener.onAnswerReceived(sessionDescription);
                        }
                        // Consume the signal
                        snapshot.getRef().removeValue()
                                .addOnSuccessListener(aVoid -> DiagnosticLogger.logInfo(TAG, "Successfully consumed and cleared remote SDP signal."))
                                .addOnFailureListener(e -> DiagnosticLogger.logError(TAG, "Failed to clear remote SDP signal node.", e));
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                DiagnosticLogger.logError(TAG, "Offer/Answer listener cancelled.", error.toException());
            }
        };

        // 3. Listener for ICE candidates
        iceCandidateListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    DiagnosticLogger.logInfo(TAG, "Received new ICE Candidates pool from remote peer.");
                    for (DataSnapshot candidateSnapshot : snapshot.getChildren()) {
                        String sdpMid = candidateSnapshot.child("sdpMid").getValue(String.class);
                        Integer sdpMLineIndexVal = candidateSnapshot.child("sdpMLineIndex").getValue(Integer.class);
                        String sdp = candidateSnapshot.child("sdp").getValue(String.class);
                        
                        if (sdpMid != null && sdpMLineIndexVal != null && sdp != null) {
                            int sdpMLineIndex = sdpMLineIndexVal;
                            IceCandidate iceCandidate = new IceCandidate(sdpMid, sdpMLineIndex, sdp);
                            listener.onIceCandidateReceived(iceCandidate);
                        }
                    }
                    // Consume all candidates
                    snapshot.getRef().removeValue()
                            .addOnSuccessListener(aVoid -> DiagnosticLogger.logInfo(TAG, "Successfully consumed and cleared remote ICE candidates pool."))
                            .addOnFailureListener(e -> DiagnosticLogger.logError(TAG, "Failed to clear remote ICE candidates pool node.", e));
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                DiagnosticLogger.logError(TAG, "ICE candidate listener cancelled.", error.toException());
            }
        };

        // Caller listens to remote answers and callee's ICE candidates
        // Callee listens to remote offers and caller's ICE candidates
        String sdpNodeName = isCaller ? "answer" : "offer";
        String iceNodeName = isCaller ? "callee_ice" : "caller_ice";

        callSessionRef.addValueEventListener(sessionListener);
        callSessionRef.child(sdpNodeName).addValueEventListener(offerListener);
        callSessionRef.child(iceNodeName).addValueEventListener(iceCandidateListener);
    }

    public void sendOffer(SessionDescription sdp, String targetUserUid) {
        if (callSessionRef == null) {
            createCallSession(targetUserUid);
        }
        DiagnosticLogger.logInfo(TAG, "Sending SDP Offer to target session root.");
        Map<String, Object> offerData = new HashMap<>();
        offerData.put("type", "offer");
        offerData.put("sdp", sdp.description);

        callSessionRef.child("callerUid").setValue(currentUserUid);
        callSessionRef.child("offer").setValue(offerData)
                .addOnSuccessListener(aVoid -> DiagnosticLogger.logInfo(TAG, "SDP Offer uploaded successfully."))
                .addOnFailureListener(e -> DiagnosticLogger.logError(TAG, "Failed to upload SDP Offer.", e));
    }

    public void sendAnswer(SessionDescription sdp, String targetUserUid) {
        DiagnosticLogger.logInfo(TAG, "Sending SDP Answer to target session root.");
        Map<String, Object> answerData = new HashMap<>();
        answerData.put("type", "answer");
        answerData.put("sdp", sdp.description);
        
        callSessionRef.child("answer").setValue(answerData)
                .addOnSuccessListener(aVoid -> DiagnosticLogger.logInfo(TAG, "SDP Answer uploaded successfully."))
                .addOnFailureListener(e -> DiagnosticLogger.logError(TAG, "Failed to upload SDP Answer.", e));
    }

    public void sendIceCandidate(IceCandidate iceCandidate, String targetUserUid) {
        if (callSessionRef == null) {
            DiagnosticLogger.logWarn(TAG, "Skipping ICE candidate upload. Active call session reference is null.");
            return;
        }
        Map<String, Object> candidateData = new HashMap<>();
        candidateData.put("sdpMid", iceCandidate.sdpMid);
        candidateData.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
        candidateData.put("sdp", iceCandidate.sdp);

        // Caller uploads to caller_ice; callee uploads to callee_ice
        String iceNodeName = isCaller ? "caller_ice" : "callee_ice";
        callSessionRef.child(iceNodeName).push().setValue(candidateData);
    }

    public void endCall() {
        DiagnosticLogger.logInfo(TAG, "endCall() triggered manually. Detaching observers and tearing down database nodes.");
        
        if (callSessionRef != null) {
            // Remove listeners immediately to prevent crash loops on active thread resource disposal
            if (sessionListener != null) {
                callSessionRef.removeEventListener(sessionListener);
                sessionListener = null;
            }
            if (offerListener != null) {
                String sdpNodeName = isCaller ? "answer" : "offer";
                callSessionRef.child(sdpNodeName).removeEventListener(offerListener);
                offerListener = null;
            }
            if (iceCandidateListener != null) {
                String iceNodeName = isCaller ? "callee_ice" : "caller_ice";
                callSessionRef.child(iceNodeName).removeEventListener(iceCandidateListener);
                iceCandidateListener = null;
            }
            
            // Signal the other user that the call is over
            Map<String, Object> endCallData = new HashMap<>();
            endCallData.put("type", "end_call");
            callSessionRef.setValue(endCallData);

            // Clean up the entire session node from the database
            callSessionRef.removeValue();
            callSessionRef = null;
            DiagnosticLogger.logInfo(TAG, "Call session node written with end_call and removed successfully from database.");
        }
        
        if (listener != null) {
            listener.onCallEnded();
        }
    }

    public String getSessionId() {
        return (callSessionRef != null) ? callSessionRef.getKey() : null;
    }
}