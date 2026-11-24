package ru.miacomsoft.live360viewerserver;

import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import ru.miacomsoft.live360viewerserver.decoder.H264Decoder;
import ru.miacomsoft.live360viewerserver.settings.PhotoSettings;
import ru.miacomsoft.live360viewerserver.usb.LiveViewSession;
import ru.miacomsoft.live360viewerserver.usb.ScsiConstants;
import ru.miacomsoft.live360viewerserver.usb.UsbCameraManager;
import ru.miacomsoft.live360viewerserver.webrtc.WebRtcServer;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQ_PERMISSIONS = 100;
    private static final int SIGNALING_PORT = 9090;

    private TextView tvStatus;
    private Button btnStart, btnStop;

    private WebRtcServer webRtc;
    private UsbCameraManager usb;
    private LiveViewSession session;
    private H264Decoder decoder;
    private Surface captureSurface;

    private volatile boolean streaming = false;

    private static final String[] REQUIRED_PERMISSIONS = Build.VERSION.SDK_INT >= 29
            ? new String[]{ Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO }
            : new String[]{ Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        btnStart = findViewById(R.id.btnStart);
        btnStop  = findViewById(R.id.btnStop);

        btnStart.setOnClickListener(v -> requestPermissionsAndStart());
        btnStop.setOnClickListener(v -> stopStreaming());
        btnStop.setEnabled(false);

        // ★ WebRTC-сервер поднимаем сразу: браузер может подключиться
        //   в любой момент, сигналинг должен быть готов.
        webRtc = new WebRtcServer(this, SIGNALING_PORT);
        webRtc.start();
        tvStatus.setText("Готов. ws://<ip>:" + SIGNALING_PORT);
    }

    // ============ Разрешения ============

    private void requestPermissionsAndStart() {
        List<String> missing = new ArrayList<>();
        for (String p : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        if (!missing.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    missing.toArray(new String[0]), REQ_PERMISSIONS);
            return;
        }
        startStreaming();
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                           @NonNull int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_PERMISSIONS) return;
        boolean all = true;
        for (int i = 0; i < perms.length; i++) {
            Log.i(TAG, perms[i] + " granted=" + (res[i] == PackageManager.PERMISSION_GRANTED));
            if (res[i] != PackageManager.PERMISSION_GRANTED) all = false;
        }
        if (!all) Toast.makeText(this, "Не все разрешения выданы", Toast.LENGTH_LONG).show();
        startStreaming();
    }

    // ============ Запуск/остановка ============

    private void startStreaming() {
        if (streaming) return;

        if (webRtc == null) {
            webRtc = new WebRtcServer(this, SIGNALING_PORT);
            webRtc.start();
        }
        captureSurface = webRtc.getCaptureSurface();
        if (captureSurface == null) {
            tvStatus.setText("WebRTC не инициализирован");
            return;
        }

        UsbManager mgr = (UsbManager) getSystemService(USB_SERVICE);
        if (mgr == null) { tvStatus.setText("UsbManager недоступен"); return; }

        UsbDevice cam = findCamera(mgr);
        if (cam == null) { tvStatus.setText("Камера не подключена"); return; }

        streaming = true;
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);

        final int photoRes = PhotoSettings.getResolution(this);
        final int videoRes = PhotoSettings.getVideoResolution(this);

        usb = new UsbCameraManager(this);
        usb.start(new UsbCameraManager.Listener() {
            @Override
            public void onConnected(UsbDeviceConnection conn, UsbInterface intf,
                                    UsbEndpoint epIn, UsbEndpoint epOut) {
                if (!streaming) return;
                try {
                    decoder = new H264Decoder(captureSurface, 1280, 640);
                    decoder.start();
                } catch (IOException e) {
                    Log.e(TAG, "decoder.start", e);
                    tvStatus.setText("Ошибка декодера: " + e.getMessage());
                    stopStreaming();
                    return;
                }

                session = new LiveViewSession(conn, epIn, epOut, photoRes, videoRes,
                        new LiveViewSession.FrameListener() {
                            @Override public void onVideoFrame(byte[] h264, long ptsUs) {
                                H264Decoder d = decoder;
                                if (d != null) d.feed(h264, ptsUs);
                            }
                            @Override public void onError(String msg) {
                                Log.e(TAG, "LiveView: " + msg);
                            }
                        });

                new Thread(() -> {
                    boolean ok = session.start();
                    Log.i(TAG, "session.start=" + ok);
                    runOnUiThread(() -> tvStatus.setText(ok
                            ? "🔴 Стрим: ws://<ip>:" + SIGNALING_PORT
                            : "Не удалось запустить камеру"));
                }, "LV-start").start();
            }

            @Override public void onDetached() {
                Log.w(TAG, "USB detached");
                stopStreaming();
                runOnUiThread(() -> tvStatus.setText("Камера отключена"));
            }
            @Override public void onPermissionDenied() {
                Log.w(TAG, "USB permission denied");
                stopStreaming();
                runOnUiThread(() -> tvStatus.setText("USB-разрешение отклонено"));
            }
            @Override public void onError(String msg) {
                Log.e(TAG, "USB: " + msg);
                stopStreaming();
                runOnUiThread(() -> tvStatus.setText("USB ошибка: " + msg));
            }
        });
    }

    private void stopStreaming() {
        streaming = false;
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);

        LiveViewSession s = session; session = null;
        if (s != null) { try { s.stop(); } catch (Exception ignored) {} }

        UsbCameraManager u = usb; usb = null;
        if (u != null) { try { u.stop(); } catch (Exception ignored) {} }

        H264Decoder d = decoder; decoder = null;
        if (d != null) { try { d.stop(); } catch (Exception ignored) {} }

        // captureSurface принадлежит WebRtcServer — не освобождаем
        tvStatus.setText("Остановлено");
    }

    private UsbDevice findCamera(UsbManager mgr) {
        HashMap<String, UsbDevice> list = mgr.getDeviceList();
        for (UsbDevice d : list.values()) {
            if (d.getVendorId() == ScsiConstants.VENDOR_ID
                    && d.getProductId() == ScsiConstants.PRODUCT_ID) return d;
        }
        return null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopStreaming();
        if (webRtc != null) { webRtc.stop(); webRtc = null; }
    }
}