package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local patch: copies the wallpaper collection of a GitHub repository (wallpapers/ and its sub-folders) into the
 *  launcher's flat wallpapers folder. Pictures already there with the same size are skipped. */
final class WallpaperDownloader {
    static final String REPO = "KirillCh143/LiquidTV", BRANCH = "main";

    interface Listener {
        void onProgress(int done, int total, String name);
        void onDone(int added, int skipped);
        void onError();
    }

    private WallpaperDownloader() {}

    /** Runs on its own thread; every callback comes on the main thread and none after cancel is set. */
    static void start(Context ctx, AtomicBoolean cancel, Listener listener) {
        Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                File dir = new File(Config.dir(ctx), ThemeFiles.WALLPAPERS);
                dir.mkdirs();
                JSONArray tree = new JSONObject(new String(read("https://api.github.com/repos/" + REPO
                        + "/git/trees/" + BRANCH + "?recursive=1"), "UTF-8")).getJSONArray("tree");
                int total = 0;
                String[] paths = new String[tree.length()];
                long[] sizes = new long[tree.length()];
                for (int i = 0; i < tree.length(); i++) {
                    JSONObject e = tree.getJSONObject(i);
                    String path = e.getString("path");
                    String name = path.substring(path.lastIndexOf('/') + 1);
                    if ("blob".equals(e.getString("type")) && path.startsWith(ThemeFiles.WALLPAPERS + "/")
                            && ThemeFiles.collectionImage(name) && e.optLong("size") <= 10 * 1024 * 1024) {
                        paths[total] = path;
                        sizes[total++] = e.optLong("size");
                    }
                }
                int added = 0, skipped = 0;
                for (int i = 0; i < total && !cancel.get(); i++) {
                    String name = paths[i].substring(paths[i].lastIndexOf('/') + 1);
                    final int index = i + 1, count = total;
                    ui.post(() -> { if (!cancel.get()) listener.onProgress(index, count, name); });
                    // wallpapers/<Group>/<file> keeps its group folder; a flat copy from an older download moves in.
                    String[] parts = paths[i].split("/");
                    File folder = parts.length >= 3 ? new File(dir, parts[1]) : dir;
                    folder.mkdirs();
                    if (folder != dir) new File(dir, name).delete();
                    File target = new File(folder, name);
                    if (target.isFile() && target.length() == sizes[i]) { skipped++; continue; }
                    File part = new File(folder, name + ".part");
                    HttpURLConnection c = open("https://raw.githubusercontent.com/" + REPO + "/" + BRANCH + "/" + paths[i]);
                    try (InputStream in = c.getInputStream(); OutputStream out = new java.io.FileOutputStream(part)) {
                        byte[] buf = new byte[16384];
                        for (int n; (n = in.read(buf)) > 0 && !cancel.get(); ) out.write(buf, 0, n);
                    } finally {
                        c.disconnect();
                    }
                    if (cancel.get()) { part.delete(); break; }
                    target.delete();
                    if (!part.renameTo(target)) throw new java.io.IOException("rename");
                    added++;
                }
                final int a = added, s = skipped;
                ui.post(() -> { if (!cancel.get()) listener.onDone(a, s); });
            } catch (Exception e) {
                ui.post(() -> { if (!cancel.get()) listener.onError(); });
            }
        }, "wallpaper-download").start();
    }

    private static HttpURLConnection open(String url) throws java.io.IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "liquidOS");
        if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
        return c;
    }

    private static byte[] read(String url) throws java.io.IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
