package dev.gbads;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** RetroAchievements: rcheevos in native code, HTTP and UI hooks here. */
final class Ra {
    static native void init(boolean hardcore);
    static native void loginPassword(String user, String pass);
    static native void loginToken(String user, String token);
    static native void loadGame(String romPath);
    static native void doFrame();
    static native void idle();
    static native boolean hardcore();
    static native void logout();
    static native void setHardcore(boolean on);
    static native void reset();
    static final int EVENT_RESET = 14; // RC_CLIENT_EVENT_RESET
    /** Set by RC_CLIENT_EVENT_RESET; the emu thread resets the core. */
    static volatile boolean resetRequested;
    private static native void response(long cb, long cbData, byte[] body, int status);

    interface Listener { void loggedIn(boolean ok, String error, String user, String token); }
    static volatile Listener listener;

    static final class Toast {
        final String title, desc; volatile Bitmap badge; final long until;
        Toast(String t, String d, long ms) { title = t; desc = d; until = System.currentTimeMillis() + ms; }
    }
    /** Popups for the bottom screen, oldest first. */
    static final ConcurrentLinkedQueue<Toast> toasts = new ConcurrentLinkedQueue<>();

    private static final ExecutorService http = Executors.newFixedThreadPool(2);
    // Honest client identity; RA uses it to tell emulators apart.
    private static final String UA = "DuoBoyAdvance/0.1 (Android) rcheevos";

    // ---- called from native ----
    static void request(String url, String post, String contentType, long cb, long cbData) {
        http.execute(() -> {
            byte[] body = null; int status = 0;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestProperty("User-Agent", UA);
                c.setConnectTimeout(15000); c.setReadTimeout(30000);
                if (post != null) {
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", contentType != null ? contentType : "application/x-www-form-urlencoded");
                    try (OutputStream o = c.getOutputStream()) { o.write(post.getBytes("UTF-8")); }
                }
                status = c.getResponseCode();
                InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
                body = in == null ? new byte[0] : readAll(in);
            } catch (Exception e) {
                Log.w("gbads", "RA request failed: " + e);
                status = -2; // RC_API_SERVER_RESPONSE_RETRYABLE_CLIENT_ERROR: rc_client retries (offline unlocks get sent later)
            }
            response(cb, cbData, body, status);
        });
    }

    static void loggedIn(int result, String error, String user, String token) {
        Listener l = listener;
        if (l != null) l.loggedIn(result == 0, error, user, token);
    }

    static void event(int kind, String title, String desc, String badgeUrl) {
        if (kind == EVENT_RESET) resetRequested = true;
        Toast t = new Toast(title, desc, kind == -2 ? 5000 : 4000);
        toasts.add(t);
        if (!badgeUrl.isEmpty()) http.execute(() -> {
            try (InputStream in = new URL(badgeUrl).openStream()) { t.badge = BitmapFactory.decodeStream(in); }
            catch (Exception e) { Log.w("gbads", "badge: " + e); }
        });
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }
}
