package ru.miacomsoft.live360viewerserver.settings;

import android.content.Context;
import android.content.SharedPreferences;

public final class PhotoSettings {

    public static final String PREFS   = "photo_settings";
    public static final String KEY_RES = "photo_res";
    public static final String KEY_VIEW_MODE = "view_mode";

    // ★ Только ДВА валидных значения photo_res на Huawei CV60
    public static final int RES_5MP  = 0x03;
    public static final int RES_13MP = 0x04;
    public static final int DEFAULT_RES = RES_5MP;

    // ★ Режимы просмотра
    public static final int VIEW_MODE_SPHERE = 0;
    public static final int VIEW_MODE_FLAT   = 1;
    public static final int DEFAULT_VIEW_MODE = VIEW_MODE_SPHERE;

    // ★ video_res — байт [4] в SET_ALL_SETTINGS (0x4857).
    //   0x0A → 1280×640 — единственное подтверждённое значение.
    //   Остальные — кандидаты, проверять по логу
    //   H264Decoder "decoder real size = WxH".
    public static final int VIDEO_RES_640x320  = 0x07; // ? кандидат
    public static final int VIDEO_RES_960x480  = 0x08; // ? кандидат
    public static final int VIDEO_RES_1024x512 = 0x09; // ? кандидат
    public static final int VIDEO_RES_1280x640 = 0x0A; // ★ подтверждено
    public static final int VIDEO_RES_1920x960 = 0x0B; // ? кандидат
    public static final int DEFAULT_VIDEO_RES = VIDEO_RES_1280x640;

    public static final String KEY_VIDEO_RES = "video_res";

    private static final int[] ALL = { RES_5MP, RES_13MP };
    private static final int[] ALL_VIDEO = {
            VIDEO_RES_640x320, VIDEO_RES_960x480, VIDEO_RES_1024x512,
            VIDEO_RES_1280x640, VIDEO_RES_1920x960
    };

    private PhotoSettings() {}

    public static int[] allCodes() { return ALL.clone(); }

    public static String label(int code) {
        switch (code) {
            case RES_5MP:  return "5 МП (0x03)";
            case RES_13MP: return "13 МП (0x04)";
            default:       return "0x" + Integer.toHexString(code) + " (невалидный)";
        }
    }

    public static boolean isValid(int code) {
        return code == RES_5MP || code == RES_13MP;
    }

    public static int getResolution(Context ctx) {
        int saved = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_RES, DEFAULT_RES);
        if (!isValid(saved)) {
            saved = DEFAULT_RES;
            setResolution(ctx, saved);
        }
        return saved;
    }

    public static void setResolution(Context ctx, int code) {
        if (!isValid(code)) code = DEFAULT_RES;
        ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_RES, code).apply();
    }

    // ============ Режим просмотра ============

    public static int[] allViewModes() {
        return new int[] { VIEW_MODE_SPHERE, VIEW_MODE_FLAT };
    }

    public static String labelView(int mode) {
        switch (mode) {
            case VIEW_MODE_SPHERE: return "Сфера (3D)";
            case VIEW_MODE_FLAT:   return "Плоский (360×180)";
            default:               return "Неизвестный режим";
        }
    }

    public static boolean isValidViewMode(int mode) {
        return mode == VIEW_MODE_SPHERE || mode == VIEW_MODE_FLAT;
    }

    public static int getViewMode(Context ctx) {
        int saved = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_VIEW_MODE, DEFAULT_VIEW_MODE);
        if (!isValidViewMode(saved)) {
            saved = DEFAULT_VIEW_MODE;
            setViewMode(ctx, saved);
        }
        return saved;
    }

    public static void setViewMode(Context ctx, int mode) {
        if (!isValidViewMode(mode)) mode = DEFAULT_VIEW_MODE;
        ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_VIEW_MODE, mode).apply();
    }

    // ============ video_res (live-stream) ============

    public static int[] allVideoCodes() { return ALL_VIDEO.clone(); }

    public static String labelVideo(int code) {
        switch (code) {
            case VIDEO_RES_640x320:  return "640×320 (0x07, ?)";
            case VIDEO_RES_960x480:  return "960×480 (0x08, ?)";
            case VIDEO_RES_1024x512: return "1024×512 (0x09, ?)";
            case VIDEO_RES_1280x640: return "1280×640 (0x0A, ★)";
            case VIDEO_RES_1920x960: return "1920×960 (0x0B, ?)";
            default: return "0x" + Integer.toHexString(code) + " (?)";
        }
    }

    public static boolean isValidVideo(int code) {
        for (int c : ALL_VIDEO) if (c == code) return true;
        return false;
    }

    public static int getVideoResolution(Context ctx) {
        int saved = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_VIDEO_RES, DEFAULT_VIDEO_RES);
        if (!isValidVideo(saved)) {
            saved = DEFAULT_VIDEO_RES;
            setVideoResolution(ctx, saved);
        }
        return saved;
    }

    public static void setVideoResolution(Context ctx, int code) {
        if (!isValidVideo(code)) code = DEFAULT_VIDEO_RES;
        ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_VIDEO_RES, code).apply();
    }
}