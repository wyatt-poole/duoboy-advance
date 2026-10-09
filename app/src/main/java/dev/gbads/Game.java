package dev.gbads;

import android.util.Log;

/**
 * Game-state snapshot + input macros. Runs on the emulation thread; UI reads the volatile snapshot.
 * Addresses are FireRed (BPRE) — CFRU/Unbound keep them. Other games get their own constants later.
 */
final class Game {
    static final int PARTY = 0x02024284, PARTY_COUNT = 0x02024029, SAVEBLOCK2_PTR = 0x0300500C;
    static final int SM_CURSOR = 0x020370F4, SM_COUNT = 0x020370F5, SM_ORDER = 0x020370F6;

    static final class Mon {
        int species, level, hp, maxHp, status, personality, heldItem;
        boolean egg; // species is then SPECIES_EGG, like GetMonData(MON_DATA_SPECIES2)
        String nick;
    }
    static boolean dexFlag(byte[] flags, int nat) { return nat > 0 && nat <= flags.length * 8 && (flags[(nat - 1) >> 3] >> ((nat - 1) & 7) & 1) != 0; }
    static final class State {
        Mon[] party = new Mon[0];
        int frameType;
        boolean hasMap;          // Town Map in the bag
        int mapX = -1, mapY = -1; // player on the region map (tile coords), -1 = not on this map
        int mapGroup = -1, mapNum = -1; // SaveBlock1 location
        boolean fieldFree; // in the field with control (drags only start then)
        boolean startMenuOpen; // the game's own start menu: the bottom bar still works then
        boolean fieldBusy; // not free for a while: the bottom party is greyed out and ignores taps
        int mapSec = -1;
        int swapSeq; // overworld swaps so far: the bottom screen slides the cards when this changes
        int hour; // RTC hour (Unbound keeps it at 0x03005EA0 +6)
        byte[] dexSeen = new byte[125], dexCaught = new byte[125]; // bit (n-1) for national number n
        int gender;
    }

    // ---- region map (FireRed engine, Unbound's Borrius) ----
    static final int SAVEBLOCK1_PTR = 0x03005008, MAP_HEADER = 0x02036DFC, BAG_POCKETS = 0x0203988C, ITEM_TOWN_MAP = 361;
    static final int MAPSEC_START = 0x58, SEC_CORNERS = 0x083F1E60, SEC_DIMS = 0x083F2178, SEC_COUNT = 0x318 / 4; // u16[2] each
    private final byte[] mapHeader = new byte[0x18], pockets = new byte[8 * 8];

    private boolean hasItem(int item) {
        Core.read(BAG_POCKETS, pockets);
        for (int p = 0; p < 8; p++) {
            int slots = le32(pockets, p * 8), cap = pockets[p * 8 + 4] & 0xFF;
            if (slots >>> 24 != 2 && slots >>> 24 != 3) break;
            byte[] items = new byte[cap * 4];
            Core.read(slots, items);
            for (int i = 0; i < cap; i++) if (le16(items, i * 4) == item) return true;
        }
        return false;
    }

    /** Same idea as GetPlayerPositionOnRegionMap: scale the player's tile position into the map section's box. */
    private int escKey = -1, escHeader, escAsked = -999;
    /** GetPlayerPositionOnRegionMap: outdoors from the map itself; indoors / underground from the escape warp (last outdoor spot). */
    private void regionMapPosition(State s) {
        Core.read(MAP_HEADER, mapHeader);
        int type = mapHeader[0x17] & 0xFF, sec = mapHeader[0x14] & 0xFF, layout = le32(mapHeader, 0), sb1 = Core.read32(SAVEBLOCK1_PTR);
        boolean outdoor = type == 1 || type == 2 || type == 3 || type == 5 || type == 6; // town, city, route, underwater, ocean
        boolean viaEscape = type == 4 || type == 7 || type == 8; // underground, unknown, indoor (the game's jump table)
        byte[] pos = new byte[8];
        int x = 0, y = 0;
        if (viaEscape) {
            Core.read(sb1 + 0x24, pos); // escapeWarp: group, num, warpId, pad, x, y
            int key = (pos[0] & 0xFF) << 8 | (pos[1] & 0xFF);
            if (key != escKey) { // the header lookup is Unbound-hooked: ask the game (Overworld_GetMapHeaderByGroupAndId)
                if (pendingCall == null && callDone == null && frame - escAsked > 60) {
                    escAsked = frame;
                    callGame(0x08055239, pos[0] & 0xFF, pos[1] & 0xFF, 0, 0, r -> { escHeader = r; escKey = key; });
                }
            } else if (escHeader >>> 25 == 4 || escHeader >>> 24 == 2) {
                layout = Core.read32(escHeader);
                if (type != 8 || sec == 0xC4) sec = Core.read8(escHeader + 0x14);
                x = (short) le16(pos, 4); y = (short) le16(pos, 6);
                outdoor = true;
            }
        } else if (outdoor) { Core.read(sb1, pos); x = (short) le16(pos, 0); y = (short) le16(pos, 2); }
        int idx = sec - MAPSEC_START;
        if (idx < 0 || idx >= SEC_COUNT) return;
        int cx = rom.u16(SEC_CORNERS + idx * 4), cy = rom.u16(SEC_CORNERS + idx * 4 + 2);
        int w = rom.u16(SEC_DIMS + idx * 4), h = rom.u16(SEC_DIMS + idx * 4 + 2);
        if (w == 0 || h == 0 || w > 30 || h > 20) return;
        int px = w / 2, py = h / 2; // ponytail: the section's centre until the escape header is known
        if (outdoor) {
            byte[] dim = new byte[8];
            Core.read(layout, dim);
            int mw = le32(dim, 0), mh = le32(dim, 4);
            px = Math.min(w - 1, Math.max(0, x / Math.max(1, mw / w)));
            py = Math.min(h - 1, Math.max(0, y / Math.max(1, mh / h)));
        }
        s.mapX = cx + px; s.mapY = cy + py;
    }

    final Rom rom;
    volatile State state = new State();
    /** A save is loaded and we're playing (seen the overworld since the last title screen / reset). */
    volatile boolean inGame;
    /** On the title screen / intro (not the save-select menu): the only place the Settings cog shows. */
    volatile boolean onTitle = true;
    private static final int[] TITLE_CB2 = {0, 0x08078915, 0x08078B9D, 0x080EC5B9, 0x080EC821, 0x080EC865, 0x080EC871, 0x080EC9D5};
    // Title, intro, copyright and main menu (FireRed; Unbound keeps them): anything here means "not in a game".
    private static final int[] OUT_OF_GAME_CB2 = {0, 0x0800C2D5, 0x0800C301, 0x0800C30D, 0x08078915, 0x08078B9D, 0x080EC5B9, 0x080EC821, 0x080EC865, 0x080EC871, 0x080EC9D5};
    private final byte[] partyBuf = new byte[600], sb2 = new byte[4], opt = new byte[2];
    private final byte[] smBuf = new byte[11];

    Game(Rom rom) { this.rom = rom; safeMode = !rom.testedBuild(); }
    /** An Unbound build we haven't tested, or a hook threw: battles keep the game's own menus, the party can't be edited. */
    volatile boolean safeMode;
    // Battle types we've played through: double, "is master", trainer, roamer, legendaries, scripted wild, partner. Anything else
    // (link, multi, Safari, Battle Tower, tutorials, ghost, e-Reader, Unbound's own extras) keeps the game's menus.
    // 0x400000: Unbound's in-game partner battles (a double where the partner's Pokémon is AI-controlled)
    private static final int SUPPORTED_BATTLES = 0x1 | 0x4 | 0x8 | 0x400 | 0x1000 | 0x2000 | 0x4000 | 0x20000 | 0x40000 | 0x400000;
    private boolean battleSupported() { return !safeMode && (Core.read32(0x02022B4C) & ~SUPPORTED_BATTLES) == 0; }

    /** Called once per frame on the emu thread. */
    private int lastCb2, lastFcb2, lastSmCb, lastStep = -1, lastCtrl;
    /** Debug: log every change of the callbacks the hook depends on (adb logcat -s gbads). */
    private void trace() {
        int cb2 = Core.read32(MAIN + 4), f = Core.read32(FIELD_CB2), sm = Core.read32(SM_CALLBACK);
        int st = target < 0 ? -1 : step;
        if (cb2 != lastCb2 || f != lastFcb2 || sm != lastSmCb || st != lastStep)
            Log.i("gbads", String.format("cb2=%08X fieldCb2=%08X smCb=%08X step=%d target=%d session=%b bar=%b", cb2, f, sm, st, target, touchSession, barOpen()));
        lastCb2 = cb2; lastFcb2 = f; lastSmCb = sm; lastStep = st;
        if (inBattle()) { // battle trace: all four controllers, active battler, target cursor (doubles debugging)
            byte[] cf = new byte[16], ab = new byte[1], mc = new byte[1];
            Core.read(CONTROLLER_FUNCS, cf); Core.read(0x02023BC4, ab); Core.read(0x03004FF4, mc);
            int sig = java.util.Arrays.hashCode(cf) * 31 + ab[0] * 7 + mc[0];
            if (sig != lastCtrl) Log.i("gbads", String.format("battle ctrl=[%08X %08X %08X %08X] active=%d target=%d",
                    le32(cf, 0), le32(cf, 4), le32(cf, 8), le32(cf, 12), ab[0], mc[0]));
            lastCtrl = sig;
        }
    }

    static final int MENU_CURSOR = 0x0203ADE6; // sMenu.cursorPos (yes/no: 0 = Yes, 1 = No)
    private int prevKeys, saveCancelFrames;

    // ---- battle (CFRU/Unbound) ----
    static final int CONTROLLER_FUNCS = 0x03004FE0, BATTLE_BUFFER_A = 0x02022BC4, BATTLE_MONS = 0x02023BE4;
    static final int ACTION_CURSOR = 0x02023FF8, MOVE_CURSOR = 0x02023FFC, TYPE_NAMES = 0x08A4EAD4;
    // Vanilla handler addresses and CFRU's replacements (CFRU itself checks both).
    static final int[] ACTION_FUNCS = {0x0802E439, 0x089F8175}, MOVE_FUNCS = {0x0802EA11, 0x089F9235};
    static final int[] TARGET_FUNCS = {0x0802E675, 0x089F8DE1}; // HandleInputChooseTarget (vanilla, CFRU)
    static final int HANDLE_MOVE_SWITCHING = 0x0802EF59; // vanilla, CFRU keeps it
    static final int MULTI_USE_CURSOR = 0x03004FF4, BATTLER_POSITIONS = 0x02023BD6, ABSENT_FLAGS = 0x02023D70;
    static final int B_ACTION_FIGHT = 0, B_ACTION_BAG = 1, B_ACTION_POKEMON = 2, B_ACTION_RUN = 3;

    static final class BattleMon {
        int species, hp, maxHp, level, type1, type2, status;
        /** Stat stages (6 = neutral): HP, Atk, Def, Spe, SpA, SpD, Acc, Eva. */
        int[] stages = new int[8];
        int ability;
        String nick = "";
    }
    static final class Battle {
        static final int WAIT = 0, ACTION = 1, MOVES = 2, TARGET = 3, SWAP = 4;
        /** SWAP: the move being moved is moveCursor; swapCursor is where it would go (gMultiUsePlayerCursor). */
        int swapCursor = -1;
        /** A battle-script Yes/No is waiting for input (yesnobox, nickname, learn-move prompts): its cursor, else -1. */
        int yesNo = -1;
        /** A move animation is playing: its attacker and target (gBattleAnimAttacker / gBattleAnimTarget), else -1. */
        int animAttacker = -1, animTarget = -1;
        /**
         * Each battler's sprite on the top screen, mirrored on the bottom: shown, offset from its home spot (GBA px),
         * affine scale (256 = 1x; send-out grows from the ball, withdraw shrinks into it) and the species it shows.
         */
        boolean[] sprShown = new boolean[4];
        int[] sprDx = new int[4], sprDy = new int[4], sprScale = {256, 256, 256, 256}, sprSpecies = new int[4];
        /** The game's choose-a-Pokémon screen is up (battle party menu), and which party slots are out in battle. */
        boolean partyMenu;
        /** Our own "choose a Pokémon" (step 2a): the battle is waiting on the bottom screen's pick. */
        boolean choosing;
        /** Our own bag (step 2b): the battle is waiting on an item from the bottom screen. */
        boolean bagOpen;
        /** Send-out / withdraw (the game's emerge / return affine anims) vs. a move just scaling the sprite (Growl). */
        boolean[] sprBall = new boolean[4];
        /** Trainer battles: the opponent's party for the status balls (null in wild battles). */
        Mon[] foeParty;
        /** Partner battles: all six player slots (yours 0-2, the partner's 3-5; null = empty), for the ball row. */
        Mon[] allyParty;
        /** Move info popup (L / Info) is open on the move screen. */
        boolean moveInfo;
        boolean[] partyInBattle = new boolean[6];
        /** The player battler currently choosing (0 = left / singles, 2 = right in doubles). */
        int battler;
        boolean doubles;
        /** Foes in on-screen order (left to right): battler ids, -1 if absent/fainted. Singles: {1, -1}. */
        int[] foes = {1, -1};
        /** Target select: the battler the cursor is on. */
        int target = -1;
        /** moveResults[position][move] for all four positions (CFRU), with each battler's position. */
        int[][] resultsByPos = new int[4][4];
        int[] position = {0, 1, 2, 3};
        /** All four battlers (doubles), and which of them are on the field. */
        BattleMon[] mons = new BattleMon[4];
        boolean[] present = new boolean[4];
        /** Target select: which battlers the chosen move may target (the game's cursor only visits these). */
        boolean[] targetable = new boolean[4];
        int mode;
        BattleMon player = new BattleMon(), foe = new BattleMon();
        // ChooseMoveStruct (gBattleBufferA[0] + 4), valid in MOVES mode
        int[] moves = new int[4], pp = new int[4], maxPp = new int[4], types = new int[4], power = new int[4], acc = new int[4], split = new int[4], result = new int[4];
        int monType1, monType2, monType3;
        boolean canMega;
        int actionCursor, moveCursor;
        /** Bottom-screen-only cursor row under the moves: 0 = none, 1 = Back, 2 = Mega. */
        int extraSel;
        boolean wild; // CFRU's Last Ball trigger only works outside trainer battles
        /** gBattle_BG0_X/Y off the message-box layout = the game is showing its action/move menu up top. */
        boolean menuOnTop, inBattleScreen;
        /** Unbound option "Quick Run" (CFRU VAR_QUICK_RUN_COMBO 0x50E3): R flees instantly, or B then A selects Run. */
        boolean quickRunWithR;
        /** CFRU created its Last Ball trigger, i.e. the game decided L will throw a ball right now. */
        boolean quickBall;
        /** Just chose an item / ball: keep the game's action menu covered until the battle takes over (no flash). */
        boolean topHold;
        /** Unfamiliar battle type (or safe mode): the top screen's own menus are used; the bottom only shows the field. */
        boolean passive;
        /** No game trigger (e.g. nothing thrown since boot) but a throw is allowed: our own Last Ball button's item. */
        int fallbackBall;
        /** That trigger's ball sprite, straight out of OBJ VRAM: 32x32 4bpp tiles + its palette (exactly the ball it'll throw). */
        byte[] ballTiles, ballPal;
    }
    volatile Battle battle; // null outside battle
    /** Battle intro in progress (field transition task running): the bottom screen mirrors the top's fade. */
    volatile boolean battleStarting;
    /** Average brightness of the last top-screen frame, 0..255 (set by the emu loop). */
    volatile int topLuma;
    static final int TASK_BATTLE_START = 0x0807F621, TASK_BATTLE_TRANSITION = 0x080D0979;
    private final byte[] moveInfo = new byte[88], bmon = new byte[0x58];
    private int battleKey, battleKeyTimer;

    private BattleMon battleMon(int i) {
        Core.read(BATTLE_MONS + i * 0x58, bmon);
        BattleMon m = new BattleMon();
        m.species = le16(bmon, 0); m.hp = le16(bmon, 0x28); m.level = bmon[0x2A] & 0xFF; m.maxHp = le16(bmon, 0x2C);
        m.nick = Rom.text(bmon, 0x30, 10);
        for (int k = 0; k < 8; k++) m.stages[k] = bmon[0x18 + k];
        m.ability = bmon[0x20] & 0xFF; m.type1 = bmon[0x21] & 0xFF; m.type2 = bmon[0x22] & 0xFF; m.status = le32(bmon, 0x4C);
        return m;
    }

    private void pollBattle() {
        if (!inBattle()) { battle = null; java.util.Arrays.fill(lastTarget, -1); chooseBattler = -1; bagBattler = -1; return; }
        Battle b = new Battle();
        b.passive = !battleSupported(); // unfamiliar battle type: hands off for the whole battle
        byte[] funcs = new byte[16];
        Core.read(CONTROLLER_FUNCS, funcs);
        interceptChoosePokemon(funcs);
        b.choosing = chooseBattler >= 0;
        b.bagOpen = bagBattler >= 0;
        if (b.choosing) chooseOrder = battlePartyOrder();
        // whichever player-side battler (0, then 2) is in an input handler is the one choosing
        for (int pb : new int[]{0, 2}) {
            int f = le32(funcs, pb * 4), mode = Battle.WAIT;
            for (int a : ACTION_FUNCS) if (f == a) mode = Battle.ACTION;
            for (int a : MOVE_FUNCS) if (f == a) mode = Battle.MOVES;
            for (int a : TARGET_FUNCS) if (f == a) mode = Battle.TARGET;
            if (f == HANDLE_MOVE_SWITCHING) mode = Battle.SWAP;
            if (b.passive) break; // the game's own menus run this one
            if (mode != Battle.WAIT) { b.mode = mode; b.battler = pb; break; }
        }
        b.doubles = (Core.read32(0x02022B4C) & 0x01) != 0; // BATTLE_TYPE_DOUBLE
        byte[] pos = new byte[4], absent = new byte[1], cur = new byte[1];
        Core.read(BATTLER_POSITIONS, pos);
        Core.read(ABSENT_FLAGS, absent);
        for (int i = 0; i < 4; i++) b.position[i] = pos[i] & 3;
        Core.read(ACTION_CURSOR + b.battler, cur); b.actionCursor = cur[0];
        Core.read(MOVE_CURSOR + b.battler, cur); b.moveCursor = cur[0];
        Core.read(MULTI_USE_CURSOR, cur); b.target = b.mode == Battle.TARGET ? cur[0] : -1;
        if (b.mode == Battle.SWAP) b.swapCursor = cur[0] & 3;
        b.player = battleMon(b.battler);
        b.foe = battleMon(1);
        for (int i = 0; i < 4; i++) {
            b.mons[i] = battleMon(i);
            b.present[i] = (b.doubles || i < 2) && ((absent[0] >> i) & 1) == 0 && b.mons[i].hp > 0;
        }
        if (b.doubles) { // on screen the opponent's "right" battler (3) stands left of its "left" one (1)
            b.foes = new int[]{3, 1};
            for (int i = 0; i < 2; i++) if (!b.present[b.foes[i]]) b.foes[i] = -1;
        }
        if (b.mode == Battle.MOVES || b.mode == Battle.TARGET || b.mode == Battle.SWAP) {
            Core.read(BATTLE_BUFFER_A + b.battler * 0x200 + 4, moveInfo);
            for (int i = 0; i < 4; i++) {
                b.moves[i] = le16(moveInfo, i * 2);
                b.pp[i] = moveInfo[8 + i] & 0xFF; b.maxPp[i] = moveInfo[12 + i] & 0xFF;
                b.types[i] = moveInfo[20 + i] & 0xFF;
                for (int p = 0; p < 4; p++) b.resultsByPos[p][i] = moveInfo[24 + p * 4 + i] & 0xFF;
                b.result[i] = b.resultsByPos[b.position[1]][i]; // singles: vs. battler 1
                b.power[i] = le16(moveInfo, 56 + i * 2);
                b.acc[i] = le16(moveInfo, 64 + i * 2);
                b.split[i] = moveInfo[72 + i] & 0xFF;
            }
            b.canMega = moveInfo[81] != 0;
            if (b.mode == Battle.TARGET) { // gBattleMoves[move].target: USER_OR_PARTNER = only your side, else anyone but you
                int move = b.moves[Math.max(0, Math.min(3, b.moveCursor))];
                int tgt = rom.u8(rom.u32(0x08000100 + 0xCC) + move * 12 + 6);
                for (int i = 0; i < 4; i++)
                    b.targetable[i] = b.present[i] && ((tgt & 2) != 0 ? (i & 1) == (b.battler & 1) : i != b.battler);
            }
            b.monType1 = moveInfo[18] & 0xFF; b.monType2 = moveInfo[19] & 0xFF; b.monType3 = moveInfo[80] & 0xFF;
        }
        if (b.mode == Battle.TARGET && lastBattleMode != Battle.TARGET && targetGoal < 0) {
            int want = lastTarget[b.battler & 3];
            if (want >= 0 && want != b.target && b.targetable[want]) { // silently, like the game's own default pick
                if (b.target >= 0) setTargetSpriteCb(b.target, HIDE_AS_TARGET);
                Core.write8(MULTI_USE_CURSOR, want);
                setTargetSpriteCb(want, SHOW_AS_TARGET);
                b.target = want;
            }
        }
        if (b.mode != lastBattleMode) extraSel = 0;
        lastBattleMode = b.mode;
        b.extraSel = extraSel;
        if (chimeRestore >= 0 && b.mode == Battle.MOVES) b.moveCursor = chimeRestore; // the nudge is undone next frame
        if (targetGoal >= 0 && b.mode == Battle.TARGET) b.target = targetGoal;         // don't show the walk's stepping stone
        b.wild = (Core.read32(0x02022B4C) & 0x08) == 0; // gBattleTypeFlags & BATTLE_TYPE_TRAINER
        scanBattleSprites(b);
        if (!b.quickBall && b.mode == Battle.ACTION) b.fallbackBall = fallbackBall(b.battler);
        b.inBattleScreen = Core.read32(MAIN + 4) == 0x08011101; // BattleMainCB2 (not the bag / party screens)
        b.menuOnTop = b.inBattleScreen && Core.read32(0x02022974) != 0; // gBattle_BG0_X | gBattle_BG0_Y << 16
        byte[] qr = new byte[2];
        Core.read(0x0203B374 + (0x50E3 - 0x5000) * 2, qr); // gExpandedVars[id - 0x5000]
        b.quickRunWithR = le16(qr, 0) != 1;
        mirrorBattlerSprites(b);
        byte[] anim = new byte[1];
        Core.read(0x02037EE1, anim); // gAnimScriptActive
        if (anim[0] != 0) {
            byte[] at = new byte[2];
            Core.read(0x02037F1A, at); // gBattleAnimAttacker, gBattleAnimTarget
            b.animAttacker = at[0] & 3; b.animTarget = at[1] & 3;
        }
        if ((Core.read32(0x02022B4C) & 0x400000) != 0) { // partner battle: the game reports only your count, the partner's are in slots 3-5
            Core.read(PLAYER_PARTY, enemyBuf);
            b.allyParty = new Mon[6];
            for (int i = 0; i < 6; i++) { Mon m = decodeMon(enemyBuf, i * 100); if (m.maxHp > 0) b.allyParty[i] = m; }
        }
        if (!b.wild) {
            Core.read(0x0202402C, enemyBuf); // gEnemyParty (Unbound leaves gEnemyPartyCount at 0: count real mons instead)
            java.util.ArrayList<Mon> foes = new java.util.ArrayList<>();
            for (int i = 0; i < 6; i++) { Mon m = decodeMon(enemyBuf, i * 100); if (m.maxHp > 0) foes.add(m); }
            b.foeParty = foes.toArray(new Mon[0]);
        }
        b.moveInfo = b.mode == Battle.MOVES && moveInfoOpen;
        if (b.mode != Battle.MOVES) moveInfoOpen = false;
        int cb2 = Core.read32(MAIN + 4);
        b.partyMenu = cb2 == CB2_UPDATE_PARTY_MENU && taskIndex(TASK_CHOOSE_MON) >= 0;
        byte[] bpi = new byte[8];
        Core.read(0x02023BCE, bpi); // gBattlerPartyIndexes (u16 per battler)
        for (int i = 0; i < 4; i += 2) if ((b.doubles || i == 0) && le16(bpi, i * 2) < 6) b.partyInBattle[le16(bpi, i * 2)] = true;
        activeBattler = b.battler;
        int instr = Core.read32(0x02023D74); // gBattlescriptCurrInstr; gBattleCommunication 0x02023E82: [0] state, [1] cursor
        if (instr >>> 25 == 4 || instr >>> 24 == 2) {
            int op = Core.read8(instr);
            if (b.inBattleScreen && battleSupported() && (op == 0x5A || op == 0x5B || op == 0x67 || op == 0xF3) && Core.read8(0x02023E82) == 0) {
                // about to draw its Yes/No box: do its setup without the drawing (the bottom screen has the buttons)
                Core.write8(0x02023E82, 1); Core.write8(0x02023E83, 0);
            }
            if (b.inBattleScreen && battleSupported() && (op == 0x5A || op == 0x5B || op == 0x67 || op == 0xF3) && Core.read8(0x02023E82) == 1) b.yesNo = Core.read8(0x02023E83);
        }
        if (topHold > 0) { // until the battle's own text starts (then its message is what should show)
            boolean printing = false;
            for (int i = 0; i < 32; i++) if (printers[i * 0x24 + 0x1B] != 0) printing = true;
            topHold = printing ? 0 : topHold - 1;
            b.topHold = topHold > 0;
        }
        battle = b;
    }
    private volatile int activeBattler;
    // Battler sprites: gBattlerSpriteIds; each is made from gMultiuseSpriteTemplate with data[0] = battler, data[2] = species.
    // Home x per position = sBattlerCoords (singles 72/176, doubles 32/200/90/152); offsets are measured from there.
    static final int MULTIUSE_TEMPLATE = 0x020244DC, OAM_MATRICES = 0x02021BCC;
    private static final int[] HOME_X_SINGLES = {72, 176, 72, 176}, HOME_X_DOUBLES = {32, 200, 90, 152};
    /** sBattlerCoords y per position (singles / doubles): where each battler stands on the top screen. */
    static final int[] HOME_Y_SINGLES = {80, 40, 80, 40}, HOME_Y_DOUBLES = {80, 40, 88, 32};
    static int homeX(Battle b, int who) { return (b.doubles ? HOME_X_DOUBLES : HOME_X_SINGLES)[b.position[who]]; }
    static int homeY(Battle b, int who) { return (b.doubles ? HOME_Y_DOUBLES : HOME_Y_SINGLES)[b.position[who]]; }
    private final byte[] oamMatrices = new byte[32 * 8];
    private void mirrorBattlerSprites(Battle b) {
        byte[] ids = new byte[4];
        Core.read(BATTLER_SPRITE_IDS, ids);
        // Animations that copy a battler into a background layer ("monbg") hide its sprite meanwhile; the copy is still on
        // screen, so those sprites count as shown. sMonAnimTaskIdArray: the copy tasks (0xFF = none), data[0] = sprite id.
        byte[] bgTasks = new byte[2];
        Core.read(0x02037F14, bgTasks);
        int bgSprite0 = -1, bgSprite1 = -1;
        for (int k = 0; k < 2; k++) {
            int t = bgTasks[k] & 0xFF;
            if (t >= 16) continue;
            byte[] task = new byte[10];
            Core.read(TASKS + t * 40, task);
            if (task[4] == 0) continue; // isActive
            if (k == 0) bgSprite0 = le16(task, 8); else bgSprite1 = le16(task, 8);
        }
        Core.read(OAM_MATRICES, oamMatrices);
        for (int i = 0; i < 4; i++) {
            int id = ids[i] & 0xFF;
            if (id >= SPRITE_COUNT) continue;
            int o = id * SPRITE_SIZE, flags = sprites[o + 0x3E];
            boolean mine = (flags & 1) != 0 && le16(sprites, o + 0x2E) == i && le32(sprites, o + 0x14) == MULTIUSE_TEMPLATE;
            if (!mine) continue;
            b.sprShown[i] = (flags & 4) == 0 || id == bgSprite0 || id == bgSprite1;
            b.sprSpecies[i] = le16(sprites, o + 0x32);
            int home = (b.doubles ? HOME_X_DOUBLES : HOME_X_SINGLES)[b.position[i]];
            b.sprDx[i] = (short) le16(sprites, o + 0x20) + (short) le16(sprites, o + 0x24) - home;
            b.sprDy[i] = (short) le16(sprites, o + 0x26);
            int attr0 = le16(sprites, o), attr1 = le16(sprites, o + 2);
            int affineAnim = sprites[o + 0x2D] & 0xFF;
            b.sprBall[i] = affineAnim == 1 || affineAnim == 2; // BATTLER_AFFINE_EMERGE / BATTLER_AFFINE_RETURN
            if ((attr0 >> 8 & 1) != 0) { // affine: scale = 256 / pa
                int pa = Math.abs((short) le16(oamMatrices, (attr1 >> 9 & 31) * 8));
                b.sprScale[i] = pa == 0 ? 0 : Math.min(512, 65536 / pa);
            }
        }
    }
    private int lastBattleMode = -1;
    /** Last target each player battler confirmed this battle (-1: none yet, so the game's default applies). */
    private final int[] lastTarget = {-1, -1, -1, -1};
    private final byte[] enemyBuf = new byte[600];
    volatile boolean moveInfoOpen;
    void toggleMoveInfo() { moveInfoOpen = !moveInfoOpen; chime(); }
    private volatile int infoSlot = -1;
    /** Tap on a move card's (i): hover that move (cursor written directly, like a tap does) and show its info. */
    void showMoveInfo(int slot) { infoSlot = slot; }
    private volatile int swapTap = -1;
    /** Reorder screen: tap where the move goes (cursor written, then the game's own A does the swap + sound). */
    void swapMoveTo(int slot) { swapTap = slot; }

    // CFRU battle trigger sprites (tile tags): its "can I throw the last ball" check decides whether these exist.
    static final int SPRITES = 0x0202063C, SPRITE_SIZE = 0x44, SPRITE_COUNT = 64;
    static final int TAG_LAST_BALL_TRIGGER = 0xFDFA, TAG_LAST_BALL_TRIGGER_BALL = 0xFDFB;
    private final byte[] sprites = new byte[SPRITE_COUNT * SPRITE_SIZE];
    private byte[] lastBallTiles, lastBallPal;
    private int lastBallTileNum = -1;

    /** Mirrors CFRU's Last Ball trigger on the bottom screen and hides it (and its ball) on the top screen. */
    private void scanBattleSprites(Battle b) {
        Core.read(SPRITES, sprites);
        int triggerCb = 0;
        for (int i = 0; i < SPRITE_COUNT; i++) { // the trigger has a real ROM template with its tile tag
            int o = i * SPRITE_SIZE, tmpl = le32(sprites, o + 0x14);
            if ((sprites[o + 0x3E] & 1) == 0 || (tmpl >>> 24 != 8 && tmpl >>> 24 != 9)) continue;
            if (rom.u16(tmpl) == TAG_LAST_BALL_TRIGGER) {
                triggerCb = le32(sprites, o + 0x1C);
                b.quickBall = (Core.read32(0x02022B4C) & 8) == 0; // never in trainer battles (BATTLE_TYPE_TRAINER)
                hide(i);
            }
        }
        if (triggerCb != 0)
            for (int i = 0; i < SPRITE_COUNT; i++) { // CFRU gives the ball the trigger's callback (SpriteCB_LastBallTrigger)
                int o = i * SPRITE_SIZE, tmpl = le32(sprites, o + 0x14);
                if ((sprites[o + 0x3E] & 1) == 0 || le32(sprites, o + 0x1C) != triggerCb) continue;
                if ((tmpl >>> 24 == 8 || tmpl >>> 24 == 9) && rom.u16(tmpl) == TAG_LAST_BALL_TRIGGER) continue;
                hide(i);
                int attr2 = le16(sprites, o + 4), tileNum = attr2 & 0x3FF, pal = attr2 >> 12;
                byte[] t = new byte[512], pl = new byte[32]; // re-read each scan: the ball's tiles can load after the trigger
                Core.read(0x06010000 + tileNum * 32, t);
                Core.read(0x05000200 + pal * 32, pl);
                if (!java.util.Arrays.equals(t, lastBallTiles) || !java.util.Arrays.equals(pl, lastBallPal)) { lastBallTiles = t; lastBallPal = pl; }
                lastBallTileNum = tileNum;
            }
        if (b.quickBall) { b.ballTiles = lastBallTiles; b.ballPal = lastBallPal; }
        else lastBallTileNum = -1;
    }
    private void hide(int sprite) {
        int o = sprite * SPRITE_SIZE;
        if ((sprites[o + 0x3E] & 4) == 0) Core.write8(SPRITES + o + 0x3E, (sprites[o + 0x3E] | 4) & 0xFF); // invisible up top
    }

    /** Bottom-screen battle input: set the game's cursor, then one A (or B / Start) press. */
    // Touch thread -> emu thread: one pending request, applied at the next poll.
    private volatile int pendingCursorAddr, pendingCursor, pendingKey;
    void battleAction(int action) { pendingCursorAddr = ACTION_CURSOR; pendingCursor = action; pendingKey = Core.A; }
    void battleMove(int slot) { pendingCursorAddr = MOVE_CURSOR; pendingCursor = slot; pendingKey = Core.A; }
    // ---- target select: the game's cursor (gMultiUsePlayerCursor) and its blinking battler sprite ----
    static final int BATTLER_SPRITE_IDS = 0x02023D44, TARGET_IDENTITIES = 0x08250980; // sTargetIdentities: the d-pad cycle order
    static final int SHOW_AS_TARGET = 0x08012045, HIDE_AS_TARGET = 0x08012099; // SpriteCb_Show/HideAsMoveTarget
    private volatile int targetGoal = -1;
    private volatile boolean targetConfirm, targetChime;
    private int targetStage, targetFrames, targetKey;
    /** Bottom-screen tap on a target: move the game's cursor there (one chime), then confirm. */
    void battleTarget(int battler) { startTarget(battler, true, false); }
    private void startTarget(int battler, boolean confirm, boolean chimeOnly) {
        targetConfirm = confirm; targetChime = chimeOnly; targetStage = 0; targetFrames = 0; targetGoal = battler;
    }
    private int battlerAtPosition(Battle b, int pos) {
        for (int i = 0; i < 4; i++) if (b.position[i] == pos) return i;
        return -1;
    }
    private void setTargetSpriteCb(int battler, int cb) {
        byte[] id = new byte[1];
        Core.read(BATTLER_SPRITE_IDS + battler, id);
        Core.write32(SPRITES + (id[0] & 0xFF) * SPRITE_SIZE + 0x1C, cb);
    }
    /**
     * Gets the cursor onto the goal with exactly one real d-pad press: if the cursor isn't already next to the goal in the
     * game's cycle, it's moved (with the game's own blink callbacks) to the neighbour first, so the press plays one chime
     * and the game redraws its effectiveness text itself. Then A when tapped.
     */
    private void runTargetWalk() {
        Battle b = battle;
        int goal = targetGoal;
        if (goal < 0) return;
        if (b == null || b.mode != Battle.TARGET || ++targetFrames > 40) { targetGoal = -1; return; }
        if (battleKeyTimer > 0) return; // a press is still in flight
        byte[] raw = new byte[1];
        Core.read(MULTI_USE_CURSOR, raw);
        int cur = raw[0];
        switch (targetStage) {
            case 0: {
                if (cur == goal && !targetChime) { targetStage = 2; runTargetWalk(); return; }
                if (targetConfirm) { // tap: jump straight there in silence; the A that follows is the only sound (like tapping a move)
                    setTargetSpriteCb(cur, HIDE_AS_TARGET);
                    Core.write8(MULTI_USE_CURSOR, goal);
                    setTargetSpriteCb(goal, SHOW_AS_TARGET); // let it run a frame so A's Hide restores the sprite cleanly
                    targetStage = 2;
                    return;
                }
                int gi = -1;
                for (int i = 0; i < 4; i++) if (rom.u8(TARGET_IDENTITIES + i) == b.position[goal]) gi = i;
                if (gi < 0) { targetGoal = -1; return; }
                int prev = battlerAtPosition(b, rom.u8(TARGET_IDENTITIES + (gi + 3) % 4)); // RIGHT from here lands on goal
                int next = battlerAtPosition(b, rom.u8(TARGET_IDENTITIES + (gi + 1) % 4)); // LEFT from here lands on goal
                int from = cur == prev || cur == next ? cur : prev >= 0 && b.present[prev] ? prev : next >= 0 && b.present[next] ? next : -1;
                if (from < 0 && cur == goal) { targetGoal = -1; return; } // chime-only with no neighbour to borrow: stay silent
                targetKey = from == next && from != prev ? Core.LEFT : Core.RIGHT;
                if (from >= 0 && from != cur) {
                    setTargetSpriteCb(cur, HIDE_AS_TARGET);
                    Core.write8(MULTI_USE_CURSOR, from);
                    setTargetSpriteCb(from, SHOW_AS_TARGET); // runs this frame, so the press's Hide restores it cleanly
                    targetStage = 1;
                    return;
                }
                // fall through: already beside the goal (or only two battlers left): press now
            }
            case 1:
                battleKey = targetKey; battleKeyTimer = 4; targetStage = 2;
                return;
            default:
                if (targetConfirm && cur == goal) { battleKey = Core.A; battleKeyTimer = 4; lastTarget[b.battler & 3] = goal; }
                targetGoal = -1;
        }
    }
    void battleBack() { pendingCursorAddr = 0; pendingKey = Core.B; }

    static final int PLAY_SE = 0x080722CD, CHIME_TASK = 5; // PlaySE(songNum): called with taskId 5 = SE_SELECT
    private volatile int chimeRequests;
    private int chimesDone, chimeLinked;
    /** Plays the game's own menu select sound once (touch taps that don't go through a game key press). */
    void chime() { chimeRequests++; }
    /**
     * Emu thread, between frames: links a task whose func is PlaySE into the game's task list (like CreateTask), lets one
     * RunTasks call it, then unlinks it again (like DestroyTask). Skipped if slot 5 is busy.
     */
    // Trampoline: a one-shot task (gets r0 = taskId) that loads r0..r3 + func from TRAMP_ARGS, calls func and stores r0.
    // ponytail: lives in EWRAM that was zero in every dump (0x0202938C.., 32 KB); written only right before a call.
    static final int TRAMP = 0x02030F00, TRAMP_ARGS = TRAMP + 0x40;
    // Runs once even when RunTasks runs twice in a frame (the battle does): skips if func is 0, clears func after the call.
    private static final int[] TRAMP_CODE = { // push{r4,lr}; ldr r4,=args; ldr r0,[r4,#16]; cmp r0,#0; beq done;
            0xB510, 0x4C09, 0x6920, 0x2800, 0xD00A, 0x6820, 0x6861, 0x68A2, 0x68E3, 0x6924, // ldr r0-r3; ldr r4,func
            0xF000, 0xF807, 0x4C03, 0x6160, 0x2000, 0x6120,  // bl bx_r4; ldr r4,=args; str r0,ret; func = 0
            0xBC10, 0xBC02, 0x4708, 0x4720};                   // done: pop{r4}; pop{r1}; bx r1; bx_r4: bx r4
    // ---- debug wild battles (test setups): the game's own script engine builds the foes ----
    private volatile int wildSpecies, wildLevel, wildSpecies2, wildStep, wildWait;
    private final byte[] wildTemp = new byte[100];
    private volatile int[] pendingScript;
    /** Debug: run raw script bytes (e.g. giveegg) in the field. */
    void debugScript(String hex) {
        int[] b = new int[hex.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        pendingScript = b;
    }
    /** species2 < 0: single battle; 0: double with two of species; else a double against species + species2. */
    void debugWild(int species, int level, int species2) { wildLevel = level; wildSpecies2 = species2; wildSpecies = species; wildStep = 0; }
    private void runScript(int... bytes) { // a tiny script in free RAM, run by ScriptContext_SetupScript
        int at = 0x02030FC0;
        for (int i = 0; i < bytes.length; i++) Core.write8(at + i, bytes[i]);
        callGame(0x08069AE5, at, 0, 0, 0, null);
    }
    private static final int ENEMY_PARTY = 0x0202402C;
    private void runDebugWild() {
        int sp = wildSpecies, lv = wildLevel;
        if (sp <= 0) return;
        boolean scriptDone = Core.read8(0x03000EB1) == 0 && pendingCall == null && callDone == null; // sGlobalScriptContext.mode
        if (wildWait > 0) { wildWait--; return; }
        switch (wildStep) {
            case 0:
                if (!fieldIdle()) { wildSpecies = 0; return; }
                if (wildSpecies2 < 0) { runScript(0xB6, sp & 0xFF, sp >> 8, lv, 0, 0, 0xB7, 0x02); wildSpecies = 0; return; } // setwildbattle; dowildbattle
                int sp2 = wildSpecies2 == 0 ? sp : wildSpecies2;
                runScript(0xB6, sp2 & 0xFF, sp2 >> 8, lv, 0, 0, 0x02); // the second foe first (setwildbattle clears the enemy party)
                wildStep = 1; wildWait = 4; return;
            case 1:
                if (!scriptDone) return;
                Core.read(ENEMY_PARTY, wildTemp);
                runScript(0xB6, sp & 0xFF, sp >> 8, lv, 0, 0, 0x02);
                wildStep = 2; wildWait = 4; return;
            case 2:
                if (!scriptDone) return;
                for (int i = 0; i < 100; i += 4) Core.write32(ENEMY_PARTY + 100 + i, le32(wildTemp, i));
                // setflag 0x9F9 (Unbound: next wild battle is a double); callnative DoStandardWildBattle; clearflag 0x9F9; end
                runScript(0x29, 0xF9, 0x09, 0x23, 0x49, 0xF7, 0x07, 0x08, 0x2A, 0xF9, 0x09, 0x02);
                wildSpecies = 0; wildStep = 0;
        }
    }
    private volatile int pendingYesNo = -1, pendingYesNoCursor = -1;
    // ---- tap anywhere to continue: dialogue waiting for A (text prompt arrow, or a script's waitbuttonpress) ----
    volatile boolean awaitingA;
    private volatile int tapA;
    void tapA() { tapA = 3; }
    private final byte[] printers = new byte[32 * 0x24];
    private boolean textWaiting() {
        Core.read(0x02020034, printers); // sTextPrinters: active +0x1B, state +0x1C (1 wait, 2 clear, 3 scroll: all wait for A)
        for (int i = 0; i < 32; i++) { int st = printers[i * 0x24 + 0x1C]; if (printers[i * 0x24 + 0x1B] != 0 && st >= 1 && st <= 3) return true; }
        int nat = Core.read32(0x03000EB4); // sGlobalScriptContext.nativePtr, mode 2 = native
        return Core.read8(0x03000EB1) == 2 && (nat == 0x08A0F275 || nat == 0x0806B899); // waitbuttonpress (Unbound's, vanilla's)
    }
    void battleYesNo(int choice) { pendingYesNo = choice; }
    private int[] pendingCall; // func, r0..r3
    private java.util.function.IntConsumer pendingCallDone, callDone;
    /** Runs func(a0..a3) inside the game on the next frame; done gets its return value (emu thread). */
    void callGame(int func, int a0, int a1, int a2, int a3, java.util.function.IntConsumer done) {
        pendingCall = new int[]{func, a0, a1, a2, a3}; pendingCallDone = done;
    }

    private void runChime() {
        if (oneShotSlot >= 0) { // it ran during the last frame: take it out again
            unlinkTask(oneShotSlot);
            int done = oneShotFunc;
            oneShotSlot = -1;
            if (done == (TRAMP | 1) && callDone != null) { java.util.function.IntConsumer cb = callDone; callDone = null; cb.accept(Core.read32(TRAMP_ARGS + 20)); }
            if (done == TRY_SWITCH_IN) { partyToFieldOrder(); afterTrySwitch(); }
            if (done == FREE_ALL_WINDOW_BUFFERS && summarySlot >= 0) {
                Core.write8(PARTY_MENU + 8, (Core.read8(PARTY_MENU + 8) & 0xF0) | 1); // menuType = PARTY_MENU_TYPE_IN_BATTLE
                Core.write8(PARTY_MENU + 9, summarySlot);
                Core.write32(MAIN + 4, CB2_SHOW_SUMMARY); // SetMainCallback2: sorts to battle order itself, opens the summary
                Core.write8(MAIN + 0x438, 0);             // gMain.state
                chooseSummaryOpen = true;
                summarySlot = -1;
            }
            return;
        }
        if (pendingCall != null) {
            int slot = freeTaskSlot();
            if (slot < 0) return;
            for (int i = 0; i < TRAMP_CODE.length; i += 2) Core.write32(TRAMP + i * 2, TRAMP_CODE[i] | TRAMP_CODE[i + 1] << 16);
            Core.write32(TRAMP + 0x28, TRAMP_ARGS);
            for (int i = 0; i < 4; i++) Core.write32(TRAMP_ARGS + i * 4, pendingCall[i + 1]);
            Core.write32(TRAMP_ARGS + 16, pendingCall[0]);
            Core.write32(TRAMP_ARGS + 20, 0);
            callDone = pendingCallDone; pendingCall = null; pendingCallDone = null;
            linkTask(TRAMP | 1, slot);
            return;
        }
        if (summaryPending >= 0) { // Summary from the bottom-screen party: the party menu's own CB2 for it (battle mode)
            // like OpenPartyMenuToChooseMon: free the battle's window buffers first (the summary needs that heap)
            if (linkTask(FREE_ALL_WINDOW_BUFFERS, freeTaskSlot())) { summarySlot = summaryPending; summaryPending = -1; }
            return;
        }
        if (chooseSwitchSlot >= 0) { // a Switch from the bottom-screen party: the game's own TrySwitchInPokemon
            int slot = chooseSwitchSlot;
            chooseSwitchSlot = -1;
            Core.write8(PARTY_MENU + 9, slot);            // gPartyMenu.slotId (GetCursorSelectionMonId)
            Core.write8(PARTY_MENU + 0xB, chooseCase);    // gPartyMenu.action (can't-switch / ability cases)
            Core.write8(PARTY_MENU_USE_EXIT_CB, 0);
            Core.write8(STRING_VAR4, 0xFF);
            partyToBattleOrder();
            if (linkTask(TRY_SWITCH_IN, freeTaskSlot())) return;
            partyToFieldOrder(); // couldn't schedule it: undo
        }
        if (chimesDone == chimeRequests) return;
        chimesDone = chimeRequests;
        linkTask(PLAY_SE, CHIME_TASK); // slot 5 -> PlaySE(5) = SE_SELECT; skipped if busy
    }
    private int oneShotSlot = -1, oneShotFunc;
    /** Like CreateTask, at the end of the list: func(taskId) runs on the next RunTasks. False if the slot is taken. */
    private boolean linkTask(int func, int slot) {
        if (slot < 0) return false;
        byte[] t = new byte[16 * 40];
        Core.read(TASKS, t);
        int me = slot * 40;
        if (t[me + 4] != 0) return false;
        int tail = 0xFE; // no tasks at all: we're the head
        for (int i = 0; i < 16; i++) if (t[i * 40 + 4] != 0 && (t[i * 40 + 6] & 0xFF) == 0xFF) tail = i;
        Core.write32(TASKS + me, func);
        Core.write8(TASKS + me + 5, tail);
        Core.write8(TASKS + me + 6, 0xFF);
        Core.write8(TASKS + me + 7, 0xFF); // priority: last
        Core.write8(TASKS + me + 4, 1);
        if (tail < 16) Core.write8(TASKS + tail * 40 + 6, slot);
        oneShotSlot = slot; oneShotFunc = func;
        return true;
    }
    /** Like DestroyTask: unlink from the list and free the slot. */
    private void unlinkTask(int slot) {
        byte[] t = new byte[16 * 40];
        Core.read(TASKS, t);
        int me = slot * 40, prev = t[me + 5] & 0xFF, next = t[me + 6] & 0xFF;
        if (prev < 16) Core.write8(TASKS + prev * 40 + 6, next);
        if (next < 16) Core.write8(TASKS + next * 40 + 5, prev);
        Core.write8(TASKS + me + 4, 0);
    }
    // UpdatePartyToBattleOrder / UpdatePartyToFieldOrder (vanilla party_menu), done directly between frames.
    // Display slot i of the battle party menu shows party index order(i) (gBattlePartyCurrentOrder nibbles).
    static final int PLAYER_PARTY = 0x02024284, BATTLE_PARTY_ORDER = 0x0203B0DC;
    private int[] battlePartyOrder() {
        byte[] o = new byte[3];
        Core.read(BATTLE_PARTY_ORDER, o);
        int[] out = new int[6];
        for (int i = 0; i < 6; i++) out[i] = ((i & 1) == 1 ? o[i / 2] & 0xF : (o[i / 2] >> 4) & 0xF) % 6;
        return out;
    }
    private void partyToBattleOrder() { reorderParty(true); }
    private void partyToFieldOrder() { reorderParty(false); }
    private void reorderParty(boolean toBattle) {
        int[] order = battlePartyOrder();
        byte[] p = new byte[600], out = new byte[600];
        Core.read(PLAYER_PARTY, p);
        for (int i = 0; i < 6; i++) {
            if (toBattle) System.arraycopy(p, order[i] * 100, out, i * 100, 100); // battle[i] = field[order(i)]
            else System.arraycopy(p, i * 100, out, order[i] * 100, 100);         // field[order(i)] = battle[i]
        }
        for (int i = 0; i < 600; i += 4) Core.write32(PLAYER_PARTY + i, le32(out, i));
    }
    /** For the bottom screen: display slot -> party index while choosing. */
    volatile int[] chooseOrder = {0, 1, 2, 3, 4, 5};

    private int freeTaskSlot() {
        byte[] t = new byte[16 * 40];
        Core.read(TASKS, t);
        for (int i = 15; i >= 0; i--) if (t[i * 40 + 4] == 0) return i;
        return -1;
    }

    // ---- step 2a: the battle's "choose a Pokémon" without the game's party menu (the top stays on the battle) ----
    // PlayerHandleChoosePokemon fades to black and parks the controller in OpenPartyMenuToChooseMon (task data[0] =
    // party action). We stop the fade, drop its task, park the controller on BattleControllerDummy and pick down here;
    // Switch runs the game's TrySwitchInPokemon (its checks, messages, party order), then WaitForMonSelection hands
    // the choice (or a cancel) back to the battle exactly as the party menu would.
    static final int OPEN_PARTY_TO_CHOOSE = 0x08030629, WAIT_FOR_MON_SELECTION = 0x08030685, CONTROLLER_DUMMY = 0x0802E311;
    static final int TRY_SWITCH_IN = 0x08127AC1, BATTLE_CONTROLLER_DATA = 0x03004FFC;
    static final int PARTY_MENU_USE_EXIT_CB = 0x0203B0C0, STRING_VAR4 = 0x02021D18;
    static final int PLTT_UNFADED = 0x020371F8, PLTT_FADED = 0x020375F8;
    static final int PARTY_ACTION_SEND_OUT = 1; // forced switch: no cancelling
    /** Battler whose choice we're holding (-1: none) and the party action the battle asked for. */
    volatile int chooseBattler = -1, chooseCase;
    /** Message the game produced for a refused switch ("... has no energy left to battle!"), or null. */
    volatile String chooseMessage;
    private volatile int chooseSwitchSlot = -1, chooseCancel, summaryPending = -1;
    private boolean chooseSummaryOpen;
    static final int SUMMARY_RETURN_TO_PARTY = 0x08122DBD, SET_CB2_RESHOW_BATTLE = 0x08030ADD, RESHOW_BATTLE = 0x08077765;
    void chooseSummary(int displaySlot) { summaryPending = displaySlot; }
    private int summarySlot = -1;
    static final int FREE_ALL_WINDOW_BUFFERS = 0x08003ECD;
    /** Summary opened from our party screen: return straight to the battle (not the party menu), in field order. */
    private void watchChooseSummary() {
        if (!chooseSummaryOpen) return;
        int cb2 = Core.read32(MAIN + 4);
        int sum = Core.read32(SUMMARY_PTR);
        if (sum >>> 24 == 2 && Core.read32(sum + 0x32F8) == SUMMARY_RETURN_TO_PARTY) Core.write32(sum + 0x32F8, SET_CB2_RESHOW_BATTLE);
        if (cb2 == SET_CB2_RESHOW_BATTLE || cb2 == RESHOW_BATTLE || cb2 == 0x08011101) { // left the summary
            partyToFieldOrder();
            chooseSummaryOpen = false;
        }
    }
    void chooseSwitch(int slot) { chooseMessage = null; chooseSwitchSlot = slot; }
    void chooseCancel() { if (chooseCanBack()) chooseCancel = 1; }
    // PlayerHandleChooseItem fades to black and parks the controller in OpenBagAndChooseItem; CompleteWhenChoseItem then
    // returns gSpecialVar_ItemId (0 = cancelled). The bag's own use callbacks remove the item / apply its effect first.
    static final int OPEN_BAG_TO_CHOOSE = 0x08030701, COMPLETE_WHEN_CHOSE_ITEM = 0x0803073D, SPECIAL_VAR_ITEM_ID = 0x0203AD30;
    static final int ITEMS = 0x08876200, ITEM_SIZE = 0x2C;
    static final int USE_BALL = 0x080A1E1D, USE_MEDICINE = 0x080A1FBD, USE_STAT_UP = 0x080A1E7D; // battleUseFunc values
    static final int EXECUTE_ITEM_EFFECT = 0x080413C1, UPDATE_HEALTHBOX = 0x08049D99, HEALTHBOX_IDS = 0x03004FF0;
    volatile int bagBattler = -1;
    volatile String bagMessage;
    volatile int bagPocket, bagCursor, bagTop, bagPartyCursor;
    volatile boolean bagTargeting, bagOnBack;
    private volatile int bagUseItem, bagUseSlot = -1, bagCancelReq;
    static final int[] BAG_POCKET_IDS = {0, 2, 4}; // gBagPockets index: Items, Balls, Berries
    /** Battle-usable items in a pocket as {item, qty} (Items / Berries: only those with a battle use). */
    java.util.List<int[]> bagList(int pocketIdx) {
        java.util.ArrayList<int[]> out = new java.util.ArrayList<>();
        int pk = BAG_POCKET_IDS[pocketIdx];
        int ptr = Core.read32(BAG_POCKETS + pk * 8), cap = Core.read8(BAG_POCKETS + pk * 8 + 4);
        if (ptr >>> 24 != 2 || cap == 0) return out;
        byte[] slots = new byte[cap * 4];
        Core.read(ptr, slots);
        for (int i = 0; i < cap; i++) {
            int item = le16(slots, i * 4), qty = le16(slots, i * 4 + 2);
            if (item == 0 || qty == 0) continue;
            if (pocketIdx != 1 && rom.u8(ITEMS + item * ITEM_SIZE + 32) == 0) continue;
            out.add(new int[]{item, qty});
        }
        return out;
    }
    int itemBattleUsage(int item) { return rom.u8(ITEMS + item * ITEM_SIZE + 32); }
    int itemBattleFunc(int item) { return rom.u32(ITEMS + item * ITEM_SIZE + 36); }
    String itemName(int item) { // long names: the field starts with a pointer to the full name
        int o = ITEMS + item * ITEM_SIZE, ptr = rom.u32(o);
        return ptr >>> 25 == 4 ? rom.text(ptr & 0x1FFFFFF, 24) : rom.text(o, 14);
    }
    String itemDescription(int item) { int p = rom.u32(ITEMS + item * ITEM_SIZE + 20); return rom.isRom(p) ? rom.text(p, 120) : ""; }
    void bagCancel() { bagCancelReq = 1; }
    /** Use: balls / X items directly (slot -1), medicine on a party member (field index). */
    void bagUse(int item, int slot) { bagUse(item, slot, -1); }
    void bagUse(int item, int slot, int move) {
        if (slot >= 0 && slot < state.party.length && state.party[slot].egg) { bagMessage = "It won't have any effect."; return; }
        if ((item == 34 || item == 35) && move < 0) { bagMoveSlot = slot; bagMessage = PROMPT_MOVE; return; } // Ether / Max Ether
        bagMessage = null; bagUseSlot = slot; bagUseMove = Math.max(0, move); bagMoveSlot = -1; bagUseItem = item;
    }
    /** Ether / Max Ether: the party slot whose move list is showing (-1: none). */
    volatile int bagMoveSlot = -1;
    private volatile int bagUseMove;
    /** A party member's moves and current PP (CFRU/Unbound: attacks substruct right after growth, unencrypted). */
    int[][] partyMoves(int slot) {
        byte[] m = new byte[100];
        Core.read(PLAYER_PARTY + slot * 100, m);
        int[][] out = new int[4][2];
        for (int i = 0; i < 4; i++) { out[i][0] = le16(m, 44 + i * 2); out[i][1] = m[52 + i] & 0xFF; }
        return out;
    }
    private void removeBagItem(int item) { // RemoveBagItem(item, 1), then close the gap like the bag's compaction
        for (int pk : BAG_POCKET_IDS) {
            int ptr = Core.read32(BAG_POCKETS + pk * 8), cap = Core.read8(BAG_POCKETS + pk * 8 + 4);
            if (ptr >>> 24 != 2) continue;
            byte[] slots = new byte[cap * 4];
            Core.read(ptr, slots);
            for (int i = 0; i < cap; i++) {
                if (le16(slots, i * 4) != item || le16(slots, i * 4 + 2) == 0) continue;
                int qty = le16(slots, i * 4 + 2) - 1;
                if (qty > 0) { Core.write32(ptr + i * 4, item | qty << 16); return; }
                for (int j = i; j < cap - 1; j++) Core.write32(ptr + j * 4, le32(slots, (j + 1) * 4));
                Core.write32(ptr + (cap - 1) * 4, 0);
                return;
            }
        }
    }
    private int topHold;
    private void bagFinish(int item) {
        topHold = 45;
        Core.write8(SPECIAL_VAR_ITEM_ID, item & 0xFF); Core.write8(SPECIAL_VAR_ITEM_ID + 1, item >> 8);
        Core.write32(CONTROLLER_FUNCS + bagBattler * 4, COMPLETE_WHEN_CHOSE_ITEM);
        bagBattler = -1; bagTargeting = false;
    }
    // ItemUseInBattle_PokeBall (vanilla 0x080A1E1C + Unbound's 0x089C94C0), check by check, each the game's own function:
    // {function, message}: PC full; can't throw yet; can't be caught; (doubles) two Pokémon out; target not in sight.
    private static final int[][] BALL_CHECKS = {{0x08040F6D, 0x08416631}, {0x089C8819, 0x08A4C9EC}, {0x089C9495, 0x08A4CA0F},
            {0x089C878D, 0x08A4C91F}, {0x089C8731, 0x08A4C962}};
    private void ballCheck(int item, int k) {
        boolean doubles = (Core.read32(0x02022B4C) & 1) != 0;
        if (k < BALL_CHECKS.length && (k < 3 || doubles)) {
            callGame(BALL_CHECKS[k][0], 0, 0, 0, 0, r -> {
                if ((r & 0xFF) != 0) bagMessage = romMessage(BALL_CHECKS[k][1]);
                else ballCheck(item, k + 1);
            });
            return;
        }
        if (doubles) { // "must be thrown from your first Pokémon": unless it fainted or is using an item itself
            int first = 0, chosen = Core.read8(0x02023D7C + first); // GetBattlerAtPosition(PLAYER_LEFT); gChosenActionByBattler
            byte[] hp = new byte[2]; Core.read(0x02023BE4 + first * 0x58 + 0x28, hp);
            if (le16(hp, 0) != 0 && chosen != 1 && chosen != 0xD) { bagMessage = romMessage(0x08A4C9AB); return; }
        }
        removeBagItem(item); bagFinish(item); lastBallUsed = item;
    }
    /** A game message, up to its wait/end control codes. */
    private String romMessage(int addr) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 160; i++) { int c = rom.u8(addr + i); if (c == 0xFF || c == 0xFC) break; b.append(c == 0xFE || c == 0xFA || c == 0xFB ? ' ' : Rom.DEC[c]); }
        return b.toString();
    }
    private void runBag() {
        if (bagBattler < 0) return;
        if (bagCancelReq != 0) { bagCancelReq = 0; bagFinish(0); return; }
        if (autoBall != 0 && pendingCall == null && callDone == null) { int a = autoBall; autoBall = 0; bagUse(a, -1); } // Last Ball button
        int item = bagUseItem;
        if (item == 0 || pendingCall != null || callDone != null) return;
        bagUseItem = 0;
        int func = itemBattleFunc(item), slot = bagUseSlot;
        if (func == USE_BALL) { ballCheck(item, 0); return; } // the game's own refusals first, like its ball handler
        if (slot < 0) { byte[] bpi = new byte[2]; Core.read(0x02023BCE + bagBattler * 2, bpi); slot = le16(bpi, 0); } // X items: the user
        final int idx = slot, it = item;
        int mon = PLAYER_PARTY + idx * 100;
        android.util.Log.i("gbads", "item effect before: status=" + Integer.toHexString(Core.read32(mon + 80)) + " hp=" + Core.read32(mon + 84));
        callGame(EXECUTE_ITEM_EFFECT, mon, item, idx, bagUseMove, ret -> {
            android.util.Log.i("gbads", "item effect item=" + it + " slot=" + idx + " status=" + Integer.toHexString(Core.read32(mon + 80)) + " ret=" + Integer.toHexString(ret));
            if ((ret & 0xFF) != 0) { bagMessage = "It won't have any effect."; return; }
            removeBagItem(it);
            byte[] bpi = new byte[8];
            Core.read(0x02023BCE, bpi);
            int battler = -1;
            for (int k = 0; k < 4; k += 2) if (le16(bpi, k * 2) == idx) battler = k;
            if (battler >= 0 && func == USE_MEDICINE) { // the healthbox up top shows the new HP / status
                int hb = Core.read8(HEALTHBOX_IDS + battler);
                callGame(UPDATE_HEALTHBOX, hb, mon, 0, 0, r -> bagFinish(it));
            } else bagFinish(it);
        });
    }

    private void interceptChoosePokemon(byte[] funcs) {
        if (!battleSupported()) return; // unfamiliar battle types keep the game's own menus
        if (bagBattler < 0 && chooseBattler < 0)
            for (int pb : new int[]{0, 2}) {
                if (le32(funcs, pb * 4) != OPEN_BAG_TO_CHOOSE) continue;
                Core.write8(PALETTE_FADE + 7, Core.read8(PALETTE_FADE + 7) & 0x7F);
                byte[] pal = new byte[0x400];
                Core.read(PLTT_UNFADED, pal);
                for (int i = 0; i < 0x400; i += 4) Core.write32(PLTT_FADED + i, le32(pal, i));
                Core.write32(CONTROLLER_FUNCS + pb * 4, CONTROLLER_DUMMY);
                bagMessage = null; bagCursor = 0; bagTop = 0; bagTargeting = false; bagOnBack = false; bagInputDelay = 20;
                bagBattler = pb;
                return;
            }
        if (chooseBattler >= 0) {
            if (chooseCancel != 0) { // cancel = WaitForMonSelection with no exit callback (PARTY_SIZE)
                chooseCancel = 0;
                Core.write8(PARTY_MENU_USE_EXIT_CB, 0);
                Core.write32(CONTROLLER_FUNCS + chooseBattler * 4, WAIT_FOR_MON_SELECTION);
                chooseBattler = -1;
            }
            return;
        }
        for (int pb : new int[]{0, 2}) {
            if (le32(funcs, pb * 4) != OPEN_PARTY_TO_CHOOSE) continue;
            byte[] id = new byte[1];
            Core.read(BATTLE_CONTROLLER_DATA + pb, id);
            int task = id[0] & 0xFF;
            if (task >= 16) return;
            byte[] data0 = new byte[2];
            Core.read(TASKS + task * 40 + 8, data0);
            int action = le16(data0, 0);
            if (action == 3 || action > 4) return; // anything unexpected: let the game's own menu handle it
            // undo the fade to black it just started, drop its holder task, park the controller
            Core.write8(PALETTE_FADE + 7, Core.read8(PALETTE_FADE + 7) & 0x7F);
            byte[] pal = new byte[0x400];
            Core.read(PLTT_UNFADED, pal);
            for (int i = 0; i < 0x400; i += 4) Core.write32(PLTT_FADED + i, le32(pal, i));
            unlinkTask(task);
            Core.write32(CONTROLLER_FUNCS + pb * 4, CONTROLLER_DUMMY);
            chooseCase = action; chooseMessage = null; chooseCancel = 0; chooseCursor = firstPickable(); choosePicked = false;
            chooseOnBack = false; chooseButton = 1; chooseInputDelay = 40; // A-mashing through text can't pick for you
            chooseBattler = pb;
            return;
        }
    }
    private void afterTrySwitch() {
        byte[] ok = new byte[1];
        Core.read(PARTY_MENU_USE_EXIT_CB, ok);
        if (ok[0] == 1 && chooseBattler >= 0) { // accepted: hand it to the battle
            Core.write32(CONTROLLER_FUNCS + chooseBattler * 4, WAIT_FOR_MON_SELECTION);
            chooseBattler = -1;
        } else { // refused: show the game's own message down here
            byte[] msg = new byte[120];
            Core.read(STRING_VAR4, msg);
            chooseMessage = plainText(msg);
        }
    }
    private static final int[] FC_ARGS = {0, 1, 1, 1, 3, 1, 1, 0, 1, 0, 0, 2, 1, 1, 0, 0, 2, 1, 1, 1, 1, 0, 0, 0, 0};
    /** Game text without control codes (FC xx.., FD xx), line breaks as spaces. */
    private static String plainText(byte[] b) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            int c = b[i] & 0xFF;
            if (c == 0xFF) break;
            if (c == 0xFC) { // control code + its arguments (colour 1-3: 1, all three: 3, pause / sound etc.: 1-2)
                int code = ++i < b.length ? b[i] & 0xFF : 0;
                i += code < FC_ARGS.length ? FC_ARGS[code] : 0;
                continue;
            }
            if (c == 0xFD) { i++; continue; }
            if (c == 0xFE || c == 0xFA || c == 0xFB) { out.append(' '); continue; }
            out.append(Rom.DEC[c]);
        }
        return out.toString().trim();
    }

    // bottom-screen party cursor while choosing (d-pad: two columns, like the game's)
    volatile int chooseCursor;
    volatile boolean choosePicked;
    /** Cursor start: the first Pokémon (in battle order) that could come in, else the top-left card. */
    private int firstPickable() {
        int[] order = battlePartyOrder();
        byte[] bpi = new byte[8];
        Core.read(0x02023BCE, bpi); // gBattlerPartyIndexes
        Mon[] party = state.party;
        for (int i = 0; i < party.length; i++) {
            int idx = order[i];
            if (idx >= party.length || party[idx].hp == 0) continue;
            boolean out = false;
            for (int k = 0; k < 4; k += 2) if (le16(bpi, k * 2) == idx) out = true;
            if (!out) return i;
        }
        return 0;
    }
    private int chooseInputDelay;
    private int bagInputDelay;
    static final int BAG_ROWS = 4; // leaves room for 4 lines of description
    /** Bag d-pad: Left/Right (or L/R) pocket, Up/Down item, A use (medicine: then pick a Pokémon), B back. */
    private int bagKeys(int pressedNow) {
        if (bagInputDelay > 0) { bagInputDelay--; return 0; }
        if (pressedNow == 0) return 0;
        boolean up = (pressedNow & Core.UP) != 0, down = (pressedNow & Core.DOWN) != 0;
        if (bagMoveSlot >= 0) { // Ether: pick the move
            if (up) bagPartyCursor = (bagPartyCursor + 3) % 4;
            if (down) bagPartyCursor = (bagPartyCursor + 1) % 4;
            if ((pressedNow & Core.A) != 0) { java.util.List<int[]> l = bagList(bagPocket); if (bagCursor < l.size()) bagUse(l.get(bagCursor)[0], bagMoveSlot, bagPartyCursor); }
            if ((pressedNow & Core.B) != 0) { bagMoveSlot = -1; bagMessage = null; }
            chime();
            return 0;
        }
        if (bagTargeting) {
            int n = Math.max(1, state.party.length);
            if (up) bagPartyCursor = (bagPartyCursor + n - 1) % n;
            if (down) bagPartyCursor = (bagPartyCursor + 1) % n;
            if ((pressedNow & Core.A) != 0) { java.util.List<int[]> l = bagList(bagPocket); if (bagCursor < l.size()) bagUse(l.get(bagCursor)[0], bagPartyCursor); }
            if ((pressedNow & Core.B) != 0) { bagTargeting = false; bagMessage = null; }
            chime();
            return 0;
        }
        if (bagOnBack) { // the Back button under the list
            if (up) bagOnBack = false;
            if ((pressedNow & (Core.A | Core.B)) != 0) bagCancel();
            chime();
            return 0;
        }
        if ((pressedNow & (Core.LEFT | Core.L)) != 0) bagSetPocket((bagPocket + 2) % 3);
        if ((pressedNow & (Core.RIGHT | Core.R)) != 0) bagSetPocket((bagPocket + 1) % 3);
        int count = bagList(bagPocket).size();
        if (up && bagCursor > 0) bagCursor--;
        if (down && bagCursor + 1 < count) bagCursor++;
        else if (down) bagOnBack = true;
        if (bagCursor < bagTop) bagTop = bagCursor;
        if (bagCursor >= bagTop + BAG_ROWS) bagTop = bagCursor - BAG_ROWS + 1;
        if ((pressedNow & Core.A) != 0) bagActivate();
        if ((pressedNow & Core.B) != 0) bagCancel();
        chime();
        return 0;
    }
    void bagSetPocket(int p) { bagPocket = p; bagCursor = 0; bagTop = 0; bagMessage = null; bagTargeting = false; bagMoveSlot = -1; }
    /** A / Use on the selected item: medicine asks for a Pokémon, everything else goes straight in. */
    static final String PROMPT_MON = "Use on which Pokémon?", PROMPT_MOVE = "Restore which move?";
    void bagActivate() {
        java.util.List<int[]> l = bagList(bagPocket);
        if (bagCursor >= l.size()) return;
        int item = l.get(bagCursor)[0];
        if (itemBattleUsage(item) == 1) { bagTargeting = true; bagPartyCursor = 0; bagMessage = PROMPT_MON; }
        else bagUse(item, -1);
    }

    /** Picked: 0 Back, 1 Switch, 2 Summary. Browsing: chooseOnBack = the Back button under the cards. */
    volatile int chooseButton = 1;
    volatile boolean chooseOnBack;
    /** Forced send-outs can't be cancelled (the game would send out an empty slot; returning to "Use next Pokémon?" needs its own path). */
    boolean chooseCanBack() { return chooseCase != PARTY_ACTION_SEND_OUT; }
    void chooseUnpick() { choosePicked = false; chooseMessage = null; }
    private int chooseKeys(int keys, int pressedNow) {
        if (chooseInputDelay > 0) { chooseInputDelay--; return 0; }
        int count = Math.max(1, state.party.length);
        boolean up = (pressedNow & Core.UP) != 0, down = (pressedNow & Core.DOWN) != 0;
        boolean left = (pressedNow & Core.LEFT) != 0, right = (pressedNow & Core.RIGHT) != 0;
        boolean a = (pressedNow & Core.A) != 0, bb = (pressedNow & Core.B) != 0;
        if (choosePicked) {
            int n = chooseButton;
            if (left) n = Math.max(0, n - 1);
            if (right) n = Math.min(2, n + 1);
            if (n != chooseButton) { chooseButton = n; chime(); }
            if (a) {
                chime();
                if (chooseButton == 0) chooseUnpick();
                else if (chooseButton == 1) chooseSwitch(chooseCursor);
                else chooseSummary(chooseCursor);
            }
            if (bb || up) { chooseUnpick(); chime(); }
            return 0;
        }
        if (chooseOnBack) {
            if (up) { chooseOnBack = false; chime(); }
            if (a || bb) { chime(); chooseCancel(); }
            return 0;
        }
        int c = chooseCursor, n = c;
        if (left && (c & 1) == 1) n = c - 1;
        if (right && (c & 1) == 0 && c + 1 < count) n = c + 1;
        if (up && c >= 2) n = c - 2;
        if (down) {
            if (c + 2 < count) n = c + 2;
            else if (chooseCanBack()) { chooseOnBack = true; chime(); }
        }
        if (n != c) { chooseCursor = n; chime(); }
        if (a) { choosePicked = true; chooseButton = 1; chooseMessage = null; chime(); }
        if (bb && chooseCanBack()) { chime(); chooseCancel(); }
        return 0; // the battle's controller is parked; nothing else should see these keys
    }
    // ---- battle party menu (USUM-style picker on the bottom): Summary jumps straight in, Switch = slot + A (Shift) + A ----
    private volatile int partySummarySlot = -1, partySwitchSlot = -1;
    private int partySwitchStage, partySwitchTimer, partySwitchWait;
    void battlePartySummary(int slot) { partySummarySlot = slot; }
    void battlePartySwitch(int slot) { partySwitchStage = 0; partySwitchTimer = 0; partySwitchWait = 0; partySwitchSlot = slot; }
    static final int TASK_SELECTION_MENU = 0x08122C5D;
    static final int CURSOR_CB_SEND_MON = 0x081240F5; // the battle party menu's "Shift" action (sCursorOptions) // Task_HandleSelectionMenuInput (Shift / Summary / Cancel)
    private void runBattlePartyActions() {
        int slot = partySummarySlot;
        if (slot >= 0) {
            partySummarySlot = -1;
            int task = taskIndex(TASK_CHOOSE_MON);
            if (task >= 0 && !fading()) { // same as the overworld jump: close the party menu straight into the summary
                Core.write8(PARTY_MENU + 9, slot);
                Core.write32(Core.read32(PARTY_INTERNAL_PTR) + 4, CB2_SHOW_SUMMARY);
                Core.write32(TASKS + task * 40, TASK_CLOSE_PARTY_MENU);
                summaryFromTouch = true;
            }
        }
        slot = partySwitchSlot;
        if (slot < 0 || battleKeyTimer > 0) return;
        int task = taskIndex(TASK_CHOOSE_MON), pmi = Core.read32(PARTY_INTERNAL_PTR);
        byte[] win = new byte[2];
        if (pmi >>> 24 == 2) Core.read(pmi + 0xC, win);
        if (partySwitchStage == 0 && task >= 0 && !fading() && (win[0] & 0xFF) == 0xFF) { // no selection window open
            // CursorCB_SendMon: chime, TrySwitchInPokemon (the game's own checks + message), close the menu
            Core.write8(PARTY_MENU + 9, slot);
            Core.write32(TASKS + task * 40, CURSOR_CB_SEND_MON);
            partySwitchSlot = -1;
            return;
        }
        if (++partySwitchTimer > 120) { partySwitchSlot = -1; return; }
        if (partySwitchStage == 0) { // cursor onto the slot, A opens "Do what with ...?"
            if (taskIndex(TASK_CHOOSE_MON) < 0) return;
            Core.write8(PARTY_MENU + 9, slot);
            battleKey = Core.A; battleKeyTimer = 4; partySwitchStage = 1;
        } else if (taskIndex(TASK_CHOOSE_MON) < 0 && ++partySwitchWait > 10) { // window up: first option = Shift / Send Out
            battleKey = Core.A; battleKeyTimer = 4; partySwitchSlot = -1;
        }
    }
    /** CFRU throws the "last ball" (per Unbound's ball-priority option) on L in action select. */
    void battleQuickBall() {
        Battle b = battle;
        if (b != null && !b.quickBall && b.fallbackBall != 0) { autoBall = b.fallbackBall; battleAction(B_ACTION_BAG); return; } // through our bag
        topHold = 45; // the game's own Last Ball: keep its action menu covered until the throw text starts
        pendingCursorAddr = 0; pendingKey = Core.L;
    }
    private volatile int autoBall, lastBallUsed;
    private static final int[] BEST_BALLS = {2, 3, 4, 12}; // Ultra, Great, Poké, Premier (ponytail: Unbound's situational "best" not modelled)
    /** The ball our Last Ball button throws, or 0 when the game wouldn't allow a throw right now. Never a Master Ball. */
    private int fallbackBall(int battler) {
        int flags = Core.read32(0x02022B4C);
        if ((flags & (0x8 | 0x2 | 0x40 | 0x80 | 0x200)) != 0) return 0; // trainer / link / multi / Safari etc.
        if ((flags & 1) != 0) { // doubles: one foe left, thrown by the first Pokémon (unless it fainted)
            int foes = 0;
            for (int f : new int[]{1, 3}) { byte[] hp = new byte[2]; Core.read(0x02023BE4 + f * 0x58 + 0x28, hp); if (le16(hp, 0) != 0) foes++; }
            byte[] hp0 = new byte[2]; Core.read(0x02023BE4 + 0x28, hp0);
            if (foes != 1 || battler != 0 && le16(hp0, 0) != 0) return 0;
        }
        int option = Core.read8(0x0203B334) >> 4 & 3; // Unbound's "Last Used Ball": 0 After 1 Ball, 1 Always, 2 Always Best Ball (ponytail: 2 inferred)
        if (option == 0) return 0; // the game's own rule: only after a ball has been thrown
        java.util.List<int[]> balls = bagList(1);
        if (option == 1) for (int[] e : balls) if (e[0] == lastBallUsed) return e[0];
        for (int want : BEST_BALLS) for (int[] e : balls) if (e[0] == want) return want;
        for (int[] e : balls) if (e[0] != 1) return e[0];
        return 0;
    }
    void battleMega() { pendingCursorAddr = 0; pendingKey = Core.START; }
    String typeName(int t) { return rom.text(TYPE_NAMES + t * 7, 7); }

    private int prevPadKeys;
    /** Bottom-screen battler info is open / should close (the bottom screen owns it). */
    volatile boolean infoOpen, infoCloseReq;
    /** A message is showing (popup, battler info, bag or party-screen message): clear it with a chime. */
    boolean dismissMessage() {
        Ra.Toast t = Ra.toasts.peek();
        if (t != null && t.until >= System.currentTimeMillis()) { Ra.toasts.poll(); chime(); return true; }
        if (infoOpen) { infoCloseReq = true; infoOpen = false; chime(); return true; }
        if (bagBattler >= 0 && bagMessage != null && !bagMessage.equals(PROMPT_MON) && !bagMessage.equals(PROMPT_MOVE)) { // a result, not a prompt
            bagMessage = bagMoveSlot >= 0 ? PROMPT_MOVE : bagTargeting ? PROMPT_MON : null; chime(); return true; // back to the question
        }
        if (chooseBattler >= 0 && chooseMessage != null) { chooseMessage = null; chime(); return true; }
        return false;
    }
    /** Player pressed Start/R in the move menu this frame (CFRU's Mega/Dynamax toggle) — the bottom screen mirrors it. */
    volatile int megaToggles;

    /**
     * Runs on the emu thread before each frame. In action select the bottom screen lays out FIGHT above Bag / Run / Pokémon,
     * so the d-pad follows that layout (cursor written directly) instead of the game's 2x2 grid.
     */
    int filterKeys(int keys) {
        int out = filterKeysInner(keys);
        // Anything we swallowed stays swallowed until released, so the game never sees a held key as a fresh press.
        swallowed |= keys & ~out;
        swallowed &= keys;
        return out & ~swallowed;
    }
    private int swallowed;

    /** Bumped whenever the player presses anything (the bottom map drops its tapped selection). */
    volatile int inputSeq;

    private int filterKeysInner(int keys) {
        Battle b = battle;
        int pressedNow = keys & ~prevPadKeys;
        prevPadKeys = keys;
        if (pressedNow != 0) inputSeq++;
        if (resetPrompt) { resetPromptKeys(pressedNow); return 0; }
        if ((keys & (Core.START | Core.SELECT)) == (Core.START | Core.SELECT) && (pressedNow & (Core.START | Core.SELECT)) != 0) {
            resetPrompt = true; menuCursor = 0; resetConfirm = false; return 0;
        }
        if (pressedNow != 0 && dismissMessage()) return 0; // like the game's text boxes: the first press only clears the message
        if (b == null || safeMode || b.passive) return keys;
        if (chooseBattler >= 0 && !chooseSummaryOpen) return chooseKeys(keys, pressedNow); // the summary takes its own keys
        if (bagBattler >= 0) return bagKeys(pressedNow);
        if (b.mode == Battle.MOVES && !moveInfoOpen && (pressedNow & (Core.START | Core.R)) != 0) megaToggles++;
        if (b.mode == Battle.MOVES) return moveRowKeys(b, keys, pressedNow);
        if (b.mode == Battle.TARGET) return targetKeys(b, keys, pressedNow);
        if (b.yesNo >= 0) { // the Yes/No box isn't drawn up top: move its cursor ourselves (the game would draw arrows)
            if ((pressedNow & (Core.UP | Core.DOWN)) != 0) { pendingYesNoCursor = (pressedNow & Core.UP) != 0 ? 0 : 1; chime(); }
            return keys & ~(Core.UP | Core.DOWN);
        }
        if (b.mode != Battle.ACTION) return keys;
        if ((pressedNow & Core.L) != 0 && (b.quickBall || b.fallbackBall != 0)) { battleQuickBall(); return keys & ~Core.L; } // one path for L and taps
        int dpad = Core.UP | Core.DOWN | Core.LEFT | Core.RIGHT;
        if ((pressedNow & dpad) != 0) {
            byte[] cur = new byte[1];
            Core.read(ACTION_CURSOR + b.battler, cur);
            int c = cur[0], n = c;
            // USUM layout: Pokémon (top-left), Bag (bottom-left), Fight (tall, right), Run (bottom centre).
            // Remembers where you came from: Left from Fight returns to Pokémon or Bag, Up from Run returns to the previous button.
            boolean up = (pressedNow & Core.UP) != 0, down = (pressedNow & Core.DOWN) != 0;
            boolean left = (pressedNow & Core.LEFT) != 0, right = (pressedNow & Core.RIGHT) != 0;
            switch (c) {
                case B_ACTION_POKEMON: if (down) n = B_ACTION_BAG; else if (right) n = B_ACTION_FIGHT; break;
                case B_ACTION_BAG: if (up) n = B_ACTION_POKEMON; else if (right || down) n = B_ACTION_RUN; break;
                case B_ACTION_RUN: if (right) n = B_ACTION_FIGHT; else if (left) n = B_ACTION_BAG; else if (up) n = beforeRun; break;
                default: if (left) n = beforeFight; else if (down) n = B_ACTION_RUN; break; // FIGHT: Left returns where you came from
            }
            if (n != c) {
                if (c == B_ACTION_POKEMON || c == B_ACTION_BAG) lastLeftColumn = c;
                if (n == B_ACTION_RUN) beforeRun = c;
                if (n == B_ACTION_FIGHT) beforeFight = c;
            }
            if (n != c) { Core.write8(ACTION_CURSOR + b.battler, n); chime(); } // the menu's own cursor sound
        }
        return keys & ~dpad;
    }

    private int extraSel;
    private int lastLeftColumn = B_ACTION_POKEMON, beforeRun = B_ACTION_FIGHT, beforeFight = B_ACTION_POKEMON;

    // ---- Start + Select: "reset the game?" (drawn on the bottom screen; the game gets no input while it's up) ----
    volatile boolean resetPrompt, resetYes;
    private volatile int resetAnswer; // set by touch: 1 yes, 2 no
    void answerReset(boolean yes) { resetAnswer = yes ? 1 : 2; }
    /** Start+Select menu: 0 Save state, 1 Load state, 2 Reset game (asks once more), 3 Cancel. */
    static final String[] MENU_ROWS = {"Save state", "Load state", "Reset game", "Cancel"};
    volatile int menuCursor;
    volatile boolean resetConfirm;
    /** For MainActivity: 1 save / 2 load the quick slot (taken by the emu thread). */
    volatile int stateRequest;
    void menuPick(int row) {
        chime();
        if (row == 0) { stateRequest = 1; resetAnswer = 2; }
        else if (row == 1) { stateRequest = 2; resetAnswer = 2; }
        else if (row == 2) { if (resetConfirm) resetAnswer = 1; else resetConfirm = true; }
        else resetAnswer = 2;
    }
    private void resetPromptKeys(int pressedNow) {
        if ((pressedNow & Core.UP) != 0) { menuCursor = (menuCursor + 3) % 4; resetConfirm = false; chime(); }
        if ((pressedNow & Core.DOWN) != 0) { menuCursor = (menuCursor + 1) % 4; resetConfirm = false; chime(); }
        if ((pressedNow & Core.B) != 0) { resetAnswer = 2; chime(); }
        if ((pressedNow & Core.A) != 0) menuPick(menuCursor);
    }
    /** Emu thread, once per frame. */
    private void applyResetAnswer() {
        int a = resetAnswer;
        if (a == 0) return;
        resetAnswer = 0;
        resetPrompt = false;
        if (a == 1) { Core.reset(); Ra.reset(); inGame = false; battle = null; target = -1; touchSession = false; freezeTop = false; fade = 0; }
    }
    /**
     * Moves are the game's 2x2 cursor; Back and Mega live in a row underneath that only the bottom screen has.
     * Down from the bottom moves enters that row; A there becomes B (back) or Start (Mega toggle).
     */
    private int chimeRestore = -1, chimeBattler;
    /** One frame of the game's own cursor move from `slot` (it plays the select chime), undone next frame. */
    private int chimeKey(Battle b, int slot) {
        int n = slot ^ 1, key = (slot & 1) == 0 ? Core.RIGHT : Core.LEFT;
        if (n > 3 || b.moves[n] == 0) { n = slot ^ 2; key = slot < 2 ? Core.DOWN : Core.UP; }
        if (n > 3 || b.moves[n] == 0) return 0; // only one move: nowhere to nudge, stay silent
        chimeRestore = slot; chimeBattler = b.battler;
        return key;
    }

    private int moveRowKeys(Battle b, int keys, int pressedNow) {
        int dpad = Core.UP | Core.DOWN | Core.LEFT | Core.RIGHT;
        keys &= ~Core.L; // L = our move info (not CFRU's top-screen detail toggle)
        if (moveInfoOpen || extraSel != 0) keys &= ~Core.SELECT; // Select = the game's move reorder, only from a move
        if (moveInfoOpen) { // a popup: any button just closes it (with the menu chime), nothing reaches the game
            if (pressedNow != 0) { moveInfoOpen = false; chime(); }
            return keys & ~(Core.A | Core.B | dpad | Core.START | Core.R);
        }
        if ((pressedNow & Core.L) != 0) { moveInfoOpen = true; chime(); return keys & ~dpad; }
        byte[] cur = new byte[1];
        if (chimeRestore >= 0) { Core.write8(MOVE_CURSOR + chimeBattler, chimeRestore); chimeRestore = -1; return keys & ~dpad; }
        Core.read(MOVE_CURSOR + b.battler, cur);
        if (extraSel == 0) {
            boolean bottomRow = cur[0] >= 2 || moveCountBelow(b, cur[0]) == 0;
            if ((pressedNow & Core.DOWN) != 0 && bottomRow) { // straight down: Back under the left column, Mega under the right
                extraSel = (cur[0] & 1) == 1 && b.canMega ? 2 : 1;
                return (keys & ~dpad) | chimeKey(b, cur[0]);
            }
            return keys;
        }
        if ((pressedNow & Core.UP) != 0) { // straight up: Back -> bottom-left move, Mega -> bottom-right move
            int slot = extraSel == 2 ? (b.moves[3] != 0 ? 3 : b.moves[1] != 0 ? 1 : 0) : (b.moves[2] != 0 ? 2 : 0);
            Core.write8(MOVE_CURSOR + b.battler, slot);
            extraSel = 0;
            return (keys & ~dpad) | chimeKey(b, slot);
        }
        int chime = 0;
        if ((pressedNow & Core.RIGHT) != 0 && b.canMega && extraSel != 2) { extraSel = 2; chime = chimeKey(b, cur[0]); }
        if ((pressedNow & Core.LEFT) != 0 && extraSel != 1) { extraSel = 1; chime = chimeKey(b, cur[0]); }
        keys = (keys & ~dpad) | chime;
        if ((keys & Core.A) != 0) { keys = (keys & ~Core.A) | (extraSel == 1 ? Core.B : Core.START); if (extraSel == 2 && (pressedNow & Core.A) != 0) megaToggles++; }
        if ((pressedNow & Core.B) != 0) extraSel = 0;
        return keys;
    }
    /** The picker's cards: [row][col] -> battler (foes on top: 3 left, 1 right; yours below: 0 left, 2 right). */
    private static final int[][] TARGET_GRID = {{3, 1}, {0, 2}};
    private int targetKeys(Battle b, int keys, int pressedNow) {
        int dpad = Core.UP | Core.DOWN | Core.LEFT | Core.RIGHT;
        if (targetGoal >= 0) return keys & ~(dpad | Core.A); // a move is in flight
        if (extraSel == 0 && (pressedNow & Core.A) != 0 && b.target >= 0) lastTarget[b.battler & 3] = b.target;
        boolean up = (pressedNow & Core.UP) != 0, down = (pressedNow & Core.DOWN) != 0;
        boolean side = (pressedNow & (Core.LEFT | Core.RIGHT)) != 0;
        if (extraSel == 1) { // on Back
            if (up) extraSel = 0; // back onto the card the game's cursor never left
            if ((keys & Core.A) != 0) keys = (keys & ~Core.A) | Core.B;
            if ((pressedNow & Core.B) != 0) extraSel = 0;
            return keys & ~dpad;
        }
        int cur = b.target, row = -1, col = -1;
        for (int r = 0; r < 2; r++) for (int c = 0; c < 2; c++) if (TARGET_GRID[r][c] == cur) { row = r; col = c; }
        if (row < 0) return keys & ~dpad;
        int goal = -1;
        if (side && b.targetable[TARGET_GRID[row][col ^ 1]]) goal = TARGET_GRID[row][col ^ 1];
        if (up && row == 1) goal = pick(b, TARGET_GRID[0][col], TARGET_GRID[0][col ^ 1]);
        if (down && row == 0) goal = pick(b, TARGET_GRID[1][col], TARGET_GRID[1][col ^ 1]);
        if (down && goal < 0) { extraSel = 1; startTarget(cur, false, true); } // Back: same chime as any cursor move
        else if (goal >= 0) startTarget(goal, false, false);
        return keys & ~dpad;
    }
    private static int pick(Battle b, int first, int second) { return b.targetable[first] ? first : b.targetable[second] ? second : -1; }

    private static int moveCountBelow(Battle b, int slot) { return slot + 2 < 4 && b.moves[slot + 2] != 0 ? 1 : 0; }

    /** @param keys what the player is physically holding this frame */
    /** After a save state is loaded: drop everything that was mid-flight on the old timeline. */
    void onStateLoaded() {
        bagBattler = -1; pendingCall = null; callDone = null;
        chooseBattler = -1; chooseSummaryOpen = false; summarySlot = -1; summaryPending = -1; chooseSwitchSlot = -1;
        targetGoal = -1; oneShotSlot = -1; pendingKey = 0; battleKeyTimer = 0; held = 0; extraSel = 0; moveInfoOpen = false;
        chimeRestore = -1; swapTap = -1; infoSlot = -1; battle = null;
        target = -1; touchSession = false; freezeTop = false; fade = 0;
    }

    /** Emulated frame count (drives the bottom screen's icon animations at the game's speed). */
    volatile int frame;
    void poll(int frame, int keys) {
        try { pollInner(frame, keys); }
        catch (RuntimeException e) { // never take the game down with us: fall back to vanilla for the rest of the session
            android.util.Log.e("gbads", "hook failed, safe mode on", e);
            if (!safeMode) toast("DuoBoy hit a problem: using the game's own menus for now.");
            safeMode = true; target = -1; held = 0;
        }
    }
    private void pollInner(int frame, int keys) {
        this.frame = frame;
        trace();
        if (pendingKey != 0 && battleKeyTimer == 0) {
            if (pendingCursorAddr != 0) Core.write8(pendingCursorAddr + activeBattler, pendingCursor);
            battleKey = pendingKey; pendingKey = 0; battleKeyTimer = 4;
        }
        if (battleKeyTimer > 0) held = --battleKeyTimer >= 2 ? battleKey : 0;
        applyResetAnswer();
        if (pendingYesNoCursor >= 0) { if (battle != null && battle.yesNo >= 0) Core.write8(0x02023E83, pendingYesNoCursor); pendingYesNoCursor = -1; }
        if (tapA > 0) tapA--;
        awaitingA = (inBattle() || inOverworld() && Core.read8(0x03000F9C) != 0) && textWaiting();
        if (pendingYesNo >= 0) { // the handler reads gBattleCommunication[CURSOR_POSITION] when A arrives
            Battle bt = battle;
            if (bt == null || bt.yesNo < 0) pendingYesNo = -1;
            else if (battleKeyTimer == 0) { Core.write8(0x02023E83, pendingYesNo); battleKey = Core.A; battleKeyTimer = 4; pendingYesNo = -1; }
        }
        if (infoSlot >= 0) { Core.write8(MOVE_CURSOR + activeBattler, infoSlot); extraSel = 0; moveInfoOpen = true; infoSlot = -1; chime(); }
        watchChooseSummary();
        runChime();
        if (swapTap >= 0 && battleKeyTimer == 0) { Core.write8(MULTI_USE_CURSOR, swapTap); battleKey = Core.A; battleKeyTimer = 4; swapTap = -1; }
        pollBattle();
        runTargetWalk();
        runBattlePartyActions();
        runBag();
        runOverworldParty();
        runDebugWild();
        int[] sc = pendingScript;
        if (sc != null && pendingCall == null && callDone == null) { pendingScript = null; if (fieldIdle()) runScript(sc); }
        battleStarting = taskIndex(TASK_BATTLE_START) >= 0 || taskIndex(TASK_BATTLE_TRANSITION) >= 0;
        int pressedNow = keys & ~prevKeys;
        prevKeys = keys;
        // Touch-opened Save: cancelling redraws the bar over several frames before its input handler returns, so
        // hold the screen from the moment the player cancels (B, or A on "No") until the bar has been closed again.
        if (touchSession && target < 0 && Core.read32(SM_CALLBACK) == SAVE_PROMPT) {
            byte[] cur = new byte[1];
            Core.read(MENU_CURSOR, cur);
            if ((pressedNow & Core.B) != 0 || ((pressedNow & Core.A) != 0 && cur[0] == 1)) { freezeTop = true; saveCancelFrames = 1; }
        } else if (saveCancelFrames > 0 && ++saveCancelFrames > 45) { saveCancelFrames = 0; if (target < 0 && holdFrames == 0) freezeTop = false; }
        if (summaryFromTouch && !inBattle()) { // a touch-opened summary: B goes straight back to the field, not the party menu
            int sum = Core.read32(SUMMARY_PTR);
            if (sum >>> 24 == 2 && Core.read32(sum + 0x32F8) == SUMMARY_RETURN_TO_PARTY) Core.write32(sum + 0x32F8, 0x080567DD); // CB2_ReturnToField
        }
        if (summaryFromTouch && Core.read32(MAIN + 4) == CB2_INIT_PARTY_MENU) {
            int pmi = Core.read32(PARTY_INTERNAL_PTR);
            if (pmi >>> 24 == 2 && Core.read32(pmi) == TASK_TRY_CREATE_SELECTION_WINDOW) {
                Core.write32(pmi, TASK_CHOOSE_MON);                               // sPartyMenuInternal->task
                Core.write32(pmi + 8, Core.read32(pmi + 8) & 0x3FFFF);            // messageId (bits 18..31) = PARTY_MSG_CHOOSE_MON
                summaryFromTouch = false;
            }
        }
        if (summaryFromTouch && target < 0 && inOverworld()) summaryFromTouch = false; // only the next party rebuild
        if (holdFrames > 0 && --holdFrames == 0 && target < 0) freezeTop = false;
        runMacro();
        turbo = false; // ponytail: fast-forwarding hidden frames also fast-forwards the music; left off
        if (frame % 6 != 0) return; // 10Hz is plenty for a status screen
        int cb2 = Core.read32(MAIN + 4);
        boolean title = false;
        for (int c : TITLE_CB2) if (cb2 == c) title = true;
        onTitle = title && !inGame;
        if (cb2 == CB2_OVERWORLD) inGame = true;
        else for (int c : OUT_OF_GAME_CB2) if (cb2 == c) inGame = false;
        State s = new State();
        byte[] n = new byte[1];
        Core.read(PARTY_COUNT, n);
        int count = Math.min(n[0] & 0xFF, 6);
        Core.read(PARTY, partyBuf);
        s.party = new Mon[count];
        for (int i = 0; i < count; i++) s.party[i] = decodeMon(partyBuf, i * 100);
        Core.read(SAVEBLOCK2_PTR, sb2);
        int p = le32(sb2, 0);
        if (p >>> 24 == 2) {
            Core.read(p + 0x14, opt); s.frameType = (le16(opt, 0) >> 3) & 31;
            byte[] g = new byte[1]; Core.read(p + 8, g); s.gender = g[0] & 1;
        }
        s.hasMap = hasItem(ITEM_TOWN_MAP);
        if (s.hasMap) regionMapPosition(s);
        int sb1 = Core.read32(SAVEBLOCK1_PTR);
        Core.read(0x0202583C, s.dexSeen); Core.read(0x020258B9, s.dexCaught); // GetSetPokedexFlag's arrays (Unbound)
        s.hour = Core.read8(0x03005EA6);
        s.swapSeq = swapCount;
        s.fieldFree = fieldFree();
        busyPolls = s.fieldFree ? 0 : busyPolls + 1;
        s.fieldBusy = busyPolls >= 2;
        s.startMenuOpen = barOpen();
        s.mapSec = Core.read8(MAP_HEADER + 0x14);
        if (sb1 >>> 24 == 2) { byte[] loc = new byte[2]; Core.read(sb1 + 4, loc); s.mapGroup = loc[0] & 0xFF; s.mapNum = loc[1] & 0xFF; }
        state = s;
    }

    private static Mon decodeMon(byte[] b, int o) {
        Mon m = new Mon();
        int pid = le32(b, o), otid = le32(b, o + 4);
        int growth;
        if (decryptedChecksum(b, o, pid ^ otid) == le16(b, o + 28)) { // vanilla: XOR-encrypted, shuffled by personality
            growth = le32(b, o + 32 + GROWTH_POS[Integer.remainderUnsigned(pid, 24)] * 12) ^ pid ^ otid;
        } else {
            growth = le32(b, o + 32); // CFRU/Unbound: unencrypted, fixed order
        }
        m.species = growth & 0xFFFF;
        m.egg = (b[o + 19] & 5) != 0; // isBadEgg | isEgg bits
        if (m.egg) m.species = 412;
        m.heldItem = growth >>> 16;
        m.personality = pid;
        m.nick = Rom.text(b, o + 8, 10);
        m.status = le32(b, o + 80);
        m.level = b[o + 84] & 0xFF;
        m.hp = le16(b, o + 86);
        m.maxHp = le16(b, o + 88);
        return m;
    }
    /** Vanilla checksum: sum of the 24 decrypted u16s. Only matches when the block really is encrypted. */
    private static int decryptedChecksum(byte[] b, int o, int key) {
        int sum = 0;
        for (int i = 0; i < 48; i += 4) { int w = le32(b, o + 32 + i) ^ key; sum += (w & 0xFFFF) + (w >>> 16); }
        return sum & 0xFFFF;
    }
    // Index of the Growth substruct in each permutation (GAEM, GAME, GEAM, GEMA, GMAE, GMEA, AGEM, ...).
    private static final int[] GROWTH_POS = {0,0,0,0,0,0, 1,1,2,3,2,3, 1,1,2,3,2,3, 1,1,2,3,2,3};

    static int le16(byte[] b, int o) { return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8; }
    static int le32(byte[] b, int o) { return le16(b, o) | le16(b, o + 2) << 16; }

    // ---- start-menu hook ----
    // Unbound's icon bar: table of {func*, label*, flag|icon<<16, id} at 0x08A6D160; sStartMenuOrder holds table indices.
    static final int SM_CALLBACK = 0x020370F0, MENU_TABLE = 0x08A6D160, BAR_INPUT = 0x08A0BD8F;
    /** The bar's own "close" callback (what its B handler runs): hides the bar, returns TRUE, no sound. */
    static final int BAR_CLOSE = 0x08A0BD85;
    /** Vanilla StartCB_HandleInput: what a cancelled Save hands the bar back to. */
    static final int VANILLA_BAR_INPUT = 0x0806F281;
    /** Save flow: callback once the "Would you like to save?" prompt is waiting for input. */
    static final int SAVE_PROMPT = 0x0806F5C9;
    // Touch-opened summary: B returns to the party via vanilla (cursor stays on the viewed mon), but rebuild it in
    // "Choose a Pokémon" mode instead of reopening the Summary/Switch/Item submenu.
    static final int CB2_INIT_PARTY_MENU = 0x0811EBD1, TASK_TRY_CREATE_SELECTION_WINDOW = 0x08122C31;
    static final int SUMMARY_PTR = 0x0203B140, SUMMARY_MON_INDEX = 0x0203B16C; // sMonSummaryScreen, sLastViewedMonIndex
    static final int CB2_RETURN_TO_FIELD_WITH_MENU = 0x080568A9; // the summary's savedCallback lives at +0x32F8
    private boolean summaryFromTouch;
    /** Frames to keep the top screen held after closing a redrawn bar, so it never reaches the display. */
    private int holdFrames;
    /** Hidden hook phases run several emulator frames per displayed frame. */
    volatile boolean turbo;
    static final int POKEDEX = 0, POKEMON = 2, CUBE = 3, SAVE = 4, MISSIONS = 5, TRAINER_CARD = 7, SETTINGS = 9;
    // FireRed field/menu plumbing (unchanged in Unbound)
    static final int MAIN = 0x030030F0, CB2_OVERWORLD = 0x080565B5, TASKS = 0x03005090, TASK_START_MENU = 0x0806F1F1;
    static final int FIELD_CB2 = 0x03005024, FIELD_CB_REOPEN_START_MENU = 0x0807E3BD;

    /** True while a hook is in flight: the top screen holds its last frame so menus open/close "directly". */
    volatile boolean freezeTop;
    /** 0..16: black over the frozen top frame, stepped like the GBA's palette fade. */
    volatile int fade;
    private volatile int target = -1;
    private int step, timer, held, bPresses, idleFrames, startTries;
    /** A touch-opened menu is up: on the way out, return to the field instead of reopening the bar. */
    private boolean touchSession;
    private final byte[] tasks = new byte[16 * 40], mainTail = new byte[1];

    /** Keys the hook is holding this frame (ORed with player input). */
    int macroKeys() { return held | (tapA >= 2 ? Core.A : 0); }

    // ---- overworld party: reorder / take held item, straight in gPlayerParty (only in the field, no menu running) ----
    private int swapCount, busyPolls; // completed overworld swaps, published with the party in State.swapSeq
    private volatile int owSwapA = -1, owSwapB = -1, owTakeSlot = -1;
    void partySwap(int a, int b) { owSwapB = b; owSwapA = a; }
    void partyTakeItem(int slot) { owTakeSlot = slot; }
    private volatile int owGiveSlot = -1, owGiveItem;
    void partyGiveItem(int slot, int item) { owGiveItem = item; owGiveSlot = slot; }
    /** Holdable items for Give: the Items and Berries pockets as {item, qty}. */
    java.util.List<int[]> holdableItems() {
        java.util.ArrayList<int[]> out = new java.util.ArrayList<>();
        for (int pk : new int[]{0, 4}) {
            int ptr = Core.read32(BAG_POCKETS + pk * 8), cap = Core.read8(BAG_POCKETS + pk * 8 + 4);
            if (ptr >>> 24 != 2) continue;
            byte[] slots = new byte[cap * 4];
            Core.read(ptr, slots);
            for (int i = 0; i < cap; i++) if (le16(slots, i * 4) != 0 && le16(slots, i * 4 + 2) != 0) out.add(new int[]{le16(slots, i * 4), le16(slots, i * 4 + 2)});
        }
        return out;
    }
    /** AddBagItem(item, 1) into its own pocket; false if there's no room. */
    private boolean addBagItem(int item) {
        int pk = rom.u8(ITEMS + item * ITEM_SIZE + 26) - 1;
        int ptr = Core.read32(BAG_POCKETS + pk * 8), cap = Core.read8(BAG_POCKETS + pk * 8 + 4);
        if (pk < 0 || ptr >>> 24 != 2) return false;
        byte[] slots = new byte[cap * 4];
        Core.read(ptr, slots);
        int at = -1;
        for (int i = 0; i < cap && at < 0; i++) if (le16(slots, i * 4) == item && le16(slots, i * 4 + 2) < 999) at = i;
        for (int i = 0; i < cap && at < 0; i++) if (le16(slots, i * 4) == 0) at = i;
        if (at < 0) return false;
        int qty = le16(slots, at * 4) == item ? le16(slots, at * 4 + 2) + 1 : 1;
        Core.write32(ptr + at * 4, item | qty << 16);
        return true;
    }
    /** In the field with control: no script / menu / hook running (sLockFieldControls, ArePlayerFieldControlsLocked). */
    private boolean fieldFree() { return !safeMode && inOverworld() && target < 0 && !fading() && Core.read8(0x03000F9C) == 0; }
    private boolean fieldIdle() {
        boolean idle = fieldFree();
        if (!idle) toast("Not right now.");
        return idle;
    }
    private void runOverworldParty() {
        if (owSwapA >= 0) {
            int a = owSwapA, b = owSwapB;
            owSwapA = -1;
            if (!fieldIdle() || a == b || a >= state.party.length || b >= state.party.length) return;
            byte[] pa = new byte[100], pb = new byte[100];
            Core.read(PLAYER_PARTY + a * 100, pa);
            Core.read(PLAYER_PARTY + b * 100, pb);
            for (int i = 0; i < 100; i += 4) { Core.write32(PLAYER_PARTY + a * 100 + i, le32(pb, i)); Core.write32(PLAYER_PARTY + b * 100 + i, le32(pa, i)); }
            chime();
            swapCount++;
        }
        if (owGiveSlot >= 0) {
            int slot = owGiveSlot, item = owGiveItem;
            owGiveSlot = -1;
            if (!fieldIdle() || slot >= state.party.length) return;
            int o = PLAYER_PARTY + slot * 100;
            byte[] m = new byte[100];
            Core.read(o, m);
            if (decryptedChecksum(m, 0, le32(m, 0) ^ le32(m, 4)) == le16(m, 28)) { toast("Can't give items in this game yet."); return; }
            int held = le16(m, 34);
            if (held >= 121 && held <= 132) { toast("Take its Mail from the party menu first."); return; }
            if (held != 0 && !addBagItem(held)) { toast("The Bag is full."); return; }
            removeBagItem(item);
            Core.write8(o + 34, item & 0xFF); Core.write8(o + 35, item >> 8);
            toast(Rom.text(m, 8, 10) + " is now holding " + itemName(item) + ".");
            chime();
        }
        if (owTakeSlot >= 0) {
            int slot = owTakeSlot;
            owTakeSlot = -1;
            if (!fieldIdle() || slot >= state.party.length) return;
            int o = PLAYER_PARTY + slot * 100;
            byte[] m = new byte[100];
            Core.read(o, m);
            int pid = le32(m, 0), otid = le32(m, 4);
            if (decryptedChecksum(m, 0, pid ^ otid) == le16(m, 28)) { toast("Can't take items in this game yet."); return; } // ponytail: vanilla-encrypted data
            int item = le16(m, 34);
            String nick = Rom.text(m, 8, 10);
            if (item == 0) { toast(nick + " isn't holding anything."); return; }
            if (item >= 121 && item <= 132) { toast("Take Mail from the party menu."); return; }
            int pk = rom.u8(ITEMS + item * ITEM_SIZE + 26) - 1;
            int ptr = Core.read32(BAG_POCKETS + pk * 8), cap = Core.read8(BAG_POCKETS + pk * 8 + 4);
            if (pk < 0 || ptr >>> 24 != 2) return;
            byte[] slots = new byte[cap * 4];
            Core.read(ptr, slots);
            int at = -1;
            for (int i = 0; i < cap && at < 0; i++) if (le16(slots, i * 4) == item && le16(slots, i * 4 + 2) < 999) at = i;
            for (int i = 0; i < cap && at < 0; i++) if (le16(slots, i * 4) == 0) at = i;
            if (at < 0) { toast("The Bag is full."); return; }
            int qty = le16(slots, at * 4) == item ? le16(slots, at * 4 + 2) + 1 : 1;
            Core.write32(ptr + at * 4, item | qty << 16);
            Core.write8(o + 34, 0); Core.write8(o + 35, 0); // held item (growth substruct, unencrypted in Unbound; no checksum)
            toast("Received " + itemName(item) + " from " + nick + ".");
            chime();
        }
    }
    private static void toast(String msg) { Ra.toasts.add(new Ra.Toast(msg, "", 2500)); }

    void openMenu(int entry) { openMenu(entry, -1); }
    /** Open a menu; for POKEMON, partySlot >= 0 puts the party cursor on that Pokémon. */
    void openMenu(int entry, int partySlot) { if (target < 0) { this.partySlot = partySlot; target = entry; step = 0; } }
    private int partySlot = -1;
    static final int PARTY_MENU = 0x0203B0A0, TASK_CHOOSE_MON = 0x0811FB29, CB2_UPDATE_PARTY_MENU = 0x0811EBA1; // gPartyMenu (slotId at +9)
    // What the party menu's "Summary" action does (Unbound's CursorCB_Summary @08A02AB0): exitCallback = summary, close menu.
    static final int PARTY_INTERNAL_PTR = 0x0203B09C, TASK_CLOSE_PARTY_MENU = 0x0811FA79, CB2_SHOW_SUMMARY = 0x08122D79;

    static final int PALETTE_FADE = 0x02037AB8;
    /** gPaletteFade.active (bit 7 of byte 7). */
    private boolean fading() { byte[] b = new byte[1]; Core.read(PALETTE_FADE + 7, b); return (b[0] & 0x80) != 0; }
    private boolean inOverworld() { return Core.read32(MAIN + 4) == CB2_OVERWORLD; }
    private boolean inBattle() { Core.read(MAIN + 0x439, mainTail); return (mainTail[0] & 2) != 0; }
    private int taskIndex(int func) {
        Core.read(TASKS, tasks);
        for (int i = 0; i < 16; i++) if (le32(tasks, i * 40) == func && tasks[i * 40 + 4] != 0) return i;
        return -1;
    }
    private boolean barOpen() {
        Core.read(TASKS, tasks);
        for (int i = 0; i < 16; i++) if (le32(tasks, i * 40) == TASK_START_MENU && tasks[i * 40 + 4] != 0) return true;
        return false;
    }

    /**
     * A touch-opened menu is closing. Vanilla reopens the bar on the way back to the field (FieldCB2_DrawStartMenu);
     * let it, but keep the top screen black, close the bar with its own close routine, then fade in from black.
     * (Clearing gFieldCallback2 instead crashes: the field then runs a stale gFieldCallback, e.g. after Unbound's Options.)
     */
    static final int FIELD_CB = 0x03005020, FIELD_CB_WARP_EXIT_FADE_FROM_BLACK = 0x0807DF7D;

    /**
     * A touch-opened menu is closing. Vanilla returns via FieldCB_ReturnToFieldOpenStartMenu, which redraws the bar.
     * Swap in the plain "fade in from black and hand back control" field callback instead: no bar, no close chime.
     * (gFieldCallback must be set too: with gFieldCallback2 null the field runs gFieldCallback, which Unbound's
     * Options leaves stale — that's the crash we hit when only clearing gFieldCallback2.)
     */
    private void returnToField() {
        int cb2 = Core.read32(MAIN + 4);
        if (Core.read32(FIELD_CB2) == FIELD_CB_REOPEN_START_MENU) {
            Core.write32(FIELD_CB, FIELD_CB_WARP_EXIT_FADE_FROM_BLACK);
            Core.write32(FIELD_CB2, 0);
            if (target < 0) touchSession = false;
            return;
        }
        // A cancelled in-field Save redraws the bar (via vanilla StartCB_HandleInput): close it with the bar's own routine.
        int sm = Core.read32(SM_CALLBACK);
        if ((target < 0 || step == 1) && cb2 == CB2_OVERWORLD && (sm == BAR_INPUT || sm == VANILLA_BAR_INPUT) && barOpen()) {
            Core.write32(SM_CALLBACK, BAR_CLOSE);
            if (target < 0) { touchSession = false; freezeTop = true; holdFrames = 4; saveCancelFrames = 0; } // this frame has the bar on it: don't show it
            return;
        }
        // In-field Save that completed: the bar closed itself and nothing returns to the field. Let the session lapse.
        if (target < 0 && cb2 == CB2_OVERWORLD && !barOpen()) { if (++idleFrames > 60) touchSession = false; }
        else idleFrames = 0;
    }

    private void done() { held = 0; freezeTop = false; fade = 0; target = -1; }

    private void runMacro() {
        if (touchSession) returnToField();
        if (target < 0) return;
        ++timer;
        switch (step) {
            case 0: // where are we?
                timer = 0; startTries = 0;
                if (inBattle()) { target = -1; return; } // battle buttons are a separate screen
                if (target == POKEMON && partySlot >= 0 && !inOverworld()) {
                    if (Core.read32(SUMMARY_PTR) != 0) { // summary already up: jump to the tapped mon with the game's own slide
                        byte[] cur = new byte[1];
                        Core.read(SUMMARY_MON_INDEX, cur);
                        if (cur[0] == partySlot) { done(); return; }
                        // Park the index one away and let a single Up/Down do the real change (loads data, animates).
                        Core.write8(SUMMARY_MON_INDEX, partySlot == 0 ? 1 : partySlot - 1);
                        held = partySlot == 0 ? Core.UP : Core.DOWN;
                        step = 5; timer = 0;
                        return;
                    }
                    if (taskIndex(TASK_CHOOSE_MON) >= 0) { step = 8; timer = 0; return; } // party menu up: straight to Summary
                }
                if (!inOverworld()) { // another menu is up: back out first
                    int sum = Core.read32(SUMMARY_PTR);
                    // from a summary go straight to the field instead of via the party menu
                    if (sum >>> 24 == 2) Core.write32(sum + 0x32F8, CB2_RETURN_TO_FIELD_WITH_MENU);
                    touchSession = true; bPresses = 0; step = 1; return;
                }
                freezeTop = true;
                int sm0 = Core.read32(SM_CALLBACK);
                if (barOpen() && sm0 != BAR_INPUT && sm0 != VANILLA_BAR_INPUT) { held = Core.B; step = 11; return; } // e.g. save prompt: cancel it first
                step = barOpen() ? 3 : 2;
                return;
            case 1: // tap B until we're back on the field (the session flag skips the bar on the way out)
                held = !inOverworld() && Core.read32(FIELD_CB2) == 0 && timer % 16 < 2 ? Core.B : 0; // stop once the field is coming back
                if (timer % 16 == 0) bPresses++;
                // Back on the field: the screen is still black from the transition, so freeze it black and carry on.
                if (inOverworld() && !barOpen() && timer > 20) { held = 0; freezeTop = true; fade = 16; step = 9; timer = 0; }
                else if (bPresses > 10) done();
                return;
            case 2: // a real Start press: the game decides whether a menu may open right now
                held = timer <= 2 ? Core.START : 0;
                if (timer > 2) { step = 3; timer = 0; }
                return;
            case 11: // cancelling a prompt (save): release B, wait for the bar to take input again
                if (timer == 3) held = 0;
                if (timer > 4) { step = 3; timer = 0; }
                return;
            case 9: // just came back from another menu: let the field finish loading/fading so it accepts Start
                if (!fading() && timer > 10) { startTries = 0; step = 2; timer = 0; }
                else if (timer > 180) done();
                return;
            case 3: { // bar is up and taking input: do what its A handler does, minus PlaySE(SE_SELECT)
                Core.read(SM_CURSOR, smBuf);
                int count = Math.min(smBuf[1], 9);
                int smNow = Core.read32(SM_CALLBACK);
                if (barOpen() && (smNow == BAR_INPUT || smNow == VANILLA_BAR_INPUT) && count > 0) {
                    int pos = -1;
                    for (int i = 0; i < count; i++) if (smBuf[2 + i] == target) pos = i;
                    if (pos < 0) { Log.i("gbads", "menu entry " + target + " not in bar"); held = Core.B; step = 5; timer = 0; return; }
                    Core.write8(SM_CURSOR, pos);
                    touchSession = true; idleFrames = 0;
                    // Leaving the overworld fades out first, like the bar's own A press (Save stays on the field).
                    step = target == SAVE ? 7 : 6; timer = 0;
                } else if (timer > 25) { // Start didn't open the bar: retry a couple of times, then give up (cutscene, script…)
                    if (++startTries < 3) { step = 2; timer = 0; } else done();
                }
                return;
            }
            case 4: // hold the old frame until the new screen takes over (or the in-field action has drawn)
                // Stay black until the new screen is fading itself in, so its setup frames never show.
                boolean arrived = !inOverworld() && fading();
                if (target == POKEMON && partySlot >= 0) { if (!inOverworld()) { step = 8; timer = 0; } else if (timer > 90) done(); } // stay black
                else if (arrived || (target == SAVE && Core.read32(SM_CALLBACK) == SAVE_PROMPT && timer > 6) || timer > 90) done();
                return;
            case 8: { // party menu is up behind the black screen: do its Summary action on the tapped slot
                int task = taskIndex(TASK_CHOOSE_MON);
                if (task < 0 || fading()) { if (timer > 180) done(); return; }
                Core.write8(PARTY_MENU + 9, partySlot);                            // gPartyMenu.slotId
                Core.write32(Core.read32(PARTY_INTERNAL_PTR) + 4, CB2_SHOW_SUMMARY); // sPartyMenuInternal->exitCallback
                Core.write32(TASKS + task * 40, TASK_CLOSE_PARTY_MENU);              // gTasks[task].func
                partySlot = -1; step = 10; timer = 0;
                return;
            }
            case 10: { // summary screen is loading: reveal it once it starts fading itself in
                summaryFromTouch = true;
                if ((Core.read32(MAIN + 4) != CB2_UPDATE_PARTY_MENU && !inOverworld() && fading() && timer > 20) || timer > 180) done();
                return;
            }
            case 5:
                if (timer > 3) done();
                return;
            case 6: // fade the frozen frame to black
                if (fade < 16) { if (timer % 5 != 0) fade++; return; } // 16 steps over 20 frames (~0.33 s)
                step = 7;
                // fall through
            case 7: // run the entry's function, exactly what the bar's A handler stores (minus PlaySE)
                Core.write32(SM_CALLBACK, rom.u32(MENU_TABLE + target * 16));
                step = 4; timer = 0;
        }
    }
}
