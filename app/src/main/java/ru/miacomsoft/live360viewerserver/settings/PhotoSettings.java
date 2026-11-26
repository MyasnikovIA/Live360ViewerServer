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

    // ★★★ Профили камеры для WebRTC-трансляции ★★★
    //
    // Механизм трансляции ОДИН — WebRTC (MainActivity.startStreaming).
    // Профиль управляет ТОЛЬКО параметрами, отправляемыми камере:
    //   - SET_ALL_SETTINGS (0x4857): байт [3]=photo_res, байт [4]=video_res,
    //                                байт [9]=bitrate
    //   - START_LIVE_VIEW (0x4811): байт [9]=liveMode
    //
    // ★ ОПТИМИЗАЦИЯ 1: профили РАЗВЕДЕНЫ по реально влияющим байтам.
    //   Реально влияет на live-видео только video_res (байт [4]).
    //   STANDARD: video_res=0x0A (1280×640) — текущее подтверждённое.
    //   HD:       video_res=0x0B (1920×960) — кандидат; если камера
    //             не поднимет live-view, заменить на VIDEO_RES_1024x512
    //             (0x09) или вернуть 0x0A — правится здесь одной строкой.
    //
    //   photo_res (0x03/0x04) влияет ТОЛЬКО на JPEG-фото, не на live-видео.
    //   bitrate (0x08/0x0A) — предположительно, но подтверждённого влияния
    //   на live-видео нет; если камера игнорирует, оставить как есть.
    public static final String KEY_STREAM_PROFILE = "stream_profile";

    public static final int PROFILE_STANDARD = 0; // 1280×640, 5 МП, MID
    public static final int PROFILE_HD       = 1; // 1920×960 (кандидат), 13 МП, HIGH
    public static final int DEFAULT_STREAM_PROFILE = PROFILE_STANDARD;

    private static final int[] ALL_PROFILES = { PROFILE_STANDARD, PROFILE_HD };

    private PhotoSettings() {}

    // ============ photo_res ============

    public static int[] allCodes() { return new int[] { RES_5MP, RES_13MP }; }

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

    // ============ video_res ============

    public static int[] allVideoCodes() {
        return new int[] {
                VIDEO_RES_640x320, VIDEO_RES_960x480, VIDEO_RES_1024x512,
                VIDEO_RES_1280x640, VIDEO_RES_1920x960
        };
    }

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
        for (int c : allVideoCodes()) if (c == code) return true;
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

    // ============ Профили камеры (stream profile) ============

    public static int[] allStreamProfiles() { return ALL_PROFILES.clone(); }

    public static String labelStreamProfile(int profile) {
        switch (profile) {
            case PROFILE_STANDARD: return "Стандарт (1280×640)";
            case PROFILE_HD:       return "HD (1920×960)";
            default:               return "Неизвестный профиль";
        }
    }

    public static boolean isValidStreamProfile(int profile) {
        return profile == PROFILE_STANDARD || profile == PROFILE_HD;
    }

    public static int getStreamProfile(Context ctx) {
        int saved = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_STREAM_PROFILE, DEFAULT_STREAM_PROFILE);
        if (!isValidStreamProfile(saved)) {
            saved = DEFAULT_STREAM_PROFILE;
            setStreamProfile(ctx, saved);
        }
        return saved;
    }

    public static void setStreamProfile(Context ctx, int profile) {
        if (!isValidStreamProfile(profile)) profile = DEFAULT_STREAM_PROFILE;
        ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_STREAM_PROFILE, profile).apply();
    }

    // ----- Параметры профилей -----

    public static int profilePhotoRes(int profile) {
        switch (profile) {
            case PROFILE_HD: return RES_13MP;                // 13 МП JPEG
            case PROFILE_STANDARD:
            default:         return RES_5MP;                 // 5 МП JPEG
        }
    }

    public static int profileVideoRes(int profile) {
        switch (profile) {
            case PROFILE_HD: return VIDEO_RES_1920x960;      // ★ 0x0B — кандидат
            case PROFILE_STANDARD:
            default:         return VIDEO_RES_1280x640;      // ★ 0x0A — подтверждено
        }
    }

    /** Байт [9] в SET_ALL_SETTINGS (0x4857). */
    public static int profileBitrate(int profile) {
        switch (profile) {
            case PROFILE_HD: return 0x0A;                    // HIGH
            case PROFILE_STANDARD:
            default:         return 0x08;                    // MID
        }
    }

    /** Байт [9] в START_LIVE_VIEW (0x4811). */
    public static int profileLiveMode(int profile) {
        switch (profile) {
            case PROFILE_HD:
            case PROFILE_STANDARD:
            default:         return 0x0A;
        }
    }

    // ----- ★ ОПТИМИЗАЦИЯ 3: хелперы размеров для SurfaceTexture / WebRTC -----

    public static int videoResWidth(int videoResCode) {
        switch (videoResCode) {
            case VIDEO_RES_640x320:  return 640;
            case VIDEO_RES_960x480:  return 960;
            case VIDEO_RES_1024x512: return 1024;
            case VIDEO_RES_1280x640: return 1280;
            case VIDEO_RES_1920x960: return 1920;
            default:                 return 1280;
        }
    }

    public static int videoResHeight(int videoResCode) {
        switch (videoResCode) {
            case VIDEO_RES_640x320:  return 320;
            case VIDEO_RES_960x480:  return 480;
            case VIDEO_RES_1024x512: return 512;
            case VIDEO_RES_1280x640: return 640;
            case VIDEO_RES_1920x960: return 960;
            default:                 return 640;
        }
    }

    public static int profileVideoWidth(int profile) {
        return videoResWidth(profileVideoRes(profile));
    }

    public static int profileVideoHeight(int profile) {
        return videoResHeight(profileVideoRes(profile));
    }
}