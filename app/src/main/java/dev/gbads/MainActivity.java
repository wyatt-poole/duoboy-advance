package dev.gbads;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Presentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

public final class MainActivity extends Activity implements Bottom.Host {
    private final ByteBuffer pixels = ByteBuffer.allocateDirect(240 * 160 * 4).order(ByteOrder.nativeOrder());
    private final Bitmap screen = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888);
    private volatile int keys, stickKeys, debugKeys, debugFrames;
    private volatile String stateOp;
    private volatile int pokeAddr, pokeVal;
    private volatile int stateSlot;
    /** One file per ROM and slot in the app's external files dir (not next to the save: states aren't Syncthing'd). */
    File statePath(int slot) {
        String rom = getPreferences(MODE_PRIVATE).getString("rom", "rom");
        File dir = new File(getExternalFilesDir(null), "states");
        dir.mkdirs();
        return new File(dir, new File(rom).getName().replaceAll("[.]gba$", "") + ".ss" + slot);
    }
    void requestState(String op, int slot) { stateSlot = slot; stateOp = op; }
    /** Emu thread, between frames. */
    private void runStateOp() {
        int req = game.stateRequest;
        if (req != 0) {
            game.stateRequest = 0;
            String path = statePath(0).getPath();
            boolean ok = req == 1 ? Core.saveState(path) : new File(path).exists() && Core.loadState(path);
            if (ok && req == 2) game.onStateLoaded();
            Ra.toasts.add(new Ra.Toast(req == 1 ? (ok ? "State saved" : "Couldn't save state") : (ok ? "State loaded" : "No saved state yet"), "", 1500));
        }
        if (pokeAddr != 0) { Core.write8(pokeAddr, pokeVal); pokeAddr = 0; }
        String op = stateOp;
        if (op == null) return;
        stateOp = null;
        String path = statePath(stateSlot).getPath();
        boolean ok = op.equals("save") ? Core.saveState(path) : Core.loadState(path);
        if (ok && op.equals("load")) game.onStateLoaded();
        android.util.Log.i("gbads", "state " + op + " slot " + stateSlot + (ok ? " ok" : " FAILED") + " " + path);
    }
    private int heldKeys() {
        if (debugFrames > 0) { debugFrames--; return keys | stickKeys | debugKeys; }
        return keys | stickKeys;
    }
    private volatile boolean running;
    private volatile String dumpTag; // debug: `adb shell am broadcast -a dev.gbads.DUMP --es tag NAME` -> Download/gbads/NAME.bin (EWRAM+IWRAM)
    private Thread emu;
    private Game game;
    private Presentation bottomPresentation;
    private View top;
    private Bottom bottomView;

    /** A home shortcut (extra "rom") or a frontend (VIEW with a file / storage uri) picks the game to boot. */
    private void adoptLaunchRom(Intent i) {
        if (i == null) return;
        String path = i.getStringExtra("rom");
        if (path == null && i.getData() != null) {
            Uri u = i.getData();
            if ("file".equals(u.getScheme())) path = u.getPath();
            else try { path = docPath(android.provider.DocumentsContract.getDocumentId(u)); } catch (Exception e) { path = null; }
        }
        if (path == null || !new File(path).canRead() || supportedTitle(new File(path)) == null) return;
        getPreferences(MODE_PRIVATE).edit().putString("rom", path).commit();
        if (game != null && !path.equals(currentRom())) restart();
    }
    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); adoptLaunchRom(i); }
    /** Pins a home-screen shortcut that boots straight into the current game. */
    @Override public void addShortcut() {
        String rom = currentRom();
        String title = supportedTitle(new File(rom));
        android.content.pm.ShortcutManager sm = getSystemService(android.content.pm.ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported() || title == null) { toast("This launcher can't add shortcuts"); return; }
        Intent launch = new Intent(this, MainActivity.class).setAction(Intent.ACTION_MAIN).putExtra("rom", rom)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        android.content.pm.ShortcutInfo info = new android.content.pm.ShortcutInfo.Builder(this, "rom:" + rom)
                .setShortLabel(title.replace("Pokémon ", "")).setLongLabel(title)
                .setIcon(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.sym_def_app_icon)) // ponytail: real icon with release prep
                .setIntent(launch).build();
        sm.requestPinShortcut(info, null);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (BuildConfig.DEBUG_TOOLS) registerDebugTools(); // adb test hooks: never in public release builds
    }
    /** adb test hooks (any app could send these, so they only exist in debug / dev builds). See CONTRIBUTING.md. */
    private void registerDebugTools() {
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { dumpTag = i.getStringExtra("tag"); }
        }, new android.content.IntentFilter("dev.gbads.DUMP"), RECEIVER_EXPORTED);
        // debug: `adb shell am broadcast -a dev.gbads.KEY --ei k 1 --ei f 6` holds Core key bits k for f frames (testing)
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { debugKeys = i.getIntExtra("k", 0); debugFrames = i.getIntExtra("f", 6); }
        }, new android.content.IntentFilter("dev.gbads.KEY"), RECEIVER_EXPORTED);
        // debug poke: `adb shell am broadcast -a dev.gbads.POKE --ei a ADDR --ei v BYTE` (test setups only)
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { pokeAddr = i.getIntExtra("a", 0); pokeVal = i.getIntExtra("v", 0); }
        }, new android.content.IntentFilter("dev.gbads.POKE"), RECEIVER_EXPORTED);
        // debug script: `adb shell am broadcast -a dev.gbads.SCRIPT --es hex 7A2001` (bytes; end is added) — test setups only
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { String h = i.getStringExtra("hex"); if (game != null && h != null) game.debugScript(h + "02"); }
        }, new android.content.IntentFilter("dev.gbads.SCRIPT"), RECEIVER_EXPORTED);
        // debug wild battle: `adb shell am broadcast -a dev.gbads.WILD --ei sp SPECIES --ei lv LEVEL [--ei sp2 SPECIES2 (0 = same) for a double]`
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { if (game != null) game.debugWild(i.getIntExtra("sp", 0), i.getIntExtra("lv", 5), i.getIntExtra("sp2", -1)); }
        }, new android.content.IntentFilter("dev.gbads.WILD"), RECEIVER_EXPORTED);
        // save states: `adb shell am broadcast -a dev.gbads.STATE --es op save|load --ei slot N`
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { stateOp = i.getStringExtra("op"); stateSlot = i.getIntExtra("slot", 0); }
        }, new android.content.IntentFilter("dev.gbads.STATE"), RECEIVER_EXPORTED);
    }

    @Override protected void onResume() {
        super.onResume();
        if (game != null) { startEmu(); return; }
        if (!Environment.isExternalStorageManager()) {
            // Saves must live next to the ROM as plain .sav so Syncthing + desktop mGBA see the same file.
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
            return;
        }
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        adoptLaunchRom(getIntent());
        String rom = prefs.getString("rom", null);
        if (rom != null && new File(rom).canRead()) { boot(rom); return; }
        pickRom();
    }

    /** First launch, or Settings > ROM. Choosing a ROM restarts the app on it. */
    @Override public void pickRom() {
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        ArrayList<File> found = new ArrayList<>();
        scan(Environment.getExternalStorageDirectory(), 4, found);
        String[] names = new String[found.size()];
        for (int i = 0; i < names.length; i++) names[i] = found.get(i).getPath().replace(Environment.getExternalStorageDirectory().getPath() + "/", "");
        new AlertDialog.Builder(this).setTitle(found.isEmpty() ? "No .gba files found" : "Pick a ROM")
                .setItems(names, (d, i) -> {
                    prefs.edit().putString("rom", found.get(i).getPath()).commit();
                    if (game == null) boot(found.get(i).getPath()); else restart();
                })
                .setCancelable(game != null).show();
    }

    private static void scan(File dir, int depth, ArrayList<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory() && depth > 0 && !f.getName().equals("Android")) scan(f, depth - 1, out);
            else if (f.getName().toLowerCase().endsWith(".gba")) out.add(f);
        }
    }

    private void boot(String romPath) {
        try {
            game = new Game(new Rom(new File(romPath)));
            if (game.safeMode) toast("Untested Unbound version: DuoBoy's bottom-screen extras are limited.");
        } catch (Exception e) {
            new AlertDialog.Builder(this).setMessage("Can't read ROM: " + e).show();
            return;
        }
        String sav = savePath(romPath);
        top = new View(this) {
            final Paint p = new Paint(), fadePaint = new Paint(); final Rect dst = new Rect(), ovDst = new Rect();
            { p.setFilterBitmap(false); } // hard pixels; Android filters scaled bitmaps by default
            @Override protected void onDraw(Canvas c) {
                // Fill the screen keeping 3:2. ponytail: non-integer (6.75x on the Thor); add an integer-scale option if shimmer bothers anyone
                float s = Math.min(getWidth() / 240f, getHeight() / 160f);
                int w = Math.round(240 * s), h = Math.round(160 * s), ox = (getWidth() - w) / 2, oy = (getHeight() - h) / 2;
                dst.set(ox, oy, ox + w, oy + h);
                synchronized (screen) { c.drawBitmap(screen, null, dst, p); }
                Bitmap over = bottomView != null ? bottomView.battleTopOverlay() : null;
                if (over != null) { // battle menus are on the bottom screen: show the plain message box + prompt here
                    float sc = (dst.bottom - dst.top) / 160f;
                    ovDst.set(dst.left, dst.top + Math.round(112 * sc), dst.right, dst.bottom);
                    c.drawBitmap(over, null, ovDst, p);
                }
                int f = game.fade; // menu-hook fade: 16 steps to black like BeginNormalPaletteFade
                if (f > 0) { fadePaint.setColor((f * 255 / 16) << 24); c.drawRect(dst, fadePaint); }
            }
        };
        top.setBackgroundColor(0xFF000000);
        Bottom bottom = new Bottom(this, game, this);
        bottomView = bottom;

        Display other = null;
        int mine = getWindowManager().getDefaultDisplay().getDisplayId();
        for (Display d : ((DisplayManager) getSystemService(DISPLAY_SERVICE)).getDisplays())
            if (d.getDisplayId() != mine) other = d;
        if (other != null) {
            setContentView(top);
            bottomPresentation = new Presentation(this, other);
            // Not focusable: gamepad keys keep going to this activity while the bottom screen still takes touch.
            bottomPresentation.getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            bottomPresentation.setContentView(bottom);
            bottomPresentation.show();
        } else { // single-screen device: stack them (dev/testing)
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(top, new LinearLayout.LayoutParams(-1, 0, 1));
            col.addView(bottom, new LinearLayout.LayoutParams(-1, 0, 1));
            setContentView(col);
        }

        final int[] rate = {0};
        emu = new Thread(() -> {
            dailyBackup(new File(sav));
            rate[0] = Core.load(romPath, sav);
            if (rate[0] == 0) return;
            Ra.init(getPreferences(MODE_PRIVATE).getBoolean("ra_hardcore", false)); // off by default: RA rejects hardcore from emulators it hasn't approved
            runOnUiThread(() -> startRa(romPath));
            runLoop(rate[0]);
        }, "emu");
        running = true;
        emu.start();
    }

    // ---------- RetroAchievements ----------
    private String romPath;

    private void startRa(String romPath) {
        this.romPath = romPath;
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        Ra.listener = (ok, error, name, tok) -> runOnUiThread(() -> {
            if (ok) {
                prefs.edit().putString("ra_user", name).putString("ra_token", tok).apply(); // token only, never the password
                Ra.loadGame(romPath);
            } else {
                prefs.edit().remove("ra_token").apply();
                Ra.event(-1, "RetroAchievements", error, "");
            }
        });
        String token = prefs.getString("ra_token", null);
        if (token != null) Ra.loginToken(prefs.getString("ra_user", ""), token); // no prompt at startup; log in from Settings
    }

    // ---------- Settings (bottom screen) ----------
    @Override public String raUser() { return getPreferences(MODE_PRIVATE).getString("ra_token", null) == null ? null : getPreferences(MODE_PRIVATE).getString("ra_user", ""); }
    @Override public boolean hardcore() { return getPreferences(MODE_PRIVATE).getBoolean("ra_hardcore", false); }
    @Override public String romName() { String r = getPreferences(MODE_PRIVATE).getString("rom", ""); return r.substring(r.lastIndexOf('/') + 1); }
    @Override public String saveDir() { String d = getPreferences(MODE_PRIVATE).getString("save_dir", null); return d == null ? "Next to ROM" : d.replace(Environment.getExternalStorageDirectory().getPath() + "/", ""); }

    @Override public void raLogout() {
        Ra.logout();
        getPreferences(MODE_PRIVATE).edit().remove("ra_token").apply();
    }
    @Override public void toggleHardcore() {
        boolean on = !hardcore();
        getPreferences(MODE_PRIVATE).edit().putBoolean("ra_hardcore", on).apply();
        Ra.setHardcore(on); // turning it on resets the game (RC_CLIENT_EVENT_RESET), like RetroArch
    }

    /** RetroArch's mGBA core keeps the same raw save bytes as a .srm, so the only difference is the extension. */
    private String saveExt() { return getPreferences(MODE_PRIVATE).getString("save_ext", ".sav"); }
    @Override public String saveType() { return saveExt(); }
    @Override public void toggleSaveType() {
        String rom = getPreferences(MODE_PRIVATE).getString("rom", "");
        String dir = getPreferences(MODE_PRIVATE).getString("save_dir", null);
        switchSave(dir, saveExt().equals(".sav") ? ".srm" : ".sav");
    }

    /** Where the save lives: next to the ROM by default (desktop mGBA's convention), or a chosen folder; .sav or .srm. */
    private String savePath(String rom) {
        String name = rom.substring(rom.lastIndexOf('/') + 1, rom.lastIndexOf('.')) + saveExt();
        String dir = getPreferences(MODE_PRIVATE).getString("save_dir", null);
        return (dir == null ? rom.substring(0, rom.lastIndexOf('/')) : dir) + "/" + name;
    }

    private static final int PICK_SAVE_DIR = 1, EXPORT_SAVE = 2, IMPORT_SAVE = 3, PICK_ROM_DIR = 4, ADD_ROM = 5, PICK_PATCH = 6, PICK_BASE = 7;
    private Uri patchUri;
    /** ROM hacks: pick a patch (IPS/UPS/BPS), then your own base ROM (.gba or .zip); the result joins the ROM list. */
    @Override public void patchRom() {
        toast("Choose the patch (.ips / .ups / .bps)");
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_PATCH);
    }
    private String displayName(Uri u) {
        try (android.database.Cursor c = getContentResolver().query(u, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        String p = u.getLastPathSegment();
        return p == null ? "patched" : p.substring(p.lastIndexOf('/') + 1);
    }
    private void runPatch(Uri patch, Uri base) {
        String patchName = displayName(patch), baseName = displayName(base);
        String basePath = null;
        try { basePath = docPath(android.provider.DocumentsContract.getDocumentId(base)); } catch (Exception ignored) {}
        File dir = !romDir().isEmpty() ? new File(romDir()) : basePath != null ? new File(basePath).getParentFile() : getExternalFilesDir(null);
        File out = new File(dir, patchName.replaceAll("[.][^.]*$", "") + ".gba");
        toast("Patching…");
        new Thread(() -> {
            String msg;
            try (java.io.InputStream pin = getContentResolver().openInputStream(patch); java.io.InputStream bin = getContentResolver().openInputStream(base)) {
                byte[] rom = Patcher.readRom(bin, baseName.toLowerCase().endsWith(".zip"));
                byte[] result = Patcher.apply(Patcher.readAll(pin), rom);
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) { fo.write(result); }
                if (supportedTitle(out) != null) { java.util.LinkedHashSet<String> set = romFiles(); set.add(out.getPath()); saveRomFiles(set); }
                msg = "Created " + out.getName() + (supportedTitle(out) == null ? " (not a supported game yet)" : "");
            } catch (Exception e) { msg = "Patch failed: " + e.getMessage(); }
            String m = msg;
            runOnUiThread(() -> new AlertDialog.Builder(this).setMessage(m).setPositiveButton("OK", null).show());
        }).start();
    }

    // ---- ROMs: a scanned folder + individually added files (supported games only) ----
    /** Storage document id ("primary:Games/GBA", "1234-ABCD:x", "raw:/storage/...") -> file path, or null. */
    private static String docPath(String id) {
        if (id == null) return null;
        if (id.startsWith("raw:")) return id.substring(4);
        int c = id.indexOf(':');
        if (c < 0) return null;
        String vol = id.substring(0, c), rel = id.substring(c + 1);
        if (vol.equals("msf") || vol.equals("msd")) return null; // media-store ids carry no path
        String root = vol.equals("primary") ? Environment.getExternalStorageDirectory().getPath() : "/storage/" + vol;
        return rel.isEmpty() ? root : root + "/" + rel;
    }
    /** Game title if this file is a supported game, else null. ponytail: Unbound only (BPRE + its move-description literal). */
    static String supportedTitle(File f) {
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
            if (r.length() < 0x01000000) return null;
            byte[] code = new byte[4];
            r.seek(0xAC); r.readFully(code);
            if (!new String(code, "US-ASCII").equals("BPRE")) return null;
            r.seek(0xE5440);
            int v = r.read() | r.read() << 8 | r.read() << 16 | r.read() << 24;
            return v == 0x0899F194 ? "Pokémon Unbound" : null;
        } catch (Exception e) { return null; }
    }
    private java.util.List<String[]> romCache;
    private java.util.LinkedHashSet<String> romFiles() {
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        String saved = prefs.getString("rom_files", null);
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        if (saved == null) { // first time: keep the ROM already in use as an added file
            String cur = prefs.getString("rom", null);
            if (cur != null) set.add(cur);
            prefs.edit().putString("rom_files", String.join("\n", set)).apply();
        } else for (String f : saved.split("\n")) if (!f.isEmpty()) set.add(f);
        return set;
    }
    private void saveRomFiles(java.util.Set<String> set) {
        getPreferences(MODE_PRIVATE).edit().putString("rom_files", String.join("\n", set)).apply();
        romCache = null;
    }
    /** {path, title, "dir" | "file"}: the folder's supported games, then the individually added ones. */
    @Override public java.util.List<String[]> roms() {
        if (romCache != null) return romCache;
        java.util.ArrayList<String[]> out = new java.util.ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        String dir = romDir();
        if (!dir.isEmpty()) {
            ArrayList<File> found = new ArrayList<>();
            scan(new File(dir), 3, found);
            for (File f : found) { String t = supportedTitle(f); if (t != null && seen.add(f.getPath())) out.add(new String[]{f.getPath(), t, "dir"}); }
        }
        for (String path : romFiles()) {
            File f = new File(path);
            String t = f.canRead() ? supportedTitle(f) : null;
            if (seen.add(path)) out.add(new String[]{path, t != null ? t : f.getName(), "file"});
        }
        return romCache = out;
    }
    @Override public String romDir() { return getPreferences(MODE_PRIVATE).getString("rom_dir", ""); }
    @Override public void pickRomDir() { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_ROM_DIR); }
    @Override public void clearRomDir() { getPreferences(MODE_PRIVATE).edit().remove("rom_dir").apply(); romCache = null; }
    @Override public void addRom() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), ADD_ROM);
    }
    @Override public void removeRom(String path) { java.util.LinkedHashSet<String> set = romFiles(); set.remove(path); saveRomFiles(set); }
    @Override public String currentRom() { return getPreferences(MODE_PRIVATE).getString("rom", ""); }
    @Override public void playRom(String path) {
        if (path.equals(currentRom())) return;
        getPreferences(MODE_PRIVATE).edit().putString("rom", path).commit();
        restart();
    }

    @Override public void pickSaveDir() {
        new AlertDialog.Builder(this).setTitle("Save folder")
                .setItems(new String[]{"Next to ROM", "Choose folder…"}, (d, i) -> {
                    if (i == 0) switchSaveDir(null);
                    else startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_SAVE_DIR);
                }).show();
    }

    @Override public void exportSave() {
        String rom = getPreferences(MODE_PRIVATE).getString("rom", "");
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, new File(savePath(rom)).getName()), EXPORT_SAVE);
    }

    @Override public void importSave() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT_SAVE);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        String rom = getPreferences(MODE_PRIVATE).getString("rom", "");
        File sav = new File(savePath(rom));
        if (req == PICK_PATCH) {
            patchUri = data.getData();
            toast("Now choose your base ROM (.gba or .zip)");
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_BASE);
            return;
        }
        if (req == PICK_BASE && patchUri != null) { runPatch(patchUri, data.getData()); patchUri = null; return; }
        if (req == PICK_ROM_DIR) {
            String dir = docPath(android.provider.DocumentsContract.getTreeDocumentId(data.getData()));
            if (dir != null) { getPreferences(MODE_PRIVATE).edit().putString("rom_dir", dir).apply(); romCache = null; }
            if (dir != null && roms().stream().noneMatch(r -> r[2].equals("dir"))) toast("No supported games in that folder");
            return;
        }
        if (req == ADD_ROM) {
            String path = docPath(android.provider.DocumentsContract.getDocumentId(data.getData()));
            if (path == null || !new File(path).canRead()) { toast("Pick the file from the device's storage (not Recent)"); return; }
            if (path.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) { // unzip the .gba next to the zip (the core needs a plain file)
                File out = new File(path.substring(0, path.length() - 4) + ".gba");
                try (java.io.InputStream in = new java.io.FileInputStream(path)) {
                    byte[] gba = Patcher.readRom(in, true);
                    if (!out.exists()) try (java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) { fo.write(gba); }
                } catch (Exception e) { toast("Couldn't unzip: " + e.getMessage()); return; }
                path = out.getPath();
            }
            if (supportedTitle(new File(path)) == null) { toast("Not a supported game"); return; }
            java.util.LinkedHashSet<String> set = romFiles(); set.add(path); saveRomFiles(set);
            return;
        }
        try {
            if (req == PICK_SAVE_DIR) {
                // Tree doc id "primary:Saves/GBA" -> /storage/emulated/0/Saves/GBA ; "1234-ABCD:x" -> /storage/1234-ABCD/x
                String id = android.provider.DocumentsContract.getTreeDocumentId(data.getData());
                String vol = id.substring(0, id.indexOf(':')), rel = id.substring(id.indexOf(':') + 1);
                String root = vol.equals("primary") ? Environment.getExternalStorageDirectory().getPath() : "/storage/" + vol;
                switchSaveDir(rel.isEmpty() ? root : root + "/" + rel);
            } else if (req == EXPORT_SAVE) {
                stopEmu();
                try (java.io.InputStream in = new java.io.FileInputStream(sav); java.io.OutputStream out = getContentResolver().openOutputStream(data.getData())) { copy(in, out); }
                startEmu();
                toast("Save exported");
            } else if (req == IMPORT_SAVE) {
                stopEmu();
                backup(sav);
                try (java.io.InputStream in = getContentResolver().openInputStream(data.getData()); java.io.OutputStream out = new java.io.FileOutputStream(sav)) { copy(in, out); }
                restart();
            }
        } catch (Exception e) {
            new AlertDialog.Builder(this).setMessage("Save operation failed: " + e).show();
            startEmu();
        }
    }

    /**
     * Point saves at a new folder. If that folder already holds this game's save, ask which one to keep; the other is
     * never deleted, just renamed to "<name>.backup-<time>" where it already is.
     */
    private void switchSaveDir(String dir) { switchSave(dir, saveExt()); }

    private void switchSave(String dir, String ext) {
        String rom = getPreferences(MODE_PRIVATE).getString("rom", "");
        File cur = new File(savePath(rom));
        String name = rom.substring(rom.lastIndexOf('/') + 1, rom.lastIndexOf('.')) + ext;
        File next = new File(dir == null ? rom.substring(0, rom.lastIndexOf('/')) : dir, name);
        Runnable apply = () -> {
            SharedPreferences.Editor e = getPreferences(MODE_PRIVATE).edit().putString("save_ext", ext);
            if (dir == null) e.remove("save_dir"); else e.putString("save_dir", dir);
            e.commit();
        };
        if (next.getAbsolutePath().equals(cur.getAbsolutePath())) { apply.run(); return; }
        if (next.exists()) {
            new AlertDialog.Builder(this).setTitle("A save already exists there")
                    .setMessage("Keep the app's current save, or the one in that folder? The other is kept as a backup next to where it is now.")
                    .setPositiveButton("Keep app's", (d, i) -> moveSave(cur, next, apply, true))
                    .setNegativeButton("Keep folder's", (d, i) -> moveSave(cur, next, apply, false))
                    .setNeutralButton("Cancel", null).show();
        } else moveSave(cur, next, apply, true);
    }

    private void moveSave(File cur, File next, Runnable apply, boolean keepCurrent) {
        try {
            stopEmu();
            if (keepCurrent) {
                backup(next); // folder's copy (if any) becomes the backup
                if (cur.exists()) try (java.io.InputStream in = new java.io.FileInputStream(cur); java.io.OutputStream out = new java.io.FileOutputStream(next)) { copy(in, out); }
            }
            backup(cur); // old location keeps its file, renamed, so only one live .sav exists
            apply.run();
            restart();
        } catch (Exception e) {
            new AlertDialog.Builder(this).setMessage("Couldn't move save: " + e).show();
            startEmu();
        }
    }

    /** Before each day's first boot: copy the save to the app's save-backups folder, keeping the newest 3 days. */
    private void dailyBackup(File sav) {
        File dir = getExternalFilesDir("save-backups");
        if (dir == null || !sav.exists()) return;
        String day = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(new java.util.Date());
        File out = new File(dir, sav.getName() + "." + day);
        if (out.exists()) return;
        try (java.io.InputStream in = new java.io.FileInputStream(sav); java.io.OutputStream o = new java.io.FileOutputStream(out)) { copy(in, o); }
        catch (java.io.IOException e) { out.delete(); return; }
        File[] old = dir.listFiles((d, n) -> n.startsWith(sav.getName() + "."));
        if (old == null) return;
        java.util.Arrays.sort(old, (x, y) -> y.getName().compareTo(x.getName()));
        for (int i = 3; i < old.length; i++) old[i].delete();
    }
    private static void backup(File f) {
        if (!f.exists()) return;
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date());
        if (!f.renameTo(new File(f.getPath() + ".backup-" + stamp))) throw new IllegalStateException("can't rename " + f);
    }
    private static void copy(java.io.InputStream in, java.io.OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
    }
    private void stopEmu() {
        running = false;
        if (emu != null) try { emu.join(); } catch (InterruptedException ignored) {}
    }
    private void toast(String msg) { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show(); }

    @Override public void raLogin() { raLoginDialog(null); }

    private void raLoginDialog(String error) {
        android.widget.EditText user = new android.widget.EditText(this), pass = new android.widget.EditText(this);
        user.setHint("Username");
        user.setText(getPreferences(MODE_PRIVATE).getString("ra_user", ""));
        pass.setHint("Password");
        pass.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(48, 16, 48, 0);
        form.addView(user); form.addView(pass);
        // No "save password to Google" prompt: keep autofill away from these fields entirely.
        form.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        new AlertDialog.Builder(this).setTitle("RetroAchievements").setMessage(error == null ? "Log in to earn achievements." : error)
                .setView(form)
                .setPositiveButton("Log in", (d, i) -> Ra.loginPassword(user.getText().toString().trim(), pass.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** ROM / save folder changed: relaunch cleanly (one mGBA core and one rc_client per process). */
    private void restart() {
        running = false;
        if (emu != null) try { emu.join(); } catch (InterruptedException ignored) {}
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        Runtime.getRuntime().exit(0);
    }

    private void startEmu() {
        if (running || game == null) return;
        running = true;
        emu = new Thread(() -> runLoop(-1), "emu");
        emu.start();
    }
    private int sampleRate;

    private void runLoop(int rate) {
        if (rate > 0) sampleRate = rate;
        int buf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack audio = new AudioTrack.Builder()
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(buf, 4096 * 4)).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build();
        audio.play();
        short[] samples = new short[16384];
        int frame = 0;
        long t0 = System.nanoTime(), emuNs = 0;
        while (running) {
            long a = System.nanoTime();
            int n = 0;
            // Hidden hook phases (screen frozen) run 4 emulator frames per displayed frame; their audio is dropped.
            for (int rep = game.turbo || fastForward ? 4 : 1; rep > 0 && running; rep--) {
                runStateOp();
                n = Core.frame(game.filterKeys(heldKeys()) | game.macroKeys(), pixels, samples);
                if (rep > 1) { Ra.doFrame(); game.poll(frame++, keys | stickKeys); }
            }
            emuNs += System.nanoTime() - a;
            if (frame % 300 == 299) { // perf log: real fps vs time spent emulating
                long now = System.nanoTime();
                android.util.Log.i("gbads", String.format("fps %.1f, emu %.1f ms/frame, audio %d frames", 300e9 / (now - t0), emuNs / 300e6, n));
                t0 = now; emuNs = 0;
            }
            Ra.doFrame();
            if (Ra.resetRequested) { Ra.resetRequested = false; Core.reset(); }
            game.poll(frame++, keys | stickKeys);
            if (dumpTag != null) { dumpRam(dumpTag); dumpTag = null; }
            if (game.battleStarting || game.battle != null) game.topLuma = luma(pixels);
            pixels.rewind();
            if (!game.freezeTop) synchronized (screen) { screen.copyPixelsFromBuffer(pixels); }
            pixels.rewind();
            top.postInvalidate();
            audio.write(samples, 0, n * 2); // blocking write paces emulation to real time
        }
        audio.stop();
        audio.release();
    }

    /** Mean brightness of a frame (every 16th pixel is plenty). */
    private static int luma(ByteBuffer px) {
        long sum = 0; int n = 0;
        for (int i = 0; i < 240 * 160; i += 16, n++) {
            int c = px.getInt(i * 4);
            sum += ((c & 255) * 3 + (c >> 8 & 255) * 6 + (c >> 16 & 255)) / 10;
        }
        return (int) (sum / n);
    }

    private static void dumpRam(String tag) {
        // EWRAM, IWRAM, then VRAM, palette RAM and I/O registers (for lifting a screen's live graphics)
        byte[] ew = new byte[0x40000], iw = new byte[0x8000], vram = new byte[0x18000], pal = new byte[0x400], io = new byte[0x400];
        Core.read(0x02000000, ew);
        Core.read(0x03000000, iw);
        Core.read(0x06000000, vram);
        Core.read(0x05000000, pal);
        Core.read(0x04000000, io);
        File dir = new File(Environment.getExternalStorageDirectory(), "Download/gbads");
        dir.mkdirs();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(dir, tag + ".bin"))) {
            out.write(ew); out.write(iw); out.write(vram); out.write(pal); out.write(io);
        } catch (java.io.IOException e) { android.util.Log.e("gbads", "dump failed", e); }
    }

    @Override protected void onPause() {
        super.onPause();
        running = false;
        if (emu != null) try { emu.join(); } catch (InterruptedException ignored) {}
    }

    @Override protected void onStop() {
        super.onStop();
        if (bottomPresentation != null) bottomPresentation.dismiss(); // Home sends both screens home
    }

    @Override protected void onStart() {
        super.onStart();
        if (bottomPresentation != null && !bottomPresentation.isShowing()) bottomPresentation.show();
    }

    @Override protected void onDestroy() {
        if (bottomPresentation != null) bottomPresentation.dismiss();
        super.onDestroy();
    }

    // ---------- input ----------
    private static int map(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A: return Core.A;
            case KeyEvent.KEYCODE_BUTTON_B: return Core.B;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return Core.SELECT;
            case KeyEvent.KEYCODE_BUTTON_START: return Core.START;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return Core.RIGHT;
            case KeyEvent.KEYCODE_DPAD_LEFT: return Core.LEFT;
            case KeyEvent.KEYCODE_DPAD_UP: return Core.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return Core.DOWN;
            case KeyEvent.KEYCODE_BUTTON_R1: return Core.R;
            case KeyEvent.KEYCODE_BUTTON_L1: return Core.L;
            default: return 0;
        }
    }
    /** Held R2: 4x fast forward (sound speeds up too). */
    private volatile boolean fastForward;
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BUTTON_R2) { fastForward = e.getAction() != KeyEvent.ACTION_UP; return true; }
        int k = map(e.getKeyCode());
        if (k == 0) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) keys |= k;
        else if (e.getAction() == KeyEvent.ACTION_UP) keys &= ~k;
        return true;
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) == 0) return super.dispatchGenericMotionEvent(e);
        float x = e.getAxisValue(MotionEvent.AXIS_X) + e.getAxisValue(MotionEvent.AXIS_HAT_X);
        float y = e.getAxisValue(MotionEvent.AXIS_Y) + e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        stickKeys = (x > .5f ? Core.RIGHT : 0) | (x < -.5f ? Core.LEFT : 0) | (y > .5f ? Core.DOWN : 0) | (y < -.5f ? Core.UP : 0);
        return true;
    }
}
