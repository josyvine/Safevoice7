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
    private ValueEventListener answerListener;
    private ValueEventListener iceCandidateListener;

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
        } catch (IllegalStateException e) {
            Log.e(TAG, "Secondary safe_voice_circle not initialized yet. Falling back to default app.", e);
            circleApp = FirebaseApp.getInstance();
        }

        this.database = FirebaseDatabase.getInstance(circleApp);
        this.currentUserUid = Objects.requireNonNull(FirebaseAuth.getInstance(circleApp).getCurrentUser()).getUid();
    }

    public void setListener(SignalingListener listener) {
        this.listener = listener;
    }

    public void createCallSession(String targetUserUid) {
        // A unique session ID is created by the caller
        this.callSessionRef = database.getReference(CALL_SESSIONS_NODE).push();
        listenForSignals(targetUserUid);
    }

    public void joinCallSession(String sessionId) {
        this.callSessionRef = database.getReference(CALL_SESSIONS_NODE).child(sessionId);
        // The callee needs to know who the caller is to send signals back
        callSessionRef.child("callerUid").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                String callerUid = snapshot.getValue(String.class);
                if (callerUid != null) {
                    listenForSignals(callerUid);
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Failed to get caller UID", error.toException());
            }
        });
    }

    private void listenForSignals(String remoteUserUid) {
        // Listener for offer or answer
        offerListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    String type = snapshot.child("type").getValue(String.class);
                    String sdp = snapshot.child("sdp").getValue(String.class);
                    if (type != null && sdp != null) {
                        SessionDescription sessionDescription = new SessionDescription(SessionDescription.Type.fromCanonicalForm(type.toLowerCase()), sdp);
                        if ("offer".equalsIgnoreCase(type)) {
                            listener.onOfferReceived(sessionDescription);
                        } else if ("answer".equalsIgnoreCase(type)) {
                            listener.onAnswerReceived(sessionDescription);
                        }
                        // Consume the signal
                        snapshot.getRef().removeValue();
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Offer/Answer listener cancelled", error.toException());
            }
        };

        // Listener for ICE candidates
        iceCandidateListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    for(DataSnapshot candidateSnapshot : snapshot.getChildren()) {
                        String sdpMid = candidateSnapshot.child("sdpMid").getValue(String.class);
                        int sdpMLineIndex = candidateSnapshot.child("sdpMLineIndex").getValue(Integer.class);
                        String sdp = candidateSnapshot.child("sdp").getValue(String.class);
                        if(sdpMid != null && sdp != null) {
                            IceCandidate iceCandidate = new IceCandidate(sdpMid, sdpMLineIndex, sdp);
                            listener.onIceCandidateReceived(iceCandidate);
                        }
                    }
                    // Consume all candidates
                    snapshot.getRef().removeValue();
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "ICE candidate listener cancelled", error.toException());
            }
        };

        callSessionRef.child(remoteUserUid).addValueEventListener(offerListener);
        callSessionRef.child(remoteUserUid + "_ice").addValueEventListener(iceCandidateListener);
    }


    public void sendOffer(SessionDescription sdp, String targetUserUid) {
        if (callSessionRef == null) {
            createCallSession(targetUserUid);
        }
        Map<String, Object> offerData = new HashMap<>();
        offerData.put("type", "offer");
        offerData.put("sdp", sdp.description);

        callSessionRef.child("callerUid").setValue(currentUserUid);
        callSessionRef.child(targetUserUid).setValue(offerData);
    }

    public void sendAnswer(SessionDescription sdp, String targetUserUid) {
        Map<String, Object> answerData = new HashMap<>();
        answerData.put("type", "answer");
        answerData.put("sdp", sdp.description);
        callSessionRef.child(targetUserUid).setValue(answerData);
    }

    public void sendIceCandidate(IceCandidate iceCandidate, String targetUserUid) {
        Map<String, Object> candidateData = new HashMap<>();
        candidateData.put("sdpMid", iceCandidate.sdpMid);
        candidateData.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
        candidateData.put("sdp", iceCandidate.sdp);

        callSessionRef.child(targetUserUid + "_ice").push().setValue(candidateData);
    }

    public void endCall() {
        if (callSessionRef != null) {
            // Remove listeners to stop receiving events
            if (offerListener != null) {
                callSessionRef.removeEventListener(offerListener);
            }
            if (iceCandidateListener != null) {
                callSessionRef.removeEventListener(iceCandidateListener);
            }
            // Signal the other user that the call is over
            Map<String, Object> endCallData = new HashMap<>();
            endCallData.put("type", "end_call");
            callSessionRef.setValue(endCallData);

            // Clean up the entire session node from the database
            callSessionRef.removeValue();
            callSessionRef = null;
        }
        if (listener != null) {
            listener.onCallEnded();
        }
    }

    public String getSessionId() {
        return (callSessionRef != null) ? callSessionRef.getKey() : null;
    }
}