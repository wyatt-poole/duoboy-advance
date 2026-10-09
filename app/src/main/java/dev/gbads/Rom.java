package dev.gbads;

import android.graphics.Bitmap;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;

/** Read-only view of the ROM file: pulls the game's own font, icons, palettes, names. */
final class Rom {
    final byte[] d;
    // GF ROM header (0x100) — present in every Gen 3 ROM and kept current by expansion tools.
    final int monIcons, monIconPalIds, monIconPals, speciesNames, moveNames;
    // FireRed-family font (profile values; Unbound keeps vanilla FR locations).
    int fontGlyphs = 0x081FF300, fontWidths = 0x08207300;
    int startMenuTable;
    final long crc32;
    /** Pokémon Unbound 2.1.1.1 on FireRed (USA) v1.0: the build DuoBoy Advance was tested against (others run in safe mode). */
    static final long TESTED_UNBOUND_CRC = 0x4B3D4957L;
    boolean testedBuild() { return crc32 == TESTED_UNBOUND_CRC; }

    Rom(File f) throws IOException {
        d = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            for (int n = 0; n < d.length; ) n += in.read(d, n, d.length - n);
        }
        java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(d, 0, d.length); crc32 = crc.getValue();
        int h = 0x100 + 8 + 32;
        monIcons = u32(h + 16); monIconPalIds = u32(h + 20); monIconPals = u32(h + 24);
        speciesNames = u32(h + 28); moveNames = u32(h + 32);
        startMenuTable = findStartMenuTable();
    }

    int u8(int a) { return d[a & 0x1FFFFFF] & 0xFF; }
    int u16(int a) { return u8(a) | u8(a + 1) << 8; }
    int u32(int a) { return u16(a) | u16(a + 2) << 16; }
    boolean isRom(int p) { return p >= 0x08000000 && p < 0x08000000 + d.length; }

    // ---- text ----
    static final HashMap<Character, Integer> ENC = new HashMap<>();
    static final char[] DEC = new char[256];
    static {
        java.util.Arrays.fill(DEC, '?');
        put(0x00, ' '); put(0xAB, '!'); put(0xAC, '?'); put(0xAD, '.'); put(0xAE, '-'); put(0xB0, '…');
        put(0xB4, '\''); put(0xB5, '♂'); put(0xB6, '♀'); put(0xB8, ','); put(0xBA, '/'); put(0x1B, 'é');
        put(0xF0, ':'); put(0x5C, '('); put(0x5D, ')'); put(0x34, 'ʟ'); /* "Lv" glyph */
        put(0xFE, '\n'); put(0xB1, '“'); put(0xB2, '”'); put(0xB3, '‘'); put(0x5B, '%'); put(0xEF, '▶');
        put(0x2E, '+'); put(0xB9, '*'); put(0x79, '↑'); put(0x7A, '↓'); put(0x7B, '←'); // CFRU move-menu markers
        for (int i = 0; i < 10; i++) put(0xA1 + i, (char) ('0' + i));
        for (int i = 0; i < 26; i++) { put(0xBB + i, (char) ('A' + i)); put(0xD5 + i, (char) ('a' + i)); }
    }
    private static void put(int b, char c) { DEC[b] = c; ENC.put(c, b); }

    String text(int addr, int max) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < max; i++) {
            int b = u8(addr + i);
            if (b == 0xFF) break;
            s.append(DEC[b]);
        }
        return s.toString();
    }
    /** Decodes in-RAM game text (nicknames). */
    static String text(byte[] b, int off, int max) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < max && (b[off + i] & 0xFF) != 0xFF; i++) s.append(DEC[b[off + i] & 0xFF]);
        return s.toString();
    }

    String speciesName(int species) { return text(speciesNames + 11 * species, 11); }
    /** SpeciesToNationalPokedexNum (Unbound's table at its literal). */
    int nationalDex(int species) { return species <= 0 ? 0 : u16(0x09A41FEC + (species - 1) * 2); }
    /** Base stats entry (28 bytes, GetGenderFromSpeciesAndPersonality's literal): HP Atk Def Spe SpA SpD, types at 6/7. */
    int baseStat(int species, int field) { return u8(u32(0x0803F7C8) + species * 28 + field); }
    String abilityName(int a) { return text(0x08A36398 + 17 * a, 17); } // Unbound gAbilityNames (17-byte names)
    String moveName(int move) { return text(moveNames + 13 * move, 13); }
    /** GetGenderFromSpeciesAndPersonality: 0 male, 1 female, 2 genderless (base stats via its literal, ratio at +0x10). */
    int gender(int species, int personality) {
        int ratio = u8(u32(0x0803F7C8) + species * 28 + 0x10);
        if (ratio == 0xFF) return 2;
        if (ratio == 0xFE) return 1;
        if (ratio == 0) return 0;
        return ratio > (personality & 0xFF) ? 1 : 0;
    }
    /** Move description (lines split by \n): CFRU's expanded table, via the literal the summary screen loads it from. */
    String moveDescription(int move) {
        int table = u32(0x080E5440), p = move > 0 ? u32(table + (move - 1) * 4) : 0;
        return isRom(p) ? text(p, 200) : "";
    }

    // ---- font: 16x16 glyphs, 4 tiles of 2bpp, high bits = leftmost pixel. 1 = ink, 2 = shadow ----
    int glyphWidth(int c) { return u8(fontWidths + c); }
    // FR small font (DecompressGlyph_Small): 8x16 glyphs as two stacked 2bpp tiles, ~5px wide, 13 tall
    int smallGlyphs = 0x081EAF00, smallWidths = 0x081EEF00;
    int smallGlyphWidth(int c) { return u8(smallWidths + c); }
    int smallGlyphPixel(int c, int x, int y) {
        int row = u16(smallGlyphs + c * 0x20 + (y >> 3) * 16 + (y & 7) * 2);
        return (row >> (14 - 2 * (x & 7))) & 3;
    }
    /** Region-map section grid (GetMapSecIdAt, layer 0, 22x15) and names (GetMapName's sMapNames, from 0x58). */
    int mapSecAt(int x, int y) { return x < 0 || y < 0 || x >= 22 || y >= 15 ? 0xC5 : u8(0x083F2490 + y * 22 + x); }
    String mapName(int mapsec) {
        int i = mapsec - 0x58;
        if (i < 0 || i >= 0x1B4 / 4) return "";
        int ptr = u32(0x083F1CAC + i * 4);
        return isRom(ptr) ? text(ptr, 24) : "";
    }
    int glyphPixel(int c, int x, int y) {
        int tile = (y >> 3) * 2 + (x >> 3);
        int row = u16(fontGlyphs + c * 0x40 + tile * 16 + (y & 7) * 2);
        return (row >> (14 - 2 * (x & 7))) & 3;
    }

    // ---- graphics ----
    /** GBA BIOS LZ77 (type 0x10). */
    byte[] lz77(int addr) {
        int o = addr & 0x1FFFFFF;
        if ((d[o] & 0xFF) != 0x10) throw new IllegalArgumentException("not LZ77 @" + Integer.toHexString(addr));
        int size = (d[o + 1] & 0xFF) | (d[o + 2] & 0xFF) << 8 | (d[o + 3] & 0xFF) << 16, n = 0;
        byte[] out = new byte[size];
        o += 4;
        while (n < size) {
            int flags = d[o++] & 0xFF;
            for (int bit = 0; bit < 8 && n < size; bit++) {
                if ((flags & (0x80 >> bit)) != 0) {
                    int b1 = d[o++] & 0xFF, b2 = d[o++] & 0xFF, len = (b1 >> 4) + 3, disp = ((b1 & 15) << 8 | b2) + 1;
                    for (int i = 0; i < len && n < size; i++, n++) out[n] = out[n - disp];
                } else out[n++] = d[o++];
            }
        }
        return out;
    }
    static int[] palette(byte[] b, int off) {
        int[] p = new int[16];
        for (int i = 0; i < 16; i++) p[i] = rgb555((b[off + i * 2] & 0xFF) | (b[off + i * 2 + 1] & 0xFF) << 8);
        p[0] = 0;
        return p;
    }
    /** Paints one 4bpp tile into px (row stride w). Index 0 is left untouched (transparent). */
    static void tile(byte[] tiles, int t, int[] pal, int[] px, int w, int x0, int y0, boolean hf, boolean vf) {
        for (int i = 0; i < 32; i++) {
            int b = tiles[t * 32 + i] & 0xFF;
            for (int h = 0; h < 2; h++) {
                int c = h == 0 ? b & 15 : b >> 4;
                if (c == 0) continue;
                int x = (i % 4) * 2 + h, y = i / 4;
                if (hf) x = 7 - x;
                if (vf) y = 7 - y;
                px[(y0 + y) * w + x0 + x] = pal[c];
            }
        }
    }
    /** Text-mode BG: u16 tilemap entries (tile | hflip<<10 | vflip<<11 | pal<<12). */
    static Bitmap bg(byte[] tiles, byte[] map, int[][] pals, int tw, int th) {
        int[] px = new int[tw * 8 * th * 8];
        for (int i = 0; i < tw * th && i * 2 + 1 < map.length; i++) {
            int e = (map[i * 2] & 0xFF) | (map[i * 2 + 1] & 0xFF) << 8, t = e & 0x3FF;
            if (t * 32 + 32 > tiles.length || (e >> 12) >= pals.length) continue;
            tile(tiles, t, pals[e >> 12], px, tw * 8, (i % tw) * 8, (i / tw) * 8, (e & 0x400) != 0, (e & 0x800) != 0);
        }
        return Bitmap.createBitmap(px, tw * 8, th * 8, Bitmap.Config.ARGB_8888);
    }

    static int rgb555(int c) {
        int r = c & 31, g = c >> 5 & 31, b = c >> 10 & 31;
        return 0xFF000000 | (r << 3 | r >> 2) << 16 | (g << 3 | g >> 2) << 8 | (b << 3 | b >> 2);
    }
    int[] palette(int addr) {
        int[] p = new int[16];
        for (int i = 0; i < 16; i++) p[i] = rgb555(u16(addr + i * 2));
        p[0] = 0; // index 0 is transparent
        return p;
    }
    /** Uncompressed 4bpp tiles laid out row-major, tilesW tiles wide. */
    Bitmap tiles4(int addr, int tilesW, int tilesH, int[] pal) {
        int w = tilesW * 8, h = tilesH * 8;
        int[] px = new int[w * h];
        for (int t = 0; t < tilesW * tilesH; t++)
            for (int i = 0; i < 32; i++) {
                int b = u8(addr + t * 32 + i);
                int x = (t % tilesW) * 8 + (i % 4) * 2, y = (t / tilesW) * 8 + i / 4;
                px[y * w + x] = pal[b & 15];
                px[y * w + x + 1] = pal[b >> 4];
            }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    private final HashMap<Integer, Bitmap> iconCache = new HashMap<>();
    /** First 32x32 frame of the party/box icon. */
    Bitmap icon(int species) { return icon(species, 0); }
    /** Icon frame 0 or 1 (the two frames the game alternates). */
    Bitmap icon(int species, int frame) {
        return iconCache.computeIfAbsent(species | (frame & 1) << 16, k -> {
            int s = k & 0xFFFF;
            int palId = u8(monIconPalIds + s);
            int pal = u32(monIconPals + palId * 8); // SpritePalette { const u16* data; u16 tag; }
            int[] pl = palette(pal);
            // ponytail: Unbound's Pancham icon paints its white fur with icon palette 1's pale yellows (11/12); redraw it
            // with that palette's white / light grey (3/2). Add species here if other icons look off.
            if (s == 782) { pl = pl.clone(); pl[11] = pl[3]; pl[12] = pl[2]; }
            return tiles4(u32(monIcons + s * 4) + (k >> 16) * 512, 4, 4, pl);
        });
    }

    // ---- start menu: run of MenuAction {text*, func*} starting "Pokédex","Pokémon" ----
    private int findStartMenuTable() {
        for (int o = 0; o < d.length - 16; o += 4) {
            int a = u32(o), f = u32(o + 4), b = u32(o + 8);
            if ((f & 1) == 1 && isRom(a) && isRom(b) && isRom(f)
                    && text(a, 12).equalsIgnoreCase("pokédex") && text(b, 12).equalsIgnoreCase("pokémon"))
                return 0x08000000 + o;
        }
        return 0;
    }
    String startMenuLabel(int action) {
        return startMenuTable == 0 ? "" : text(u32(startMenuTable + action * 8), 16);
    }
}
