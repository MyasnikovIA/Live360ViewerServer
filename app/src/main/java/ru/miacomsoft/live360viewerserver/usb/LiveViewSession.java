package ru.miacomsoft.live360viewerserver.usb;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Сессия USB-камеры Huawei CV60: live-view H.264 + JPEG-фото.
 *
 * Механизм трансляции — ОДИН: H.264 Annex-B через FrameListener.
 * Параметры камеры задаются в конструкторе:
 *   - photoRes  — байт [3] в SET_ALL_SETTINGS (JPEG-разрешение);
 *   - videoRes  — байт [4] в SET_ALL_SETTINGS (разрешение live-view);
 *   - bitrate   — байт [9] в SET_ALL_SETTINGS (0x08 = MID, 0x0A = HIGH);
 *   - liveMode  — байт [9] в START_LIVE_VIEW  (0x0A — подтверждённое).
 *
 * Старые конструкторы сохранены: bitrate по умолчанию 0x08, liveMode 0x0A —
 * это поведение проекта 1 «как было».
 */
public class LiveViewSession {

    private static final String TAG = "LiveView";
    private static final int MAX_FRAME = 1 * 1024 * 1024;
    private static final long PHOTO_TIMEOUT_MS = 30_000L;
    private static final long PAUSE_JOIN_TIMEOUT_MS = 8000L;
    private static final int PAUSE_THREADS = 2;

    // ★ Значения по умолчанию для обратной совместимости.
    public static final int DEFAULT_BITRATE = 0x08;  // MID
    public static final int DEFAULT_LIVE_MODE = 0x0A;

    public interface FrameListener {
        void onVideoFrame(byte[] h264, long ptsUs);
        void onError(String msg);
    }
    public interface PhotoListener {
        void onPhotoReady(byte[] jpeg, long timestampMs);
        void onPhotoError(String msg);
    }

    private final UsbBulkTransfer io;
    private final FrameListener listener;

    private volatile int photoRes;
    private volatile int videoRes;
    // ★ НОВОЕ: параметры профиля
    private volatile int bitrate;
    private volatile int liveMode;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread reader;
    private Thread keepAlive;
    private int frameCounter = 0;

    private final Object pauseLock = new Object();
    private volatile boolean pauseRequested = false;
    private volatile CountDownLatch pauseLatch;

    private volatile boolean commOpen = false;
    private volatile boolean closeCommOnStop = true;

    // ============ ★ НОВЫЙ КОНСТРУКТОР (полный профиль) ============

    public LiveViewSession(UsbDeviceConnection conn, UsbEndpoint epIn, UsbEndpoint epOut,
                           int photoRes, int videoRes, int bitrate, int liveMode,
                           FrameListener l) {
        this.io = new UsbBulkTransfer(conn, epIn, epOut);
        this.photoRes = photoRes;
        this.videoRes = videoRes;
        this.bitrate = bitrate;
        this.liveMode = liveMode;
        this.listener = l;
    }

    // ============ СТАРЫЕ КОНСТРУКТОРЫ (совместимость) ============

    /** Совместимость: photoRes + videoRes, bitrate=0x08, liveMode=0x0A. */
    public LiveViewSession(UsbDeviceConnection conn, UsbEndpoint epIn, UsbEndpoint epOut,
                           int photoRes, int videoRes, FrameListener l) {
        this(conn, epIn, epOut, photoRes, videoRes,
                DEFAULT_BITRATE, DEFAULT_LIVE_MODE, l);
    }

    @Deprecated
    public LiveViewSession(UsbDeviceConnection conn, UsbEndpoint epIn, UsbEndpoint epOut,
                           int photoRes, FrameListener l) {
        this(conn, epIn, epOut, photoRes, 0x0A,
                DEFAULT_BITRATE, DEFAULT_LIVE_MODE, l);
    }

    // ============ Геттеры ============

    public int getVideoRes()   { return videoRes; }
    public int getPhotoRes()   { return photoRes; }
    public int getBitrate()    { return bitrate; }
    public int getLiveMode()   { return liveMode; }
    public boolean isCommOpen(){ return commOpen; }
    public void setCloseCommOnStop(boolean close) { this.closeCommOnStop = close; }

    // ============ Start ============

    public boolean start() {
        try {
            byte[] notifyCdb = new byte[]{0x7A, 0x00, 0x02, 0, 0, 0, 0, 0, 0x01, 0x01, 0, 0, 0x0C, 0x01, 0, 0};
            Log.i(TAG, "notify result=" + exec(0x7A, notifyCdb, 0, false, null));
            Thread.sleep(200);

            byte[] inqCdb = new byte[]{0x12, 0x00, 0x00, 0x00, 36, 0x00};
            Log.i(TAG, "INQUIRY result=" + exec(0x0012, inqCdb, 36, true, new byte[64]));

            if (!commOpen) {
                byte[] initCdb = new byte[]{0x7A, 0x00, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
                int initRes = exec(0x4800, initCdb, 8, true, new byte[16]);
                Log.i(TAG, "init result=" + initRes);
                if (initRes < 0) return false;
                Thread.sleep(100);

                byte[] startCdb = new byte[]{0x7A, 0x00, 0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
                int rStartComm = exec(0x480A, startCdb, 0, false, null);
                Log.i(TAG, "startComm result=" + rStartComm);
                if (rStartComm < 0) return false;
                commOpen = true;
                Thread.sleep(200);
            } else {
                Log.i(TAG, "start: comm already open, skipping INIT/START_COMM");
            }

            sendSetAllSettings();

            byte[] clearCdb = new byte[]{0x7A, 0x01, (byte) 0x85, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
            Log.i(TAG, "clearBuf result=" + exec(0x481B, clearCdb, 0, false, null));
            Thread.sleep(100);

            byte[] fwCdb = new byte[]{0x7A, 0x00, 0x10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
            Log.i(TAG, "getFwVersion result=" + exec(0x4830, fwCdb, 64, true, new byte[128]));

            // ★ liveMode берётся из поля (раньше был захардкожен 0x0A)
            byte[] liveCdb = buildStartLiveCdb();
            Log.i(TAG, "startLive result=" + exec(0x4811, liveCdb, 0, false, null));
            Thread.sleep(300);

            running.set(true);
            reader = new Thread(this::readLoop, "LV-reader"); reader.start();
            keepAlive = new Thread(this::keepAliveLoop, "LV-keepalive"); keepAlive.start();
            Log.i(TAG, "Live view started (photo_res=0x" + Integer.toHexString(photoRes)
                    + " video_res=0x" + Integer.toHexString(videoRes)
                    + " bitrate=0x" + Integer.toHexString(bitrate)
                    + " liveMode=0x" + Integer.toHexString(liveMode) + ")");
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** START_LIVE_VIEW CDB — единый билдер, чтобы liveMode не разъезжался. */
    private byte[] buildStartLiveCdb() {
        return new byte[]{
                0x7A, 0x01, 0x01,
                0, 0, 0, 0, 0,
                0x00, (byte) (liveMode & 0xFF),
                0, 0, 0, 0, 0, 0
        };
    }

    private void sendSetAllSettings() {
        // byte[3]=photoRes, byte[4]=videoRes, byte[9]=bitrate
        byte[] settingsCdb = new byte[]{
                0x7A, 0x00, 0x04,
                (byte) (photoRes & 0xFF),
                (byte) (videoRes & 0xFF),
                0x00, 0x00, 0x00, 0x01,
                (byte) (bitrate & 0xFF),
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        };
        Log.i(TAG, "setSettings photo_res=0x" + Integer.toHexString(photoRes)
                + " video_res=0x" + Integer.toHexString(videoRes)
                + " bitrate=0x" + Integer.toHexString(bitrate)
                + " result=" + exec(0x4857, settingsCdb, 0, false, null));
    }

    // ============ Stop ============

    public void stop() {
        if (!running.get()) return;
        running.set(false);
        synchronized (pauseLock) {
            pauseRequested = false;
            pauseLock.notifyAll();
        }
        if (reader != null) { try { reader.join(1500); } catch (InterruptedException ignored) {} reader = null; }
        if (keepAlive != null) { try { keepAlive.join(1000); } catch (InterruptedException ignored) {} keepAlive = null; }

        if (commOpen) {
            try {
                byte[] stopCdb = new byte[]{0x7A, 0x01, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
                Log.i(TAG, "stopLive result=" + exec(0x4812, stopCdb, 0, false, null));
            } catch (Exception e) {
                Log.w(TAG, "stopLive: " + e.getMessage());
            }
        }

        if (commOpen && closeCommOnStop) {
            try {
                byte[] closeCdb = new byte[]{0x7A, 0x00, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
                int r = exec(0x480B, closeCdb, 0, false, null);
                Log.i(TAG, "closeComm result=" + r);
                if (r >= 0) {
                    commOpen = false;
                }
            } catch (Exception e) {
                Log.w(TAG, "closeComm: " + e.getMessage());
            }
        }
    }

    // ============ Смена разрешения на лету ============

    public boolean changeVideoRes(int newVideoRes) {
        if (!running.get()) {
            this.videoRes = newVideoRes;
            return true;
        }
        if (newVideoRes == videoRes) return true;

        final CountDownLatch pausedLatch = new CountDownLatch(PAUSE_THREADS);
        synchronized (pauseLock) {
            if (pauseRequested) return false;
            pauseLatch = pausedLatch;
            pauseRequested = true;
            pauseLock.notifyAll();
        }

        try {
            try {
                pausedLatch.await(PAUSE_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (!running.get()) return false;

            byte[] stopCdb = {0x7A, 0x01, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
            Log.i(TAG, "changeVideoRes: stopLive=" + exec(0x4812, stopCdb, 0, false, null));
            Thread.sleep(500);

            this.videoRes = newVideoRes;
            sendSetAllSettings();
            Thread.sleep(200);

            // ★ использует liveMode из поля
            Log.i(TAG, "changeVideoRes: startLive="
                    + exec(0x4811, buildStartLiveCdb(), 0, false, null));
            Thread.sleep(300);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            synchronized (pauseLock) {
                pauseRequested = false;
                pauseLatch = null;
                pauseLock.notifyAll();
            }
        }
    }

    // ============ Фоновые циклы (без изменений) ============

    private void keepAliveLoop() {
        byte[] cdb = new byte[]{0x7A, 0x03, (byte) 0xFF};
        while (running.get()) {
            waitIfPaused();
            if (!running.get()) break;
            try {
                exec(0x037A, cdb, 64, true, new byte[64]);
                Thread.sleep(ScsiConstants.KEEPALIVE_PERIOD_MS);
            } catch (InterruptedException e) { return; }
            catch (Exception e) { Log.w(TAG, "keepalive: " + e.getMessage()); }
        }
    }

    private void readLoop() {
        byte[] frameBuf = new byte[MAX_FRAME];
        byte[] frameCdb = new byte[]{0x7A, 0x05, 0x01, 0, 0, 0, 0, 0, 0x00, 0, 0, 0, 0, 0, 0, 0};
        byte[] response = new byte[ScsiConstants.DEFAULT_READ_BUF];

        while (running.get()) {
            waitIfPaused();
            if (!running.get()) break;
            try {
                int totalLen = 0;
                int keepReading = 0;
                int retries = 0;

                while (keepReading == 0 && retries < 5) {
                    if (pauseRequested && running.get()) break;
                    int n = exec(0x4820, frameCdb, ScsiConstants.DEFAULT_READ_BUF, true, response);
                    if (n <= 0) { retries++; Thread.sleep(20); continue; }
                    if (n < 0x20) { retries++; continue; }
                    retries = 0;
                    int status = response[0] & 0xFF;
                    keepReading = response[1] & 0xFF;
                    int chunkLen = readIntLE(response, 0x1C);
                    if (status == 3 || status == 1) { Thread.sleep(10); continue; }
                    if (status != 0) { Log.w(TAG, "frame status=" + status); break; }
                    if (chunkLen <= 0 || chunkLen > n - 0x20) { break; }
                    if (totalLen + chunkLen > MAX_FRAME) { break; }
                    System.arraycopy(response, 0x20, frameBuf, totalLen, chunkLen);
                    totalLen += chunkLen;
                }

                if (totalLen > 0) {
                    if (pauseRequested && running.get()) {
                        Log.d(TAG, "readLoop: pause requested mid-frame, dropping " + totalLen + " bytes");
                    } else {
                        parseFrame(frameBuf, totalLen);
                    }
                } else {
                    Thread.sleep(5);
                }
            } catch (InterruptedException e) { return; }
            catch (Exception e) {
                Log.e(TAG, "readLoop: " + e.getMessage());
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void parseFrame(byte[] buf, int n) {
        int start = findAnnexB(buf, 0, Math.min(n, 1024));
        if (start < 0) return;
        int nalOff = start + (buf[start + 2] == 0 ? 4 : 3);
        int nalType = (nalOff < n) ? (buf[nalOff] & 0x1F) : -1;
        if (frameCounter++ % 30 == 1) {
            Log.d(TAG, "frame n=" + n + " startCode@" + start + " nalType=" + nalType);
        }
        byte[] h264 = new byte[n - start];
        System.arraycopy(buf, start, h264, 0, n - start);
        if (listener != null) listener.onVideoFrame(h264, System.nanoTime() / 1000L);
    }

    // ============ Фото (без изменений) ============

    public void takePhoto(PhotoListener listener) {
        if (listener == null) return;
        if (!running.get()) { listener.onPhotoError("session not running"); return; }

        final CountDownLatch pausedLatch = new CountDownLatch(PAUSE_THREADS);

        synchronized (pauseLock) {
            if (pauseRequested) {
                listener.onPhotoError("another photo in progress");
                return;
            }
            pauseLatch = pausedLatch;
            pauseRequested = true;
            pauseLock.notifyAll();
        }

        new Thread(() -> {
            boolean liveViewStopped = false;
            try {
                boolean paused;
                try {
                    paused = pausedLatch.await(PAUSE_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    listener.onPhotoError("interrupted while waiting for pause");
                    return;
                }

                if (!running.get()) {
                    listener.onPhotoError("session stopped during pause");
                    return;
                }
                if (!paused) {
                    Log.w(TAG, "photo: pause timeout — proceeding anyway ("
                            + pausedLatch.getCount() + " thread(s) still busy)");
                } else {
                    Log.i(TAG, "photo: reader/keepalive paused, sending STOP_LIVE_VIEW");
                }

                byte[] stopCdb = new byte[]{0x7A, 0x01, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
                int rStop = exec(0x4812, stopCdb, 0, false, null);
                Log.i(TAG, "photo: stopLive=" + rStop);
                liveViewStopped = true;
                Thread.sleep(500);

                Log.i(TAG, "photo: re-sending setSettings");
                sendSetAllSettings();
                Thread.sleep(200);

                byte[] clearCdb = new byte[]{0x7A, 0x01, (byte) 0x85, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
                Log.i(TAG, "photo: clearBuf=" + exec(0x481B, clearCdb, 0, false, null));
                Thread.sleep(100);

                byte[] takeCdb = new byte[]{0x7A, 0x01, 0x05, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
                int rTake = exec(0x4815, takeCdb, 0, false, null);
                Log.i(TAG, "photo: takePicture=" + rTake);
                if (rTake < 0) { listener.onPhotoError("TAKE_PICTURE failed: " + rTake); return; }

                Thread.sleep(2000);

                byte[] getCdb = new byte[]{0x7A, 0x05, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
                byte[] response = new byte[ScsiConstants.DEFAULT_READ_BUF];
                ByteArrayOutputStream out = new ByteArrayOutputStream();

                long deadline = System.currentTimeMillis() + PHOTO_TIMEOUT_MS;
                int emptyCount = 0;
                boolean headerLogged = false;
                int chunkIdx = 0;

                while (System.currentTimeMillis() < deadline) {
                    int n = exec(0x4821, getCdb, ScsiConstants.DEFAULT_READ_BUF, true, response);
                    if (n <= 0) {
                        Log.d(TAG, "photo: empty n=" + n + " retry=" + emptyCount);
                        Thread.sleep(150);
                        if (++emptyCount > 100) break;
                        continue;
                    }
                    if (n < 0x20) { Thread.sleep(150); continue; }
                    emptyCount = 0;
                    chunkIdx++;

                    if (!headerLogged) {
                        StringBuilder sb = new StringBuilder("photo header[0..0x24]: ");
                        for (int i = 0; i < Math.min(0x24, n); i++)
                            sb.append(String.format("%02X ", response[i]));
                        Log.i(TAG, sb.toString());
                        headerLogged = true;
                    }

                    int status      = response[0] & 0xFF;
                    int keepReading = response[1] & 0xFF;
                    int chunkLen    = readIntLE(response, 0x10);

                    Log.i(TAG, "photo chunk#" + chunkIdx
                            + " status=" + status
                            + " keepReading=" + keepReading
                            + " chunkLen=" + chunkLen
                            + " n=" + n
                            + " total=" + out.size());

                    if (status == 1 || status == 3) { Thread.sleep(250); continue; }
                    if (status != 0) {
                        Log.w(TAG, "photo: unexpected status=" + status);
                        Thread.sleep(200);
                        if (++emptyCount > 20) break;
                        continue;
                    }
                    if (chunkLen <= 0) {
                        if (keepReading == 0) break;
                        Thread.sleep(150);
                        continue;
                    }

                    int available = n - 0x20;
                    if (chunkLen > available) {
                        Log.e(TAG, "photo: chunkLen=" + chunkLen
                                + " > available " + available + " — DATA TRUNCATED");
                        break;
                    }
                    out.write(response, 0x20, chunkLen);

                    if (keepReading == 0 && out.size() > 0) {
                        Log.i(TAG, "photo: keepReading=0, total=" + out.size());
                        break;
                    }
                }

                // Возвращаемся в live-view
                int rStart = exec(0x4811, buildStartLiveCdb(), 0, false, null);
                Log.i(TAG, "photo: startLive=" + rStart);
                liveViewStopped = false;

                if (out.size() == 0) { listener.onPhotoError("no photo data"); return; }

                byte[] raw = out.toByteArray();
                int js = findJpegStart(raw);
                int je = findJpegEnd(raw, js);
                byte[] jpeg;
                if (js >= 0 && je > js) {
                    jpeg = new byte[je - js + 2];
                    System.arraycopy(raw, js, jpeg, 0, jpeg.length);
                    Log.i(TAG, "photo: JPEG " + jpeg.length + " bytes (raw " + raw.length + ")");
                } else {
                    jpeg = raw;
                    Log.w(TAG, "photo: no JPEG markers, raw " + raw.length + " bytes");
                }

                int kb = jpeg.length / 1024;
                Log.i(TAG, "photo: final size=" + kb + " KB (photo_res=0x"
                        + Integer.toHexString(photoRes) + ")");
                if (kb < 100) {
                    Log.w(TAG, "photo: VERY SMALL — проверьте photo_res и bitrate");
                } else if (kb < 300) {
                    Log.w(TAG, "photo: SMALL — возможно LOW bitrate");
                } else {
                    Log.i(TAG, "photo: OK");
                }
                listener.onPhotoReady(jpeg, System.currentTimeMillis());

            } catch (Exception e) {
                Log.e(TAG, "takePhoto: " + e);
                listener.onPhotoError(e.getMessage() == null ? e.toString() : e.getMessage());
            } finally {
                if (liveViewStopped && running.get()) {
                    try {
                        exec(0x4811, buildStartLiveCdb(), 0, false, null);
                        Log.i(TAG, "photo: startLive (finally) OK");
                    } catch (Exception ignored) {}
                }
                synchronized (pauseLock) {
                    pauseRequested = false;
                    pauseLatch = null;
                    pauseLock.notifyAll();
                }
            }
        }, "LV-photo").start();
    }

    // ============ Вспомогательные (без изменений) ============

    private void waitIfPaused() {
        synchronized (pauseLock) {
            CountDownLatch signaled = null;
            while (pauseRequested && running.get()) {
                CountDownLatch current = pauseLatch;
                if (current != null && current != signaled) {
                    current.countDown();
                    signaled = current;
                }
                try {
                    pauseLock.wait(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private static int findJpegStart(byte[] buf) {
        for (int i = 0; i < buf.length - 2; i++) {
            if ((buf[i] & 0xFF) == 0xFF && (buf[i+1] & 0xFF) == 0xD8
                    && (buf[i+2] & 0xFF) == 0xFF) return i;
        }
        return -1;
    }

    private static int findJpegEnd(byte[] buf, int from) {
        if (from < 0) from = 0;
        for (int i = buf.length - 2; i >= from; i--) {
            if ((buf[i] & 0xFF) == 0xFF && (buf[i+1] & 0xFF) == 0xD9) return i;
        }
        return -1;
    }

    private static int findAnnexB(byte[] buf, int from, int to) {
        int limit = Math.min(to, buf.length) - 3;
        for (int i = from; i < limit; i++) {
            if (buf[i] == 0 && buf[i+1] == 0 && buf[i+2] == 0 && buf[i+3] == 1) return i;
            if (buf[i] == 0 && buf[i+1] == 0 && buf[i+2] == 1) return i;
        }
        return -1;
    }

    private static int readIntLE(byte[] buf, int off) {
        return (buf[off] & 0xFF) | ((buf[off+1] & 0xFF) << 8)
                | ((buf[off+2] & 0xFF) << 16) | ((buf[off+3] & 0xFF) << 24);
    }

    private int exec(int code, byte[] cdb, int dataLen, boolean read, byte[] out) {
        ScsiPacket pkt = new ScsiPacket(code, dataLen, read);
        pkt.setCdb(cdb);
        return io.execute(pkt, null, out);
    }
}