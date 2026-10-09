package dev.gbads;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** ROM hack patching: IPS, UPS and BPS. The user brings their own base ROM (.gba or a .zip holding one). */
final class Patcher {
    private Patcher() {}

    /** Reads a base ROM; for a .zip, its first .gba entry. */
    static byte[] readRom(InputStream in, boolean zip) throws IOException {
        if (!zip) return readAll(in);
        try (ZipInputStream z = new ZipInputStream(in)) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; )
                if (e.getName().toLowerCase().endsWith(".gba")) return readAll(z);
        }
        throw new IOException("No .gba inside that zip");
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        byte[] buf = new byte[1 << 16];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static byte[] apply(byte[] patch, byte[] rom) throws IOException {
        if (starts(patch, "PATCH")) return ips(patch, rom);
        if (starts(patch, "UPS1")) return ups(patch, rom);
        if (starts(patch, "BPS1")) return bps(patch, rom);
        throw new IOException("Not an IPS, UPS or BPS patch");
    }

    private static boolean starts(byte[] b, String magic) {
        if (b.length < magic.length()) return false;
        for (int i = 0; i < magic.length(); i++) if (b[i] != magic.charAt(i)) return false;
        return true;
    }

    private static int u8(byte[] b, int i) { return b[i] & 0xFF; }

    private static byte[] ips(byte[] p, byte[] rom) {
        byte[] out = rom.clone();
        int pos = 5;
        while (pos + 3 <= p.length && !(p[pos] == 'E' && p[pos + 1] == 'O' && p[pos + 2] == 'F')) {
            int off = u8(p, pos) << 16 | u8(p, pos + 1) << 8 | u8(p, pos + 2), size = u8(p, pos + 3) << 8 | u8(p, pos + 4);
            pos += 5;
            int rle = 0, value = 0;
            if (size == 0) { rle = u8(p, pos) << 8 | u8(p, pos + 1); value = p[pos + 2]; pos += 3; }
            int end = off + (size == 0 ? rle : size);
            if (end > out.length) out = java.util.Arrays.copyOf(out, end);
            if (size == 0) java.util.Arrays.fill(out, off, end, (byte) value);
            else { System.arraycopy(p, pos, out, off, size); pos += size; }
        }
        if (pos + 6 <= p.length) { // optional truncate after EOF
            int len = u8(p, pos + 3) << 16 | u8(p, pos + 4) << 8 | u8(p, pos + 5);
            out = java.util.Arrays.copyOf(out, len);
        }
        return out;
    }

    private static int vpos;
    /** UPS / BPS variable-length number. */
    private static long varint(byte[] p) {
        long data = 0, shift = 1;
        while (true) {
            int x = u8(p, vpos++);
            data += (x & 0x7F) * shift;
            if ((x & 0x80) != 0) return data;
            shift <<= 7;
            data += shift;
        }
    }

    private static long crc(byte[] b, int len) { CRC32 c = new CRC32(); c.update(b, 0, len); return c.getValue(); }
    private static long le32(byte[] b, int i) { return (u8(b, i) | u8(b, i + 1) << 8 | u8(b, i + 2) << 16 | (long) u8(b, i + 3) << 24); }

    private static synchronized byte[] ups(byte[] p, byte[] rom) throws IOException {
        vpos = 4;
        int inSize = (int) varint(p), outSize = (int) varint(p);
        if (rom.length != inSize || crc(rom, rom.length) != le32(p, p.length - 12)) throw new IOException("Wrong base ROM for this patch");
        byte[] out = java.util.Arrays.copyOf(rom, outSize);
        int ptr = 0;
        while (vpos < p.length - 12) {
            ptr += (int) varint(p);
            while (true) {
                int b = u8(p, vpos++);
                if (ptr < outSize) out[ptr] ^= (byte) b;
                ptr++;
                if (b == 0) break;
            }
        }
        if (crc(out, out.length) != le32(p, p.length - 8)) throw new IOException("Patched ROM failed its checksum");
        return out;
    }

    private static synchronized byte[] bps(byte[] p, byte[] rom) throws IOException {
        vpos = 4;
        int srcSize = (int) varint(p), dstSize = (int) varint(p), meta = (int) varint(p);
        vpos += meta;
        if (rom.length != srcSize || crc(rom, rom.length) != le32(p, p.length - 12)) throw new IOException("Wrong base ROM for this patch");
        byte[] out = new byte[dstSize];
        int outPos = 0, srcRel = 0, dstRel = 0;
        while (vpos < p.length - 12) {
            long data = varint(p);
            int cmd = (int) (data & 3), len = (int) (data >> 2) + 1;
            switch (cmd) {
                case 0: System.arraycopy(rom, outPos, out, outPos, len); outPos += len; break;         // SourceRead
                case 1: System.arraycopy(p, vpos, out, outPos, len); vpos += len; outPos += len; break; // TargetRead
                case 2: { long d = varint(p); srcRel += (int) ((d & 1) != 0 ? -(d >> 1) : d >> 1);      // SourceCopy
                    System.arraycopy(rom, srcRel, out, outPos, len); srcRel += len; outPos += len; break; }
                default: { long d = varint(p); dstRel += (int) ((d & 1) != 0 ? -(d >> 1) : d >> 1);     // TargetCopy (may overlap)
                    for (int i = 0; i < len; i++) out[outPos++] = out[dstRel++]; }
            }
        }
        if (crc(out, out.length) != le32(p, p.length - 8)) throw new IOException("Patched ROM failed its checksum");
        return out;
    }
}
