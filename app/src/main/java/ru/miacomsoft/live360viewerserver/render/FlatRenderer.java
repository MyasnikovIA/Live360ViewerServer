package ru.miacomsoft.live360viewerserver.render;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * «Плоский» рендерер полной equirect-панорамы (360×180).
 *
 * Задача этого режима — показать ВСЮ развёртку панорамы на экране
 * целиком, без «окна» FOV и без интерактивного yaw/pitch.
 *
 * Изображение масштабируется с сохранением пропорций 2:1 (360°×180°):
 *  - если экран шире 2:1 → поля слева/справа,
 *  - если уже → поля сверху/снизу.
 *
 * В отличие от WebRTC-стрима (MainActivity.SurfaceTextureCapturer), здесь
 * кадр идёт напрямую из MediaCodec в SurfaceTexture — без повторного
 * кодирования, поэтому картинка сохраняет исходную чёткость.
 *
 * setRotation(...) оставлен как no-op для API-совместимости со SphereRenderer
 * (если он появится в будущем).
 */
public class FlatRenderer implements GLSurfaceView.Renderer,
        SurfaceTexture.OnFrameAvailableListener {

    private static final String TAG = "FlatRenderer";

    public interface OnSurfaceReadyListener {
        void onSurfaceReady(SurfaceTexture st);
    }

    private int program;
    private int aPos, aTex, uTex, uScale;
    private int texId;
    private FloatBuffer posBuf, texBuf;
    private ShortBuffer idxBuf;
    private int idxCount;

    private SurfaceTexture surfaceTexture;
    private final Object texLock = new Object();
    private boolean frameAvailable;

    private float scaleX = 1f;
    private float scaleY = 1f;

    private OnSurfaceReadyListener readyListener;

    public FlatRenderer() {}

    public SurfaceTexture getSurfaceTexture() { return surfaceTexture; }

    public void setOnSurfaceReadyListener(OnSurfaceReadyListener l) {
        this.readyListener = l;
    }

    /** No-op: в плоском режиме нет вращения. Оставлен для совместимости. */
    public void setRotation(float dYaw, float dPitch) { /* no-op */ }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        buildQuad();
        buildProgram();

        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        texId = t[0];

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        synchronized (texLock) {
            if (surfaceTexture != null) {
                try { surfaceTexture.release(); } catch (Exception ignored) {}
                surfaceTexture = null;
            }
            surfaceTexture = new SurfaceTexture(texId);
            surfaceTexture.setOnFrameAvailableListener(this);
            frameAvailable = false;
        }
        Log.i(TAG, "onSurfaceCreated: SurfaceTexture created @ " + surfaceTexture);

        if (readyListener != null) {
            readyListener.onSurfaceReady(surfaceTexture);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        GLES20.glViewport(0, 0, w, h);

        // Сохраняем пропорции 2:1 (equirect 360×180) при вписывании в экран
        float aspect = (float) w / h;
        final float PANO_ASPECT = 2f;
        if (aspect > PANO_ASPECT) {
            scaleX = PANO_ASPECT / aspect;   // поля слева/справа
            scaleY = 1f;
        } else {
            scaleX = 1f;
            scaleY = aspect / PANO_ASPECT;   // поля сверху/снизу
        }
        Log.i(TAG, "onSurfaceChanged " + w + "x" + h
                + " → scale=" + scaleX + "x" + scaleY);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        synchronized (texLock) {
            if (frameAvailable && surfaceTexture != null) {
                surfaceTexture.updateTexImage();
                frameAvailable = false;
            }
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glUniform1i(uTex, 0);
        GLES20.glUniform2f(uScale, scaleX, scaleY);

        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, posBuf);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texBuf);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, idxCount,
                GLES20.GL_UNSIGNED_SHORT, idxBuf);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aTex);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        synchronized (texLock) { frameAvailable = true; }
    }

    // ---------- helpers ----------

    private void buildQuad() {
        float[] pos = new float[] {
                -1f, -1f, 0f,
                1f, -1f, 0f,
                1f,  1f, 0f,
                -1f,  1f, 0f
        };
        float[] tex = new float[] {
                0f, 1f,
                1f, 1f,
                1f, 0f,
                0f, 0f
        };
        short[] idx = new short[] { 0,1,2, 0,2,3 };
        idxCount = idx.length;

        posBuf = ByteBuffer.allocateDirect(pos.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        posBuf.put(pos).position(0);

        texBuf = ByteBuffer.allocateDirect(tex.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        texBuf.put(tex).position(0);

        idxBuf = ByteBuffer.allocateDirect(idx.length * 2)
                .order(ByteOrder.nativeOrder()).asShortBuffer();
        idxBuf.put(idx).position(0);
    }

    private void buildProgram() {
        String vs =
                "uniform vec2 uScale;\n" +
                        "attribute vec4 aPos;\n" +
                        "attribute vec2 aTex;\n" +
                        "varying vec2 vTex;\n" +
                        "void main(){\n" +
                        "  gl_Position = vec4(aPos.x * uScale.x, aPos.y * uScale.y, aPos.z, 1.0);\n" +
                        "  vTex = aTex;\n" +
                        "}\n";

        String fs =
                "#extension GL_OES_EGL_image_external : require\n" +
                        "precision mediump float;\n" +
                        "varying vec2 vTex;\n" +
                        "uniform samplerExternalOES uTex;\n" +
                        "void main(){ gl_FragColor = texture2D(uTex, vTex); }\n";

        int v = compile(GLES20.GL_VERTEX_SHADER, vs);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        if (v == 0 || f == 0) {
            Log.e(TAG, "buildProgram: shader compile failed");
            return;
        }
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, v);
        GLES20.glAttachShader(program, f);
        GLES20.glLinkProgram(program);

        int[] ok = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e(TAG, "program link: " + GLES20.glGetProgramInfoLog(program));
        }

        aPos   = GLES20.glGetAttribLocation(program, "aPos");
        aTex   = GLES20.glGetAttribLocation(program, "aTex");
        uTex   = GLES20.glGetUniformLocation(program, "uTex");
        uScale = GLES20.glGetUniformLocation(program, "uScale");
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e(TAG, "shader compile: " + GLES20.glGetShaderInfoLog(s));
            return 0;
        }
        return s;
    }
}