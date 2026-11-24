package ru.miacomsoft.live360viewerserver.usb;

public final class ScsiConstants {

    public static final int VENDOR_ID  = 4817;   // 0x12D1 Huawei
    public static final int PRODUCT_ID = 4251;   // 0x109B

    public static final int CMD_USB_CORE_INIT    = 0x4800;
    public static final int CMD_USB_CORE_DEINIT  = 0x4801;
    public static final int CMD_USB_CORE_RESET   = 0x4802;
    public static final int CMD_START_COMM       = 0x480A;
    public static final int CMD_CLOSE_COMM       = 0x480B;
    public static final int CMD_RESET_CAMERA     = 0x4810;
    public static final int CMD_START_LIVE_VIEW  = 0x4811;
    public static final int CMD_STOP_LIVE_VIEW   = 0x4812;
    public static final int CMD_START_RECORD     = 0x4813;
    public static final int CMD_STOP_RECORD      = 0x4814;
    public static final int CMD_TAKE_PICTURE     = 0x4815;
    public static final int CMD_CLEAR_BUF        = 0x481B;
    public static final int CMD_GET_FRAME        = 0x4820;
    public static final int CMD_GET_PICTURE      = 0x4821;
    public static final int CMD_GET_THUMBNAIL    = 0x4822;
    public static final int CMD_GET_FW_VERSION   = 0x4830;
    public static final int CMD_GET_SCSI_VERSION = 0x4834;
    public static final int CMD_SET_ALL_SETTINGS = 0x4857;
    public static final int CMD_GET_PHOTO_RES    = 0x4840;

    public static final int CBW_MAGIC = 0x55534243;   // "USBC"
    public static final int CSW_MAGIC = 0x55534253;   // "USBS"
    public static final int MAGIC     = CSW_MAGIC;

    public static final int CSW_SIZE = 13;

    // ★ Увеличено: один bulk-transfer до 64 КБ (было 16 КБ)
    public static final int MAX_BULK = 65536;

    // ★ Увеличено: держим полный JPEG в буфере (было 64 КБ)
    public static final int DEFAULT_READ_BUF = 2 * 1024 * 1024;   // 2 МБ

    public static final long KEEPALIVE_PERIOD_MS = 500L;

    private ScsiConstants() {}
}