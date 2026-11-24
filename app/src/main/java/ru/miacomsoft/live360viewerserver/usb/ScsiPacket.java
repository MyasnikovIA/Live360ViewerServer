package ru.miacomsoft.live360viewerserver.usb;

import java.nio.ByteBuffer;
import java.util.Random;

/**
 * CBW (Command Block Wrapper), 31 байт, стандарт USB SCSI BOT.
 *
 *  4B signature   = "USBC" = 0x55534243 (BE в буфере)
 *  4B tag         = random (LE)
 *  4B dataLength  = LE
 *  1B flags       = 0x80 (READ) / 0x00 (WRITE)
 *  1B lun         = 0
 *  1B cdbLength   = 16
 * 16B CDB
 * -------------
 * 31 байт итого
 */
public final class ScsiPacket {

    private final int tag;
    private final int dataLength;
    private final byte flags;
    private final byte lun = 0;
    private final byte cdbLength = 16;
    private byte[] cdb;
    private final int cmd;

    public ScsiPacket(int cmd, int dataLength, boolean read) {
        this.cmd = cmd;
        this.tag = new Random().nextInt();
        this.dataLength = dataLength;
        this.flags = (byte) (read ? 0x80 : 0x00);

        this.cdb = new byte[16];
        this.cdb[0] = (byte) (cmd & 0xFF);
        this.cdb[1] = (byte) ((cmd >>> 8) & 0xFF);
    }

    public int tag() { return tag; }
    public int cmd() { return cmd; }
    public int dataLength() { return dataLength; }

    public ScsiPacket setCdb(byte[] data) {
        this.cdb = new byte[16];
        if (data != null && data.length > 0) {
            int n = Math.min(data.length, 16);
            System.arraycopy(data, 0, this.cdb, 0, n);
        } else {
            this.cdb[0] = (byte) (cmd & 0xFF);
            this.cdb[1] = (byte) ((cmd >>> 8) & 0xFF);
        }
        return this;
    }

    public ScsiPacket setPayload(byte[] data) {
        return setCdb(data);
    }

    public byte[] build() {
        ByteBuffer b = ByteBuffer.allocate(31);
        b.putInt(ScsiConstants.CBW_MAGIC);              // ← "USBC"!!! НЕ "USBS"
        b.putInt(tag);                                  // tag (как есть)
        b.putInt(Integer.reverseBytes(dataLength));     // ← LE
        b.put(flags);                                   // 0x80 read / 0x00 write
        b.put(lun);                                     // 0
        b.put(cdbLength);                               // 16
        b.put(cdb);                                     // 16 байт CDB
        return b.array();
    }
}