package ru.miacomsoft.live360viewerserver.webrtc;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.util.Log;
import android.view.Surface;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebRtcServer implements SignalingServer.Listener {

    private static final String TAG = "WebRtcServer";
    private static final String VIDEO_TRACK_ID = "video0";
    private static final String AUDIO_TRACK_ID = "audio0";
    private static final String STREAM_ID      = "live360";

    private static final int VIDEO_WIDTH  = 1280;
    private static final int VIDEO_HEIGHT = 640;
    private static final int VIDEO_FPS    = 30;

    private final Context context;
    private final SignalingServer signaling;

    private PeerConnectionFactory factory;
    private EglBase eglBase;
    private JavaAudioDeviceModule audioDeviceModule;

    private SurfaceTextureCapturer videoCapturer;
    private VideoSource videoSource;
    private VideoTrack videoTrack;
    private AudioSource audioSource;
    private AudioTrack  audioTrack;

    private final Map<org.java_websocket.WebSocket, PeerConnection> peerConnections = new ConcurrentHashMap<>();
    private final ExecutorService wsExecutor = Executors.newSingleThreadExecutor();

    private volatile boolean started = false;
    private volatile boolean webRtcReady = false;

    public WebRtcServer(Context ctx, int signalingPort) {
        this.context = ctx.getApplicationContext();
        this.signaling = new SignalingServer(signalingPort, this);
    }

    public synchronized void start() {
        if (started) return;
        started = true;

        try {
            signaling.start();
            Log.i(TAG, "Signaling server start() called");
        } catch (Exception e) {
            Log.e(TAG, "Signaling server FAILED to start", e);
        }

        try {
            Log.i(TAG, "Initializing WebRTC...");
            PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                            .setEnableInternalTracer(false)
                            .createInitializationOptions());

            eglBase = EglBase.create();
            DefaultVideoEncoderFactory encFactory =
                    new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true);

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "RECORD_AUDIO не выдан — стрим пойдёт только с видео");
            }

            audioDeviceModule = JavaAudioDeviceModule.builder(context)
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setUseHardwareAcousticEchoCanceler(false)
                    .setUseHardwareNoiseSuppressor(false)
                    .createAudioDeviceModule();

            factory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(encFactory)
                    .setAudioDeviceModule(audioDeviceModule)
                    .createPeerConnectionFactory();

            createVideoTrack();
            createAudioTrack();

            webRtcReady = true;
            Log.i(TAG, "WebRTC initialized successfully");
        } catch (Throwable e) {
            Log.e(TAG, "CRITICAL: WebRTC initialization FAILED.", e);
        }
    }

    public synchronized void stop() {
        if (!started) return;
        started = false;
        webRtcReady = false;

        for (PeerConnection pc : peerConnections.values()) {
            try { pc.close(); } catch (Exception ignored) {}
        }
        peerConnections.clear();

        if (audioTrack  != null) { audioTrack.dispose();  audioTrack = null; }
        if (audioSource != null) { audioSource.dispose(); audioSource = null; }
        if (videoTrack  != null) { videoTrack.dispose();  videoTrack = null; }
        if (videoCapturer != null) { videoCapturer.dispose(); videoCapturer = null; }
        if (videoSource != null) { videoSource.dispose(); videoSource = null; }
        if (factory     != null) { factory.dispose();     factory = null; }
        if (audioDeviceModule != null) { audioDeviceModule.release(); audioDeviceModule = null; }
        if (eglBase     != null) { eglBase.release();     eglBase = null; }

        try { signaling.stop(); } catch (InterruptedException e) { Log.w(TAG, e.getMessage()); }
        wsExecutor.shutdown();
        Log.i(TAG, "WebRtcServer stopped");
    }

    private void createVideoTrack() {
        SurfaceTextureHelper helper = SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
        videoCapturer = new SurfaceTextureCapturer();
        videoSource = factory.createVideoSource(false);
        videoCapturer.initialize(helper, context, videoSource.getCapturerObserver());
        videoCapturer.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS);

        videoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource);
        videoTrack.setEnabled(true);
        Log.i(TAG, "videoTrack created " + VIDEO_WIDTH + "x" + VIDEO_HEIGHT + "@" + VIDEO_FPS);
    }

    private void createAudioTrack() {
        MediaConstraints c = new MediaConstraints();
        c.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "false"));
        c.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl", "false"));
        c.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "false"));
        c.mandatory.add(new MediaConstraints.KeyValuePair("googHighpassFilter", "false"));

        audioSource = factory.createAudioSource(c);
        audioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, audioSource);
        audioTrack.setEnabled(true);
        Log.i(TAG, "audioTrack created");
    }

    public Surface getCaptureSurface() {
        return videoCapturer != null ? videoCapturer.getSurface() : null;
    }

    public void updateVideoResolution(int w, int h) {
        if (videoCapturer != null) {
            Log.i(TAG, "updateVideoResolution: " + w + "x" + h);
            videoCapturer.changeCaptureFormat(w, h, VIDEO_FPS);
        }
    }

    @Override
    public void onClientConnected(org.java_websocket.WebSocket conn) {
        if (!webRtcReady) {
            Log.e(TAG, "Client connected, but WebRTC failed to initialize!");
            try {
                JSONObject err = new JSONObject();
                err.put("type", "error");
                err.put("message", "WebRTC initialization failed on device.");
                conn.send(err.toString());
            } catch (Exception ignored) {}
            return;
        }
        Log.i(TAG, "Browser connected: " + conn.getRemoteSocketAddress());
        createPeerConnectionFor(conn);
    }

    @Override
    public void onMessage(org.java_websocket.WebSocket conn, String message) {
        try {
            JSONObject json = new JSONObject(message);
            String type = json.getString("type");
            PeerConnection pc = peerConnections.get(conn);
            if (pc == null) return;

            if ("answer".equals(type)) {
                pc.setRemoteDescription(new SimpleSdpObserver(),
                        new SessionDescription(SessionDescription.Type.ANSWER, json.getString("sdp")));
            } else if ("candidate".equals(type)) {
                JSONObject c = json.getJSONObject("candidate");
                String mid  = c.optString("sdpMid", null);
                String cand = c.optString("candidate", null);
                if (mid != null && cand != null) {
                    pc.addIceCandidate(new IceCandidate(mid, c.optInt("sdpMLineIndex", 0), cand));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "onMessage: " + e.getMessage());
        }
    }

    @Override
    public void onClientDisconnected(org.java_websocket.WebSocket conn) {
        Log.i(TAG, "Browser disconnected: " + conn.getRemoteSocketAddress());
        PeerConnection pc = peerConnections.remove(conn);
        if (pc != null) {
            try { pc.close(); } catch (Exception ignored) {}
        }
    }

    private void createPeerConnectionFor(final org.java_websocket.WebSocket conn) {
        List<PeerConnection.IceServer> ice = new ArrayList<>();
        ice.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());

        PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(ice);
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        PeerConnection pc = factory.createPeerConnection(cfg, new PeerConnection.Observer() {
            @Override public void onIceCandidate(IceCandidate c) {
                try {
                    JSONObject cand = new JSONObject();
                    cand.put("sdpMid", c.sdpMid);
                    cand.put("sdpMLineIndex", c.sdpMLineIndex);
                    cand.put("candidate", c.sdp);
                    JSONObject msg = new JSONObject();
                    msg.put("type", "candidate");
                    msg.put("candidate", cand);
                    sendTo(conn, msg.toString());
                } catch (Exception ignored) {}
            }
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                Log.i(TAG, "ICE [" + conn.getRemoteSocketAddress() + "]: " + s);
            }
            @Override public void onSignalingChange(PeerConnection.SignalingState s) {}
            @Override public void onIceConnectionReceivingChange(boolean b) {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}
            @Override public void onIceCandidatesRemoved(IceCandidate[] c) {}
            @Override public void onAddStream(MediaStream s) {}
            @Override public void onRemoveStream(MediaStream s) {}
            @Override public void onDataChannel(DataChannel dc) {}
            @Override public void onRenegotiationNeeded() {}
            @Override public void onAddTrack(RtpReceiver r, MediaStream[] ms) {}
        });

        if (pc == null) {
            Log.e(TAG, "createPeerConnection returned NULL");
            return;
        }

        peerConnections.put(conn, pc);

        if (videoTrack != null) pc.addTrack(videoTrack, Collections.singletonList(STREAM_ID));
        if (audioTrack != null) pc.addTrack(audioTrack, Collections.singletonList(STREAM_ID));

        createOfferFor(conn, pc);
    }

    private void createOfferFor(final org.java_websocket.WebSocket conn, final PeerConnection pc) {
        MediaConstraints c = new MediaConstraints();
        c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));
        c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));

        pc.createOffer(new SimpleSdpObserver() {
            @Override public void onCreateSuccess(SessionDescription sdp) {
                pc.setLocalDescription(new SimpleSdpObserver(), sdp);
                try {
                    JSONObject msg = new JSONObject();
                    msg.put("type", "offer");
                    msg.put("sdp", sdp.description);
                    sendTo(conn, msg.toString());
                } catch (Exception ignored) {}
            }
        }, c);
    }

    private void sendTo(org.java_websocket.WebSocket conn, String text) {
        if (conn != null && conn.isOpen()) {
            wsExecutor.execute(() -> {
                try { conn.send(text); } catch (Exception ignored) {}
            });
        }
    }

    private static class SimpleSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription s) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String s) { Log.e(TAG, "SDP: " + s); }
        @Override public void onSetFailure(String s)  { Log.e(TAG, "SDP: " + s); }
    }
}