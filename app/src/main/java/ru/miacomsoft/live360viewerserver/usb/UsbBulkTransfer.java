package ru.miacomsoft.live360viewerserver.usb;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.util.Log;

import java.io.ByteArrayOutputStream;

public class UsbBulkTransfer {

    private static final String TAG = "Bulk";

    // ★ 5 с вместо 1 с: камера может делать паузы при отправке большого JPEG
    private static final int TIMEOUT_MS = 5000;

    // ★ до 16 МБ — с запасом для фото 8-13 МП
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

    private final UsbDeviceConnection conn;
    private final UsbEndpoint epIn;
    private final UsbEndpoint epOut;

    public UsbBulkTransfer(UsbDeviceConnection conn, UsbEndpoint epIn, UsbEndpoint epOut) {
        this.conn = conn;
        this.epIn = epIn;
        this.epOut = epOut;
    }

    public synchronized int readBulk(byte[] out, int timeoutMs) {
        int n = conn.bulkTransfer(epIn, out, out.length, timeoutMs);
        if (n > 0) {
            Log.d(TAG, "readBulk standalone n=" + n
                    + " first8: " + hex(slice(out, Math.min(n, 8))));
        }
        return n > 0 ? n : 0;
    }

    public synchronized int execute(ScsiPacket pkt, byte[] writeData, byte[] outBuf) {
        byte[] header = pkt.build();
        Log.d(TAG, "CBW cmd=0x" + Integer.toHexString(pkt.cmd())
                + " len=" + pkt.dataLength() + " : " + hex(header));

        int w = conn.bulkTransfer(epOut, header, header.length, TIMEOUT_MS);
        if (w != header.length) {
            Log.e(TAG, "CBW write fail: " + w + " cmd=0x" + Integer.toHexString(pkt.cmd()));
            return -3;
        }

        if (writeData != null && writeData.length > 0) {
            int off = 0;
            while (off < writeData.length) {
                int chunk = Math.min(ScsiConstants.MAX_BULK, writeData.length - off);
                byte[] s = new byte[chunk];
                System.arraycopy(writeData, off, s, 0, chunk);
                int n = conn.bulkTransfer(epOut, s, chunk, TIMEOUT_MS);
                if (n <= 0) { Log.e(TAG, "data write fail: " + n); return -3; }
                off += n;
            }
        }

        ByteArrayOutputStream acc = new ByteArrayOutputStream();
        byte[] tmp = new byte[ScsiConstants.MAX_BULK];
        int retries = 0;
        while (true) {
            int n = conn.bulkTransfer(epIn, tmp, tmp.length, TIMEOUT_MS);
            if (n < 0) {
                if (retries++ < 2) continue;
                Log.e(TAG, "bulk in fail: " + n + " cmd=0x" + Integer.toHexString(pkt.cmd()));
                return -3;
            }
            if (n == 0) { if (retries++ < 2) continue; return -1; }
            retries = 0;
            acc.write(tmp, 0, n);
            byte[] all = acc.toByteArray();
            if (CswParser.isCsw(all, all.length, pkt.tag())) {
                int status = CswParser.status(all, all.length);
                int dataLen = all.length - ScsiConstants.CSW_SIZE;

                // ★ всегда логируем размер — важно для диагностики фото
                Log.d(TAG, "CSW status=" + status + " cmd=0x"
                        + Integer.toHexString(pkt.cmd()) + " dataLen=" + dataLen);

                if (outBuf != null && dataLen > 0) {
                    int copy = Math.min(dataLen, outBuf.length);
                    System.arraycopy(all, 0, outBuf, 0, copy);
                }
                return dataLen;
            }
            if (all.length > MAX_RESPONSE_BYTES) {
                Log.e(TAG, "response too big: " + all.length);
                return -1;
            }
        }
    }

    private static byte[] slice(byte[] src, int len) {
        byte[] r = new byte[len];
        System.arraycopy(src, 0, r, 0, len);
        return r;
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) sb.append(String.format("%02X ", b));
        return sb.toString();
    }
}