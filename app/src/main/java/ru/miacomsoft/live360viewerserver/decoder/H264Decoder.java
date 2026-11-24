package ru.miacomsoft.live360viewerserver.decoder;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

public class H264Decoder {

    private static final String TAG = "H264Decoder";
    private static final String MIME = "video/avc";

    // ★ codec и surface больше не final: их можно менять в updateSurface().
    //   volatile — потому что drainLoop читает их без synchronized.
    private volatile MediaCodec codec;
    private volatile Surface surface;
    private final int width, height;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private volatile boolean configured = false;
    private volatile boolean running = false;
    private Thread outputThread;

    public H264Decoder(Surface surface, int width, int height) {
        this.surface = surface;
        this.width = width;
        this.height = height;
    }

    public synchronized void start() throws IOException {
        startInternal();
    }

    /**
     * Swap output surface without tearing down the caller.
     *
     * Recreates MediaCodec: codec.configure(surface) cannot be changed after
     * start(). setOutputSurface() (API 23+) is not universally reliable across
     * vendor codecs, so recreate is the safe path.
     *
     * Safe to call from any thread. If the codec was running, it is
     * restarted transparently with the new surface.
     */
    public synchronized void updateSurface(Surface newSurface) throws IOException {
        if (newSurface == null) {
            Log.w(TAG, "updateSurface: null surface, ignoring");
            return;
        }
        if (newSurface == surface && configured && codec != null) {
            Log.i(TAG, "updateSurface: same surface, no-op");
            return;
        }
        Log.i(TAG, "updateSurface: swapping output surface");
        boolean restart = running;
        stopInternal();
        this.surface = newSurface;
        if (restart) {
            startInternal();
        }
    }

    private void startInternal() throws IOException {
        Surface s = surface;
        if (s == null) {
            throw new IOException("surface is null");
        }

        // ★ Не задаём width/height жёстко — MediaCodec возьмёт SPS из потока.
        MediaFormat fmt = MediaFormat.createVideoFormat(MIME,
                width > 0 ? width : 1920,
                height > 0 ? height : 960);
        if (Build.VERSION.SDK_INT >= 21) {
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024);
        }
        fmt.setInteger(MediaFormat.KEY_PUSH_BLANK_BUFFERS_ON_STOP, 1);

        MediaCodec c = MediaCodec.createDecoderByType(MIME);
        c.configure(fmt, s, null, 0);
        c.start();

        codec = c;
        configured = true;
        running = true;
        outputThread = new Thread(this::drainLoop, "H264-drain");
        outputThread.start();
    }

    public synchronized void feed(byte[] annexB, long ptsUs) {
        MediaCodec c = codec;
        if (!configured || c == null) return;
        try {
            int inIdx = c.dequeueInputBuffer(0);
            if (inIdx < 0) return;
            ByteBuffer inBuf = c.getInputBuffer(inIdx);
            if (inBuf == null) return;
            inBuf.clear();
            int n = Math.min(inBuf.capacity(), annexB.length);
            inBuf.put(annexB, 0, n);
            c.queueInputBuffer(inIdx, 0, n, ptsUs, 0);
        } catch (Exception e) {
            if (configured) Log.w(TAG, "feed: " + e.getMessage());
        }
    }

    private void drainLoop() {
        while (running) {
            MediaCodec c = codec;
            if (c == null) {
                try { Thread.sleep(10); }
                catch (InterruptedException e) { return; }
                continue;
            }
            try {
                int outIdx = c.dequeueOutputBuffer(info, 10000);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outFmt = c.getOutputFormat();
                    Log.i(TAG, "output format changed: " + outFmt);
                    int w = outFmt.getInteger(MediaFormat.KEY_WIDTH);
                    int h = outFmt.getInteger(MediaFormat.KEY_HEIGHT);
                    Log.i(TAG, "decoder real size = " + w + "x" + h);
                } else if (outIdx >= 0) {
                    c.releaseOutputBuffer(outIdx, true);
                }
            } catch (Exception e) {
                if (running) Log.w(TAG, "drain: " + e.getMessage());
            }
        }
    }

    public synchronized void stop() {
        stopInternal();
    }

    private void stopInternal() {
        configured = false;
        running = false;

        // ★ Сначала stop() — это разбудит drainLoop из dequeueOutputBuffer
        //   (он получит IllegalStateException и выйдет по running==false).
        //   Потом join — уже быстро.
        MediaCodec c = codec;
        codec = null;
        if (c != null) {
            try { c.stop(); } catch (Exception ignored) {}
        }
        if (outputThread != null) {
            try { outputThread.join(1000); } catch (InterruptedException ignored) {}
            outputThread = null;
        }
        if (c != null) {
            try { c.release(); } catch (Exception ignored) {}
        }
    }
}