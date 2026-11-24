package ru.miacomsoft.live360viewerserver.usb;

public final class CswParser {

    public static boolean isCsw(byte[] buf, int len, int expectedTag) {
        if (buf == null || len < ScsiConstants.CSW_SIZE) return false;
        int o = len - ScsiConstants.CSW_SIZE;
        // CSW signature = "USBS" = 0x55534253
        if (buf[o] != 'U' || buf[o+1] != 'S' || buf[o+2] != 'B' || buf[o+3] != 'S') return false;
        if (expectedTag == 0) return true;
        return buf[o+4] == (byte)(expectedTag >>> 24)
                && buf[o+5] == (byte)(expectedTag >>> 16)
                && buf[o+6] == (byte)(expectedTag >>> 8)
                && buf[o+7] == (byte)(expectedTag);
    }

    public static int status(byte[] buf, int len) {
        if (len < ScsiConstants.CSW_SIZE) return -1;
        return buf[len - 1] & 0xFF;
    }

    private CswParser() {}
}