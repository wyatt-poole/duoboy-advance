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
    /** DuoBoy's own folder on shared storage: survives an uninstall (the app-private dir doesn't). Old files are moved over once. */
    private File appDir(String sub) {
        File dir = new File(Environment.getExternalStorageDirectory(), "DuoBoy Advance/" + sub);
        dir.mkdirs();
        File old = getExternalFilesDir(sub);
        File[] fs = old == null ? null : old.listFiles();
        if (fs != null) for (File f : fs) if (f.isFile() && !new File(dir, f.getName()).exists()) f.renameTo(new File(dir, f.getName()));
        return dir;
    }
    /** One file per ROM and slot (not next to the save: states aren't Syncthing'd). */
    File statePath(int slot) {
        String rom = getPreferences(MODE_PRIVATE).getString("rom", "rom");
        return new File(appDir("states"), new File(rom).getName().replaceAll("[.]gba$", "") + ".ss" + slot);
    }
    void requestState(String op, int slot) { stateSlot = slot; stateOp = op; }
    /** One tap: download the official Unbound patch (hosted with the author's permission), patch a FireRed v1.0 found on the device, delete the download. */
    static final String UNBOUND_PATCH_URL = "https://github.com/wyatt-poole/duoboy-advance/releases/download/v0.2.1/Pokemon-Unbound-2.1.1.1-patch.zip";
    @Override public void installUnbound() {
        ArrayList<File> all = new ArrayList<>(), bases = new ArrayList<>();
        scan(Environment.getExternalStorageDirectory(), 4, all);
        for (File f : all) if (f.getName().toLowerCase().contains("fire") && supportedTitle(f) == null) bases.add(f); // FireRed candidates, not already Unbound
        if (bases.isEmpty()) { toast("Put your Pokémon FireRed (USA) v1.0 ROM (.gba or .zip) on the device first"); return; }
        toast("Downloading the Unbound patch (17 MB)…");
        new Thread(() -> {
            File tmp = new File(getCacheDir(), "unbound-patch.zip");
            String msg;
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(UNBOUND_PATCH_URL).openConnection();
                c.setInstanceFollowRedirects(true);
                try (java.io.InputStream in = c.getInputStream(); java.io.OutputStream o = new java.io.FileOutputStream(tmp)) { copy(in, o); }
                byte[] ups = null;
                try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(new java.io.FileInputStream(tmp))) {
                    for (java.util.zip.ZipEntry e; (e = z.getNextEntry()) != null; ) if (e.getName().toLowerCase().endsWith(".ups")) { ups = Patcher.readAll(z); break; }
                }
                if (ups == null) throw new java.io.IOException("no .ups in the download");
                File made = null; String why = "";
                for (File base : bases) { // the patch checks the base ROM's CRC, so a wrong file is simply skipped
                    try (java.io.InputStream in = new java.io.FileInputStream(base)) {
                        byte[] out = Patcher.apply(ups, Patcher.readRom(in, base.getName().toLowerCase().endsWith(".zip")));
                        made = new File(base.getParentFile(), "Pokemon Unbound.gba");
                        try (java.io.FileOutputStream fo = new java.io.FileOutputStream(made)) { fo.write(out); }
                        break;
                    } catch (Exception e) { why = e.getMessage(); }
                }
                if (made == null) throw new java.io.IOException("none of " + bases.size() + " FireRed file(s) is a clean (USA) v1.0 ROM: " + why);
                java.util.LinkedHashSet<String> set = romFiles(); set.add(made.getPath()); saveRomFiles(set);
                msg = "Pokémon Unbound is ready: " + made.getName() + "\n\nOpen Settings > ROMs to play it.";
            } catch (Exception e) { msg = "Couldn't install Unbound: " + e.getMessage(); }
            tmp.delete(); // the download isn't kept
            String m = msg;
            runOnUiThread(() -> new AlertDialog.Builder(this).setMessage(m).setPositiveButton("OK", null).show());
        }).start();
    }
    @Override public void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { toast("No browser found"); }
    }
    @Override public java.util.List<String[]> stateSlots() {
        java.util.ArrayList<String[]> out = new java.util.ArrayList<>();
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.US);
        for (int i = 0; i < 100; i++) { File st = statePath(i); if (st.exists()) out.add(new String[]{String.valueOf(i), f.format(new java.util.Date(st.lastModified()))}); }
        return out;
    }
    /** Start+Select → Screenshot: both screens as shown, stacked like the handheld, 4x, into Pictures/DuoBoy Advance. */
    private void saveScreenshot() {
        Bitmap shot = Bitmap.createBitmap(248, 376, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(shot);
        c.drawColor(0xFF000000);
        synchronized (screen) { c.drawBitmap(screen, 4, 0, null); }
        Bitmap over = bottomView != null ? bottomView.battleTopOverlay() : null;
        if (over != null) c.drawBitmap(over, 4, 112, null);
        if (bottomView != null) c.drawBitmap(bottomView.snapshot(), 0, 160, null);
        Bitmap big = Bitmap.createScaledBitmap(shot, 248 * 4, 376 * 4, false);
        String name = "DuoBoy_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date())
                + "_" + (bottomView != null ? bottomView.viewTag() : "top") + ".png"; // where it was taken, for bug reports
        android.content.ContentValues v = new android.content.ContentValues();
        v.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png");
        v.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DuoBoy Advance");
        Uri uri = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        boolean ok = false;
        if (uri != null) try (java.io.OutputStream o = getContentResolver().openOutputStream(uri)) { ok = big.compress(Bitmap.CompressFormat.PNG, 100, o); } catch (Exception ignored) {}
        Ra.toasts.add(new Ra.Toast(ok ? "Screenshot saved" : "Couldn't save screenshot", "", 1500));
    }
    /** Emu thread, between frames. */
    private void runStateOp() {
        int req = game.stateRequest;
        if (req != 0) {
            game.stateRequest = 0;
            int slot = req == 2 ? game.stateLoadSlot : 0;
            String path = statePath(slot).getPath();
            boolean ok = req == 1 ? Core.saveState(path) : new File(path).exists() && Core.loadState(path);
            if (ok && req == 2) game.onStateLoaded();
            Ra.toasts.add(new Ra.Toast(req == 1 ? (ok ? "State saved" : "Couldn't save state") : (ok ? (slot == 0 ? "State loaded" : "Slot " + slot + " loaded") : "No saved state yet"), "", 1500));
        }
        if (game.screenshotReq > 0 && --game.screenshotReq == 0) runOnUiThread(this::saveScreenshot); // after the menu has closed
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
                .setIcon(android.graphics.drawable.Icon.createWithResource(this, R.mipmap.ic_launcher))
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
            @Override public void onReceive(Context c, Intent i) { debugKeys = i.getIntExtra("k", 0); debugFrames = i.getIntExtra("f", 6); if (i.hasExtra("classic")) game.setClassic(i.getIntExtra("classic", 0) != 0); }
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
        // fast forward for tests: `adb shell am broadcast -a dev.gbads.FF --ei on 1|0`
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { fastForward = i.getIntExtra("on", 0) != 0; }
        }, new android.content.IntentFilter("dev.gbads.FF"), RECEIVER_EXPORTED);
        // first-run ROM picker: `adb shell am broadcast -a dev.gbads.PICK` (tests the picker / bad-ROM recovery from a running game)
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { runOnUiThread(() -> pickRom()); }
        }, new android.content.IntentFilter("dev.gbads.PICK"), RECEIVER_EXPORTED);
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

    /** First launch, or Settings > ROM. Supported games are listed first and marked; a .zip is unpacked next to itself. */
    @Override public void pickRom() {
        ArrayList<File> found = new ArrayList<>(), ordered = new ArrayList<>();
        scan(Environment.getExternalStorageDirectory(), 4, found);
        String root = Environment.getExternalStorageDirectory().getPath() + "/";
        ArrayList<String> names = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) // supported first
            for (File f : found) {
                String t = f.getName().toLowerCase().endsWith(".gba") ? supportedTitle(f) : null;
                if ((t != null) != (pass == 0)) continue;
                ordered.add(f);
                names.add((t != null ? "\u2713 " + t + "\n" : "") + f.getPath().replace(root, ""));
            }
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(found.isEmpty() ? "No ROMs found" : "Pick a ROM")
                .setPositiveButton("Browse\u2026", (d, i) -> addRom())
                .setNeutralButton("Rescan", (d, i) -> pickRom())
                .setCancelable(game != null);
        if (found.isEmpty()) b.setMessage("Copy your Pok\u00e9mon Unbound .gba (or .zip) to the device, then tap Rescan.");
        else b.setItems(names.toArray(new String[0]), (d, i) -> useRom(ordered.get(i).getPath()));
        b.show();
    }
    /** A chosen ROM file (.gba or .zip): unzip if needed, then boot or restart on it. */
    private void useRom(String path) {
        if (path.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
            File out = new File(path.substring(0, path.length() - 4) + ".gba");
            try (java.io.InputStream in = new java.io.FileInputStream(path)) {
                byte[] gba = Patcher.readRom(in, true);
                if (!out.exists()) try (java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) { fo.write(gba); }
            } catch (Exception e) { toast("Couldn't unzip: " + e.getMessage()); if (game == null) pickRom(); return; }
            path = out.getPath();
        }
        getPreferences(MODE_PRIVATE).edit().putString("rom", path).commit();
        if (game == null) boot(path); else restart();
    }

    /** Only zips that hold a .gba (not patches, drivers, ...): reads the zip's directory, no unpacking. */
    private static boolean zipHasGba(File f) {
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(f)) {
            for (java.util.Enumeration<? extends java.util.zip.ZipEntry> e = z.entries(); e.hasMoreElements(); )
                if (e.nextElement().getName().toLowerCase().endsWith(".gba")) return true;
        } catch (Exception ignored) {}
        return false;
    }
    private static void scan(File dir, int depth, ArrayList<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName().toLowerCase();
            if (f.isDirectory() && depth > 0 && !f.getName().equals("Android")) scan(f, depth - 1, out);
            else if (n.endsWith(".gba") || n.endsWith(".zip") && f.length() > 0x400000 && f.length() < 0x4000000 && zipHasGba(f)) out.add(f);
        }
    }

    /** A save file with nothing in it yet (fresh flash is all 0xFF), e.g. the one a first boot creates. */
    private static boolean blankSave(File f) {
        if (!f.exists()) return true;
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) for (int i = 0; i < n; i++) if (buf[i] != (byte) 0xFF) return false;
        } catch (java.io.IOException e) { return false; }
        return true;
    }
    /** Where a save for this game may already be, from other emulators: same folder, then RetroArch's. */
    private File findExistingSave(String rom) {
        String name = rom.substring(rom.lastIndexOf('/') + 1, rom.lastIndexOf('.')), dir = rom.substring(0, rom.lastIndexOf('/'));
        String sd = Environment.getExternalStorageDirectory().getPath();
        for (String d : new String[]{dir, sd + "/RetroArch/saves/mGBA", sd + "/RetroArch/saves", sd + "/mGBA/saves", sd + "/Saves"})
            for (String ext : new String[]{".srm", ".sav"}) { File f = new File(d, name + ext); if (f.length() >= 0x8000 && !blankSave(f)) return f; }
        return null;
    }
    private void boot(String romPath) {
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        String romKey = "setup:" + romPath;
        if (!prefs.getBoolean(romKey, false) && blankSave(new File(savePath(romPath)))) { // first boot of this ROM: adopt a save it already has
            File found = findExistingSave(romPath);
            if (found != null) {
                String shown = found.getPath().replace(Environment.getExternalStorageDirectory().getPath() + "/", "");
                new AlertDialog.Builder(this).setTitle("Existing save found")
                        .setMessage("There's already a save for this game at\n" + shown + "\n\nUse it? DuoBoy keeps saving there, so other emulators see the same progress.")
                        .setPositiveButton("Use it", (d, i) -> {
                            String ext = found.getName().substring(found.getName().lastIndexOf('.'));
                            SharedPreferences.Editor e = prefs.edit().putBoolean(romKey, true).putString("save_ext", ext);
                            if (found.getParent().equals(romPath.substring(0, romPath.lastIndexOf('/')))) e.remove("save_dir"); else e.putString("save_dir", found.getParent());
                            e.commit();
                            boot(romPath);
                        })
                        .setNegativeButton("Start fresh", (d, i) -> { prefs.edit().putBoolean(romKey, true).commit(); boot(romPath); })
                        .setCancelable(false).show();
                return;
            }
        }
        prefs.edit().putBoolean(romKey, true).apply();
        appDir("states"); // move any states / backups out of the app-private folder now, not on first use
        String sav = savePath(romPath);
        try {
            game = new Game(new Rom(new File(romPath)));
            if (game.safeMode) toast("Untested Unbound version: DuoBoy's bottom-screen extras are limited.");
            if (prefs.getBoolean("classic", false)) { game.setClassic(true); classicShown = false; } // applied on the first top-screen draw
            buildScreens(romPath, sav);
        } catch (Throwable e) { // a ROM we can't run (not Unbound, truncated, ...): never crash-loop on it
            android.util.Log.e("gbads", "can't boot " + romPath, e);
            game = null; bottomView = null;
            prefs.edit().remove("rom").commit();
            String title = supportedTitle(new File(romPath));
            new AlertDialog.Builder(this).setTitle("Can't run this ROM")
                    .setMessage(new File(romPath).getName() + (title == null ? "\n\nOnly Pok\u00e9mon Unbound 2.1.1.1 is supported right now." : "\n\n" + e))
                    .setPositiveButton("Pick another", (d, i) -> pickRom()).setCancelable(false).show();
        }
    }
    private void buildScreens(String romPath, String sav) {
        top = new View(this) {
            final Paint p = new Paint(), fadePaint = new Paint(); final Rect dst = new Rect(), ovDst = new Rect(), menuDst = new Rect();
            { p.setFilterBitmap(false); } // hard pixels; Android filters scaled bitmaps by default
            @Override protected void onDraw(Canvas c) {
                // Fill the screen keeping 3:2. ponytail: non-integer (6.75x on the Thor); add an integer-scale option if shimmer bothers anyone
                float s = Math.min(getWidth() / 240f, getHeight() / 160f);
                if (pixelPerfect()) s = Math.max(1, (float) Math.floor(s)); // whole multiples: every game pixel the same size
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
                if (game.classic != classicShown) applyClassic(game.classic);
                if (game.classic && game.resetPrompt && bottomView != null) { // the pause menu, d-pad driven, over the game
                    Bitmap m = bottomView.pauseMenuForTop();
                    float ms = Math.min(getWidth() / (float) Bottom.W, getHeight() / (float) Bottom.H);
                    int mw = Math.round(Bottom.W * ms), mh = Math.round(Bottom.H * ms), mx = (getWidth() - mw) / 2, my = (getHeight() - mh) / 2;
                    menuDst.set(mx, my, mx + mw, my + mh);
                    c.drawBitmap(m, null, menuDst, p);
                }
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
    @Override public boolean pixelPerfect() { return getPreferences(MODE_PRIVATE).getBoolean("pixel_perfect", false); }
    @Override public void togglePixelPerfect() { getPreferences(MODE_PRIVATE).edit().putBoolean("pixel_perfect", !pixelPerfect()).apply(); if (top != null) top.postInvalidate(); }
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
            String m = msg; boolean ok = !msg.startsWith("Patch failed");
            runOnUiThread(() -> {
                AlertDialog.Builder b = new AlertDialog.Builder(this).setMessage(ok ? m + "\n\nDelete the patch file " + patchName + "? (The patched game is kept.)" : m);
                if (ok) b.setPositiveButton("Delete", (d, i) -> { try { android.provider.DocumentsContract.deleteDocument(getContentResolver(), patch); toast("Patch file deleted"); } catch (Exception e) { toast("Couldn't delete it"); } })
                        .setNegativeButton("Keep", null);
                else b.setPositiveButton("OK", null);
                b.show();
            });
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
        if (res != RESULT_OK || data == null || data.getData() == null) { if (game == null) pickRom(); return; } // first run: back to the list
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
            if (supportedTitle(new File(path)) == null) { toast("Not a supported game"); if (game == null) pickRom(); return; }
            java.util.LinkedHashSet<String> set = romFiles(); set.add(path); saveRomFiles(set);
            if (game == null) useRom(path);
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
        File dir = appDir("save-backups");
        if (!sav.exists()) return;
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
    /** Messages look like the game's once the bottom screen is up; before that (first run) Android's own toast. */
    private void toast(String msg) {
        if (bottomView != null) Ra.toasts.add(new Ra.Toast(msg, "", 2500));
        else android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

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
        game.volume = getPreferences(MODE_PRIVATE).getInt("volume", 100);
        int appliedVolume = -1;
        short[] samples = new short[16384];
        int frame = 0;
        long t0 = System.nanoTime(), emuNs = 0;
        while (running) {
            long a = System.nanoTime();
            int n = 0;
            // Hidden hook phases (screen frozen) run 4 emulator frames per displayed frame; their audio is dropped.
            for (int rep = game.turbo || fastForward || game.ffLatched ? 4 : 1; rep > 0 && running; rep--) {
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
            if (game.volume != appliedVolume) { appliedVolume = game.volume; audio.setVolume(appliedVolume / 100f); getPreferences(MODE_PRIVATE).edit().putInt("volume", appliedVolume).apply(); }
            Ra.doFrame();
            if (Ra.resetRequested) { Ra.resetRequested = false; Core.reset(); }
            game.poll(frame++, keys | stickKeys);
            if (dumpTag != null) { dumpRam(dumpTag); dumpTag = null; }
            if (game.battleStarting || game.battle != null) game.topLuma = luma(pixels);
            pixels.rewind();
            if (game.skipTopFrame || game.skipTopFrames > 0) { game.skipTopFrame = false; if (game.skipTopFrames > 0) game.skipTopFrames--; } // a frame we don't show: keep the previous one
            else if (!game.freezeTop) synchronized (screen) { screen.copyPixelsFromBuffer(pixels); }
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
        if (bottomPresentation != null && !bottomPresentation.isShowing() && !(game != null && game.classic)) bottomPresentation.show();
    }
    private boolean classicShown;
    /** Classic mode on: release the second screen (dismiss our window there); off: take it back. Remembered. */
    private void applyClassic(boolean on) {
        classicShown = on;
        getPreferences(MODE_PRIVATE).edit().putBoolean("classic", on).apply();
        if (bottomPresentation == null) return;
        if (on) { if (bottomPresentation.isShowing()) bottomPresentation.dismiss(); }
        else if (!bottomPresentation.isShowing()) bottomPresentation.show();
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
