package ru.miacomsoft.live360viewerserver.webrtc;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.Surface;

import org.webrtc.CapturerObserver;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

/**
 * «Капчер» без собственной камеры: отдаёт Surface, в который пишет H264Decoder.
 * Кадры, отрендеренные в Surface, попадают в VideoSource → VideoTrack → PeerConnection.
 */
public class SurfaceTextureCapturer implements VideoCapturer, VideoSink {

    private SurfaceTextureHelper helper;
    private CapturerObserver observer;
    private SurfaceTexture surfaceTexture;
    private Surface surface;

    @Override
    public void initialize(SurfaceTextureHelper helper, Context appContext,
                           CapturerObserver observer) {
        this.helper = helper;
        this.observer = observer;
        this.surfaceTexture = helper.getSurfaceTexture();
        this.surfaceTexture.setDefaultBufferSize(1280, 640);
        this.helper.setTextureSize(1280, 640);
        this.surface = new Surface(surfaceTexture);
        helper.startListening(this);
    }

    @Override
    public void startCapture(int w, int h, int fps) {
        if (helper != null) helper.setTextureSize(w, h);
        if (surfaceTexture != null) surfaceTexture.setDefaultBufferSize(w, h);
        if (observer != null) observer.onCapturerStarted(true);
    }

    @Override public void stopCapture() { if (observer != null) observer.onCapturerStopped(); }

    @Override
    public void changeCaptureFormat(int w, int h, int fps) {
        if (helper != null) helper.setTextureSize(w, h);
        if (surfaceTexture != null) surfaceTexture.setDefaultBufferSize(w, h);
    }

    @Override
    public void dispose() {
        if (surface != null) surface.release();
        if (surfaceTexture != null) surfaceTexture.release();
        if (helper != null) helper.dispose();
        surface = null; surfaceTexture = null; helper = null;
    }

    @Override public boolean isScreencast() { return false; }

    @Override public void onFrame(VideoFrame frame) {
        if (observer != null) observer.onFrameCaptured(frame);
    }

    public Surface getSurface() { return surface; }
}