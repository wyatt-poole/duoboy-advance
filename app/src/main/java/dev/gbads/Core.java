package dev.gbads;

import java.nio.ByteBuffer;

/** libmgba via JNI. Only call from the emulation thread. */
final class Core {
    static { System.loadLibrary("gbads"); }
    static native int load(String rom, String sav);
    static native int frame(int keys, ByteBuffer pixels, short[] audio);
    static native void read(int addr, byte[] out);
    static native void write8(int addr, int value);
    static native void write32(int addr, int value);
    static int read8(int addr) { byte[] b = new byte[1]; read(addr, b); return b[0] & 0xFF; }
    static int read32(int addr) { byte[] b = new byte[4]; read(addr, b); return Game.le32(b, 0); }
    static native void layer(int id, boolean on);
    static native void reset();
    /** Save states (emu thread, between frames); the battery save is never part of them. */
    static native boolean saveState(String path);
    static native boolean loadState(String path);

    // GBA KEYINPUT bits
    static final int A = 1, B = 2, SELECT = 4, START = 8, RIGHT = 16, LEFT = 32, UP = 64, DOWN = 128, R = 256, L = 512;
}
