package com.gathin.tvhome;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Local patch, not upstream: the launcher's one network feature. Streams Apple TV's aerial videos the way Lampa's
 * "aerial" screensaver does: the list is the Aerial project's videos.json on GitHub (cached in the app's files, so
 * it still starts offline after one good fetch), each video a 1080p H.264 stream from Apple's CDN, played muted in
 * shuffled order, with the place name in a label. Needs the INTERNET permission, which the stock launcher
 * deliberately does not have. Used by AerialDream (the system screensaver) and AerialActivity (the Settings grid's
 * Screensaver tile, which starts it straight away).
 */
final class AerialPlayer implements TextureView.SurfaceTextureListener {

    private static final String LIST_URL = "https://raw.githubusercontent.com/OrangeJedi/Aerial/master/videos.json";
    private static final String CACHE_NAME = "aerial_videos.json";
    /** Give up after this many videos in a row that would not play (no network, a dead link). */
    private static final int MAX_FAILURES = 6;

    private final Context context;
    private final TextureView texture;
    private final TextView label;
    /** Called on the main thread when no video list could be had at all (no cache and no network). */
    private final Runnable onUnavailable;

    private static final class Video {
        final String url, label;
        Video(String url, String label) {
            this.url = url;
            this.label = label;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Video> videos = new ArrayList<>();
    private SurfaceTexture surfaceTexture;
    private MediaPlayer player;
    private boolean running;
    private int index, failures;

    AerialPlayer(Context context, TextureView texture, TextView label, Runnable onUnavailable) {
        this.context = context;
        this.texture = texture;
        this.label = label;
        this.onUnavailable = onUnavailable;
        texture.setSurfaceTextureListener(this);
    }

    void start() {
        running = true;
        failures = 0;
        final File cache = new File(context.getFilesDir(), CACHE_NAME);
        new Thread(() -> {
            List<Video> list = parse(read(cache));
            boolean fromCache = !list.isEmpty();
            if (!fromCache) {
                String fresh = download();
                if (fresh != null) {
                    write(cache, fresh);
                    list = parse(fresh);
                }
            }
            final List<Video> ready = list;
            main.post(() -> {
                if (!running) return;
                if (ready.isEmpty()) {
                    if (onUnavailable != null) onUnavailable.run();
                    return;
                }
                videos.clear();
                videos.addAll(ready);
                Collections.shuffle(videos);
                index = 0;
                playNext();
            });
            if (fromCache) {
                // Started from the cache; refresh it for next time.
                String fresh = download();
                if (fresh != null) write(cache, fresh);
            }
        }, "AerialList").start();
    }

    void stop() {
        running = false;
        releasePlayer();
    }

    private void playNext() {
        if (!running || videos.isEmpty() || surfaceTexture == null || failures >= MAX_FAILURES) return;
        releasePlayer();
        Video video = videos.get(index++ % videos.size());
        label.setText(video.label);
        texture.setAlpha(0f);
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setSurface(new Surface(surfaceTexture));
            mp.setVolume(0f, 0f);
            mp.setDataSource(video.url);
            mp.setOnPreparedListener(p -> {
                if (p != player) return;
                failures = 0;
                p.start();
                texture.animate().alpha(1f).setDuration(900).start();
            });
            mp.setOnCompletionListener(p -> {
                if (p != player) return;
                texture.animate().alpha(0f).setDuration(500).withEndAction(this::playNext).start();
            });
            mp.setOnErrorListener((p, what, extra) -> {
                if (p != player) return true;
                failures++;
                main.post(this::playNext);
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            failures++;
            main.post(this::playNext);
        }
    }

    private void releasePlayer() {
        texture.animate().cancel();
        MediaPlayer mp = player;
        player = null;
        if (mp != null) {
            try {
                mp.release();
            } catch (RuntimeException ignored) {
            }
        }
    }

    // ---- the video list --------------------------------------------------------------------------

    /** videos.json: one object per video, with its place (accessibilityLabel + name) and src.H2641080p. */
    private static List<Video> parse(String json) {
        List<Video> out = new ArrayList<>();
        if (json == null) return out;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                JSONObject src = o.optJSONObject("src");
                String url = src == null ? "" : src.optString("H2641080p", "");
                if (!url.startsWith("https://sylvan.apple.com/")) continue;
                // Android does not trust that host's HTTPS certificate; Lampa plays the files over plain HTTP too
                // (network_security_config.xml allows cleartext for this one host).
                url = "http://" + url.substring("https://".length());
                String place = o.optString("accessibilityLabel", "");
                String name = o.optString("name", "");
                String text = place.isEmpty() ? name : name.isEmpty() ? place : place + " – " + name;
                out.add(new Video(url, text));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String download() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(LIST_URL).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(15000);
            if (connection.getResponseCode() != 200) return null;
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    bytes.write(buffer, 0, n);
                    if (bytes.size() > 4 * 1024 * 1024) return null;   // far bigger than the real list
                }
                String text = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                return parse(text).isEmpty() ? null : text;   // keep only a list that parses
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String read(File file) {
        if (!file.isFile() || file.length() > 4 * 1024 * 1024) return null;
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int total = 0, n;
            while (total < bytes.length && (n = in.read(bytes, total, bytes.length - total)) > 0) total += n;
            return new String(bytes, 0, total, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(File file, String text) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    // ---- TextureView.SurfaceTextureListener ------------------------------------------------------

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
        surfaceTexture = st;
        if (running && !videos.isEmpty() && player == null) playNext();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) { }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        releasePlayer();
        surfaceTexture = null;
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) { }
}
