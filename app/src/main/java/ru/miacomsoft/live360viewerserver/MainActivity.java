package ru.miacomsoft.live360viewerserver;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ru.miacomsoft.live360viewerserver.decoder.H264Decoder;
import ru.miacomsoft.live360viewerserver.settings.PhotoSettings;
import ru.miacomsoft.live360viewerserver.usb.LiveViewSession;
import ru.miacomsoft.live360viewerserver.usb.ScsiConstants;
import ru.miacomsoft.live360viewerserver.usb.UsbCameraManager;
// ★ ИЗМЕНЕНО: Используем новый WebServerManager
import ru.miacomsoft.live360viewerserver.web.WebServerManager;
import ru.miacomsoft.live360viewerserver.webrtc.WebRtcServer;

public class MainActivity extends AppCompatActivity implements WebServerManager.CommandHandler {

    private static final String TAG = "MainActivity";
    private static final int REQ_PERMISSIONS = 100;
    private static final int SIGNALING_PORT  = 9090;
    private static final int DEFAULT_WEB_PORT = 8080;

    private static final int MAX_BOOT_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 10000L;
    private static final long CONNECT_TIMEOUT_MS = 15000L;

    private static final String PREF = "app_prefs";
    private static final String KEY_WEB_PORT   = "web_port";
    private static final String KEY_STREAM_ACTIVE = "stream_was_active";

    private TextView tvStatus, tvInfo;
    private Button btnStart, btnStop, btnSettings, btnLauncher, btnApplyPort, btnAccessibility;
    private EditText etPort;
    private Spinner spinnerVideo, spinnerPhoto;

    private WebRtcServer webRtc;
    private WebServerManager webServer; // ★ ИЗМЕНЕНО
    private UsbCameraManager usb;
    private LiveViewSession  session;
    private H264Decoder      decoder;
    private Surface          captureSurface;

    private volatile boolean streaming = false;
    private volatile boolean isConnecting = false;
    private volatile int     webPort   = DEFAULT_WEB_PORT;
    private String localIp = "?";

    private boolean isUpdatingSpinners = false;

    private int bootRetryCount = 0;
    private final Handler retryHandler = new Handler(Looper.getMainLooper());
    private Runnable retryRunnable;
    private Runnable connectTimeoutRunnable;

    private static final String[] REQUIRED_PERMISSIONS = Build.VERSION.SDK_INT >= 29
            ? new String[]{ Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO }
            : new String[]{ Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus     = findViewById(R.id.tvStatus);
        tvInfo       = findViewById(R.id.tvInfo);
        etPort       = findViewById(R.id.etPort);
        btnStart     = findViewById(R.id.btnStart);
        btnStop      = findViewById(R.id.btnStop);
        btnSettings  = findViewById(R.id.btnSettings);
        btnLauncher  = findViewById(R.id.btnLauncher);
        btnApplyPort = findViewById(R.id.btnApplyPort);
        btnAccessibility = findViewById(R.id.btnAccessibility);
        spinnerVideo = findViewById(R.id.spinnerVideo);
        spinnerPhoto = findViewById(R.id.spinnerPhoto);

        btnStart.setOnClickListener(v -> requestPermissionsAndStart());
        btnStop.setOnClickListener(v -> stopStreaming(true));
        btnSettings.setOnClickListener(v -> openAndroidSettings());
        btnLauncher.setOnClickListener(v -> openHomeSettings());
        btnApplyPort.setOnClickListener(v -> applyPortFromUi());

        btnAccessibility.setOnClickListener(v -> {
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "Найдите 'Live360 Server' и включите службу", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } else {
                Toast.makeText(this, "Служба уже включена!", Toast.LENGTH_SHORT).show();
            }
        });

        btnStop.setEnabled(false);
        setupSpinners();

        int saved = getSharedPreferences(PREF, MODE_PRIVATE).getInt(KEY_WEB_PORT, DEFAULT_WEB_PORT);
        if (saved < 1024 || saved > 65535) saved = DEFAULT_WEB_PORT;
        webPort = saved;
        etPort.setText(String.valueOf(webPort));

        new Thread(() -> {
            localIp = fetchLocalIp();
            runOnUiThread(MainActivity.this::updateIpInfo);

            // ★ ИЗМЕНЕНО: Используем WebServerManager
            webServer = new WebServerManager(MainActivity.this, webPort, MainActivity.this);
            if (!webServer.start()) {
                runOnUiThread(() -> {
                    webServer = null;
                    Toast.makeText(MainActivity.this, "Порт " + webPort + " занят", Toast.LENGTH_LONG).show();
                });
            }

            webRtc = new WebRtcServer(MainActivity.this, SIGNALING_PORT);
            webRtc.start();
            runOnUiThread(MainActivity.this::updateIpInfo);
        }).start();

        boolean wasActive = getSharedPreferences(PREF, MODE_PRIVATE).getBoolean(KEY_STREAM_ACTIVE, false);
        if (wasActive) {
            Log.i(TAG, "Auto-start: resuming stream...");
            if (hasAllPermissions()) {
                tvStatus.postDelayed(this::startStreaming, 2000);
            } else {
                requestPermissionsAndStart();
            }
        }
    }

    // ... (Остальные методы остаются без изменений вплоть до changeWebPort) ...

    @Override
    protected void onResume() {
        super.onResume();
        updateAccessibilityButtonState();
    }

    private boolean isAccessibilityServiceEnabled() {
        String expectedComponentName = getPackageName() + "/" + UsbPermissionService.class.getName();
        String enabledServices = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabledServices)) return false;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabledServices);
        while (splitter.hasNext()) {
            if (splitter.next().equalsIgnoreCase(expectedComponentName)) return true;
        }
        return false;
    }

    private void updateAccessibilityButtonState() {
        if (isAccessibilityServiceEnabled()) {
            btnAccessibility.setText("✅ АВТО-ПОДТВЕРЖДЕНИЕ USB ВКЛЮЧЕНО");
            btnAccessibility.setBackgroundColor(Color.parseColor("#4CAF50"));
        } else {
            btnAccessibility.setText("⚠️ ВКЛЮЧИТЕ АВТО-ПОДТВЕРЖДЕНИЕ USB");
            btnAccessibility.setBackgroundColor(Color.parseColor("#F44336"));
        }
    }

    private void setupSpinners() {
        ArrayAdapter<CharSequence> videoAdapter = ArrayAdapter.createFromResource(this,
                R.array.video_res_labels, android.R.layout.simple_spinner_item);
        videoAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerVideo.setAdapter(videoAdapter);

        ArrayAdapter<CharSequence> photoAdapter = ArrayAdapter.createFromResource(this,
                R.array.photo_res_labels, android.R.layout.simple_spinner_item);
        photoAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerPhoto.setAdapter(photoAdapter);

        isUpdatingSpinners = true;
        int vRes = PhotoSettings.getVideoResolution(this);
        int pRes = PhotoSettings.getResolution(this);
        int vIndex = 3;
        if (vRes == 0x07) vIndex = 0;
        else if (vRes == 0x08) vIndex = 1;
        else if (vRes == 0x09) vIndex = 2;
        else if (vRes == 0x0B) vIndex = 4;
        spinnerVideo.setSelection(vIndex);
        spinnerPhoto.setSelection(pRes == 0x04 ? 1 : 0);
        isUpdatingSpinners = false;

        spinnerVideo.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isUpdatingSpinners) return;
                int code = 0x07 + position;
                PhotoSettings.setVideoResolution(MainActivity.this, code);
                if (session != null && streaming) new Thread(() -> session.changeVideoRes(code)).start();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        spinnerPhoto.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isUpdatingSpinners) return;
                int code = position == 0 ? 0x03 : 0x04;
                PhotoSettings.setResolution(MainActivity.this, code);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void requestPermissionsAndStart() {
        List<String> missing = new ArrayList<>();
        for (String p : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (!missing.isEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), REQ_PERMISSIONS);
            return;
        }
        bootRetryCount = 0;
        startStreaming();
    }

    private boolean hasAllPermissions() {
        for (String p : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_PERMISSIONS) return;
        boolean all = true;
        for (int i = 0; i < perms.length; i++) if (res[i] != PackageManager.PERMISSION_GRANTED) all = false;
        if (!all) Toast.makeText(this, "Не все разрешения выданы", Toast.LENGTH_LONG).show();
        boolean wasActive = getSharedPreferences(PREF, MODE_PRIVATE).getBoolean(KEY_STREAM_ACTIVE, false);
        if (wasActive || all) startStreaming();
    }

    private void startStreaming() {
        if (streaming || isConnecting) return;
        if (webRtc == null) {
            setStatus("Инициализация WebRTC...");
            retryHandler.postDelayed(this::startStreaming, 1000);
            return;
        }
        captureSurface = webRtc.getCaptureSurface();
        if (captureSurface == null) {
            setStatus("Ожидание Surface...");
            retryHandler.postDelayed(this::startStreaming, 1000);
            return;
        }

        UsbManager mgr = (UsbManager) getSystemService(USB_SERVICE);
        UsbDevice cam = findCamera(mgr);
        if (cam == null) {
            handleStreamFailure("USB камера не найдена");
            return;
        }

        isConnecting = true;
        streaming = true;
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putBoolean(KEY_STREAM_ACTIVE, true).apply();
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);

        connectTimeoutRunnable = () -> {
            if (isConnecting) {
                Log.w(TAG, "Connection timeout!");
                handleStreamFailure("Таймаут подключения USB");
            }
        };
        retryHandler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS);

        final int photoRes = PhotoSettings.getResolution(this);
        final int videoRes = PhotoSettings.getVideoResolution(this);

        usb = new UsbCameraManager(this);
        usb.start(new UsbCameraManager.Listener() {
            @Override public void onPermissionRequested() { setStatus("⚠ Ожидание разрешения USB..."); }

            @Override
            public void onConnected(UsbDeviceConnection conn, UsbInterface intf, UsbEndpoint epIn, UsbEndpoint epOut) {
                if (!streaming) return;
                if (connectTimeoutRunnable != null) retryHandler.removeCallbacks(connectTimeoutRunnable);

                try {
                    decoder = new H264Decoder(captureSurface, 1280, 640);
                    decoder.start();
                } catch (IOException e) {
                    handleStreamFailure("Ошибка декодера: " + e.getMessage());
                    return;
                }

                session = new LiveViewSession(conn, epIn, epOut, photoRes, videoRes,
                        new LiveViewSession.FrameListener() {
                            @Override public void onVideoFrame(byte[] h264, long ptsUs) {
                                H264Decoder d = decoder;
                                if (d != null) d.feed(h264, ptsUs);
                            }
                            @Override public void onError(String msg) { Log.e(TAG, "LiveView: " + msg); }
                        });

                new Thread(() -> {
                    boolean ok = session.start();
                    runOnUiThread(() -> {
                        if (!ok) {
                            handleStreamFailure("Сессия LiveView не запустилась");
                        } else {
                            bootRetryCount = 0;
                            isConnecting = false;
                            setStatus("🔴 Стрим активен");
                            updateIpInfo();
                        }
                    });
                }, "LV-start").start();
            }

            @Override public void onDetached() { handleStreamFailure("Камера отключена"); }
            @Override public void onPermissionDenied() { handleStreamFailure("USB-разрешение отклонено"); }
            @Override public void onError(String msg) { handleStreamFailure("USB ошибка: " + msg); }
        });
    }

    private void handleStreamFailure(String reason) {
        Log.e(TAG, "Stream failure: " + reason);
        cleanupResources();
        boolean wasActive = getSharedPreferences(PREF, MODE_PRIVATE).getBoolean(KEY_STREAM_ACTIVE, false);

        if (wasActive) {
            if (bootRetryCount < MAX_BOOT_RETRIES) {
                bootRetryCount++;
                int attempt = bootRetryCount;
                setStatus("Ошибка: " + reason + "\nПовторная попытка " + attempt + "/" + MAX_BOOT_RETRIES + " через 10 сек...");
                if (retryRunnable != null) retryHandler.removeCallbacks(retryRunnable);
                retryRunnable = () -> { retryRunnable = null; startStreaming(); };
                retryHandler.postDelayed(retryRunnable, RETRY_DELAY_MS);
            } else {
                setStatus("Не удалось запустить трансляцию после " + MAX_BOOT_RETRIES + " попыток.");
                getSharedPreferences(PREF, MODE_PRIVATE).edit().putBoolean(KEY_STREAM_ACTIVE, false).apply();
            }
        } else {
            setStatus("Ошибка: " + reason);
        }
        updateIpInfo();
    }

    private void cleanupResources() {
        if (connectTimeoutRunnable != null) retryHandler.removeCallbacks(connectTimeoutRunnable);
        LiveViewSession s = session; session = null;
        if (s != null) { try { s.stop(); } catch (Exception ignored) {} }
        UsbCameraManager u = usb; usb = null;
        if (u != null) { try { u.stop(); } catch (Exception ignored) {} }
        H264Decoder d = decoder; decoder = null;
        if (d != null) { try { d.stop(); } catch (Exception ignored) {} }
        streaming = false;
        isConnecting = false;
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
    }

    private void stopStreaming(boolean userStopped) {
        if (userStopped) {
            if (retryRunnable != null) retryHandler.removeCallbacks(retryRunnable);
            bootRetryCount = 0;
            getSharedPreferences(PREF, MODE_PRIVATE).edit().putBoolean(KEY_STREAM_ACTIVE, false).apply();
            setStatus("Готов. Нажмите «Запустить стрим»");
        }
        cleanupResources();
        updateIpInfo();
    }

    private void setStatus(String text) { runOnUiThread(() -> tvStatus.setText(text)); }

    private void updateIpInfo() {
        runOnUiThread(() -> {
            tvInfo.setText(
                    "Web-панель:  http://" + localIp + ":" + webPort + "/\n"
                            + "WebRTC Pano: http://" + localIp + ":" + webPort + "/webrtc_pano.html\n"
                            + "WebRTC 2D:   http://" + localIp + ":" + webPort + "/webrtc_2d.html\n"
                            + "Signaling:   ws://" + localIp + ":" + SIGNALING_PORT);
            if (webServer != null) {
                try { webServer.broadcast("status", getStatus()); } catch (Exception ignored) {}
            }
        });
    }

    private void openAndroidSettings() { try { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); } catch (Exception ignored) {} }
    private void openHomeSettings() {
        try { startActivity(new Intent("android.settings.HOME_SETTINGS")); return; } catch (Exception ignored) {}
        try { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); } catch (Exception ignored) {}
    }

    private void applyPortFromUi() {
        int p;
        try { p = Integer.parseInt(etPort.getText().toString().trim()); } catch (NumberFormatException e) { p = -1; }
        if (p < 1024 || p > 65535) { Toast.makeText(this, "Порт: 1024–65535", Toast.LENGTH_LONG).show(); return; }
        JSONObject payload = new JSONObject();
        try { payload.put("port", p); } catch (Exception ignored) {}
        try {
            JSONObject r = handleCommand("set_web_port", payload);
            if (!r.optBoolean("ok", false)) Toast.makeText(this, "Ошибка: " + r.optString("error"), Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {}
    }

    // ★ ИЗМЕНЕНО: Реализация интерфейса WebServerManager.CommandHandler
    @Override
    public JSONObject handleCommand(String cmd, JSONObject payload) throws Exception {
        JSONObject r = new JSONObject();
        switch (cmd) {
            case "start_stream": runOnUiThread(this::requestPermissionsAndStart); r.put("ok", true); return r;
            case "stop_stream": runOnUiThread(() -> stopStreaming(true)); r.put("ok", true); return r;
            case "ping": r.put("ok", true); r.put("pong", System.currentTimeMillis()); return r;
            case "take_photo": {
                LiveViewSession s = session;
                if (s != null && streaming) {
                    final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                    final String[] resultBase64 = new String[1];
                    s.takePhoto(new LiveViewSession.PhotoListener() {
                        @Override public void onPhotoReady(byte[] jpeg, long timestampMs) {
                            resultBase64[0] = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP);
                            latch.countDown();
                        }
                        @Override public void onPhotoError(String msg) { latch.countDown(); }
                    });
                    if (latch.await(40, java.util.concurrent.TimeUnit.SECONDS)) {
                        if (resultBase64[0] != null) { r.put("ok", true); r.put("image_base64", resultBase64[0]); return r; }
                    }
                    r.put("ok", false); r.put("error", "timeout"); return r;
                }
                r.put("ok", false); r.put("error", "not streaming"); return r;
            }
            case "set_web_port": return changeWebPort(payload.optInt("port", -1));
            case "get_status": return getStatus();
            default: r.put("ok", false); r.put("error", "unknown cmd: " + cmd); return r;
        }
    }

    private JSONObject changeWebPort(int newPort) {
        JSONObject r = new JSONObject();
        try {
            if (newPort < 1024 || newPort > 65535) { r.put("ok", false); r.put("error", "invalid port"); return r; }
            if (newPort == webPort) { r.put("ok", true); r.put("port", webPort); return r; }

            // ★ ИЗМЕНЕНО: Используем WebServerManager
            WebServerManager newServer = new WebServerManager(this, newPort, this);
            if (!newServer.start()) { r.put("ok", false); r.put("error", "port busy"); return r; }

            WebServerManager oldServer = webServer;
            int oldPort = webPort;
            webServer = newServer;
            webPort = newPort;
            getSharedPreferences(PREF, MODE_PRIVATE).edit().putInt(KEY_WEB_PORT, newPort).apply();

            String newUrl = "http://" + localIp + ":" + newPort + "/";
            if (oldServer != null) {
                try {
                    JSONObject moved = new JSONObject();
                    moved.put("newPort", newPort); moved.put("newUrl", newUrl);
                    oldServer.broadcast("server_moved", moved);
                } catch (Exception ignored) {}
                new Thread(() -> { try { Thread.sleep(1000); } catch (Exception ignored) {} oldServer.stop(); }).start();
            }
            runOnUiThread(() -> etPort.setText(String.valueOf(newPort)));
            r.put("ok", true); r.put("port", newPort); r.put("url", newUrl); r.put("previousPort", oldPort);
            return r;
        } catch (Exception e) {
            try { r.put("ok", false); r.put("error", "unknown error"); } catch (Exception ignored) {}
            return r;
        }
    }

    @Override
    public JSONObject getStatus() {
        JSONObject s = new JSONObject();
        try {
            s.put("streaming", streaming);
            s.put("signalingPort", SIGNALING_PORT);
            s.put("webPort", webPort);
            s.put("ip", localIp);
            s.put("videoRes", PhotoSettings.getVideoResolution(this));
            s.put("photoRes", PhotoSettings.getResolution(this));
            s.put("autoStart", getSharedPreferences(PREF, MODE_PRIVATE).getBoolean(KEY_STREAM_ACTIVE, false));
            s.put("retryCount", bootRetryCount);
        } catch (Exception ignored) {}
        return s;
    }

    private UsbDevice findCamera(UsbManager mgr) {
        if (mgr == null) return null;
        for (UsbDevice d : mgr.getDeviceList().values()) {
            if (d.getVendorId() == ScsiConstants.VENDOR_ID && d.getProductId() == ScsiConstants.PRODUCT_ID) return d;
        }
        return null;
    }

    private String fetchLocalIp() {
        try {
            for (NetworkInterface intf : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!intf.isUp() || intf.isLoopback()) continue;
                for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) return addr.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "?";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!isChangingConfigurations()) {
            stopStreaming(false);
        }
        if (webServer != null) webServer.stop();
        if (webRtc != null) webRtc.stop();
    }
}