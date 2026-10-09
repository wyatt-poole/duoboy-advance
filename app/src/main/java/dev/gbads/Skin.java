package dev.gbads;

import android.graphics.Bitmap;

/**
 * Unbound's own UI art, pulled from the ROM at startup: start-menu bar + gold frames + icons, party-menu background and slots.
 * Every pointer is read from the literal pool of the code that uses it, so a ROM revision that moves data still works.
 */
final class Skin {
    // ---- start-menu bar overlay (purple/gold) ----
    final Bitmap bar;               // 256x160, transparent where the overworld shows through
    final Bitmap[] box = new Bitmap[9]; // gold-framed purple box: TL T TR / L C R / BL B BR
    final int textInk, textShadow;  // white text, dark shadow
    // ---- icons: 32x32, colour and greyed (Unbound greys unselected entries) ----
    final Bitmap[] icon, iconGrey, icon2, icon2Grey;
    // ---- party menu ----
    final Bitmap partyBg;           // 240x160 grid
    final Bitmap slotNormal, slotFainted, slotSelected; // 112x40 cards
    final Bitmap slotEgg, slotEggSelected; // sSlotTilemap_MainNoHP (right after Main): the game's card for an Egg
    final Bitmap[] menuBall = new Bitmap[2];   // 32x32: closed, open (the selected card's)
    static final int STATUS_PSN = 0, STATUS_PRZ = 1, STATUS_SLP = 2, STATUS_FRZ = 3, STATUS_BRN = 4, STATUS_FNT = 6;
    final Bitmap[] statusIcon = new Bitmap[7]; // 32x8 badges: PSN PRZ SLP FRZ BRN PKRS FNT
    final Bitmap[] heldItem = new Bitmap[2];   // 8x8: item, mail
    static final int SLOT_W = 112, SLOT_H = 40, HP_X = 56, HP_Y = 22, HP_W = 48;
    // ---- battle ----
    private final Bitmap typeSheet;  // FR menu-info sheet (CFRU's type icons incl. Fairy), 16 tiles wide
    private final Rom rom;
    private final int typeTable;     // {u8 w, u8 h, u16 tileOffset} per icon id; type t is icon t + 1
    final Bitmap[] pss = new Bitmap[3]; // Physical / Special / Status, 24x16
    final int[] effColours;          // CFRU type-highlighting palette: super 0/1, not very 4/5, no effect 8/9, regular 12/13
    final Bitmap escapeRope;         // item icon, used for Run
    final Bitmap pokeBall;           // item icon, drawn on the Last Ball trigger like the game does
    final Bitmap townMap;            // item icon for the map/party toggle
    final Bitmap smallBall;          // party menu's small Poké Ball sprite (closed frame), 16x16
    final Bitmap megaTrigger, lastBallTrigger; // CFRU battle triggers (tags 0xFDF4 / 0xFDFA), 32x32
    // ---- region map (FR engine; Unbound's Borrius): LoadRegionMapGfx / CreatePlayerIcon literals ----
    final Bitmap regionMap;          // 240x160, map tile (x, y) centred at pixel (8x + 36, 8y + 36)
    final Bitmap mapFrame;           // 240x160 frame drawn over it in-game (window = x 16..216)
    final Bitmap[] mapCursor = new Bitmap[2]; // the Town Map's selection brackets (2 animation frames), 16x16
    final Bitmap[] playerMapIcon = new Bitmap[2]; // [male, female] 16x16 heads
    static final int KEY_A = 0, KEY_B = 1, KEY_L = 2, KEY_R = 3, KEY_START = 4, KEY_SELECT = 5;
    final Bitmap[] keypad = new Bitmap[6]; // gKeypadIconTiles via DrawKeypadIcon's literals: {u16 tile, u8 w, u8 h}
    final Bitmap battleTextbox;      // 240x48: Unbound's plain message box (what the top screen shows between menus)
    // ---- UI colours: only ever colours from the game's palettes ----
    final int[] tb;                  // battle textbox: purples 6 403050, 8 583880, 15 684898, 11 9070c0; golds 13 d09010, 14 f0b850; 9 282828
    final int[] hp;                  // healthbox: HP green 10/11, yellow 12/13, red 14/15 (ink/shade); 7 282828 frame, 5 484058 track
    final int[] barPal;              // start-menu bar: 1 9068c0 purple, 3 f0d068 gold, 5 282828
    final int[][] partyPal;          // party-menu card palettes
    static final int BALL_OK = 0, BALL_EMPTY = 1, BALL_STATUS = 2, BALL_FAINTED = 3;
    final Bitmap[] partyBalls = new Bitmap[4]; // the battle's party status balls (8x8), healthbox palette

    Skin(Rom r) {
        rom = r;
        // Bar: literals in Unbound's start-menu setup (tiles, tilemap, palette).
        byte[] barTiles = r.lz77(r.u32(0x08A0C210)), barMap = r.lz77(r.u32(0x08A0C218));
        barPal = Rom.palette(r.lz77(r.u32(0x08A0C1FC)), 0);
        int[][] barPals = new int[16][];
        java.util.Arrays.fill(barPals, barPal); // single palette, but the tilemap references it at whatever slot the game loads it to
        bar = Rom.bg(barTiles, barMap, barPals, 32, 20);
        textInk = barPal[14];
        textShadow = barPal[15];
        // The left info box sits at tile cols 0..11, rows 10..13 of the bar map.
        int[][] at = {{0, 10}, {1, 10}, {11, 10}, {0, 11}, {1, 11}, {11, 11}, {0, 13}, {1, 13}, {11, 13}};
        for (int i = 0; i < 9; i++) box[i] = Bitmap.createBitmap(bar, at[i][0] * 8, at[i][1] * 8, 8, 8);

        // Icons: SpriteSheet {lz data*, size, tag} / SpritePalette {data*, tag} next to the menu table.
        icon = frames(r.lz77(r.u32(0x08A6D118)), Rom.palette(r.d, r.u32(0x08A6D100) & 0x1FFFFFF));
        icon2 = frames(r.lz77(r.u32(0x08A6D120)), Rom.palette(r.d, r.u32(0x08A6D108) & 0x1FFFFFF));
        iconGrey = grey(icon);
        icon2Grey = grey(icon2);

        // Party menu: literals in AllocPartyMenuBgGfx (vanilla FR function, Unbound's data).
        byte[] pTiles = r.lz77(r.u32(0x0811EFB0)), pMap = r.lz77(r.u32(0x0811EFCC)), pPalRaw = r.lz77(r.u32(0x0811EFF4));
        int[][] pPals = partyPal = new int[pPalRaw.length / 32][];
        for (int i = 0; i < pPals.length; i++) { pPals[i] = Rom.palette(pPalRaw, i * 32); pPals[i][0] = Rom.rgb555((pPalRaw[i * 32] & 0xFF) | (pPalRaw[i * 32 + 1] & 0xFF) << 8); }
        partyBg = Bitmap.createBitmap(Rom.bg(pTiles, pMap, pPals, 32, 20), 0, 0, 240, 160);
        int slotMap = r.u32(0x08121380); // sSlotTilemap_Main: 14x5 tile indices
        // ponytail: palettes 3/5/0 match the game's normal/fainted/selected cards by eye; emulate LoadPartyBoxPalette if a hack differs
        // The HP label / bar colours (9..15) always come from the normal card's palette, like the game's separate HP palette
        int[] faintPal = pPals[5].clone(), selPal = pPals[0].clone();
        System.arraycopy(pPals[3], 9, faintPal, 9, 7);
        System.arraycopy(pPals[3], 9, selPal, 9, 7);
        slotNormal = slot(r, pTiles, slotMap, pPals[3]);
        slotFainted = slot(r, pTiles, slotMap, faintPal);
        slotSelected = slot(r, pTiles, slotMap, selPal);
        slotEgg = slot(r, pTiles, slotMap + 0x46, pPals[3]);
        slotEggSelected = slot(r, pTiles, slotMap + 0x46, selPal);
        // party menu sprites (FR sprite records, Unbound's data): big Poké Ball (closed / open), status badges, held item
        int[] ballPal = Rom.palette(r.lz77(r.u32(0x0845A47C)), 0);
        byte[] ballGfx = r.lz77(r.u32(0x0845A474));
        for (int f = 0; f < 2; f++) menuBall[f] = sprite32(java.util.Arrays.copyOfRange(ballGfx, f * 512, f * 512 + 512), ballPal);
        int sp = r.u32(0x0845A57C);
        int[] statusPal = r.u8(sp) == 0x10 ? Rom.palette(r.lz77(sp), 0) : r.palette(sp);
        byte[] statusGfx = r.lz77(r.u32(0x0845A574));
        for (int f = 0; f < 7; f++) {
            int[] px = new int[32 * 8];
            for (int t = 0; t < 4; t++) Rom.tile(statusGfx, f * 4 + t, statusPal, px, 32, t * 8, 0, false, false);
            statusIcon[f] = Bitmap.createBitmap(px, 32, 8, Bitmap.Config.ARGB_8888);
        }
        int[] heldPal = r.palette(r.u32(0x0845A434));
        for (int f = 0; f < 2; f++) {
            int[] px = new int[64];
            Rom.tile(r.d, (r.u32(0x0845A42C) & 0x1FFFFFF) / 32 + f, heldPal, px, 8, 0, 0, false, false);
            heldItem[f] = Bitmap.createBitmap(px, 8, 8, Bitmap.Config.ARGB_8888);
        }

        // Type icons: BlitMenuInfoIcon's literals (table, sheet); palette = gMenuInfoElements2_Pal.
        typeTable = r.u32(0x08107DAC); // BlitMenuInfoIcon's literal pool
        int sheet = r.u32(0x08107DB0);
        int[] tPal = r.palette(0x08E95DBC);
        byte[] sheetBytes = java.util.Arrays.copyOfRange(r.d, sheet & 0x1FFFFFF, (sheet & 0x1FFFFFF) + 16 * 20 * 32);
        int[] spx = new int[128 * 160];
        for (int t = 0; t < 16 * 20; t++) Rom.tile(sheetBytes, t, tPal, spx, 128, (t % 16) * 8, (t / 16) * 8, false, false);
        typeSheet = Bitmap.createBitmap(spx, 128, 160, Bitmap.Config.ARGB_8888);

        // ponytail: Unbound addresses found by content-matching CFRU's art (PSSIcons / TypeHighlighting); other hacks need their own
        int[] pssPal = r.palette(0x09FD24AC);
        for (int i = 0; i < 3; i++) {
            int[] px = new int[24 * 16];
            for (int t = 0; t < 6; t++) Rom.tile(r.d, (0x08AC9B20 & 0x1FFFFFF) / 32 + i * 6 + t, pssPal, px, 24, (t % 3) * 8, (t / 3) * 8, false, false);
            pss[i] = Bitmap.createBitmap(px, 24, 16, Bitmap.Config.ARGB_8888);
        }
        effColours = r.palette(0x08ACA3E4);
        effColours[0] = Rom.rgb555(r.u16(0x08ACA3E4));

        // Escape Rope: item icon table entry {lz gfx, lz palette}, 24x24
        byte[] rope = r.lz77(r.u32(0x083D453C));
        int[] ropePal = Rom.palette(r.lz77(r.u32(0x083D4540)), 0);
        int[] rpx = new int[24 * 24];
        for (int t = 0; t < 9; t++) Rom.tile(rope, t, ropePal, rpx, 24, (t % 3) * 8, (t / 3) * 8, false, false);
        escapeRope = Bitmap.createBitmap(rpx, 24, 24, Bitmap.Config.ARGB_8888);
        pokeBall = itemIcon(r, 0x083D453C - (85 - 4) * 8); // item table entry for ITEM_POKE_BALL (4), Escape Rope is 85
        townMap = itemIcon(r, 0x083D453C + (361 - 85) * 8); // ITEM_TOWN_MAP
        { // LoadPartyMenuPokeballGfx: small ball sheet struct @0845A4EC, palette struct @0845A47C (both LZ)
            byte[] g = r.lz77(r.u32(0x0845A4EC));
            int[] bp = Rom.palette(r.lz77(r.u32(0x0845A47C)), 0), px = new int[16 * 16];
            for (int t = 0; t < 4; t++) Rom.tile(g, t, bp, px, 16, (t % 2) * 8, (t / 2) * 8, false, false);
            smallBall = Bitmap.createBitmap(px, 16, 16, Bitmap.Config.ARGB_8888);
        }

        int kTable = r.u32(0x08006410), kTiles = r.u32(0x08006414); // DrawKeypadIcon literal pool
        int[] kPal = r.palette(0x0841F408); // gStandardMenuPalette, same as the text it sits in
        int[] kpx = new int[128 * 32];
        for (int t = 0; t < 64; t++) Rom.tile(r.d, (kTiles & 0x1FFFFFF) / 32 + t, kPal, kpx, 128, (t % 16) * 8, (t / 16) * 8, false, false);
        Bitmap kSheet = Bitmap.createBitmap(kpx, 128, 32, Bitmap.Config.ARGB_8888);
        for (int i = 0; i < 6; i++) {
            int e = kTable + i * 4, tile = r.u16(e), w = r.u8(e + 2), h = r.u8(e + 3);
            keypad[i] = Bitmap.createBitmap(kSheet, (tile % 16) * 8, (tile / 16) * 8, w, h);
        }

        byte[] rmTiles = r.lz77(r.u32(0x080C0330)), rmLayout = r.lz77(r.u32(0x080C035C));
        byte[] rmPalRaw = java.util.Arrays.copyOfRange(r.d, r.u32(0x080C02EC) & 0x1FFFFFF, (r.u32(0x080C02EC) & 0x1FFFFFF) + 5 * 32);
        int[][] rmPals = new int[16][];
        for (int i = 0; i < 16; i++) { rmPals[i] = Rom.palette(rmPalRaw, (i % 5) * 32); rmPals[i][0] = Rom.rgb555((rmPalRaw[(i % 5) * 32] & 0xFF) | (rmPalRaw[(i % 5) * 32 + 1] & 0xFF) << 8); }
        // the layout is 30 tiles wide; re-pack to the 32-wide rows Rom.bg expects
        byte[] rm32 = new byte[32 * 20 * 2];
        for (int i = 0; i < rmLayout.length / 2 && i < 30 * 20; i++) {
            int dst = ((i / 30) * 32 + i % 30) * 2, e = (rmLayout[i * 2] & 0xFF) | (rmLayout[i * 2 + 1] & 0xFF) << 8;
            // ponytail: the in-game map's close "X" (3x3 tiles at 24..26, 16..18) becomes plain ocean (tile 2); Unbound layout only
            if (e == 0x30D8 || e == 0x30D9 || e == 0x30DA || e == 0x30E7 || e == 0x30E8 || e == 0x30E9 || e == 0x30FE || e == 0x30FF || e == 0x3100) e = 0x0002;
            rm32[dst] = (byte) e; rm32[dst + 1] = (byte) (e >> 8);
        }
        Bitmap rm = Bitmap.createBitmap(Rom.bg(rmTiles, rm32, rmPals, 32, 20), 0, 0, 240, 160);
        // the layout's last row is only partly filled: continue each column from the pixel above (ocean stays ocean)
        int[] rmPx = new int[240 * 160];
        rm.getPixels(rmPx, 0, 240, 0, 0, 240, 160);
        for (int y = 1; y < 160; y++) for (int x = 0; x < 240; x++) if (rmPx[y * 240 + x] >>> 24 == 0) rmPx[y * 240 + x] = rmPx[(y - 1) * 240 + x];
        regionMap = Bitmap.createBitmap(rmPx, 240, 160, Bitmap.Config.ARGB_8888);
        // the Town Map's frame (white panel + orange/yellow side pieces): LoadMapEdgeGfx's literals, 30-wide layout
        byte[] edgeTiles = r.lz77(r.u32(0x080C2460)), edgeLayout = r.lz77(r.u32(0x080C2478));
        byte[] edge32 = new byte[32 * 20 * 2];
        for (int i = 0; i < edgeLayout.length / 2 && i < 30 * 20; i++) {
            int dst = ((i / 30) * 32 + i % 30) * 2;
            edge32[dst] = edgeLayout[i * 2]; edge32[dst + 1] = edgeLayout[i * 2 + 1];
        }
        int[][] edgePals = new int[16][];
        for (int i = 0; i < 16; i++) { edgePals[i] = rmPals[i].clone(); edgePals[i][0] = 0; }
        mapFrame = Bitmap.createBitmap(Rom.bg(edgeTiles, edge32, edgePals, 32, 20), 0, 0, 240, 160);
        { // CreateMapCursor's sheet; the game tints it yellow at runtime (OBJ palette, colour 1 = 248,240,0)
            byte[] cg = r.lz77(r.u32(0x080C3094));
            int[] cp = new int[16]; cp[1] = 0xFFF8F000;
            for (int f = 0; f < 2; f++) {
                int[] px = new int[16 * 16];
                for (int t = 0; t < 4; t++) Rom.tile(cg, f * 4 + t, cp, px, 16, (t % 2) * 8, (t / 2) * 8, false, false);
                mapCursor[f] = Bitmap.createBitmap(px, 16, 16, Bitmap.Config.ARGB_8888);
            }
        }
        int[] headPal = r.palette(0x08EF3E04);
        for (int g = 0; g < 2; g++) {
            byte[] head = r.lz77(r.u32(g == 0 ? 0x080C423C : 0x080C420C)); // male (spiky), female
            int[] hp = new int[16 * 16];
            for (int t = 0; t < 4; t++) Rom.tile(head, t, headPal, hp, 16, (t % 2) * 8, (t / 2) * 8, false, false);
            playerMapIcon[g] = Bitmap.createBitmap(hp, 16, 16, Bitmap.Config.ARGB_8888);
        }

        // ponytail: Unbound's CFRU sprite sheets/palette located by tag; other CFRU hacks keep the same tags
        int[] trigPal = r.palette(0x08AC97DC);
        megaTrigger = sprite32(r.lz77(0x08AC96CC), trigPal);
        lastBallTrigger = sprite32(r.lz77(0x08AC9568), trigPal);

        // Battle text box: LoadBattleTextboxAndBackground's literals; rows 14..19 of the first screen = the message box.
        byte[] tbTiles = r.lz77(r.u32(0x0800F454)), tbMap = r.lz77(r.u32(0x0800F458));
        int[] tbPal = tb = Rom.palette(r.lz77(r.u32(0x0800F45C)), 0);
        hp = r.palette(0x08D11BA4);
        for (int i = 0; i < 4; i++) { // ponytail: sheet found via its SpriteSheet record (tag 0xD714) at 0x08260498
            int[] px = new int[64];
            Rom.tile(r.d, (0x08D12404 & 0x1FFFFFF) / 32 + i, hp, px, 8, 0, 0, false, false);
            partyBalls[i] = Bitmap.createBitmap(px, 8, 8, Bitmap.Config.ARGB_8888);
        } // ponytail: Unbound's healthbox palette found by content (battle OBJ palette); other hacks need theirs
        int[][] tbPals = new int[16][];
        java.util.Arrays.fill(tbPals, tbPal);
        battleTextbox = Bitmap.createBitmap(Rom.bg(tbTiles, tbMap, tbPals, 32, 20), 0, 112, 240, 48);
    }

    private final java.util.HashMap<Integer, Bitmap> itemIcons = new java.util.HashMap<>();
    /** Item icon by id: Unbound's expanded gItemIconTable {pic, pal} (GetItemIconPicOrPalette's literal). */
    Bitmap item(int id) {
        return itemIcons.computeIfAbsent(id, k -> { try { return itemIcon(rom, 0x0825A554 + k * 8); } catch (Exception e) { return pokeBall; } });
    }
    private static Bitmap itemIcon(Rom r, int entry) {
        byte[] g = r.lz77(r.u32(entry));
        int[] p = Rom.palette(r.lz77(r.u32(entry + 4)), 0), px = new int[24 * 24];
        for (int t = 0; t < 9; t++) Rom.tile(g, t, p, px, 24, (t % 3) * 8, (t / 3) * 8, false, false);
        return Bitmap.createBitmap(px, 24, 24, Bitmap.Config.ARGB_8888);
    }
    private static Bitmap sprite32(byte[] g, int[] p) {
        int[] px = new int[32 * 32];
        for (int t = 0; t < 16; t++) Rom.tile(g, t, p, px, 32, (t % 4) * 8, (t / 4) * 8, false, false);
        return Bitmap.createBitmap(px, 32, 32, Bitmap.Config.ARGB_8888);
    }

    private final java.util.HashMap<Integer, Integer> typeColours = new java.util.HashMap<>();
    /** Button colour for a type: the fill of its official icon, exactly as the ROM has it. */
    int typeColour(int type) {
        return typeColours.computeIfAbsent(type, t -> {
            Bitmap ic = typeIcon(t);
            return ic == null ? tb[15] : ic.getPixel(1, 1); // the top fill just inside the border is the type's colour
        });
    }

    /** Official type icon (CFRU ids, Fairy = 23), or null. */
    Bitmap typeIcon(int type) {
        int e = typeTable + (type + 1) * 4, w = rom.u8(e), h = rom.u8(e + 1), off = rom.u16(e + 2);
        int x = (off % 16) * 8, y = (off / 16) * 8;
        if (w == 0 || h == 0 || y + h > typeSheet.getHeight() || x + w > 128) return null;
        return Bitmap.createBitmap(typeSheet, x, y, w, h);
    }

    private static Bitmap slot(Rom r, byte[] tiles, int map, int[] pal) {
        int[] px = new int[SLOT_W * SLOT_H];
        for (int i = 0; i < 14 * 5; i++) Rom.tile(tiles, r.u8(map + i), pal, px, SLOT_W, (i % 14) * 8, (i / 14) * 8, false, false);
        return Bitmap.createBitmap(px, SLOT_W, SLOT_H, Bitmap.Config.ARGB_8888);
    }

    private static Bitmap[] frames(byte[] sheet, int[] pal) {
        Bitmap[] out = new Bitmap[sheet.length / 512];
        for (int f = 0; f < out.length; f++) {
            int[] px = new int[32 * 32];
            for (int t = 0; t < 16; t++) Rom.tile(sheet, f * 16 + t, pal, px, 32, (t % 4) * 8, (t / 4) * 8, false, false);
            out[f] = Bitmap.createBitmap(px, 32, 32, Bitmap.Config.ARGB_8888);
        }
        return out;
    }

    private static Bitmap[] grey(Bitmap[] in) {
        Bitmap[] out = new Bitmap[in.length];
        for (int i = 0; i < in.length; i++) {
            int[] px = new int[32 * 32];
            in[i].getPixels(px, 0, 32, 0, 0, 32, 32);
            for (int k = 0; k < px.length; k++) {
                int c = px[k];
                if (c >>> 24 == 0) continue;
                // the game's own TintPalette_GrayScale on 5-bit channels (Q8.8 0.3 / 0.59 / 0.1133)
                int y = Math.min(31, ((c >> 19 & 31) * 76 + (c >> 11 & 31) * 151 + (c >> 3 & 31) * 29) >> 8) << 3;
                px[k] = 0xFF000000 | y << 16 | y << 8 | y;
            }
            out[i] = Bitmap.createBitmap(px, 32, 32, Bitmap.Config.ARGB_8888);
        }
        return out;
    }
}
