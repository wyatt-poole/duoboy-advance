package dev.gbads;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Bottom screen. Everything is drawn into a 248x216 pixel canvas (Thor's 1240x1080 panel = exactly 5x)
 * using Unbound's own art (see Skin) and the ROM's font, then scaled by a whole number with no filtering.
 */
final class Bottom extends View {
    static final int W = 248, H = 216;
    /** Bottom bar entries, as Unbound start-menu table indices. */
    static final int[] BUTTON_ENTRY = {Game.POKEDEX, Game.POKEMON, Game.CUBE, Game.SAVE, Game.MISSIONS};

    /** App-level settings the bottom screen exposes (implemented by MainActivity). */
    interface Host {
        String raUser(); boolean hardcore(); String romName(); String saveDir();
        void raLogin(); void raLogout(); void toggleHardcore(); void pickRom(); void pickSaveDir(); void importSave(); void exportSave(); String saveType(); void toggleSaveType();
        java.util.List<String[]> roms(); String romDir(); void pickRomDir(); void clearRomDir(); void addRom(); void removeRom(String path);
        String currentRom(); void playRom(String path); void addShortcut(); void patchRom();
    }

    private final Host host;
    private final Game game;
    private final Rom rom;
    private final Skin skin;
    private final Bitmap fb = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final Canvas c = new Canvas(fb);
    private final Paint fill = new Paint(), blit = new Paint();
    private final Rect src = new Rect(), dst = new Rect();
    private boolean settingsOpen;
    private int pressed = -1;
    private final SimpleDateFormat clock = new SimpleDateFormat("h:mm a", Locale.US);

    Bottom(Context ctx, Game game, Host host) {
        super(ctx);
        this.host = host;
        this.game = game;
        this.rom = game.rom;
        this.skin = new Skin(rom);
        blit.setFilterBitmap(false);
        setBackgroundColor(0xFF000000);
    }

    // ---------- pixel helpers ----------
    private void rect(int x, int y, int w, int h, int argb) { fill.setColor(argb); c.drawRect(x, y, x + w, y + h, fill); }
    private void blit(Bitmap b, int sx, int sy, int w, int h, int x, int y) {
        src.set(sx, sy, sx + w, sy + h); dst.set(x, y, x + w, y + h); c.drawBitmap(b, src, dst, null);
    }

    /** Game text: white ink + dark shadow like Unbound's menus. Returns width in pixels. */
    private int text(String s, int x, int y) {
        s = s.replace("~", "");
        int x0 = x;
        for (int i = 0; i < s.length(); i++) {
            Integer g = Rom.ENC.get(s.charAt(i));
            int gl = g == null ? 0xAC : g, w = rom.glyphWidth(gl);
            for (int py = 0; py < 14; py++)
                for (int px = 0; px < w; px++) {
                    int v = rom.glyphPixel(gl, px, py);
                    if (x + px < 0 || x + px >= W || y + py < 0 || y + py >= H) continue;
                    if (v == 1) fb.setPixel(x + px, y + py, inkOverride != 0 ? inkOverride : skin.textInk);
                    else if (v == 2) fb.setPixel(x + px, y + py, shadowOverride != 0 ? shadowOverride : skin.textShadow);
                }
            x += w;
        }
        return x - x0;
    }
    /** FR's small font (party cards etc.), same ink/shadow as text(). */
    private int small(String s, int x, int y) {
        int x0 = x;
        for (int i = 0; i < s.length(); i++) {
            Integer g = Rom.ENC.get(s.charAt(i));
            int gl = g == null ? 0xAC : g, w = rom.smallGlyphWidth(gl);
            for (int py = 0; py < 13; py++)
                for (int px = 0; px < 8 && px < w + 1; px++) {
                    int v = rom.smallGlyphPixel(gl, px, py);
                    if (x + px < 0 || x + px >= W || y + py < 0 || y + py >= H) continue;
                    if (v == 1) fb.setPixel(x + px, y + py, skin.textInk);
                    else if (v == 2) fb.setPixel(x + px, y + py, skin.textShadow);
                }
            x += w;
        }
        return x - x0;
    }
    private int smallW(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) { Integer g = Rom.ENC.get(s.charAt(i)); w += rom.smallGlyphWidth(g == null ? 0xAC : g); }
        return w;
    }

    private int textW(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) { Integer g = Rom.ENC.get(s.charAt(i)); w += rom.glyphWidth(g == null ? 0xAC : g); }
        return w;
    }
    private String fit(String s, int maxW) {
        if (textW(s) <= maxW) return s;
        while (s.length() > 1 && textW(s + "…") > maxW) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    /** A strip of the bar art, rebuilt from one clean pixel column so on-screen prompts ("SELECT Move") don't come along. */
    private void strip(int sy, int h, int y) {
        src.set(8, sy, 9, sy + h); dst.set(0, y, W, y + h); c.drawBitmap(skin.bar, src, dst, null);
    }

    private static final String[] INFINITY = { // 11x5, sits on the digits' middle
        ".###...###.",
        "#...#.#...#",
        "#....#....#",
        "#...#.#...#",
        ".###...###."};
    /** ∞ (the font has none) in the text's own ink + shadow, about the width of "100". */
    private void infinity(int x, int y) {
        for (int pass = 0; pass < 2; pass++)
            for (int py = 0; py < 5; py++)
                for (int px = 0; px < 11; px++)
                    if (INFINITY[py].charAt(px) == '#') fb.setPixel(x + px + 1 - pass, y + 5 + py + 1 - pass, pass == 0 ? skin.textShadow : skin.textInk);
    }

    /** Settings cog, pixel art in the menu text colours (white + shadow), 16x16. */
    private void cog(int x, int y) {
        for (int pass = 0; pass < 2; pass++)
            for (int py = 0; py < 16; py++)
                for (int px = 0; px < 16; px++) {
                    double dx = px - 7.5, dy = py - 7.5, r = Math.sqrt(dx * dx + dy * dy), a = Math.atan2(dy, dx);
                    boolean tooth = Math.cos(a * 8) > 0.35;
                    boolean on = r >= 2.6 && (r <= 5.4 || (tooth && r <= 7.6));
                    if (on) fb.setPixel(x + px + (pass == 0 ? 1 : 0), y + py + (pass == 0 ? 1 : 0), pass == 0 ? skin.textShadow : skin.textInk);
                }
    }

    /** Unbound's gold-framed purple box, 9-sliced from the start-menu art. */
    private void window(int x, int y, int w, int h) {
        for (int ty = 0; ty < h; ty += 8)
            for (int tx = 0; tx < w; tx += 8) {
                int col = tx == 0 ? 0 : tx + 8 >= w ? 2 : 1;
                int row = ty == 0 ? 0 : ty + 8 >= h ? 2 : 1;
                c.drawBitmap(skin.box[row * 3 + col], x + Math.min(tx, w - 8), y + Math.min(ty, h - 8), null);
            }
    }

    /** Icon for a start-menu table entry: its {tag | frame<<16} field picks sheet (0x27F0 / 0x27F1) and frame. */
    private Bitmap entryIcon(int entry, boolean colour) {
        int a = rom.u32(Game.MENU_TABLE + entry * 16 + 8), frame = a >> 16 & 0xFF;
        Bitmap[] sheet = (a & 0xFFFF) == 0x27F1 ? (colour ? skin.icon2 : skin.icon2Grey) : (colour ? skin.icon : skin.iconGrey);
        return sheet[Math.min(frame, sheet.length - 1)];
    }

    /** Healthbox palette index of the bar's ink (shade = +1), same thresholds as the game: >1/2 green, >1/5 yellow, else red. */
    private static int hpColour(int hp, int max) { return hp * 2 > max ? 10 : hp * 5 > max ? 12 : 14; }

    private void hpBar(int x, int y, int hp, int max) {
        int fillW = max == 0 ? 0 : Math.max(hp > 0 ? 1 : 0, Skin.HP_W * hp / max);
        int k = hpColour(hp, max);
        rect(x, y, Skin.HP_W, 3, skin.hp[5]);
        rect(x, y, fillW, 1, skin.hp[k]);
        rect(x, y + 1, fillW, 2, skin.hp[k + 1]);
    }

    // ---------- layout ----------
    private static final int TOP_H = 34, BAR_Y = 174, ICON_Y = 180;
    private static final int COG_X = W - 22, COG_Y = H - 22;
    private static final int SET_W = 72, SET_H = 24, SET_X = W - SET_W - 4, SET_Y = H - SET_H - 4, ROW_H = 20, ROW_Y = 26;

    /**
     * The game's icon idle animation: two frames, faster the healthier the mon (party menu: full 6, >1/2 8, >1/5 14,
     * else 22 frames per frame; fainted stands still), timed on the emulated frame count.
     */
    private Bitmap animIcon(int species, int hp, int maxHp) { return rom.icon(species, iconFrame(hp, maxHp)); }
    private int iconFrame(int hp, int maxHp) {
        int dur = hp <= 0 ? 0 : hp >= maxHp ? 6 : hp * 2 > maxHp ? 8 : hp * 5 > maxHp ? 14 : 22;
        return dur == 0 ? 0 : (game.frame / dur) & 1;
    }

    @Override protected void onDraw(Canvas screen) {
        try { drawFrame(screen); }
        catch (RuntimeException e) { // a drawing bug must never take the game down: plain black this frame, log it
            android.util.Log.e("gbads", "bottom screen draw failed", e);
            screen.drawColor(0xFF000000);
        }
    }
    private void drawFrame(Canvas screen) {
        Game.Battle battle = game.battle;
        // Don't spoil the reveal: the two Pokémon in the middle appear only once the player can first act.
        if (game.battleStarting) intro = true;
        if (battle != null && battle.mode == Game.Battle.ACTION) { revealed = true; intro = false; }
        if (battle == null && !game.battleStarting && !intro) revealed = false;
        if (intro && battle == null && !game.battleStarting && ++introGapFrames > 240) intro = false; // transition that never became a battle
        else if (battle != null || game.battleStarting) introGapFrames = 0;
        if (battle != null) { settingsOpen = false; drawBattle(battle); }
        else if (game.inGame) { settingsOpen = false; drawGame(game.state); }
        else if (settingsOpen) drawSettings();
        else { // title: black with a settings cog; save select and everything else: just black
            fb.eraseColor(0xFF000000);
            if (game.onTitle) { cog(COG_X, COG_Y + (pressed == 198 ? 1 : 0)); small("v" + BuildConfig.VERSION_NAME, 6, H - 14); }
        }
        // Battle intro: follow the game's own fade to/from black, in step with the top screen's brightness.
        if (intro) {
            int black = Math.max(0, Math.min(16, 16 - game.topLuma * 16 / 48));
            if (black > 0) { fill.setColor((black * 255 / 16) << 24); c.drawRect(0, 0, W, H, fill); }
        }
        if (game.awaitingA && !game.resetPrompt && battle == null && game.inGame) { fill.setColor(0x40000000); c.drawRect(0, 0, W, H, fill); } // tap = A (battles are already dimmed while text plays)
        else if (battle == null && game.inGame && game.state.fieldBusy) { // start menu: party greyed; cutscene / script: everything
            fill.setColor(0x60000000); c.drawRect(0, game.state.startMenuOpen ? TOP_H : 0, W, game.state.startMenuOpen ? BAR_Y : H, fill);
        }
        if (game.resetPrompt) drawResetPrompt();
        drawToast();

        int scale = Math.max(1, Math.min(getWidth() / W, getHeight() / H));
        int ox = (getWidth() - W * scale) / 2, oy = (getHeight() - H * scale) / 2;
        src.set(0, 0, W, H);
        dst.set(ox, oy, ox + W * scale, oy + H * scale);
        screen.drawBitmap(fb, src, dst, blit);
        postInvalidateOnAnimation();
    }

    private void drawSettingsButton(String label, boolean down) {
        int y = SET_Y + (down ? 1 : 0);
        window(SET_X, y, SET_W, SET_H);
        text(label, SET_X + (SET_W - textW(label)) / 2, y + 5);
    }

    private void drawGame(Game.State s) {
        if (s.party.length == 0) { fb.eraseColor(0xFF000000); return; } // new game / intro: nothing to show until the first Pokémon
        fb.eraseColor(0xFF000000);
        // party grid behind everything (tiled from its 32px cell, so none of the party menu's Cancel box shows)
        gridBackground();

        // top bar: Unbound's purple strip, trainer card (left), clock box, options (right)
        strip(0, TOP_H - 4, 0);
        strip(40, 4, TOP_H - 4); // separator line
        c.drawBitmap(entryIcon(Game.TRAINER_CARD, pressed != 10), 4, pressed == 10 ? 1 : 0, null);
        c.drawBitmap(entryIcon(Game.SETTINGS, pressed != 11), W - 36, pressed == 11 ? 1 : 0, null);
        // time + map/party toggle, centred together as one group
        String time = clock.format(new Date());
        boolean canToggle = s.hasMap && s.mapX >= 0;
        int tw = textW(time) + 16, groupW = tw + (canToggle ? 4 + TOGGLE_W : 0), gx = (W - groupW) / 2;
        window(gx, 4, tw, 24);
        text(time, gx + 8, 9);
        toggleX = gx + tw + 4;

        if ((owMenuSlot >= 0 || owSwitchFrom >= 0) && (game.inputSeq != menuInputSeq || game.resetPrompt)) closeOwMenu(); // any button, step or pause closes it
        if (mapShown()) drawMapView(s);
        else if (owView == 2) { drawArea(s); if (dexSpecies > 0) drawDexEntry(s); }
        else drawPartyCards(s);
        toggleButton(pressed == 50);

        // bottom bar: Unbound's purple strip with the menu icons (colour; grey while touched — the reverse of the game's cursor)
        strip(120, 40, BAR_Y + 2);
        for (int i = 0; i < BUTTON_ENTRY.length; i++)
            c.drawBitmap(entryIcon(BUTTON_ENTRY[i], pressed != i), buttonX(i), ICON_Y + (pressed == i ? 1 : 0), null);
    }
    private static int buttonX(int i) { return 12 + i * 48; }

    // ---------- overworld centre: map view / party cards ----------
    // party by default; static so the choice survives the bottom screen being rebuilt (battles, menus) until the app is quit
    private static int owView; // 0 party, 1 map (needs the Town Map), 2 area (wild Pokémon here)
    private boolean mapShown() { return owView == 1 && game.state.hasMap && game.state.mapX >= 0; }
    private boolean partyShown() { return !mapShown() && owView != 2; }
    private int nextView() { // party -> map -> area (the DexNav-style Area view is experimental: debug / dev builds only)
        boolean map = game.state.hasMap && game.state.mapX >= 0, area = BuildConfig.EXPERIMENTAL;
        return owView == 0 ? (map ? 1 : area ? 2 : 0) : owView == 1 && area ? 2 : 0;
    }

    // ---------- Area: the wild Pokémon of the current map (the game's gWildMonHeaders, repointed in Unbound) ----------
    private static final int WILD_HEADERS = 0x08C230D8;
    private static final int[] LAND_RATES = {20, 20, 10, 10, 10, 10, 5, 5, 4, 4, 1, 1}, WATER_RATES = {60, 30, 5, 4, 1};
    private static final int[] FISH_RATES = {70, 30, 60, 20, 20, 40, 40, 15, 4, 1}; // old 0-1, good 2-4, super 5-9
    private static final String[] AREA_KINDS = {"Grass", "Surfing", "Rock Smash", "Fishing"};
    private String areaKey;
    private java.util.List<int[]> areaRows; // {kind, species, minLv, maxLv, percent, period (0 = any time)}
    private int headerFor(int table, int group, int num) {
        for (int e = table; table != 0 && rom.u8(e) != 0xFF && e < table + 20 * 600; e += 20)
            if (rom.u8(e) == group && rom.u8(e + 1) == num) return e;
        return 0;
    }
    private static final String[] PERIODS = {"", "Morning", "Day", "Evening", "Night"};
    private static final int[] PERIOD_ICONS = {0, 100, 93, 99, 94}; // Dawn, Sun, Dusk, Moon Stone
    private static final int[] PERIOD_TABLES = {0, 0x08A7DC18, 0, 0x08A7DC04, 0x08A7DA74}; // Unbound's time-of-day headers
    private static int periodOf(int h) { return h < 5 || h >= 20 ? 4 : h <= 7 ? 1 : h >= 17 ? 3 : 2; }
    /** One encounter list ({kind, species, minLv, maxLv, percent, period}) from a WildPokemonInfo, or null. */
    private java.util.List<int[]> monsOf(int info, int k, int period) {
        if (!rom.isRom(info)) return null;
        int mons = rom.u32(info + 4);
        if (!rom.isRom(mons)) return null;
        int[] rates = k == 0 ? LAND_RATES : k == 3 ? FISH_RATES : WATER_RATES;
        java.util.LinkedHashMap<Integer, int[]> bySpecies = new java.util.LinkedHashMap<>();
        for (int i = 0; i < rates.length; i++) {
            int lo = rom.u8(mons + i * 4), hi = rom.u8(mons + i * 4 + 1), sp = rom.u16(mons + i * 4 + 2);
            if (sp == 0) continue;
            int[] r = bySpecies.computeIfAbsent(sp, x -> new int[]{k, x, 255, 0, 0, period});
            r[2] = Math.min(r[2], lo); r[3] = Math.max(r[3], hi);
            r[4] += k == 3 ? 0 : rates[i]; // ponytail: fishing % depends on the rod; shown without a %
        }
        return new java.util.ArrayList<>(bySpecies.values());
    }
    private static boolean sameSpecies(java.util.List<int[]> a, java.util.List<int[]> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i)[1] != b.get(i)[1] || a.get(i)[4] != b.get(i)[4]) return false;
        return true;
    }
    /** Encounters per method; where Unbound has morning / evening / night lists that differ, each gets its own section. */
    private java.util.List<int[]> areaFor(int group, int num) {
        int now = periodOf(game.state.hour);
        String key = group + "." + num + "." + now;
        if (key.equals(areaKey)) return areaRows;
        java.util.ArrayList<int[]> rows = new java.util.ArrayList<>();
        int e = headerFor(WILD_HEADERS, group, num);
        int[] timed = new int[5];
        for (int p = 1; p < 5; p++) timed[p] = headerFor(PERIOD_TABLES[p], group, num);
        for (int k = 0; k < 4; k++) {
            java.util.List<int[]> day = e != 0 ? monsOf(rom.u32(e + 4 + k * 4), k, 2) : null;
            java.util.ArrayList<java.util.List<int[]>> variants = new java.util.ArrayList<>();
            for (int p : new int[]{1, 3, 4}) {
                java.util.List<int[]> v = timed[p] != 0 ? monsOf(rom.u32(timed[p] + 4 + k * 4), k, p) : null;
                if (v != null && (day == null || !sameSpecies(v, day))) variants.add(v);
            }
            if (day != null) { if (variants.isEmpty()) for (int[] r : day) r[5] = 0; rows.addAll(day); }
            for (java.util.List<int[]> v : variants) rows.addAll(v);
        }
        areaKey = key; areaRows = rows;
        return rows;
    }
    private void drawArea(Game.State s) {
        java.util.List<int[]> rows = areaFor(s.mapGroup, s.mapNum);
        int y = TOP_H + 4 - areaScroll, x = 8, kind = -1;
        areaChips.clear();
        String place = s.mapSec >= 0 ? rom.mapName(s.mapSec) : "";
        if (rows.isEmpty()) { window(8, TOP_H + 40, 232, 30); text("No wild Pokémon here.", 20, TOP_H + 47); return; }
        c.save(); c.clipRect(0, TOP_H, W, BAR_Y);
        if (!place.isEmpty()) { // where you are, like the Town Map's label
            int lw = textW(place) + 12;
            fill.setColor(skin.tb[6]); c.drawRect(8, y, 8 + lw, y + 16, fill);
            text(place, 14, y + 1);
            y += 20;
        }
        for (int[] r : rows) {
            if (r[0] * 8 + r[5] != kind) { // section label: method, and the time of day when it has its own list
                if (x != 8) { y += 36; x = 8; }
                kind = r[0] * 8 + r[5];
                String label = AREA_KINDS[r[0]] + (r[5] == 0 ? "" : " - " + PERIODS[r[5]] + (r[5] == periodOf(s.hour) ? " (now)" : ""));
                if (r[5] != 0) { c.drawBitmap(skin.item(PERIOD_ICONS[r[5]]), 6, y - 6, null); y += 6; } // Dawn / Sun / Dusk / Moon Stone
                if (y + 2 >= TOP_H && y + 10 <= BAR_Y) small(label, r[5] != 0 ? 32 : 10, y + 2 - (r[5] != 0 ? 3 : 0)); // small() draws past the clip
                y += 13;
            }
            int nat = rom.nationalDex(r[1]);
            boolean seen = Game.dexFlag(s.dexSeen, nat), caught = Game.dexFlag(s.dexCaught, nat);
            window(x, y + (pressed == 201 + areaChips.size() ? 1 : 0), 56, 34);
            c.save(); c.clipRect(x + 3, Math.max(TOP_H, y + 3), x + 53, Math.min(BAR_Y, y + 31));
            Bitmap ic = animIcon(r[1], 1, 1);
            c.drawBitmap(seen ? ic : silhouette(ic, skin.tb[9]), x - 2, y - 2, null); // unseen: a silhouette (no spoilers)
            if (caught) c.drawBitmap(skin.partyBalls[Skin.BALL_OK], x + 4, y + 22, null);
            c.restore();
            areaChips.add(new int[]{x, y, r[1]});
            String lv = r[2] == r[3] ? "ʟ" + r[2] : r[2] + "-" + r[3];
            if (y + 6 >= TOP_H && y + 14 <= BAR_Y) small(lv, x + 54 - smallW(lv) - 3, y + 6);
            if (r[4] > 0 && y + 19 >= TOP_H && y + 27 <= BAR_Y) { String pc = r[4] + "%"; small(pc, x + 54 - smallW(pc) - 3, y + 19); }
            x += 58;
            if (x + 56 > 244) { x = 8; y += 36; }
        }
        c.restore();
        int content = y + (x != 8 ? 36 : 0) + areaScroll - (TOP_H + 4);
        areaMaxScroll = Math.max(0, content - (BAR_Y - TOP_H - 6));
        if (areaScroll > areaMaxScroll) areaScroll = areaMaxScroll;
    }
    private int areaScroll, areaMaxScroll, dragLastY = -1;
    private final java.util.ArrayList<int[]> areaChips = new java.util.ArrayList<>(); // {x, y, species} as drawn
    private int dexSpecies = -1;
    private static final String[] STAT_NAMES = {"HP", "Atk", "Def", "Spe", "SpA", "SpD"};
    /** Pokédex entry over the Area view: number, name, types, base stats; unseen species stay unknown. */
    private void drawDexEntry(Game.State s) {
        int sp = dexSpecies, nat = rom.nationalDex(sp);
        boolean seen = Game.dexFlag(s.dexSeen, nat), caught = Game.dexFlag(s.dexCaught, nat);
        window(8, TOP_H + 4, 232, BAR_Y - TOP_H - 8);
        Bitmap ic = animIcon(sp, 1, 1);
        c.drawBitmap(seen ? ic : silhouette(ic, skin.tb[9]), 14, TOP_H + 8, null);
        text(String.format(java.util.Locale.ROOT, "No.%03d  %s", nat, seen ? rom.speciesName(sp) : "?????"), 52, TOP_H + 10);
        small(caught ? "Caught" : seen ? "Seen" : "Not seen yet", 52, TOP_H + 28);
        if (caught) c.drawBitmap(skin.partyBalls[Skin.BALL_OK], 40, TOP_H + 29, null);
        if (!seen) { small("Find it here to learn more.", 20, TOP_H + 52); return; }
        int t1 = rom.baseStat(sp, 6), t2 = rom.baseStat(sp, 7), tx = 150;
        Bitmap b1 = skin.typeIcon(t1);
        if (b1 != null) { outlined(b1, tx, TOP_H + 28); tx += b1.getWidth() + 4; }
        if (t2 != t1) { Bitmap b2 = skin.typeIcon(t2); if (b2 != null) outlined(b2, tx, TOP_H + 28); }
        int total = 0;
        for (int k = 0; k < 6; k++) {
            int v = rom.baseStat(sp, k), y = TOP_H + 44 + k * 12;
            total += v;
            small(STAT_NAMES[k], 20, y);
            small(String.valueOf(v), 70 - smallW(String.valueOf(v)), y);
            rect(76, y + 2, Math.max(1, v * 150 / 255), 5, skin.hp[v >= 90 ? 10 : v >= 60 ? 12 : 14]); // game's HP bar greens / yellows / reds
        }
        small("Total " + total, 20, TOP_H + 44 + 6 * 12);
    }
    private static final int SIDE_X = 216, SIDE_SLOT = 23; // party column x (= PARTY_COL_X)

    private void drawPartyCards(Game.State s) {
        if (owGiveFor >= 0) { drawGiveList(s); return; }
        drawPartyCards(s, 20, owMenuSlot >= 0 ? owMenuSlot : owSwitchFrom, false);
        if (dragging && dragFrom - 20 < s.party.length) drawCard(s.party[dragFrom - 20], dragX - grabDX, dragY - grabDY, true);
        if (owMenuSlot >= 0 && owMenuSlot < s.party.length) drawOwMenu(s);
        if (owSwitchFrom >= 0) { window(8, BAR_Y - 22, 232, 20); small("Tap where to move it. Tap it again to cancel.", 14, BAR_Y - 16); }
    }
    // Overworld party: tap a card for a small menu beside it (ORAS-style); drag a card onto another to swap them.
    private int owMenuSlot = -1, owSwitchFrom = -1, menuInputSeq;
    // drag: the card / head follows the finger; after a swap both slide to their new places
    private boolean dragging;
    private int dragX, dragY, grabDX, grabDY, downX, downY;
    private Game.Mon dragHead;
    private long slideStart, pendTime;
    private int slideSeq, slideA = -1, slideB = -1, slideFromAX, slideFromAY, slideFromBX, slideFromBY, pendFromAX, pendFromAY, pendFromBX, pendFromBY, pendA = -1, pendB;
    private int[] slotXY(int i) { return mapShown() ? new int[]{PARTY_COL_X, TOP_H + 2 + i * SIDE_SLOT} : cardPos(i, false); }
    /** Queues the slide for a swap request: slot a's new occupant comes from (ax,ay), slot b's from (bx,by). */
    private void requestSwap(int a, int b, int ax, int ay, int bx, int by) {
        pendTime = android.os.SystemClock.uptimeMillis();
        pendA = a; pendB = b; pendFromAX = ax; pendFromAY = ay; pendFromBX = bx; pendFromBY = by;
        game.partySwap(a, b);
    }
    private static final int SLIDE_MS = 160;
    private int[] animPos(int i, int x, int y) {
        if (game.state.swapSeq != slideSeq) { // the swap landed: start sliding
            slideSeq = game.state.swapSeq;
            if (pendA >= 0) { slideA = pendA; slideB = pendB; slideFromAX = pendFromAX; slideFromAY = pendFromAY; slideFromBX = pendFromBX; slideFromBY = pendFromBY; slideStart = android.os.SystemClock.uptimeMillis(); }
            pendA = -1;
        }
        if (pendA >= 0 && android.os.SystemClock.uptimeMillis() - pendTime > 500) pendA = -1; // refused (e.g. a script started)
        if (pendA >= 0 && (i == pendA || i == pendB)) return i == pendA ? new int[]{pendFromBX, pendFromBY} : new int[]{pendFromAX, pendFromAY}; // hold until it lands
        float t = (android.os.SystemClock.uptimeMillis() - slideStart) / (float) SLIDE_MS;
        if (t >= 1 || (i != slideA && i != slideB)) return new int[]{x, y};
        t = 1 - (1 - t) * (1 - t); // ease out
        int fx = i == slideA ? slideFromAX : slideFromBX, fy = i == slideA ? slideFromAY : slideFromBY;
        return new int[]{Math.round(fx + (x - fx) * t), Math.round(fy + (y - fy) * t)};
    }
    private void closeOwMenu() { owMenuSlot = -1; owSwitchFrom = -1; }
    private static final String[] OW_MENU = {"Summary", "Shift", "Item", "Cancel"};
    private int owGiveFor = -1, giveScroll;
    private static final int GIVE_ROW = 24, GIVE_Y = TOP_H + 30;
    /** Give: the holdable items over the party view; tap one to give it (a held item goes back to the Bag). */
    private void drawGiveList(Game.State s) {
        window(4, TOP_H + 2, W - 8, BAR_Y - TOP_H - 4);
        Game.Mon m = owGiveFor < s.party.length ? s.party[owGiveFor] : null;
        boolean holding = m != null && m.heldItem != 0;
        String head = m == null ? "" : holding ? "Holding " + game.itemName(m.heldItem) : "Give " + m.nick + " an item";
        small(fit(head, holding ? 100 : 150), 12, TOP_H + 10);
        chooseButtonAt(170, W - 12 - 66, TOP_H + 4, 66, "Cancel", 22);
        if (holding) chooseButtonAt(171, W - 12 - 66 - 4 - 54, TOP_H + 4, 54, "Take", 22);
        java.util.List<int[]> items = game.holdableItems();
        c.save(); c.clipRect(8, GIVE_Y, W - 8, BAR_Y - 6);
        for (int i = 0; i < items.size(); i++) {
            int y = GIVE_Y + i * GIVE_ROW - giveScroll;
            if (y + GIVE_ROW < GIVE_Y || y > BAR_Y) continue;
            if (pressed == 172 && giveTapIndex == i) rect(10, y, W - 20, GIVE_ROW - 2, colDarkSel());
            c.drawBitmap(skin.item(items.get(i)[0]), 12, y - 1, null);
            if (y + 7 >= GIVE_Y && y + 15 <= BAR_Y - 6) {
                small(fit(game.itemName(items.get(i)[0]), 140), 40, y + 7);
                String q = "x" + items.get(i)[1];
                small(q, W - 16 - smallW(q), y + 7);
            }
        }
        c.restore();
        giveMax = Math.max(0, items.size() * GIVE_ROW - (BAR_Y - 6 - GIVE_Y));
    }
    private int giveTapIndex = -1, giveMax;
    private void chooseButtonAt(int hit, int x, int y, int w, String label, int h) {
        int d = pressed == hit ? 1 : 0;
        window(x, y + d, w, h);
        text(label, x + (w - textW(label)) / 2, y + 3 + d);
    }
    private static final int OW_MENU_W = 96, OW_ROW = 18;
    private int[] owMenuPos() {
        if (mapShown()) return new int[]{SIDE_X - OW_MENU_W - 2, Math.max(TOP_H + 2, Math.min(TOP_H + 2 + owMenuSlot * SIDE_SLOT, BAR_Y - 8 - OW_MENU.length * OW_ROW - 8))};
        int[] at = cardPos(owMenuSlot, false);
        int x = owMenuSlot % 2 == 0 ? 136 : 16, y = Math.max(TOP_H + 2, Math.min(at[1], BAR_Y - 8 - OW_MENU.length * OW_ROW - 8));
        return new int[]{x, y};
    }
    private void drawOwMenu(Game.State s) {
        int[] p = owMenuPos();
        window(p[0], p[1], OW_MENU_W, OW_MENU.length * OW_ROW + 8);
        for (int i = 0; i < OW_MENU.length; i++) {
            int y = p[1] + 4 + i * OW_ROW, d = pressed == 160 + i ? 1 : 0;
            if (pressed == 160 + i) rect(p[0] + 4, y, OW_MENU_W - 8, OW_ROW, colDarkSel());
            text(OW_MENU[i], p[0] + 10, y + 2 + d);
        }
    }
    private int owMenuHit(int x, int y) {
        if (owMenuSlot < 0) return -1;
        int[] p = owMenuPos();
        if (x < p[0] || x >= p[0] + OW_MENU_W || y < p[1] + 4 || y >= p[1] + 4 + OW_MENU.length * OW_ROW) return -1;
        return 160 + (y - p[1] - 4) / OW_ROW;
    }
    private void owTap(int hit) {
        game.chime();
        if (hit >= 160) { // menu row
            int slot = owMenuSlot;
            owMenuSlot = -1;
            if (hit == 160) game.openMenu(Game.POKEMON, slot);
            else if (hit == 161) owSwitchFrom = slot;
            else if (hit == 162) { owGiveFor = slot; giveScroll = 0; }
            return;
        }
        int slot = hit - 20;
        if (owSwitchFrom >= 0) {
            if (slot != owSwitchFrom) { int[] pa = slotXY(owSwitchFrom), pb = slotXY(slot); requestSwap(owSwitchFrom, slot, pb[0], pb[1], pa[0], pa[1]); }
            owSwitchFrom = -1; return;
        }
        owMenuSlot = owMenuSlot == slot ? -1 : slot;
        menuInputSeq = game.inputSeq;
    }
    /** Card top-left: the overworld's even grid, or the battle party menu's two columns with the right one dropped 8px. */
    private static int[] cardPos(int i, boolean staggered) {
        return staggered ? new int[]{i % 2 == 0 ? 8 : 128, 36 + (i / 2) * 40 + (i % 2) * 8} : new int[]{8 + (i % 2) * 120, TOP_H + 4 + (i / 2) * 45};
    }
    /**
     * Party cards as the game's party menu draws them: the card art (normal / fainted / selected), its Poké Ball (open on
     * the selected card) with the icon on top, held-item tag, name + gender symbol, HP bar, level or status badge, HP.
     * The selected icon hops like the game's (up 3 on one frame, down 1 on the other).
     */
    private void drawPartyCards(Game.State s, int hitBase, int picked, boolean staggered) {
        for (int i = 0; i < 6 && i < s.party.length; i++) {
            Game.Mon m = s.party[i];
            int[] at = cardPos(i, staggered);
            int x = at[0], y = at[1];
            boolean sel = pressed == hitBase + i || picked == i;
            if (hitBase == 20 && !staggered) { if (dragging && dragFrom == 20 + i) continue; int[] a = animPos(i, x, y); x = a[0]; y = a[1]; }
            drawCard(m, x, y, sel);
        }
    }
    private void drawCard(Game.Mon m, int x, int y, boolean sel) {
            c.drawBitmap(m.egg ? (sel ? skin.slotEggSelected : skin.slotEgg) : sel ? skin.slotSelected : m.hp == 0 ? skin.slotFainted : skin.slotNormal, x, y, null);
            c.drawBitmap(skin.menuBall[sel ? 1 : 0], x - 6, y - 3, null);
            // hop on the icon's anim steps; fainted mons keep frame 0 but still hop on the game's "still" anim (29 frames)
            int f = iconFrame(m.hp, m.maxHp), phase = m.hp > 0 ? f : (game.frame / 29) & 1, hop = sel ? (phase == 1 ? -3 : 1) : 0;
            c.drawBitmap(rom.icon(m.species, f), x + 3, y - 6 + hop, null);
            if (m.heldItem != 0) c.drawBitmap(skin.heldItem[m.heldItem >= 121 && m.heldItem <= 132 ? 1 : 0], x + 19, y + 16 + hop, null);
            text(m.nick, x + 38, y + 3);
            if (m.egg) return; // the game's party menu shows an Egg's icon and name only
            int g = rom.gender(m.species, m.personality);
            if (g < 2) {
                int[] pal = skin.partyPal[g == 0 ? 3 : 4];
                textCol(g == 0 ? "♂" : "♀", x + 100, y + 3, pal[11], pal[12]);
            }
            hpBar(x + Skin.HP_X, y + Skin.HP_Y, m.hp, m.maxHp);
            int badge = m.hp == 0 ? Skin.STATUS_FNT : statusBadge(m.status);
            if (badge >= 0) c.drawBitmap(skin.statusIcon[badge], x + 1, y + 27, null);
            else text("ʟ" + m.level, x + 4, y + 23);
            String hp = String.format(java.util.Locale.ROOT, "%3d/%3d", m.hp, m.maxHp);
            text(hp, x + 96 - textW(hp), y + 23);
    }
    /** Status condition bits -> the party menu's badge (sleep 0..2, poison 3, burn 4, freeze 5, paralysis 6, toxic 7). */
    private static int statusBadge(int st) {
        if ((st & 7) != 0) return Skin.STATUS_SLP;
        if ((st & 0x88) != 0) return Skin.STATUS_PSN;
        if ((st & 0x10) != 0) return Skin.STATUS_BRN;
        if ((st & 0x20) != 0) return Skin.STATUS_FRZ;
        if ((st & 0x40) != 0) return Skin.STATUS_PRZ;
        return -1;
    }

    // Map view: the map's content area (x 24..215, y 16..159 of the 240x160 map, no grey border), the Town Map frame's
    // side pieces on both sides (inner 12px of each), and the party column on the right. Fills TOP_H..BAR_Y+2 exactly.
    private static final int MAP_SRC_X = 24, MAP_SRC_Y = 16, MAP_FRAME_W = 12, MAP_W = 192;
    private static final int MAP_X = 0, PARTY_COL_X = MAP_FRAME_W * 2 + MAP_W; // 216
    private int selX = -1, selY = -1, selSeq, selPlayerX, selPlayerY;

    private void drawMapView(Game.State s) {
        int viewY = TOP_H, viewH = BAR_Y + 2 - TOP_H;
        blit(skin.regionMap, MAP_SRC_X, MAP_SRC_Y, MAP_W, viewH, MAP_FRAME_W, viewY);
        blit(skin.mapFrame, 16 - MAP_FRAME_W, MAP_SRC_Y, MAP_FRAME_W, viewH, 0, viewY);                    // left piece
        blit(skin.mapFrame, 224, MAP_SRC_Y, MAP_FRAME_W, viewH, MAP_FRAME_W + MAP_W, viewY);               // right piece
        int px = 8 * s.mapX + 36, py = 8 * s.mapY + 36;
        c.drawBitmap(skin.playerMapIcon[s.gender], MAP_FRAME_W + px - MAP_SRC_X - 8, viewY + py - MAP_SRC_Y - 8, null);
        // a tapped spot stays selected until the player does anything else (button, step, menu)
        if (selX >= 0 && (game.inputSeq != selSeq || s.mapX != selPlayerX || s.mapY != selPlayerY)) selX = selY = -1;
        if (selX >= 0) {
            Bitmap cur = skin.mapCursor[(int) (System.currentTimeMillis() / 300 % 2)];
            c.drawBitmap(cur, MAP_FRAME_W + 8 * selX + 36 - MAP_SRC_X - 8, viewY + 8 * selY + 36 - MAP_SRC_Y - 8, null);
        }
        // location label, like the in-game map's: the selected spot, else where you are
        String name = rom.mapName(selX >= 0 ? rom.mapSecAt(selX, selY) : rom.mapSecAt(s.mapX, s.mapY));
        if (!name.isEmpty()) {
            int lw = textW(name) + 12;
            fill.setColor(skin.tb[6]); c.drawRect(MAP_FRAME_W, viewY + 2, MAP_FRAME_W + lw, viewY + 18, fill);
            text(name, MAP_FRAME_W + 6, viewY + 3);
        }
        if (dragging && dragFrom - 20 < s.party.length) { Game.Mon dm = s.party[dragFrom - 20]; dragHead = dm; }
        // party column: heads with a slim game-style HP bar under each
        fill.setColor(skin.tb[9]); c.drawRect(PARTY_COL_X, viewY, W, viewY + viewH, fill);
        for (int i = 0; i < s.party.length && i < 6; i++) {
            Game.Mon m = s.party[i];
            if (dragging && dragFrom == 20 + i) continue;
            int[] a = animPos(i, PARTY_COL_X, viewY + 2 + i * SIDE_SLOT);
            int x = a[0], y = a[1], d = pressed == 20 + i ? 1 : 0;
            if (owMenuSlot == i || owSwitchFrom == i || dragging && pressed == 20 + i) rect(x, y - 1, 32, SIDE_SLOT - 1, colDarkSel());
            c.save(); c.clipRect(x, y, x + 32, y + SIDE_SLOT - 5);
            c.drawBitmap(animIcon(m.species, m.hp, m.maxHp), x, y - 10 + d, null);
            c.restore();
            if (m.egg) continue;
            int fillW = m.maxHp == 0 ? 0 : Math.max(m.hp > 0 ? 1 : 0, 24 * m.hp / m.maxHp);
            int by = y + SIDE_SLOT - 4;
            rect(x + 3, by, 26, 3, skin.hp[7]);
            rect(x + 4, by + 1, 24, 1, skin.hp[5]);
            rect(x + 4, by + 1, fillW, 1, skin.hp[hpColour(m.hp, m.maxHp)]);
        }
        if (dragging && dragHead != null) c.drawBitmap(animIcon(dragHead.species, dragHead.hp, dragHead.maxHp), dragX - grabDX, dragY - grabDY - 10, null);
        if (owMenuSlot >= 0 && owMenuSlot < s.party.length) drawOwMenu(s);
        if (owSwitchFrom >= 0) { window(8, BAR_Y - 22, 200, 20); small("Tap where to move it.", 14, BAR_Y - 16); }
    }

    /** Tap on the map: select that spot with the Town Map's cursor and show its name. */
    private void mapTap(int x, int y) {
        int bx = x - MAP_FRAME_W + MAP_SRC_X, by = y - TOP_H + MAP_SRC_Y;
        int tx = (bx - 32) / 8, ty = (by - 32) / 8, sec = rom.mapSecAt(tx, ty);
        if (sec == 0xC5 || rom.mapName(sec).isEmpty()) return;
        Game.State st = game.state;
        selX = tx; selY = ty; selSeq = game.inputSeq; selPlayerX = st.mapX; selPlayerY = st.mapY;
    }

    private int toggleX;
    private static final int TOGGLE_W = 40;
    // Rectangular "swap" mark: two right-angle arrows chasing each other (11x11), drawn in the text ink with its shadow.
    private static final String[] SWAP = {
            ".XXXXXXX...",
            ".X.....X...",
            ".X.....X...",
            "......XXX..",
            ".......X...",
            "...........",
            "...X.......",
            "..XXX......",
            "...X.....X.",
            "...X.....X.",
            "...XXXXXXX."};
    private Bitmap townMapTrim;
    private void swapMark(int x, int y) {
        for (int pass = 0; pass < 2; pass++)
            for (int r = 0; r < SWAP.length; r++)
                for (int k = 0; k < SWAP[r].length(); k++)
                    if (SWAP[r].charAt(k) == 'X') fb.setPixel(x + k + (pass == 0 ? 1 : 0), y + r + (pass == 0 ? 1 : 0), pass == 0 ? skin.textShadow : skin.textInk);
    }
    /** Map <-> party toggle: gold box with the target view's icon (small Poké Ball / Town Map) and a swap mark. */
    private Bitmap dexNavTrim;
    private void toggleButton(boolean down) {
        int d = down ? 1 : 0;
        window(toggleX, 4 + d, TOGGLE_W, 24);
        Bitmap ic;
        int next = mapShown() ? 1 : owView == 2 ? 2 : 0; // the view you're on
        if (next == 1) {
            if (townMapTrim == null) { int[] r = opaqueBounds(skin.townMap); townMapTrim = Bitmap.createBitmap(skin.townMap, r[0], r[1], Math.max(1, r[2]), Math.max(1, r[3])); }
            ic = townMapTrim;
        } else if (next == 2) { // area: Unbound's DexNav menu icon, trimmed like the Town Map
            if (dexNavTrim == null) { Bitmap b = entryIcon(1, true); int[] r = opaqueBounds(b); dexNavTrim = Bitmap.createBitmap(b, r[0], r[1], Math.max(1, r[2]), Math.max(1, r[3])); }
            ic = dexNavTrim;
        } else ic = skin.smallBall;
        c.drawBitmap(ic, toggleX + 4 + Math.max(0, (18 - ic.getWidth()) / 2), 4 + d + (24 - ic.getHeight()) / 2, null);
        swapMark(toggleX + TOGGLE_W - 16, 10 + d);
    }

    private static final int RP_W = 160, RP_ROW = 24, RP_H = 4 * 24 + 12, RP_X = (248 - 160) / 2, RP_Y = (216 - RP_H) / 2;
    private void drawResetPrompt() {
        fill.setColor(0x90000000); c.drawRect(0, 0, W, H, fill);
        window(RP_X, RP_Y, RP_W, RP_H);
        for (int i = 0; i < 4; i++) {
            boolean sel = game.menuCursor == i;
            int y = RP_Y + 6 + i * RP_ROW, d = pressed == 60 + i ? 1 : 0;
            rect(RP_X + 6, y + d, RP_W - 12, RP_ROW - 2, sel ? colDarkSel() : colDark());
            String label = i == 2 && game.resetConfirm ? "Reset? Tap again" : Game.MENU_ROWS[i];
            text(label, RP_X + (RP_W - textW(label)) / 2, y + 4 + d);
        }
    }

    private boolean revealed, intro;
    private Bitmap fightIconBmp, ball2xBmp, cubeBmp, runBmp;
    private static final String[] RUNNER = { // facing right, mid-stride
            "........XX..",
            "........XX..",
            "......XXX...",
            ".....X.XXX..",
            "....X..X..X.",
            ".......X....",
            "......XX....",
            ".....X..X...",
            "....X....X..",
            "...X......X.",
            "..X.........",
            "............"};
    /** Run icon: a little white running figure in the text ink with its drop shadow, doubled. */
    private Bitmap runIcon() {
        if (runBmp == null) {
            int w = RUNNER[0].length() + 1, h = RUNNER.length + 1;
            int[] px = new int[w * h];
            for (int pass = 0; pass < 2; pass++)
                for (int y = 0; y < RUNNER.length; y++)
                    for (int x = 0; x < RUNNER[y].length(); x++)
                        if (RUNNER[y].charAt(x) == 'X') px[(y + (pass == 0 ? 1 : 0)) * w + x + (pass == 0 ? 1 : 0)] = pass == 0 ? skin.textShadow : skin.textInk;
            Bitmap b1 = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
            runBmp = Bitmap.createScaledBitmap(b1, w * 2, h * 2, false);
        }
        return runBmp;
    }
    /** Unbound's Cube icon with its outer white outline stripped, doubled (pixel-exact). */
    private Bitmap cubeIcon() {
        if (cubeBmp == null) {
            Bitmap src0 = skin.icon2[0];
            int w = src0.getWidth(), h = src0.getHeight();
            int[] px = new int[w * h];
            src0.getPixels(px, 0, w, 0, 0, w, h);
            int[] out = px.clone();
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int c = px[y * w + x];
                    boolean white = c >>> 24 != 0 && (c >> 16 & 255) > 230 && (c >> 8 & 255) > 230 && (c & 255) > 230;
                    boolean edge = x == 0 || y == 0 || x == w - 1 || y == h - 1
                            || px[y * w + x - 1] >>> 24 == 0 || px[y * w + x + 1] >>> 24 == 0 || px[(y - 1) * w + x] >>> 24 == 0 || px[(y + 1) * w + x] >>> 24 == 0;
                    if (white && edge) out[y * w + x] = 0;
                }
            Bitmap stripped = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888);
            int[] r = opaqueBounds(stripped);
            Bitmap trimmed = Bitmap.createBitmap(stripped, r[0], r[1], Math.max(1, r[2]), Math.max(1, r[3]));
            cubeBmp = Bitmap.createScaledBitmap(trimmed, trimmed.getWidth() * 2, trimmed.getHeight() * 2, false);
        }
        return cubeBmp;
    }
    private int fightRed;
    /** The party menu's small 2D Poké Ball, doubled. */
    private Bitmap ball2x() {
        if (ball2xBmp == null) ball2xBmp = Bitmap.createScaledBitmap(skin.smallBall, 32, 32, false);
        return ball2xBmp;
    }
    /** Red of the Physical burst icon's background, so the Fight button blends with it and only the burst shows. */
    private int fightRed() {
        if (fightRed == 0) { // most common opaque colour = the burst's red background
            Bitmap ps = pss(0);
            java.util.HashMap<Integer, Integer> n = new java.util.HashMap<>();
            for (int y = 0; y < ps.getHeight(); y++) for (int x = 0; x < ps.getWidth(); x++) { int c = ps.getPixel(x, y); if (c >>> 24 != 0) n.merge(c, 1, Integer::sum); }
            int most = 0;
            for (java.util.Map.Entry<Integer, Integer> e : n.entrySet()) if (e.getValue() > most) { most = e.getValue(); fightRed = e.getKey(); }
        }
        return fightRed;
    }
    /** Fight button art: CFRU's Physical category burst (PSS icon), doubled. */
    private Bitmap fightIcon() {
        if (fightIconBmp == null) {
            Bitmap ps = pss(0);
            int w = ps.getWidth(), h = ps.getHeight(), red = fightRed();
            int[] px = new int[w * h];
            ps.getPixels(px, 0, w, 0, 0, w, h);
            // clear only the red background reachable from the edge; red inside the burst stays, so highlighting
            // the button can't show through the icon
            java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
            for (int x = 0; x < w; x++) { q.add(x); q.add((h - 1) * w + x); }
            for (int y = 0; y < h; y++) { q.add(y * w); q.add(y * w + w - 1); }
            while (!q.isEmpty()) {
                int i = q.poll();
                if (px[i] != red) continue;
                px[i] = 0;
                int x = i % w, y = i / w;
                if (x > 0) q.add(i - 1); if (x < w - 1) q.add(i + 1); if (y > 0) q.add(i - w); if (y < h - 1) q.add(i + w);
            }
            Bitmap burst = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
            int[] r = opaqueBounds(burst);
            burst = Bitmap.createBitmap(burst, r[0], r[1], Math.max(1, r[2]), Math.max(1, r[3]));
            fightIconBmp = Bitmap.createScaledBitmap(burst, burst.getWidth() * 2, burst.getHeight() * 2, false);
        }
        return fightIconBmp;
    }
    private int introGapFrames;

    // ---------- battle (Unbound purple/gold, game assets) ----------
    // USUM-style action screen: quick ball top-left, Pokémon / Bag stacked left, tall Fight right, Run bottom centre
    // Pokémon/Bag stack and Fight share one block, centred vertically on the 216px screen
    // shifted up to balance the Run button underneath
    private static final int FIGHT_H = 140, SIDE_Y = 24, COL_Y = 32, SIDE_H = (SIDE_Y + FIGHT_H - COL_Y - 4) / 2; // left column starts under the Last Ball box
    private static final int QB_X = 2, SIDE_W = 72, FIGHT_X = 172, FIGHT_W = 72;
    private static final int RUN_X = 84, RUN_W = 80, RUN_Y = 166, RUN_H = 44;
    // attack grid centred vertically; Back / Mega sit in the bottom margin
    // attack grid centred vertically and horizontally; Back / Mega sit in the bottom margin
    private static final int MOVE_W = 114, MOVE_H = 70, MOVE_X = (248 - (2 * 114 + 4)) / 2, MOVE_Y = 22, BACK_Y = 184, BACK_W = 52, BACK_H = 26, MEGA_W = 72;
    // Every fill is a colour from the game's palettes, never invented: textbox purples, party-menu card colours.
    private int purpleLight() { return skin.tb[11]; } // selected (9070c0)
    private int colDark() { return skin.tb[6]; }      // Back / Mega (403050)
    private int colDarkSel() { return skin.tb[15]; }  // Back / Mega selected (684898)

    /** Unbound box; the selected one gets a lighter purple, pressed ones drop a pixel. */
    private void box(int x, int y, int w, int h, boolean selected, boolean down) {
        if (down) y += 1;
        window(x, y, w, h);
        if (selected) rect(x + 4, y + 4, w - 8, h - 8, purpleLight());
    }
    private int inkOverride, shadowOverride;
    /** Text in a given ink/shadow pair (CFRU's effectiveness colours). */
    private int textCol(String str, int x, int y, int ink, int shadow) {
        inkOverride = ink; shadowOverride = shadow;
        int w = text(str, x, y);
        inkOverride = 0; shadowOverride = 0;
        return w;
    }
    /** Unbound's red cursor arrow (start-menu icon sheet), pointing down at the selected button. */
    /**
     * Hover shade for fills with no lighter partner in the game's palettes (type colours, Fight's red): halfway to white on
     * the GBA's 5-bit grid. ponytail: the only computed colour left; swap for a palette shade if the game ever has one.
     */
    private static int lighten(int argb) {
        int r = (argb >> 16 & 255), g = (argb >> 8 & 255), b = argb & 255;
        r = ((r + 255) / 2) & 0xF8; g = ((g + 255) / 2) & 0xF8; b = ((b + 255) / 2) & 0xF8;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }
    private void cursorArrow(int cx, int y) { c.drawBitmap(skin.icon[Math.min(8, skin.icon.length - 1)], cx - 16, y - 22, null); }

    private void battleHeader() { strip(0, 22, 0); strip(40, 4, 22); }

    private byte[] ballSrc;
    private Bitmap ballBmp;
    /** The game's own Last Ball sprite (tiles + palette copied from VRAM) as a bitmap. */
    private Bitmap trimSrc, trimOut;
    /** Just the visible pixels (sprites and icons sit off-centre in their frames). */
    private Bitmap trimmed(Bitmap src) {
        if (src != trimSrc) { int[] r = opaqueBounds(src); trimOut = Bitmap.createBitmap(src, r[0], r[1], Math.max(1, r[2]), Math.max(1, r[3])); trimSrc = src; }
        return trimOut;
    }
    private Bitmap ballBitmap(Game.Battle b) {
        if (b.ballTiles == null) return skin.pokeBall;
        if (b.ballTiles != ballSrc) {
            int[] pal = Rom.palette(b.ballPal, 0), px = new int[32 * 32];
            for (int t = 0; t < 16; t++) Rom.tile(b.ballTiles, t, pal, px, 32, (t % 4) * 8, (t / 4) * 8, false, false);
            ballBmp = Bitmap.createBitmap(px, 32, 32, Bitmap.Config.ARGB_8888);
            ballSrc = b.ballTiles;
        }
        return ballBmp;
    }

    /** 1px black outline around an icon so it reads on any button colour. */
    private void outlined(Bitmap bmp, int x, int y) {
        rect(x - 1, y - 1, bmp.getWidth() + 2, 1, 0xFF000000);
        rect(x - 1, y + bmp.getHeight(), bmp.getWidth() + 2, 1, 0xFF000000);
        rect(x - 1, y, 1, bmp.getHeight(), 0xFF000000);
        rect(x + bmp.getWidth(), y, 1, bmp.getHeight(), 0xFF000000);
        c.drawBitmap(bmp, x, y, null);
    }

    /** Bounding box of a bitmap's opaque pixels: [x, y, w, h] (PSS icons have padding; this lines them up). */
    private static int[] opaqueBounds(Bitmap bmp) {
        int x0 = bmp.getWidth(), y0 = bmp.getHeight(), x1 = -1, y1 = -1;
        for (int y = 0; y < bmp.getHeight(); y++)
            for (int x = 0; x < bmp.getWidth(); x++)
                if (bmp.getPixel(x, y) >>> 24 != 0) { x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y); }
        return x1 < 0 ? new int[]{0, 0, 0, 0} : new int[]{x0, y0, x1 - x0 + 1, y1 - y0 + 1};
    }
    private final Bitmap[] pssTrim = new Bitmap[3];
    private Bitmap pss(int split) {
        int i = Math.max(0, Math.min(2, split));
        if (pssTrim[i] == null) { int[] r = opaqueBounds(skin.pss[i]); pssTrim[i] = Bitmap.createBitmap(skin.pss[i], r[0], r[1], Math.max(1, r[2]), Math.max(1, r[3])); }
        return pssTrim[i];
    }

    /** A labelled action button: Unbound box, icon centred, label underneath. */
    private void actionButton(int hit, int x, int y, int w, int h, Bitmap icon, String label, boolean sel, int fill, int selFill) {
        boolean down = pressed == hit;
        int d = down ? 1 : 0;
        window(x, y + d, w, h);
        rect(x + 4, y + 4 + d, w - 8, h - 8, sel ? selFill : fill);
        if (h < 56) { // short button: icon and label side by side
            int total = icon.getWidth() + 4 + textW(label), ix = x + (w - total) / 2;
            c.drawBitmap(icon, ix, y + (h - icon.getHeight()) / 2 + d, null);
            text(label, ix + icon.getWidth() + 4, y + (h - 14) / 2 + d);
        } else {
            c.drawBitmap(icon, x + (w - icon.getWidth()) / 2, y + h / 2 - icon.getHeight() / 2 - 8 + d, null);
            text(label, x + (w - textW(label)) / 2, y + h / 2 + 10 + d);
        }
        if (sel) cursorArrow(x + w / 2, y + 6);
    }

    private void gridBackground() {
        for (int y = 0; y < H; y += 32) for (int x = 0; x < W; x += 32) blit(skin.partyBg, 0, 0, 32, 32, x, y);
    }

    private Bitmap backdrop;
    /**
     * Battle backdrop, USUM-style: the grid under a flat purple tint, a faint Poké Ball (ring, band, centre button) in the
     * middle, and two curved purple/gold bars top and bottom that the buttons simply draw over. Built once, flat colours.
     */
    private void battleBackdrop() {
        if (backdrop == null) {
            backdrop = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            Canvas bc = new Canvas(backdrop);
            for (int y = 0; y < H; y += 32) for (int x = 0; x < W; x += 32) { src.set(0, 0, 32, 32); dst.set(x, y, x + 32, y + 32); bc.drawBitmap(skin.partyBg, src, dst, null); }
            int ring = skin.tb[6], band = skin.tb[6], cx = W / 2, cy = H / 2;
            for (int y = 0; y < H; y++)
                for (int x = 0; x < W; x++) {
                    double r = Math.hypot(x - cx, y - cy);
                    boolean on = (r > 78 && r < 84) || (Math.abs(y - cy) < 3 && r < 84 && r > 16) || (r > 11 && r < 16);
                    if (on) backdrop.setPixel(x, y, Math.abs(y - cy) < 3 ? band : ring);
                }
            // curved bars: thick at the sides, thin in the middle, gold edge + dark outline along the curve
            int purple = skin.barPal[1], gold = skin.barPal[3], dark = skin.barPal[5];
            for (int x = 0; x < W; x++) {
                double t = (x - cx) / (double) cx;
                int edge = (int) Math.round(5 + 9 * t * t); // 5 px in the middle, 14 px at the corners
                for (int y = 0; y <= edge + 2; y++) {
                    int col = y < edge ? purple : y == edge ? gold : dark;
                    backdrop.setPixel(x, y, col);
                    backdrop.setPixel(x, H - 1 - y, col);
                }
            }
        }
        c.drawBitmap(backdrop, 0, 0, null);
    }
    /** Pixel-art battle base: flat dark-purple ellipse with a gold rim (no gradients). */
    private void platform(int cx, int cy, int rx, int ry) {
        for (int y = -ry; y <= ry; y++)
            for (int x = -rx; x <= rx; x++) {
                double e = (double) (x * x) / (rx * rx) + (double) (y * y) / (ry * ry);
                if (e > 1) continue;
                fb.setPixel(cx + x, cy + y, e > 0.72 ? skin.barPal[3] : skin.tb[6]);
            }
    }

    private static final int[][] FIELD_DOUBLES = {{3, 106, 78}, {1, 146, 70}, {0, 102, 140}, {2, 142, 132}}; // battler, platform cx, cy
    /** Top-left of a battler's icon on the battle field. */
    private static int[] fieldSpot(Game.Battle b, int who) {
        int up = (who & 1) == 1 && b.foeParty == null ? 10 : 0; // wild: no foe ball row to clear, so the foes sit higher
        if (!b.doubles) return (who & 1) == 0 ? new int[]{92, 112} : new int[]{124, 54 - up};
        for (int[] sp : FIELD_DOUBLES) if (sp[0] == who) return new int[]{sp[1] - 16, sp[2] - 28 - up};
        return new int[]{0, 0};
    }

    /**
     * One battler on the field, mirroring its sprite on the top screen: the move animations' lunges / shakes / blinks,
     * Circle Throw-style exits and faints (offsets at half scale, icons are half the sprite's size), and the send-out /
     * withdraw growth: while the sprite is scaled below 1x it's a white silhouette over the party menu's Poké Ball.
     */
    private void drawFieldMon(Game.Battle b, int who) {
        if (!b.sprShown[who] || b.sprSpecies[who] == 0) return;
        Game.BattleMon m = b.mons[who] != null ? b.mons[who] : who == 0 ? b.player : b.foe;
        int[] at = fieldSpot(b, who);
        int x = at[0] + b.sprDx[who] / 2, y = at[1] + b.sprDy[who] / 2, scale = b.sprScale[who];
        int tgt = b.animTarget;
        if (who == b.animAttacker && tgt >= 0 && tgt != who) {
            // split the top-screen offset into "toward the target" and "sideways"; replay it along the bottom's line
            double tx = Game.homeX(b, tgt) - Game.homeX(b, who), ty = Game.homeY(b, tgt) - Game.homeY(b, who);
            double topLen = Math.max(1, Math.hypot(tx, ty));
            double along = (b.sprDx[who] * tx + b.sprDy[who] * ty) / topLen, side = (b.sprDy[who] * tx - b.sprDx[who] * ty) / topLen;
            int[] to = fieldSpot(b, tgt);
            double bx = to[0] - at[0], by = to[1] - at[1], botLen = Math.max(1, Math.hypot(bx, by));
            double k = along * botLen / topLen; // a lunge that reaches the target up top reaches it down here too
            x = at[0] + (int) Math.round(bx / botLen * k - by / botLen * side / 2);
            y = at[1] + (int) Math.round(by / botLen * k + bx / botLen * side / 2);
        }
        Bitmap ic = animIcon(b.sprSpecies[who], m == null ? 1 : m.hp, m == null ? 1 : m.maxHp);
        c.save();
        c.clipRect(0, 0, W, at[1] + 32); // sinking (faint) disappears into the platform
        if (scale >= 240) c.drawBitmap(ic, x, y, null);
        else if (scale > 0) {
            int sz = Math.max(1, 32 * scale / 256), cx = x + 16, by = y + 32;
            src.set(0, 0, 32, 32); dst.set(cx - sz / 2, by - sz, cx - sz / 2 + sz, by);
            c.drawBitmap(b.sprBall[who] ? silhouette(ic) : ic, src, dst, null);
            if (b.sprBall[who]) c.drawBitmap(skin.smallBall, cx - 8, by - 12, null);
        }
        c.restore();
    }
    /** The game's party status balls (healthy / status / fainted / empty), six in a row centred on the field. */
    private void partyBallRow(Game.Mon[] party, int y) {
        int x = (W - (6 * 9 - 1)) / 2;
        for (int i = 0; i < 6; i++, x += 9) {
            Game.Mon m = i < party.length ? party[i] : null;
            int k = m == null ? Skin.BALL_EMPTY : m.hp == 0 ? Skin.BALL_FAINTED : m.status != 0 ? Skin.BALL_STATUS : Skin.BALL_OK;
            c.drawBitmap(skin.partyBalls[k], x, y, null);
        }
    }
    private final java.util.HashMap<Bitmap, Bitmap> silhouettes = new java.util.HashMap<>();
    /** The icon in the menu text's white (the send-out flash). */
    private final java.util.HashMap<Long, Bitmap> tintCache = new java.util.HashMap<>();
    private Bitmap silhouette(Bitmap ic, int colour) {
        long key = (long) System.identityHashCode(ic) << 32 | (colour & 0xFFFFFFFFL);
        return tintCache.computeIfAbsent(key, k -> {
            int[] px = new int[32 * 32];
            ic.getPixels(px, 0, 32, 0, 0, 32, 32);
            for (int i = 0; i < px.length; i++) if (px[i] >>> 24 != 0) px[i] = colour;
            return Bitmap.createBitmap(px, 32, 32, Bitmap.Config.ARGB_8888);
        });
    }
    private Bitmap silhouette(Bitmap ic) {
        return silhouettes.computeIfAbsent(ic, k -> {
            int[] px = new int[32 * 32];
            k.getPixels(px, 0, 32, 0, 0, 32, 32);
            for (int i = 0; i < px.length; i++) if (px[i] >>> 24 != 0) px[i] = skin.textInk;
            return Bitmap.createBitmap(px, 32, 32, Bitmap.Config.ARGB_8888);
        });
    }

    // ---------- battle party menu: USUM's "Choose a Pokémon." grid ----------
    private int partyPick = -1; // card picked on the bottom screen (Switch / Summary then act on it)
    private static final int PARTY_BTN_Y = 184, SWITCH_X = 64, SWITCH_W = 84, SUMMARY_X = 152, SUMMARY_W = 92;
    private void drawBattleParty(Game.Battle b) {
        Game.State s = game.state;
        if (partyPick >= s.party.length) partyPick = -1;
        battleBackdrop();
        // flat bottom bar here: the backdrop's curve steps visibly beside the Back button
        rect(0, H - 18, W, 18, skin.barPal[1]);
        rect(0, H - 18, W, 1, skin.barPal[5]);
        rect(0, H - 17, W, 1, skin.barPal[3]);
        if (partyPick >= 0) { // "Choose a Pokémon." is already on the top screen
            String head = "Do what with " + s.party[partyPick].nick + "?";
            window(4, 4, W - 8, 26);
            text(head, (W - textW(head)) / 2, 9);
        }
        drawPartyCards(s, 80, partyPick, true);
        int bd = pressed == 44 ? 1 : 0;
        window(4, PARTY_BTN_Y + bd, BACK_W, BACK_H);
        rect(8, PARTY_BTN_Y + 4 + bd, BACK_W - 8, BACK_H - 8, colDark());
        Bitmap key = skin.keypad[Skin.KEY_B];
        int ax = 4 + (BACK_W - textW("←") - 3 - key.getWidth()) / 2;
        int aw = text("←", ax, PARTY_BTN_Y + (BACK_H - 14) / 2 + bd);
        c.drawBitmap(key, ax + aw + 3, PARTY_BTN_Y + (BACK_H - key.getHeight()) / 2 + bd, null);
        if (partyPick >= 0) {
            boolean canSwitch = s.party[partyPick].hp > 0 && !b.partyInBattle[partyPick];
            smallButton(86, SWITCH_X, SWITCH_W, "Shift", canSwitch);
            smallButton(87, SUMMARY_X, SUMMARY_W, "Summary", true);
        }
    }
    /** Step 2a: the battle waits on this screen (top stays on the battle). Cursor and picked state live in Game (d-pad). */
    private void drawChooseParty(Game.Battle b) {
        Game.State fs = game.state;
        // the battle party menu lists the party in battle order: display slot i = party index order[i]
        int[] order = game.chooseOrder;
        Game.State s = new Game.State();
        java.util.ArrayList<Game.Mon> disp = new java.util.ArrayList<>();
        for (int i = 0; i < fs.party.length; i++) disp.add(fs.party[order[i] < fs.party.length ? order[i] : i]);
        s.party = disp.toArray(new Game.Mon[0]);
        battleBackdrop();
        rect(0, H - 18, W, 18, skin.barPal[1]);
        rect(0, H - 18, W, 1, skin.barPal[5]);
        rect(0, H - 17, W, 1, skin.barPal[3]);
        int cur = Math.min(game.chooseCursor, Math.max(0, s.party.length - 1));
        boolean picked = game.choosePicked, onBack = game.chooseOnBack && !picked;
        String msg = game.chooseMessage;
        String head = msg != null ? msg : picked && cur < s.party.length ? "Do what with " + s.party[cur].nick + "?" : null; // "Choose a Pokémon." is up top
        if (head != null) {
            window(4, 4, W - 8, 26);
            if (textW(head) <= W - 20) text(head, (W - textW(head)) / 2, 9);
            else small(head, (W - smallW(head)) / 2, 12);
        }
        drawPartyCards(s, 80, onBack ? -1 : cur, true);
        boolean back = picked || game.chooseCanBack();
        if (back) chooseButton(88, 4, BACK_W, null, picked ? game.chooseButton == 0 : onBack);
        if (picked) {
            chooseButton(89, SWITCH_X, SWITCH_W, "Shift", game.chooseButton == 1); // the battle party menu's own word
            chooseButton(96, SUMMARY_X, SUMMARY_W, "Summary", game.chooseButton == 2);
        }
    }
    // ---------- step 2b: the bag, USUM style: party column left, pocket tabs + item list right ----------
    private static final int BAG_X = 104, BAG_W = 140, BAG_LIST_Y = 32, BAG_ROW_H = 24, PARTY_ROW_H = 29;
    private static final int[] BAG_TAB_ITEMS = {13, 4, 139}; // tab icons: Potion, Poké Ball, Oran Berry
    private void drawBag(Game.Battle b) {
        battleBackdrop();
        rect(0, H - 18, W, 18, skin.barPal[1]);
        rect(0, H - 18, W, 1, skin.barPal[5]);
        rect(0, H - 17, W, 1, skin.barPal[3]);
        Game.State s = game.state;
        boolean targeting = game.bagTargeting;
        for (int i = 0; i < s.party.length && i < 6; i++) { // party column
            Game.Mon m = s.party[i];
            int y = 4 + i * PARTY_ROW_H, d = pressed == 140 + i ? 1 : 0;
            boolean sel = targeting && game.bagPartyCursor == i;
            window(4, y + d, 96, PARTY_ROW_H - 1);
            rect(8, y + 4 + d, 88, PARTY_ROW_H - 9, sel ? colDarkSel() : colDark());
            c.save(); c.clipRect(8, y + 4 + d, 40, y + PARTY_ROW_H - 5 + d);
            c.drawBitmap(animIcon(m.species, m.hp, m.maxHp), 6, y - 6 + d, null);
            c.restore();
            if (sel) cursorArrow(52, y + 4);
            if (m.egg) { small(m.nick, 42, y + 5 + d); continue; }
            small("ʟ" + m.level, 42, y + 5 + d);
            int badge = m.hp == 0 ? Skin.STATUS_FNT : statusBadge(m.status);
            if (badge >= 0) c.drawBitmap(skin.statusIcon[badge], 62, y + 6 + d, null);
            int fw = m.maxHp == 0 ? 0 : Math.max(m.hp > 0 ? 1 : 0, 50 * m.hp / m.maxHp);
            rect(42, y + 17 + d, 52, 4, skin.hp[7]);
            rect(43, y + 18 + d, 50, 2, skin.hp[5]);
            rect(43, y + 18 + d, fw, 2, skin.hp[hpColour(m.hp, m.maxHp)]);
        }
        for (int t = 0; t < 3; t++) { // pocket tabs
            int x = BAG_X + t * 47, d = pressed == 120 + t ? 1 : 0;
            window(x, 4 + d, 45, 26);
            rect(x + 4, 8 + d, 37, 18, game.bagPocket == t ? colDarkSel() : colDark());
            Bitmap ic = skin.item(BAG_TAB_ITEMS[t]);
            c.drawBitmap(ic, x + (45 - ic.getWidth()) / 2, 5 + d, null);
        }
        java.util.List<int[]> list = game.bagList(game.bagPocket);
        window(BAG_X, BAG_LIST_Y - 2, BAG_W + 2, Game.BAG_ROWS * BAG_ROW_H + 8);
        if (game.bagMoveSlot >= 0) { // Ether: the chosen Pokémon's moves instead of the items
            int[][] mv = game.partyMoves(game.bagMoveSlot);
            for (int r = 0; r < 4; r++) {
                if (mv[r][0] == 0) continue;
                int y = BAG_LIST_Y + 2 + r * BAG_ROW_H, d = pressed == 130 + r ? 1 : 0;
                if (game.bagPartyCursor == r || pressed == 130 + r) rect(BAG_X + 4, y + d, BAG_W - 6, BAG_ROW_H - 2, colDarkSel());
                small(fit(rom.moveName(mv[r][0]), 90), BAG_X + 8, y + 7 + d);
                String pp = "PP " + mv[r][1];
                small(pp, BAG_X + BAG_W - 4 - smallW(pp), y + 7 + d);
            }
            list = java.util.Collections.emptyList();
        }
        int top = Math.min(game.bagTop, Math.max(0, list.size() - Game.BAG_ROWS));
        for (int r = 0; r < Game.BAG_ROWS && top + r < list.size(); r++) {
            int[] e = list.get(top + r);
            int y = BAG_LIST_Y + 2 + r * BAG_ROW_H, d = pressed == 130 + r ? 1 : 0;
            boolean sel = top + r == game.bagCursor && !game.bagOnBack;
            if (sel) { rect(BAG_X + 4, y + d, BAG_W - 6, BAG_ROW_H - 2, colDarkSel()); text("▶", BAG_X + 5, y + 4 + d); } // the game's menu cursor
            c.drawBitmap(skin.item(e[0]), BAG_X + 11, y - 1 + d, null);
            small(fit(game.itemName(e[0]), 74), BAG_X + 36, y + 7 + d);
            String q = "x" + e[1];
            small(q, BAG_X + BAG_W - 4 - smallW(q), y + 7 + d);
        }
        if (list.isEmpty() && game.bagMoveSlot < 0) small("Nothing here.", BAG_X + 10, BAG_LIST_Y + 10);
        // description / prompt / result line
        String msg = game.bagMessage;
        if (msg == null && game.bagMoveSlot < 0 && game.bagCursor < list.size()) msg = game.itemDescription(list.get(game.bagCursor)[0]).replace('\n', ' ');
        if (msg == null || !msg.equals(descShown)) descOpen = false; // a different item / message closes the full text
        descShown = msg;
        int d = pressed == 154 ? 1 : 0;
        if (descOpen) { // tapped: the whole text over the list; any tap closes it
            java.util.List<String> lines = wrapSmall(msg, BAG_W - 12);
            window(BAG_X, 30 + d, BAG_W + 2, DESC_Y + DESC_H - 30);
            for (int i = 0; i < lines.size(); i++) small(lines.get(i), BAG_X + 6, 35 + d + i * 9);
        } else {
            window(BAG_X, DESC_Y + d, BAG_W + 2, DESC_H);
            if (msg != null) {
                java.util.List<String> lines = wrapSmall(msg, BAG_W - 12);
                for (int i = 0; i < Math.min(4, lines.size()); i++)
                    small(i == 3 && lines.size() > 4 ? fit(lines.get(i) + " " + lines.get(4), BAG_W - 12) : lines.get(i), BAG_X + 6, DESC_Y + 3 + d + i * 9);
            }
        }
        // bottom row: Back (the list scrolls with a drag or the d-pad)
        chooseButton(150, 4, BACK_W, null, game.bagOnBack);
    }
    private static final int DESC_Y = 136, DESC_H = 47;
    private boolean descOpen;
    private String descShown;
    private java.util.List<String> wrapSmall(String msg, int width) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String w : msg.split(" +")) {
            if (line.length() > 0 && smallW(line + " " + w) > width) { out.add(line.toString()); line.setLength(0); }
            if (line.length() > 0) line.append(' ');
            line.append(w);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }
    private int bagHit(int x, int y) {
        if (descOpen && y < PARTY_BTN_Y) return 154;
        if (x >= BAG_X && y >= DESC_Y && y < DESC_Y + DESC_H && descShown != null) return 154;
        if (y >= PARTY_BTN_Y) return x < 4 + BACK_W ? 150 : -1; // no Use button: tap the selected item again, or A
        if (x < 100) { int i = (y - 4) / PARTY_ROW_H; return i >= 0 && i < game.state.party.length && i < 6 ? 140 + i : -1; }
        if (x >= BAG_X && y < 30) return 120 + Math.min(2, (x - BAG_X) / 47);
        if (x >= BAG_X && y >= BAG_LIST_Y && y < BAG_LIST_Y + Game.BAG_ROWS * BAG_ROW_H) return 130 + (y - BAG_LIST_Y) / BAG_ROW_H;
        return -1;
    }
    private void bagTap(int hit) {
        game.chime();
        if (hit == 150 && game.bagMoveSlot >= 0) { game.bagMoveSlot = -1; game.bagMessage = null; }
        else if (hit >= 130 && hit < 134 && game.bagMoveSlot >= 0) {
            java.util.List<int[]> l = game.bagList(game.bagPocket);
            if (game.bagCursor < l.size() && game.partyMoves(game.bagMoveSlot)[hit - 130][0] != 0) game.bagUse(l.get(game.bagCursor)[0], game.bagMoveSlot, hit - 130);
        }
        else if (hit == 150) { if (game.bagTargeting) { game.bagTargeting = false; game.bagMessage = null; } else game.bagCancel(); }
        else if (hit == 153) game.bagActivate();
        else if (hit == 154) descOpen = !descOpen;
        else if (hit >= 120 && hit < 123) game.bagSetPocket(hit - 120);
        else if (hit >= 130 && hit < 135) {
            int i = Math.min(game.bagTop, Math.max(0, game.bagList(game.bagPocket).size() - Game.BAG_ROWS)) + hit - 130;
            if (i < game.bagList(game.bagPocket).size()) { if (i == game.bagCursor && !game.bagTargeting) game.bagActivate(); else { game.bagCursor = i; game.bagMessage = null; game.bagTargeting = false; } }
        }
        else if (hit >= 140 && hit < 146 && game.bagTargeting) {
            java.util.List<int[]> l = game.bagList(game.bagPocket);
            game.bagPartyCursor = hit - 140; // the cursor follows the tap
            if (game.bagCursor < l.size()) game.bagUse(l.get(game.bagCursor)[0], hit - 140);
        }
    }

    /** Bottom-row button with the hover look every B-row button has: lighter fill + the cursor arrow. Null label = Back. */
    private void chooseButton(int hit, int x, int w, String label, boolean hover) {
        int d = pressed == hit ? 1 : 0;
        window(x, PARTY_BTN_Y + d, w, BACK_H);
        rect(x + 4, PARTY_BTN_Y + 4 + d, w - 8, BACK_H - 8, hover ? colDarkSel() : colDark());
        if (label == null) {
            Bitmap key = skin.keypad[Skin.KEY_B];
            int ax = x + (w - textW("←") - 3 - key.getWidth()) / 2;
            int aw = text("←", ax, PARTY_BTN_Y + (BACK_H - 14) / 2 + d);
            c.drawBitmap(key, ax + aw + 3, PARTY_BTN_Y + (BACK_H - key.getHeight()) / 2 + d, null);
        } else text(label, x + (w - textW(label)) / 2, PARTY_BTN_Y + (BACK_H - 14) / 2 + d);
        if (hover) cursorArrow(x + w / 2, PARTY_BTN_Y + 4);
    }

    private void smallButton(int hit, int x, int w, String label, boolean enabled) {
        int d = pressed == hit ? 1 : 0;
        window(x, PARTY_BTN_Y + d, w, BACK_H);
        rect(x + 4, PARTY_BTN_Y + 4 + d, w - 8, BACK_H - 8, enabled ? purpleLight() : colDark());
        text(label, x + (w - textW(label)) / 2, PARTY_BTN_Y + (BACK_H - 14) / 2 + d);
        if (!enabled) { fill.setColor(0xA0000000); c.drawRect(x, PARTY_BTN_Y + d, x + w, PARTY_BTN_Y + BACK_H + d, fill); }
    }

    private static final String[] INFO_ICON = { // 9x9 (i), drawn in the text's ink + shadow
            "..#####..",
            ".#.....#.",
            "#...#...#",
            "#.......#",
            "#...#...#",
            "#...#...#",
            "#...#...#",
            ".#.....#.",
            "..#####.."};
    private void infoIcon(int x, int y) {
        for (int pass = 0; pass < 2; pass++)
            for (int py = 0; py < 9; py++)
                for (int px = 0; px < 9; px++)
                    if (INFO_ICON[py].charAt(px) == '#') fb.setPixel(x + px + 1 - pass, y + py + 1 - pass, pass == 0 ? skin.textShadow : skin.textInk);
    }
    /**
     * Move info as a popup: everything but the hovered move dims, and a half-width column beside it (the other column)
     * shows type + category, name, power / accuracy / PP and the game's description, re-wrapped in the small font.
     */
    private void drawMoveInfo(Game.Battle b, int i) {
        int move = b.moves[i];
        if (move == 0) return;
        int cx = MOVE_X + (i % 2) * (MOVE_W + 4), cy = MOVE_Y + (i / 2) * (MOVE_H + 4);
        fill.setColor(0xA0000000); // dim around the hovered card
        c.drawRect(0, 0, W, cy, fill);
        c.drawRect(0, cy + MOVE_H, W, H, fill);
        c.drawRect(0, cy, cx, cy + MOVE_H, fill);
        c.drawRect(cx + MOVE_W, cy, W, cy + MOVE_H, fill);
        int w = MOVE_W, h = 2 * MOVE_H + 4 + 30, x = MOVE_X + (1 - i % 2) * (MOVE_W + 4), y = MOVE_Y;
        window(x, y, w, h);
        Bitmap ti = skin.typeIcon(b.types[i]), ps = pss(b.split[i]);
        int ix = x + 8;
        if (ti != null) { outlined(ti, ix, y + 8); ix += ti.getWidth() + 4; }
        outlined(ps, ix, y + 8 + ((ti != null ? ti.getHeight() : ps.getHeight()) - ps.getHeight()) / 2);
        text(fit(rom.moveName(move), w - 16), x + 8, y + 24);
        String pw = b.split[i] == 2 || b.power[i] == 0 ? "-" : b.power[i] == 1 ? "KO" : String.valueOf(b.power[i]);
        int sx = x + 8;
        sx += text("Pw " + pw, sx, y + 42) + 8;
        if (b.acc[i] == 0xFFFF) infinity(sx + text("Ac ", sx, y + 42), y + 42);
        else text("Ac " + (b.acc[i] == 0 ? "-" : String.valueOf(b.acc[i])), sx, y + 42);
        text("PP " + b.pp[i] + "/" + b.maxPp[i], x + 8, y + 58);
        rect(x + 6, y + 76, w - 12, 1, skin.textShadow);
        int ly = y + 80;
        StringBuilder line = new StringBuilder();
        for (String word : rom.moveDescription(move).replace('\n', ' ').split(" +")) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (smallW(next) > w - 16 && line.length() > 0) { small(line.toString(), x + 8, ly); ly += 10; line = new StringBuilder(word); }
            else line = new StringBuilder(next);
        }
        if (line.length() > 0) small(line.toString(), x + 8, ly);
    }

    private int infoBattler = -1;
    private static final String[] STAGE_NAMES = {"", "Atk", "Def", "Spe", "SpA", "SpD", "Acc", "Eva"};
    /** Battler info over the field (SM's "+"): types, status, HP (numbers for your side), stat stage changes. */
    private void drawBattlerInfo(Game.Battle b) {
        int who = infoBattler;
        Game.BattleMon m = b.doubles || who > 1 ? b.mons[who] : who == 0 ? b.player : b.foe;
        if (m == null || !b.sprShown[who]) { infoBattler = -1; return; }
        int x = 64, y = 30, w = 120, h = 104;
        window(x, y, w, h);
        text(fit(m.nick, w - 50), x + 8, y + 6);
        small("ʟ" + m.level, x + w - 10 - smallW("ʟ" + m.level), y + 9);
        int tx = x + 8;
        Bitmap t1 = skin.typeIcon(m.type1);
        if (t1 != null) { outlined(t1, tx, y + 24); tx += t1.getWidth() + 4; }
        if (m.type2 != m.type1) { Bitmap t2 = skin.typeIcon(m.type2); if (t2 != null) { outlined(t2, tx, y + 24); tx += t2.getWidth() + 4; } }
        int badge = m.hp == 0 ? Skin.STATUS_FNT : statusBadge(m.status);
        if (badge >= 0) c.drawBitmap(skin.statusIcon[badge], x + w - 40, y + 26, null);
        int fw = m.maxHp == 0 ? 0 : Math.max(m.hp > 0 ? 1 : 0, 64 * m.hp / m.maxHp);
        rect(x + 8, y + 40, 66, 4, skin.hp[7]);
        rect(x + 9, y + 41, 64, 2, skin.hp[5]);
        rect(x + 9, y + 41, fw, 2, skin.hp[hpColour(m.hp, m.maxHp)]);
        if ((who & 1) == 0) small(m.hp + "/" + m.maxHp, x + 78, y + 37); // the game shows numbers only for your side
        int line = 0, sy = y + 52;
        if ((who & 1) == 0) { small(rom.abilityName(m.ability), x + 8, sy); sy += 12; } // like the game: only your own abilities
        for (int k = 1; k < 8; k++) {
            int st = m.stages[k] - 6;
            if (st == 0) continue;
            String txt = STAGE_NAMES[k] + " " + (st > 0 ? "+" : "") + st;
            small(txt, x + 8 + (line % 2) * 54, sy + (line / 2) * 10);
            line++;
        }
        if (line == 0) small("No stat changes", x + 8, sy);
    }

    private void drawBattle(Game.Battle b) {
        fb.eraseColor(0xFF000000);
        if (b.mode != Game.Battle.ACTION || game.infoCloseReq) { infoBattler = -1; game.infoCloseReq = false; } // actions / battle text / any press close it
        game.infoOpen = infoBattler >= 0;
        if (b.choosing) { drawChooseParty(b); return; }
        if (b.yesNo >= 0) { battleBackdrop(); yesNoButton(230, YES_Y, "Yes", b.yesNo == 0); yesNoButton(231, YES_Y + 52, "No", b.yesNo == 1); return; }
        if (b.bagOpen) { drawBag(b); return; }
        if (b.partyMenu) { drawBattleParty(b); return; }
        partyPick = -1;
        battleBackdrop();
        if (b.mode == Game.Battle.ACTION || b.mode == Game.Battle.WAIT) {
            boolean live = b.mode == Game.Battle.ACTION;
            // centre: both Pokémon, like USUM's field view
            int up = b.foeParty == null ? 10 : 0; // wild: foes (and their bases) sit higher, as in fieldSpot
            if (b.doubles) for (int[] sp : FIELD_DOUBLES) platform(sp[1], sp[2] - ((sp[0] & 1) == 1 ? up : 0), 18, 6);
            else { platform(140, 82 - up, 22, 7); platform(108, 140, 22, 7); }
            for (int who = 0; who < 4; who++) drawFieldMon(b, who);
            if (b.foeParty != null) partyBallRow(b.foeParty, 34);
            partyBallRow(b.allyParty != null ? b.allyParty : game.state.party, 153);
            actionButton(33, 4, COL_Y, SIDE_W, SIDE_H, ball2x(), "Pokémon", live && b.actionCursor == Game.B_ACTION_POKEMON, skin.partyPal[10][5], skin.partyPal[10][4]);
            actionButton(31, 4, COL_Y + SIDE_H + 4, SIDE_W, SIDE_H, cubeIcon(), "Cube", live && b.actionCursor == Game.B_ACTION_BAG, skin.partyPal[4][10], skin.partyPal[4][9]);
            actionButton(30, FIGHT_X, COL_Y, FIGHT_W, SIDE_Y + FIGHT_H - COL_Y, fightIcon(), "Fight", live && b.actionCursor == Game.B_ACTION_FIGHT, fightRed(), lighten(fightRed()));
            actionButton(32, RUN_X, RUN_Y, RUN_W, RUN_H, runIcon(), "Run", live && b.actionCursor == Game.B_ACTION_RUN, skin.partyPal[7][5], skin.partyPal[7][4]);
            if ((b.quickBall || b.fallbackBall != 0) && live) { // CFRU's Last Ball trigger over the exact ball it will throw (copied from the game's sprite)
                int qd = pressed == 34 ? 1 : 0;
                Bitmap lk = skin.keypad[Skin.KEY_L]; // its button, hanging off the corner like Run's R
                final int qw = 40, qh = 24, qy = 3; // wider than tall like Unbound's trigger box
                window(QB_X, qy + qd, qw, qh);
                Bitmap bi = trimmed(b.quickBall ? ballBitmap(b) : skin.item(b.fallbackBall)); // the exact ball (game's sprite) / our pick's icon
                c.drawBitmap(bi, QB_X + (qw - bi.getWidth()) / 2, qy + qd + (qh - bi.getHeight()) / 2, null);
                c.drawBitmap(lk, QB_X + qw - lk.getWidth() / 2 - 4, qy - lk.getHeight() / 2 + qd, null);
            }
            if (infoBattler >= 0) drawBattlerInfo(b);
            Bitmap runKey = skin.keypad[b.quickRunWithR ? Skin.KEY_R : Skin.KEY_B];
            c.drawBitmap(runKey, RUN_X + RUN_W - runKey.getWidth() / 2 - 4, RUN_Y - runKey.getHeight() / 2 + (pressed == 32 ? 1 : 0), null);
            if (!live) { fill.setColor(0xA0000000); c.drawRect(0, 0, W, H, fill); } // dimmed: the battle is playing out
        } else if (b.mode == Game.Battle.TARGET) {
            drawTargetPicker(b);
        } else if (b.mode == Game.Battle.MOVES || b.mode == Game.Battle.SWAP) {
            boolean targeting = false, swapping = b.mode == Game.Battle.SWAP;
            for (int i = 0; i < 4; i++) {
                int x = MOVE_X + (i % 2) * (MOVE_W + 4), y = MOVE_Y + (i / 2) * (MOVE_H + 4);
                boolean down = pressed == 40 + i, sel = swapping ? b.swapCursor == i : b.moveCursor == i && b.extraSel == 0;
                boolean picked = swapping && b.moveCursor == i;
                int d = down ? 1 : 0;
                if (b.moves[i] == 0) { box(x, y, MOVE_W, MOVE_H, false, false); continue; }
                // gold frame, filled with the type's colour (taken from its official icon)
                int fill = skin.typeColour(b.types[i]);
                window(x, y + d, MOVE_W, MOVE_H);
                rect(x + 4, y + 4 + d, MOVE_W - 8, MOVE_H - 8, sel || (picked && (game.frame & 16) == 0) ? lighten(fill) : fill); // picked: blinks
                if (sel) cursorArrow(x + MOVE_W / 2, y + 4);
                boolean stab = b.split[i] != 2 && (b.types[i] == b.monType1 || b.types[i] == b.monType2 || b.types[i] == b.monType3);
                text(fit(rom.moveName(b.moves[i]), MOVE_W - 26 - (stab ? 10 : 0)), x + 7, y + 6 + d);
                // move info: the hovered card shows its key (L), the others a tappable (i)
                Bitmap lk = skin.keypad[Skin.KEY_L];
                int infoX = x + MOVE_W - 7 - (sel ? lk.getWidth() : 10);
                if (swapping) infoX = x + MOVE_W - 7;
                else if (sel) c.drawBitmap(lk, infoX, y + 8 + d, null); else infoIcon(infoX, y + 8 + d);
                if (stab) text("+", infoX - 3 - textW("+"), y + 6 + d); // same-type attack bonus
                // type + category icons on one baseline, both outlined
                Bitmap ti = skin.typeIcon(b.types[i]), ps = pss(b.split[i]);
                int iy = y + 25 + d;
                int tiw = 0;
                if (ti != null) { outlined(ti, x + 8, iy); tiw = ti.getWidth(); }
                outlined(ps, x + 8 + tiw + 4, iy + ((ti != null ? ti.getHeight() : ps.getHeight()) - ps.getHeight()) / 2);
                String pp = b.pp[i] + "/" + b.maxPp[i];
                text(pp, x + MOVE_W - 7 - textW(pp), y + 23 + d);
                // power / accuracy (what CFRU shows on L) + CFRU's effectiveness / STAB markers
                String pw = b.split[i] == 2 || b.power[i] == 0 ? "-" : b.power[i] == 1 ? "KO" : String.valueOf(b.power[i]);
                String ac = b.acc[i] == 0 ? "-" : String.valueOf(b.acc[i]);
                int ey = y + 47 + d, ex = x + 7;
                ex += text("Pw " + pw, ex, ey) + 6;
                if (b.acc[i] == 0xFFFF) infinity(ex + text("Ac ", ex, ey), ey); // CFRU's "can't miss"
                else text("Ac " + ac, ex, ey);
                if (b.split[i] != 2) effectMarkers(b, i, x + MOVE_W - 7, ey, targeting);
            }
            boolean backSel = b.extraSel == 1, megaSel = b.extraSel == 2;
            if (swapping) { // the game's "Switch which?" where Mega would be; Back = B cancels
                String q = rom.text(0x083FE7A8, 16).replace('\n', ' ');
                int qw = textW(q) + 16, qx = W - 4 - qw;
                window(qx, BACK_Y, qw, BACK_H);
                text(q, qx + 8, BACK_Y + (BACK_H - 14) / 2);
            }
            int bd = pressed == 44 ? 1 : 0;
            window(4, BACK_Y + bd, BACK_W, BACK_H);
            rect(8, BACK_Y + 4 + bd, BACK_W - 8, BACK_H - 8, backSel ? colDarkSel() : colDark());
            Bitmap key = skin.keypad[Skin.KEY_B];
            int ax = 4 + (BACK_W - textW("←") - 3 - key.getWidth()) / 2;
            int aw = text("←", ax, BACK_Y + (BACK_H - 14) / 2 + bd);                        // the game's back-arrow glyph
            c.drawBitmap(key, ax + aw + 3, BACK_Y + (BACK_H - key.getHeight()) / 2 + bd, null); // then its B-button glyph
            if (backSel) cursorArrow(4 + BACK_W / 2, BACK_Y + 4);
            if (b.canMega && !swapping) { // CFRU's Mega trigger art + the START glyph that toggles it; lit while armed
                int mx = W - 4 - MEGA_W, md = pressed == 45 ? 1 : 0;
                window(mx, BACK_Y + md, MEGA_W, BACK_H);
                rect(mx + 4, BACK_Y + 4 + md, MEGA_W - 8, BACK_H - 8, megaOn || megaSel ? colDarkSel() : colDark());
                c.drawBitmap(skin.megaTrigger, mx + 4, BACK_Y - 2 + md, null);
                c.drawBitmap(skin.keypad[Skin.KEY_START], mx + 40, BACK_Y + (BACK_H - 12) / 2 + md, null);
                if (megaSel) cursorArrow(mx + MEGA_W / 2, BACK_Y + 4);
            }
        }
        if (b.mode == Game.Battle.MOVES && b.moveInfo) drawMoveInfo(b, Math.max(0, Math.min(3, b.moveCursor)));
        if (b.mode != Game.Battle.MOVES) megaOn = false;
        int t = game.megaToggles;
        if (t != seenMegaToggles) { if (b.canMega && ((t - seenMegaToggles) & 1) == 1) megaOn = !megaOn; seenMegaToggles = t; }
    }
    private boolean megaOn;
    private int seenMegaToggles;

    /** Top-screen overlay while the battle menus live down here: Unbound's plain message box + the prompt. */
    /** Top-screen overlay while the battle menus live down here: Unbound's plain message box + the prompt. */
    private final Bitmap topBox = Bitmap.createBitmap(240, 48, Bitmap.Config.ARGB_8888);
    private final Canvas topBoxCanvas = new Canvas(topBox);
    Bitmap battleTopOverlay() {
        Game.Battle b = game.battle;
        if (b == null || b.passive || !b.topHold && (!b.inBattleScreen || (b.mode == Game.Battle.WAIT && !b.menuOnTop && !b.choosing && !b.bagOpen))) return null;
        topBox.eraseColor(0);
        topBoxCanvas.drawBitmap(skin.battleTextbox, 0, 0, null);
        String prompt = b.choosing ? "Choose a Pokémon." : b.bagOpen ? "Choose an item." : b.mode == Game.Battle.TARGET ? "Choose a target." : "What will " + b.player.nick + " do?";
        for (int i = 0, x = 16; i < prompt.length(); i++) {
            Integer g = Rom.ENC.get(prompt.charAt(i));
            int gl = g == null ? 0xAC : g, w = rom.glyphWidth(gl);
            for (int py = 0; py < 14; py++)
                for (int px = 0; px < w; px++) {
                    int v = rom.glyphPixel(gl, px, py);
                    if (v == 1) topBox.setPixel(x + px, 9 + py, skin.textInk);
                    else if (v == 2) topBox.setPixel(x + px, 9 + py, skin.textShadow);
                }
            x += w;
        }
        return topBox;
    }


    /**
     * CFRU's effectiveness markers, right-aligned ending at rightX: up arrow super effective, down arrow not very, * no effect,
     * a dash for neutral (doubles / targeting, where every foe gets a marker), then + for STAB. Singles keep CFRU's single
     * marker; doubles show one per foe in on-screen order; target select shows just the targeted battler's.
     */
    private void effectMarkers(Game.Battle b, int move, int rightX, int y, boolean targeting) {
        java.util.ArrayList<Integer> results = new java.util.ArrayList<>();
        if (targeting) { if (b.target >= 0 && b.target < 4) results.add(b.resultsByPos[b.position[b.target]][move]); }
        else if (b.doubles) { for (int f : b.foes) results.add(f < 0 ? -1 : b.resultsByPos[b.position[f]][move]); }
        else results.add(b.result[move]);
        boolean neutralDash = b.doubles || targeting;
        int x = rightX;
        int[] e = skin.effColours;
        for (int k = results.size() - 1; k >= 0; k--) { // right-to-left so the left foe's marker lands on the left
            int r = results.get(k);
            String m; int ink, sh;
            if (r < 0) { x -= textW("-") + 2; continue; }          // fainted / absent foe: keep its slot empty
            if ((r & 8) != 0) { m = "*"; ink = e[8]; sh = e[9]; }
            else if ((r & 2) != 0) { m = "↑"; ink = e[0]; sh = e[1]; }
            else if ((r & 4) != 0) { m = "↓"; ink = e[4]; sh = e[5]; }
            else if (neutralDash) { x -= 12; neutralMarker(x, y, e[12], e[13]); x -= 2; continue; }
            else continue;
            x -= textW(m);
            textCol(m, x, y, ink, sh);
            x -= 2;
        }
    }

    /** Neutral effectiveness: a chunky bar drawn like the font's arrow glyphs (9px body, 1px shadow, 12px advance). */
    private void neutralMarker(int x, int y, int ink, int shadow) {
        for (int r = 5; r <= 7; r++) { rect(x, y + r, 9, 1, ink); rect(x + 9, y + r, 1, 1, shadow); }
        rect(x, y + 8, 10, 1, shadow);
    }

    // ---------- doubles target picker (SM field view as touch targets) ----------
    private static final int TGT_W = 112, TGT_H = 64, TGT_Y_FOE = 40, TGT_Y_ALLY = 110;
    /** Screen slot -> battler: foes on top (the opponent's 'right' battler stands on the left), yours below. */
    private static final int[][] TGT_SLOTS = {{3, 8, TGT_Y_FOE}, {1, 128, TGT_Y_FOE}, {0, 8, TGT_Y_ALLY}, {2, 128, TGT_Y_ALLY}};

    private void drawTargetPicker(Game.Battle b) {
        int sel = Math.max(0, Math.min(3, b.moveCursor)), move = b.moves[sel];
        // banner: the move you're aiming
        window(8, 4, W - 16, 28);
        Bitmap ti = skin.typeIcon(b.types[sel]);
        String name = rom.moveName(move);
        int bw = (ti != null ? ti.getWidth() + 6 : 0) + textW(name), bx = (W - bw) / 2;
        if (ti != null) { outlined(ti, bx, 12); bx += ti.getWidth() + 6; }
        text(name, bx, 9);
        for (int[] slot : TGT_SLOTS) {
            int who = slot[0], x = slot[1], y = slot[2];
            boolean ok = b.targetable[who], cur = who == b.target, down = pressed == 70 + who;
            int d = down ? 1 : 0;
            window(x, y + d, TGT_W, TGT_H);
            rect(x + 4, y + 4 + d, TGT_W - 8, TGT_H - 8, cur ? purpleLight() : skin.tb[15]);
            platform(x + TGT_W / 2, y + 48 + d, 24, 7);
            if (b.present[who]) {
                Game.BattleMon m = b.mons[who];
                c.drawBitmap(animIcon(m.species, m.hp, m.maxHp), x + TGT_W / 2 - 16, y + 18 + d, null);
                small(m.nick, x + 8, y + 6 + d);
                int fw = m.maxHp == 0 ? 0 : Math.max(m.hp > 0 ? 1 : 0, 30 * m.hp / m.maxHp);
                rect(x + TGT_W - 40, y + 9 + d, 32, 4, skin.hp[7]);
                rect(x + TGT_W - 39, y + 10 + d, 30, 2, skin.hp[5]);
                rect(x + TGT_W - 39, y + 10 + d, fw, 2, skin.hp[hpColour(m.hp, m.maxHp)]);
                if (ok && b.split[sel] != 2) singleMarker(b.resultsByPos[b.position[who]][sel], x + TGT_W - 8, y + 44 + d);
            }
            if (!ok) { fill.setColor(0xA0000000); c.drawRect(x, y + d, x + TGT_W, y + TGT_H + d, fill); } // can't be targeted
            if (cur && b.extraSel == 0) cursorArrow(x + TGT_W / 2, y + 4);
        }
        // Back (cancels targeting, = B)
        int bd = pressed == 44 ? 1 : 0;
        window(4, BACK_Y + bd, BACK_W, BACK_H);
        rect(8, BACK_Y + 4 + bd, BACK_W - 8, BACK_H - 8, b.extraSel == 1 ? colDarkSel() : colDark());
        if (b.extraSel == 1) cursorArrow(4 + BACK_W / 2, BACK_Y + 4);
        Bitmap key = skin.keypad[Skin.KEY_B];
        int ax = 4 + (BACK_W - textW("←") - 3 - key.getWidth()) / 2;
        int aw = text("←", ax, BACK_Y + (BACK_H - 14) / 2 + bd);
        c.drawBitmap(key, ax + aw + 3, BACK_Y + (BACK_H - key.getHeight()) / 2 + bd, null);
    }

    /** One effectiveness marker (target picker), right-aligned at rightX: arrows, * or the neutral bar. */
    private void singleMarker(int r, int rightX, int y) {
        int[] e = skin.effColours;
        if ((r & 8) != 0) { textCol("*", rightX - textW("*"), y, e[8], e[9]); }
        else if ((r & 2) != 0) { textCol("↑", rightX - textW("↑"), y, e[0], e[1]); }
        else if ((r & 4) != 0) { textCol("↓", rightX - textW("↓"), y, e[4], e[5]); }
        else neutralMarker(rightX - 12, y, e[12], e[13]);
    }

    private static final int YES_W = 120, YES_H = 36, YES_X = (W - YES_W) / 2, YES_Y = 54;
    /** The game's battle Yes/No, mirrored as two buttons (the cursor shows as the hover). */
    private void yesNoButton(int hit, int y, String label, boolean hover) {
        int d = pressed == hit ? 1 : 0;
        window(YES_X, y + d, YES_W, YES_H);
        rect(YES_X + 4, y + 4 + d, YES_W - 8, YES_H - 8, hover ? colDarkSel() : colDark());
        text(label, YES_X + (YES_W - textW(label)) / 2, y + (YES_H - 14) / 2 + d);
        if (hover) cursorArrow(YES_X + YES_W / 2, y + 4);
    }
    private int battleHit(Game.Battle b, int x, int y) {
        if (b.passive) return -1; // the game's own menus are running this battle
        if (b.yesNo >= 0) return x < YES_X || x >= YES_X + YES_W ? -1 : y >= YES_Y && y < YES_Y + YES_H ? 230 : y >= YES_Y + 52 && y < YES_Y + 52 + YES_H ? 231 : -1;
        if (b.bagOpen) return bagHit(x, y);
        if (b.choosing) {
            if (y >= PARTY_BTN_Y) {
                if (x < 4 + BACK_W && (game.choosePicked || game.chooseCanBack())) return 88;
                if (game.choosePicked && x >= SWITCH_X && x < SWITCH_X + SWITCH_W) return 89;
                if (game.choosePicked && x >= SUMMARY_X && x < SUMMARY_X + SUMMARY_W) return 96;
                return -1;
            }
            for (int i = 0; i < game.state.party.length && i < 6; i++) {
                int[] at = cardPos(i, true);
                if (x >= at[0] && x < at[0] + Skin.SLOT_W && y >= at[1] && y < at[1] + Skin.SLOT_H) return 90 + i;
            }
            return -1;
        }
        if (b.partyMenu) {
            if (y >= PARTY_BTN_Y) {
                if (x < 4 + BACK_W) return 44;
                if (partyPick >= 0 && x >= SWITCH_X && x < SWITCH_X + SWITCH_W) return 86;
                if (partyPick >= 0 && x >= SUMMARY_X && x < SUMMARY_X + SUMMARY_W) return 87;
                return -1;
            }
            for (int i = 0; i < game.state.party.length && i < 6; i++) {
                int[] at = cardPos(i, true);
                if (x >= at[0] && x < at[0] + Skin.SLOT_W && y >= at[1] && y < at[1] + Skin.SLOT_H) return 80 + i;
            }
            return -1;
        }
        if (b.mode == Game.Battle.ACTION || b.mode == Game.Battle.WAIT) { // field Pokémon: tap for its info
            if (infoBattler >= 0) return 189;
            for (int who = 0; who < 4; who++) {
                if (!b.sprShown[who]) continue;
                int[] at = fieldSpot(b, who);
                if (x >= at[0] + 4 && x < at[0] + 28 && y >= at[1] + 4 && y < at[1] + 32) return 180 + who;
            }
        }
        if (b.mode == Game.Battle.ACTION) {
            if ((b.quickBall || b.fallbackBall != 0) && x < QB_X + 40 && y < COL_Y) return 34;
            if (x >= FIGHT_X && y >= COL_Y && y < SIDE_Y + FIGHT_H) return 30; // same top and bottom as the left column
            if (x < SIDE_W + 4 && y >= COL_Y && y < COL_Y + SIDE_H) return 33;      // Pokémon
            if (x < SIDE_W + 4 && y >= COL_Y + SIDE_H + 4 && y < SIDE_Y + FIGHT_H) return 31; // Bag
            if (x >= RUN_X && x < RUN_X + RUN_W && y >= RUN_Y && y < RUN_Y + RUN_H) return 32;
        } else if (b.mode == Game.Battle.TARGET) {
            if (y >= BACK_Y && x < 4 + BACK_W) return 44;
            for (int[] slot : TGT_SLOTS)
                if (x >= slot[1] && x < slot[1] + TGT_W && y >= slot[2] && y < slot[2] + TGT_H) return b.targetable[slot[0]] ? 70 + slot[0] : -1;
        } else if (b.mode == Game.Battle.MOVES || b.mode == Game.Battle.SWAP) {
            if (b.moveInfo) return 47; // a popup: any tap just closes it
            if (b.mode == Game.Battle.SWAP) { // reorder: a card = put the move there, Back = cancel
                if (y >= BACK_Y) return x < 4 + BACK_W ? 44 : -1;
                for (int i = 0; i < 4; i++) {
                    int cx = MOVE_X + (i % 2) * (MOVE_W + 4), cy = MOVE_Y + (i / 2) * (MOVE_H + 4);
                    if (b.moves[i] != 0 && x >= cx && x < cx + MOVE_W && y >= cy && y < cy + MOVE_H) return 78 + i;
                }
                return -1;
            }
            for (int i = 0; i < 4; i++) { // a card's (i) corner: info instead of using the move
                int cx = MOVE_X + (i % 2) * (MOVE_W + 4) + MOVE_W, cy = MOVE_Y + (i / 2) * (MOVE_H + 4);
                if (b.moves[i] != 0 && x >= cx - 22 && x < cx && y >= cy && y < cy + 22) return 74 + i;
            }
            if (y >= MOVE_Y && y < MOVE_Y + 2 * (MOVE_H + 4)) {
                int i = (y - MOVE_Y) / (MOVE_H + 4) * 2 + (x >= MOVE_X + MOVE_W + 2 ? 1 : 0);
                return b.moves[i] != 0 ? 40 + i : -1;
            }
            if (y >= BACK_Y) {
                if (x < 4 + BACK_W) return 44;
                if (b.canMega && x >= W - 4 - MEGA_W) return 45;
            }

        }
        return -1;
    }

    private void chooseTap(int hit) {
        if (hit == 88) { // Back: unpick, else cancel the switch
            if (game.choosePicked) game.chooseUnpick(); else game.chooseCancel();
            game.chime();
        } else if (hit == 89) { game.chooseButton = 1; game.chime(); game.chooseSwitch(game.chooseCursor); }
        else if (hit == 96) { game.chooseButton = 2; game.chime(); game.chooseSummary(game.chooseCursor); }
        else { game.chooseCursor = hit - 90; game.choosePicked = true; game.chooseButton = 1; game.chooseOnBack = false; game.chooseMessage = null; game.chime(); }
    }

    private void battleTap(int hit) {
        Game.Battle battle = game.battle;
        switch (hit) {
            case 30: game.battleAction(Game.B_ACTION_FIGHT); break;
            case 31: game.battleAction(Game.B_ACTION_BAG); break;
            case 32: game.battleAction(Game.B_ACTION_RUN); break;
            case 33: game.battleAction(Game.B_ACTION_POKEMON); break;
            case 34: game.battleQuickBall(); break;
            case 44: if (battle != null && battle.partyMenu && partyPick >= 0) { partyPick = -1; game.chime(); } else game.battleBack(); break;
            case 80: case 81: case 82: case 83: case 84: case 85: partyPick = hit - 80; game.chime(); break;
            case 86: {
                Game.State s = game.state;
                if (partyPick >= 0 && partyPick < s.party.length && s.party[partyPick].hp > 0 && battle != null && !battle.partyInBattle[partyPick])
                    game.battlePartySwitch(partyPick);
                break;
            }
            case 87: if (partyPick >= 0) { game.chime(); game.battlePartySummary(partyPick); } break;
            case 70: case 71: case 72: case 73: game.battleTarget(hit - 70); break;
            case 45: game.battleMega(); megaOn = !megaOn; break;
            case 47: game.toggleMoveInfo(); break;
            case 74: case 75: case 76: case 77: game.showMoveInfo(hit - 74); break;

            default: if (hit >= 40 && hit < 44) game.battleMove(hit - 40);
        }
    }

    // ---------- settings (main menu only) ----------
    private boolean saveOptions, romsPage, aboutPage; // settings sub-pages
    /** Rows: {label, value, action[, x-button action]}. Main page, Save options, or ROMs. */
    private String[][] settingsRows() {
        String user = host.raUser();
        if (romsPage) {
            java.util.ArrayList<String[]> rows = new java.util.ArrayList<>();
            String dir = host.romDir();
            rows.add(dir.isEmpty() ? new String[]{"ROM folder", "Choose…", "d"} : new String[]{"ROM folder", dir.substring(dir.lastIndexOf('/') + 1), "d", "D"});
            for (String[] r : host.roms()) {
                String v = r[0].equals(host.currentRom()) ? "Playing" : new java.io.File(r[0]).getName();
                rows.add(r[2].equals("file") ? new String[]{r[1], v, "p" + r[0], "r" + r[0]} : new String[]{r[1], v, "p" + r[0]});
            }
            rows.add(new String[]{"Add ROM…", "", "a"});
            rows.add(new String[]{"Patch a ROM…", "IPS UPS BPS", "h"});
            rows.add(new String[]{"Home shortcut", "this game", "s"});
            return rows.toArray(new String[0][]);
        }
        if (aboutPage) return new String[][]{
                {"DuoBoy Advance", "v" + BuildConfig.VERSION_NAME, "x"},
                {"Supported game", "Pokémon Unbound", "x"},
                {"More games", "Planned", "x"},
                {"ROMs", "Bring your own", "x"},
                {"Made by", "Wyatt Poole", "x"},
        };
        if (saveOptions) return new String[][]{
                {"Save folder", host.saveDir(), "3"},
                {"Save type", host.saveType().equals(".srm") ? ".srm (RetroArch)" : ".sav (mGBA)", "4"},
                {"Import save", "", "5"},
                {"Export save", "", "6"},
        };
        return new String[][]{
                {"RetroAchievements", user == null ? "Log in" : user, "0"},
                {"Hardcore", host.hardcore() ? "On" : "Off", "1"},
                {"ROMs", host.romName(), "9"},
                {"Save options", "", "8"},
                {"About", "", "10"},
                {user == null ? "" : "Log out RetroAchievements", "", "7"},
        };
    }

    private static final String[] REMOVE_ICON = {"#.....#", ".#...#.", "..#.#..", "...#...", "..#.#..", ".#...#.", "#.....#"};
    /** A small x (remove) in the text's ink + shadow. */
    private void removeIcon(int x, int y) {
        for (int pass = 0; pass < 2; pass++)
            for (int py = 0; py < 7; py++)
                for (int px = 0; px < 7; px++)
                    if (REMOVE_ICON[py].charAt(px) == '#') fb.setPixel(x + px + 1 - pass, y + py + 1 - pass, pass == 0 ? skin.textShadow : skin.textInk);
    }

    private void drawSettings() {
        fb.eraseColor(0xFF000000);
        strip(0, 22, 0);
        strip(40, 4, 22);
        text(saveOptions ? "Save options" : romsPage ? "ROMs" : aboutPage ? "About" : "Settings", 8, 4);
        String[][] rows = settingsRows();
        for (int i = 0; i < rows.length; i++) {
            if (rows[i][0].isEmpty()) continue;
            int y = ROW_Y + i * ROW_H + (pressed == 100 + i ? 1 : 0);
            window(4, y, W - 8, ROW_H - 2);
            int lw = text(fit(rows[i][0], W - 80), 12, y + 3);
            boolean x = rows[i].length > 3;
            int right = W - 12 - (x ? 16 : 0);
            String v = fit(rows[i][1], right - 24 - Math.min(lw, W - 80));
            text(v, right - textW(v), y + 3);
            if (x) removeIcon(W - 22, y + 5);
        }
        drawSettingsButton("Back", pressed == 199);
    }

    private void settingsAction(int row) {
        String[][] rows = settingsRows();
        if (row < 0 || row >= rows.length || rows[row][0].isEmpty()) return;
        String act = rows[row].length > 3 && lastTouchX >= W - 30 ? rows[row][3] : rows[row][2];
        switch (act.charAt(0)) { // ROMs page actions carry a path after the letter
            case 'd': host.pickRomDir(); return;
            case 'D': host.clearRomDir(); return;
            case 'p': host.playRom(act.substring(1)); return;
            case 'r': host.removeRom(act.substring(1)); return;
            case 'a': host.addRom(); return;
            case 's': host.addShortcut(); return;
            case 'h': host.patchRom(); return;
            case 'x': return; // About page: information only
        }
        switch (Integer.parseInt(act)) {
            case 8: saveOptions = true; break;
            case 9: romsPage = true; break;
            case 10: aboutPage = true; break;
            case 0: if (host.raUser() == null) host.raLogin(); break;
            case 1: host.toggleHardcore(); break;
            case 2: host.pickRom(); break;
            case 3: host.pickSaveDir(); break;
            case 4: host.toggleSaveType(); break;
            case 5: host.importSave(); break;
            case 6: host.exportSave(); break;
            case 7: if (host.raUser() != null) host.raLogout(); break;
        }
    }

    /** RetroAchievements popup: gold box across the top with the badge (downscaled into the pixel grid). */
    private void drawToast() {
        Ra.Toast t = Ra.toasts.peek();
        while (t != null && t.until < System.currentTimeMillis()) { Ra.toasts.poll(); t = Ra.toasts.peek(); }
        if (t == null) return;
        int x = 4, y = 2, w = W - 8, h = 44;
        window(x, y, w, h);
        int tx = x + 8;
        if (t.badge != null) { src.set(0, 0, t.badge.getWidth(), t.badge.getHeight()); dst.set(x + 6, y + 6, x + 38, y + 38); c.drawBitmap(t.badge, src, dst, null); tx = x + 42; }
        if (t.desc == null || t.desc.isEmpty()) text(fit(t.title, x + w - 8 - tx), tx, y + (h - 14) / 2); // one line: centred
        else { text(fit(t.title, x + w - 8 - tx), tx, y + 5); text(fit(t.desc, x + w - 8 - tx), tx, y + 22); }
    }

    // ---------- touch ----------
    private int lastTouchX, dragFrom = -1;
    private boolean swallowTouch, moved;
    private int bagDrag;
    private int tapX, tapY;
    private Game.Battle battle0() { return game.battle; }
    @Override public boolean onTouchEvent(MotionEvent e) {
        try { return touch(e); }
        catch (RuntimeException ex) { android.util.Log.e("gbads", "bottom screen touch failed", ex); pressed = -1; dragging = false; return true; }
    }
    private boolean touch(MotionEvent e) {
        int scale = Math.max(1, Math.min(getWidth() / W, getHeight() / H));
        int x = (int) ((e.getX() - (getWidth() - W * scale) / 2f) / scale), y = (int) ((e.getY() - (getHeight() - H * scale) / 2f) / scale);
        lastTouchX = x;
        // a message is up (popup, battler info, bag / party message): the first touch only clears it, like the game's text
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (infoBattler >= 0) game.infoOpen = true;
            swallowTouch = game.dismissMessage();
            if (game.infoCloseReq) { infoBattler = -1; game.infoCloseReq = false; }
            if (swallowTouch) { pressed = -1; dragFrom = -1; dragging = false; return true; }
        }
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && game.awaitingA && !game.resetPrompt && (battle0() != null || game.inGame)) {
            game.tapA(); swallowTouch = true; pressed = -1; return true; // dialogue waiting: a tap anywhere is A
        }
        if (swallowTouch) { if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) swallowTouch = false; return true; }
        int hit = -1;
        boolean inSetButton = x >= SET_X && x < SET_X + SET_W && y >= SET_Y && y < SET_Y + SET_H;
        Game.Battle battle = game.battle;
        if (game.resetPrompt) {
            if (x >= RP_X && x < RP_X + RP_W && y >= RP_Y + 6 && y < RP_Y + 6 + 4 * RP_ROW) hit = 60 + (y - RP_Y - 6) / RP_ROW;
        } else if (battle != null) hit = battleHit(battle, x, y);
        else if (game.inGame && game.state.party.length > 0) {
            if (y >= BAR_Y) { for (int i = 0; i < BUTTON_ENTRY.length; i++) if (x >= buttonX(i) - 6 && x < buttonX(i) + 38) hit = i; }
            else if (x >= toggleX - 2 && x < toggleX + TOGGLE_W + 2 && y < TOP_H) hit = 50; // party -> map -> area
            else if (owMenuHit(x, y) >= 0) hit = owMenuHit(x, y);
            else if (mapShown() && x >= SIDE_X && y >= TOP_H && y < BAR_Y) { // party column -> summary
                int slot = (y - TOP_H - 2) / SIDE_SLOT;
                if (slot >= 0 && slot < game.state.party.length) hit = 20 + slot;
            }
            else if (mapShown() && x >= MAP_FRAME_W && x < SIDE_X && y >= TOP_H && y < BAR_Y) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP && !moved) mapTap(x, y);
            }
            else if (owView == 2 && dexSpecies > 0 && y >= TOP_H && y < BAR_Y) hit = 199 + 1; // close the entry (200)
            else if (owView == 2 && y >= TOP_H && y < BAR_Y) {
                for (int i = 0; i < areaChips.size(); i++) { int[] ch = areaChips.get(i); if (x >= ch[0] && x < ch[0] + 56 && y >= ch[1] && y < ch[1] + 34 && ch[1] >= TOP_H - 20) hit = 201 + i; }
            }
            else if (owGiveFor >= 0 && y >= TOP_H && y < BAR_Y) {
                if (y < GIVE_Y && x >= W - 78) hit = 170;
                else if (y < GIVE_Y && x >= W - 78 - 58) hit = 171; // Take
                else if (y >= GIVE_Y) { giveTapIndex = (y - GIVE_Y + giveScroll) / GIVE_ROW; hit = 172; }
            }
            else if (partyShown() && y >= TOP_H + 4 && y < TOP_H + 4 + 3 * 45 && x >= 8 && x < 240) { // party card -> its menu
                int slot = ((y - TOP_H - 4) / 45) * 2 + (x >= 128 ? 1 : 0);
                if (slot < game.state.party.length) hit = 20 + slot;
            }
            else if (y < TOP_H && x < 40) hit = 10;          // trainer card
            else if (y < TOP_H && x >= W - 40) hit = 11;     // options
        } else if (settingsOpen) {
            if (inSetButton) hit = 199;
            else if (y >= ROW_Y && (y - ROW_Y) / ROW_H < settingsRows().length) hit = 100 + (y - ROW_Y) / ROW_H;
        } else if (game.onTitle && x >= COG_X - 6 && y >= COG_Y - 6) hit = 198;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (battle == null && game.inGame && game.state.fieldBusy && hit >= 0 && hit < 200 && !(game.state.startMenuOpen && hit < 12)) hit = -1; // greyed out (the bar still works over the start menu)
                dragFrom = hit; pressed = hit; dragLastY = y; dragging = false; moved = false; tapX = x; tapY = y; bagDrag = 0;
                boolean slotHit = hit >= 20 && hit < 26, menuRow = hit >= 160 && hit < 164;
                if (battle == null && owMenuSlot >= 0 && !slotHit && !menuRow) closeOwMenu();      // any other tap closes the menu
                if (battle == null && owSwitchFrom >= 0 && !slotHit) owSwitchFrom = -1;
                if (battle == null && slotHit) { int[] p = slotXY(hit - 20); downX = x; downY = y; grabDX = x - p[0]; grabDY = y - p[1]; }
                break;
            }
            case MotionEvent.ACTION_MOVE:
                if (battle == null && game.inGame && (partyShown() || mapShown()) && owGiveFor < 0 && owSwitchFrom < 0 && dragFrom >= 20 && dragFrom < 26) {
                    if (!dragging && game.state.fieldFree && Math.abs(x - downX) + Math.abs(y - downY) > 6) { dragging = true; dragHead = null; closeOwMenu(); game.chime(); } // pick up
                    if (dragging) { dragX = x; dragY = y; }
                }
                if (battle != null && battle.bagOpen && dragLastY >= 0 && y >= BAG_LIST_Y && y < DESC_Y && x >= BAG_X) { // drag the item list
                    bagDrag += dragLastY - y; dragLastY = y;
                    int n = game.bagList(game.bagPocket).size(), max = Math.max(0, n - Game.BAG_ROWS);
                    while (Math.abs(bagDrag) >= BAG_ROW_H) {
                        int step = bagDrag > 0 ? 1 : -1; bagDrag -= step * BAG_ROW_H;
                        game.bagTop = Math.max(0, Math.min(max, game.bagTop + step));
                    }
                    game.bagCursor = Math.max(game.bagTop, Math.min(game.bagCursor, game.bagTop + Game.BAG_ROWS - 1)); // keep the arrow on screen
                }
                else if (owGiveFor >= 0 && battle == null && dragLastY >= 0) { giveScroll = Math.max(0, Math.min(giveMax, giveScroll + dragLastY - y)); dragLastY = y; }
                else if (owView == 2 && battle == null && dragLastY >= 0 && y >= TOP_H && y < BAR_Y) { // drag-scroll the Area list
                    areaScroll = Math.max(0, Math.min(areaMaxScroll, areaScroll + dragLastY - y));
                    dragLastY = y;
                }
                if (Math.abs(x - tapX) + Math.abs(y - tapY) > 8) moved = true; // scrolling / dragging: no click at the end
                pressed = moved ? -1 : hit; break;
            case MotionEvent.ACTION_UP:
                boolean partyView = battle == null && game.inGame && (partyShown() || mapShown());
                if (dragging) { // drop: swap onto another slot, or slide back home
                    dragging = false;
                    int a = dragFrom - 20, fx = dragX - grabDX, fy = dragY - grabDY;
                    if (partyView && hit >= 20 && hit < 26 && hit != dragFrom) { int[] pb = slotXY(hit - 20); requestSwap(a, hit - 20, pb[0], pb[1], fx, fy); }
                    else { slideA = a; slideB = -1; slideFromAX = fx; slideFromAY = fy; slideStart = android.os.SystemClock.uptimeMillis(); }
                    pressed = -1; break;
                }
                if (moved) { pressed = -1; break; }
                if (hit == pressed && hit >= 0) {
                    performClick();
                    if (hit == 50) { owView = nextView(); owMenuSlot = -1; owSwitchFrom = -1; }
                    else if (hit >= 60 && hit < 64 && game.resetPrompt) { game.menuCursor = hit - 60; game.menuPick(hit - 60); }
                    else if (hit >= 30 && hit < 50) { if (battle != null) battleTap(hit); }
                    else if ((hit == 230 || hit == 231) && battle != null && battle.yesNo >= 0) game.battleYesNo(hit - 230); // the game plays its own select sound
                    else if (hit >= 70 && hit < 74) { if (battle != null && battle.mode == Game.Battle.TARGET) battleTap(hit); }
                    else if (hit >= 74 && hit < 78) { if (battle != null && battle.mode == Game.Battle.MOVES) battleTap(hit); }
                    else if (hit >= 78 && hit < 82 && battle != null && battle.mode == Game.Battle.SWAP) game.swapMoveTo(hit - 78);
                    else if (hit >= 80 && hit < 88) { if (battle != null && battle.partyMenu) battleTap(hit); }
                    else if (hit >= 88 && hit <= 96 && battle != null && battle.choosing) chooseTap(hit);
                    else if (hit >= 120 && hit < 160 && battle != null && battle.bagOpen) bagTap(hit);
                    else if (hit >= 180 && hit < 190 && battle != null && battle.mode == Game.Battle.ACTION) { infoBattler = hit == 189 ? -1 : hit - 180; game.chime(); }
                    else if (hit == 198) { settingsOpen = true; saveOptions = false; romsPage = false; aboutPage = false; }
                    else if (hit == 199) { if (saveOptions || romsPage || aboutPage) { saveOptions = false; romsPage = false; aboutPage = false; } else settingsOpen = false; }
                    else if (hit == 200 && dexSpecies > 0) { dexSpecies = -1; game.chime(); }
                    else if (hit > 200 && hit - 201 < areaChips.size() && owView == 2) { dexSpecies = areaChips.get(hit - 201)[2]; game.chime(); }
                    else if (hit >= 160 && hit < 164) owTap(hit);
                    else if (hit >= 170 && hit < 173 && owGiveFor >= 0) {
                        game.chime();
                        int slot = owGiveFor;
                        if (hit == 171) { if (slot < game.state.party.length && game.state.party[slot].heldItem != 0) { game.partyTakeItem(slot); owGiveFor = -1; } }
                        else if (hit == 172) {
                            java.util.List<int[]> items = game.holdableItems();
                            if (giveTapIndex >= 0 && giveTapIndex < items.size()) { game.partyGiveItem(slot, items.get(giveTapIndex)[0]); owGiveFor = -1; }
                        } else owGiveFor = -1;
                    }
                    else if (hit >= 100) settingsAction(hit - 100);
                    else if (hit >= 20 && hit < 26 && partyView) owTap(hit); // party cards and the map's party column alike
                    else if (hit >= 20) game.openMenu(Game.POKEMON, hit - 20);
                    else game.openMenu(hit < 10 ? BUTTON_ENTRY[hit] : hit == 10 ? Game.TRAINER_CARD : Game.SETTINGS);
                }
                pressed = -1; break;
            case MotionEvent.ACTION_CANCEL: pressed = -1; dragging = false; break;
        }
        return true;
    }
    @Override public boolean performClick() { return super.performClick(); }
}
