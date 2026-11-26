package ru.miacomsoft.live360viewerserver;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Surface;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import ru.miacomsoft.live360viewerserver.decoder.H264Decoder;
import ru.miacomsoft.live360viewerserver.render.FlatRenderer;
import ru.miacomsoft.live360viewerserver.settings.PhotoSettings;
import ru.miacomsoft.live360viewerserver.usb.LiveViewSession;
import ru.miacomsoft.live360viewerserver.usb.ScsiConstants;
import ru.miacomsoft.live360viewerserver.usb.UsbCameraManager;

/**
 * Локальный просмотр FLAT 360×180.
 *
 * ★ ОПТИМИЗАЦИЯ 2: теперь использует ПРОФИЛЬ камеры (PhotoSettings.getStreamProfile)
 *   и НОВЫЙ конструктор LiveViewSession с bitrate/liveMode — камера получает те же
 *   команды SET_ALL_SETTINGS / START_LIVE_VIEW, что и в WebRTC-пути (MainActivity).
 *
 * ★ ОПТИМИЗАЦИЯ 3 (FLAT-путь): размеры SurfaceTexture берутся из профиля
 *   (profileVideoWidth / profileVideoHeight) и передаются в setDefaultBufferSize.
 *
 * ★ Смена профиля на лету: при возврате в onResume сравниваем применённый
 *   videoRes с текущим из профиля — если разошлись, перезапускаем поток.
 *
 * Механизм вывода кадра остаётся прямым: H264Decoder → SurfaceTexture → GLSurfaceView,
 * без WebRTC-перекодирования (это и даёт чёткость).
 */
public class FlatViewerActivity extends AppCompatActivity {

    private static final String TAG = "FlatViewer";
    private static final int REQ_STORAGE = 1001;
    private static final String PHOTO_SUBDIR = "Live360Viewer";

    private GLSurfaceView glSurface;
    private FlatRenderer renderer;
    private TextView overlay;
    private Button btnPhoto;

    private H264Decoder decoder;
    private LiveViewSession session;
    private UsbCameraManager usb;
    private Surface surface;

    private volatile boolean running = false;
    private volatile boolean cameraStarted = false;
    private volatile int frameCount = 0;

    // ★ Применённый на текущий момент профиль — для onProfileChanged()
    private volatile int appliedProfile = -1;
    private volatile int appliedVideoRes = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_flat_viewer);

        glSurface = findViewById(R.id.glSurface);
        overlay   = findViewById(R.id.tvOverlay);
        btnPhoto  = findViewById(R.id.btnPhoto);

        btnPhoto.setOnClickListener(v -> onTakePhotoClicked());

        glSurface.setEGLContextClientVersion(2);
        renderer = new FlatRenderer();
        renderer.setOnSurfaceReadyListener(st ->
                runOnUiThread(() -> startCamera(st)));
        glSurface.setRenderer(renderer);
        glSurface.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    }

    @Override
    protected void onResume() {
        super.onResume();
        glSurface.onResume();

        // ★ Профиль мог измениться в MainActivity, пока мы были на паузе.
        if (cameraStarted && appliedProfile >= 0) {
            int current = PhotoSettings.getStreamProfile(this);
            if (current != appliedProfile) {
                Log.i(TAG, "onResume: profile changed "
                        + PhotoSettings.labelStreamProfile(appliedProfile) + " → "
                        + PhotoSettings.labelStreamProfile(current)
                        + ", restarting camera");
                restartWithCurrentProfile();
            }
        }
    }

    @Override protected void onPause()   { super.onPause();   glSurface.onPause(); }
    @Override protected void onStop()    { super.onStop();    stopCamera(); }
    @Override protected void onDestroy() { super.onDestroy(); stopCamera(); }

    // ============ Камера ============

    private void startCamera(SurfaceTexture st) {
        if (st == null) return;

        final int profile = PhotoSettings.getStreamProfile(this);
        final int photoRes = PhotoSettings.profilePhotoRes(profile);
        final int videoRes = PhotoSettings.profileVideoRes(profile);
        final int bitrate  = PhotoSettings.profileBitrate(profile);
        final int liveMode = PhotoSettings.profileLiveMode(profile);
        final int vw = PhotoSettings.profileVideoWidth(profile);
        final int vh = PhotoSettings.profileVideoHeight(profile);

        // ★ ОПТИМИЗАЦИЯ 3 (FLAT): размер буфера SurfaceTexture = размеру профиля
        try { st.setDefaultBufferSize(vw, vh); } catch (Exception ignored) {}

        Log.i(TAG, "startCamera: profile=" + PhotoSettings.labelStreamProfile(profile)
                + " photoRes=0x" + Integer.toHexString(photoRes)
                + " videoRes=0x" + Integer.toHexString(videoRes)
                + " bitrate=0x" + Integer.toHexString(bitrate)
                + " liveMode=0x" + Integer.toHexString(liveMode)
                + " size=" + vw + "x" + vh);

        // Поверхность пересоздана (например, после сворачивания) — переключаем декодер
        if (cameraStarted) {
            if (decoder != null) {
                Surface newS = new Surface(st);
                try {
                    decoder.updateSurface(newS);
                    if (surface != null) surface.release();
                    surface = newS;
                } catch (IOException e) {
                    Log.w(TAG, "updateSurface: " + e.getMessage());
                }
            }
            return;
        }

        UsbManager mgr = (UsbManager) getSystemService(USB_SERVICE);
        if (mgr == null) { overlay.setText("No USB"); return; }
        UsbDevice cam = findCamera(mgr);
        if (cam == null) { overlay.setText("Camera not connected"); return; }

        cameraStarted = true;
        running = true;
        frameCount = 0;
        appliedProfile = profile;
        appliedVideoRes = videoRes;

        surface = new Surface(st);
        decoder = new H264Decoder(surface, vw, vh);
        try {
            decoder.start();
        } catch (IOException e) {
            cameraStarted = false;
            running = false;
            overlay.setText("Decoder start failed");
            return;
        }

        usb = new UsbCameraManager(this);
        usb.start(new UsbCameraManager.Listener() {
            @Override
            public void onPermissionRequested() {
                runOnUiThread(() -> overlay.setText("⚠ Ожидание разрешения USB…"));
            }
            @Override
            public void onConnected(UsbDeviceConnection conn, UsbInterface intf,
                                    UsbEndpoint in, UsbEndpoint out) {
                if (!cameraStarted) return;

                // ★ ОПТИМИЗАЦИЯ 2: новый конструктор с bitrate/liveMode из профиля
                session = new LiveViewSession(conn, in, out,
                        photoRes, videoRes, bitrate, liveMode,
                        new LiveViewSession.FrameListener() {
                            @Override public void onVideoFrame(byte[] h264, long ptsUs) {
                                H264Decoder d = decoder;
                                if (d != null) d.feed(h264, ptsUs);
                                int fc = ++frameCount;
                                if (fc % 30 == 0) {
                                    final int n = fc;
                                    runOnUiThread(() -> overlay.setText(
                                            "FLAT " + vw + "×" + vh + "  frames=" + n));
                                }
                            }
                            @Override public void onError(String msg) {
                                Log.e(TAG, "LiveView err: " + msg);
                            }
                        });
                new Thread(() -> {
                    boolean ok = session.start();
                    runOnUiThread(() -> overlay.setText(ok
                            ? "FLAT " + vw + "×" + vh + " — live"
                            : "Camera start failed"));
                }, "LV-start").start();
            }
            @Override public void onDetached() {
                runOnUiThread(() -> { overlay.setText("Camera detached"); stopCamera(); });
            }
            @Override public void onPermissionDenied() {
                runOnUiThread(() -> { overlay.setText("USB denied"); stopCamera(); });
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> { overlay.setText("USB error: " + msg); stopCamera(); });
            }
        });
    }

    /** Полный перезапуск камеры при смене профиля на лету. */
    private void restartWithCurrentProfile() {
        SurfaceTexture st = renderer != null ? renderer.getSurfaceTexture() : null;
        stopCamera();
        if (st != null) startCamera(st);
    }

    private void stopCamera() {
        cameraStarted = false;
        running = false;
        if (session != null) { try { session.stop(); } catch (Exception ignored) {} session = null; }
        if (usb     != null) { try { usb.stop();     } catch (Exception ignored) {} usb     = null; }
        if (decoder != null) { try { decoder.stop(); } catch (Exception ignored) {} decoder = null; }
        if (surface != null) { try { surface.release(); } catch (Exception ignored) {} surface = null; }
        appliedProfile = -1;
        appliedVideoRes = -1;
    }

    private UsbDevice findCamera(UsbManager mgr) {
        for (UsbDevice d : mgr.getDeviceList().values()) {
            if (d.getVendorId() == ScsiConstants.VENDOR_ID
                    && d.getProductId() == ScsiConstants.PRODUCT_ID) return d;
        }
        return null;
    }

    // ============ Фото (без изменений) ============

    private void onTakePhotoClicked() {
        if (session == null || !running) {
            Toast.makeText(this, "Нет соединения", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hasStoragePermission()) { requestStoragePermission(); return; }
        doTakePhoto();
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStoragePermission() {
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.WRITE_EXTERNAL_STORAGE;
        ActivityCompat.requestPermissions(this, new String[]{perm}, REQ_STORAGE);
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_STORAGE) return;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) doTakePhoto();
        else Toast.makeText(this, "Без разрешения фото не сохранить", Toast.LENGTH_LONG).show();
    }

    private void doTakePhoto() {
        btnPhoto.setEnabled(false);
        overlay.setText("📷 Захват…");
        session.takePhoto(new LiveViewSession.PhotoListener() {
            @Override public void onPhotoReady(byte[] jpeg, long ts) {
                if (jpeg == null || jpeg.length < 2
                        || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
                    runOnUiThread(() -> {
                        btnPhoto.setEnabled(true);
                        overlay.setText("Photo err: invalid JPEG");
                    });
                    return;
                }
                runOnUiThread(() -> savePhoto(jpeg));
            }
            @Override public void onPhotoError(String msg) {
                runOnUiThread(() -> {
                    btnPhoto.setEnabled(true);
                    overlay.setText("Photo err: " + msg);
                });
            }
        });
    }

    private void savePhoto(byte[] jpeg) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) savePhotoQ(jpeg);
        else savePhotoLegacy(jpeg);
    }

    private void savePhotoQ(byte[] jpeg) {
        String name = "LIVE360FLAT_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date())
                + ".jpg";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + PHOTO_SUBDIR);
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = null;
        try {
            uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IOException("insert() returned null");
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new IOException("openOutputStream() returned null");
                os.write(jpeg);
                os.flush();
            }
            ContentValues pub = new ContentValues();
            pub.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, pub, null, null);
            btnPhoto.setEnabled(true);
            overlay.setText("Saved: " + name);
            Toast.makeText(this, "Фото сохранено: " + name, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            if (uri != null) try { getContentResolver().delete(uri, null, null); }
            catch (Exception ignored) {}
            btnPhoto.setEnabled(true);
            overlay.setText("Save failed: " + e.getMessage());
        }
    }

    private void savePhotoLegacy(byte[] jpeg) {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), PHOTO_SUBDIR);
        if (!dir.exists() && !dir.mkdirs()) {
            btnPhoto.setEnabled(true);
            overlay.setText("mkdir failed: " + dir);
            return;
        }
        String name = "LIVE360FLAT_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date())
                + ".jpg";
        final File f = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(jpeg);
            fos.flush();
        } catch (IOException e) {
            btnPhoto.setEnabled(true);
            overlay.setText("Save failed: " + e.getMessage());
            return;
        }
        MediaScannerConnection.scanFile(this,
                new String[]{f.getAbsolutePath()},
                new String[]{"image/jpeg"}, null);
        btnPhoto.setEnabled(true);
        overlay.setText("Saved: " + f.getAbsolutePath());
        Toast.makeText(this, "Фото: " + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
    }
}