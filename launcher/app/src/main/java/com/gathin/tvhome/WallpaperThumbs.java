package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.List;

/**
 * Local patch: the small rounded previews in the wallpaper picker, kept so they are not drawn again each time.
 * Memory first (survives leaving the settings), then a PNG per preview in the app's cache folder (survives a
 * restart, and Android may clear it), and only then decoded from the picture. A picture that changes (size or date)
 * or a different preview size gets a new key, so a stale preview is never shown.
 */
final class WallpaperThumbs {
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final String DIR = "wallpaper-thumbs";
    private static final Object LOCK = new Object();

    private WallpaperThumbs() {}

    /** The cache key: a downloaded picture by path, size and date; a built-in one by its id and render version. */
    static String key(Context context, File file, String choice, int w, int h) {
        String base = file != null ? file.getAbsolutePath() + "|" + file.length() + "|" + file.lastModified()
                : "builtin|" + choice + "|v2";
        return base + "|" + w + "x" + h;
    }

    /** The preview if it is already in memory; never blocks. */
    static Bitmap peek(String key) {
        return MEMORY.get(key);
    }

    /** The preview from memory, the disk cache or the picture itself (the built-in ones are drawn first if need be). */
    static Bitmap load(Context context, String key, File file, String choice, int w, int h, float radius) {
        synchronized (LOCK) {
            Bitmap cached = MEMORY.get(key);
            if (cached != null) return cached;
            File stored = new File(new File(context.getCacheDir(), DIR), name(key));
            if (stored.isFile()) {
                Bitmap fromDisk = BitmapFactory.decodeFile(stored.getPath());
                if (fromDisk != null) {
                    MEMORY.put(key, fromDisk);
                    return fromDisk;
                }
            }
            File source = file != null ? file : DefaultWallpaper.fileFor(context, choice);
            Bitmap made = draw(source, w, h, radius);
            if (made == null) return null;
            MEMORY.put(key, made);
            stored.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(stored)) {
                made.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (Exception ignored) {
                stored.delete();   // a half-written file must not be read back
            }
            return made;
        }
    }

    /** Makes every preview of the picker ahead of time, on a background thread. */
    static void prefetch(Context context, int w, int h, float radius) {
        Thread thread = new Thread(() -> {
            File dir = Config.dir(context);
            File root = dir == null ? null : new File(dir, ThemeFiles.WALLPAPERS);
            load(context, key(context, null, DefaultWallpaper.CHOICE, w, h), null, DefaultWallpaper.CHOICE, w, h, radius);
            for (String[] extra : DefaultWallpaper.EXTRA) {
                load(context, key(context, null, extra[0], w, h), null, extra[0], w, h, radius);
            }
            List<File> images = WallpaperRotation.images(root);
            for (File image : images) load(context, key(context, image, image.getName(), w, h), image, image.getName(), w, h, radius);
        }, "wallpaper-thumbs");
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static String name(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(key.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex + ".png";
        } catch (Exception e) {
            return Integer.toHexString(key.hashCode()) + ".png";
        }
    }

    /** A small rounded preview, centre-cropped; null when the picture cannot be read. */
    private static Bitmap draw(File file, int w, int h, float radius) {
        if (file == null) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        if (o.outWidth <= 0) return null;
        o.inSampleSize = Math.max(1, Integer.highestOneBit(Math.max(1, o.outWidth / (w * 2))));
        o.inJustDecodeBounds = false;
        Bitmap src = BitmapFactory.decodeFile(file.getPath(), o);
        if (src == null) return null;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG
                | Paint.FILTER_BITMAP_FLAG);
        canvas.drawRoundRect(new RectF(0, 0, w, h), radius, radius, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        float dw = src.getWidth() * scale, dh = src.getHeight() * scale;
        canvas.drawBitmap(src, null, new RectF((w - dw) / 2f, (h - dh) / 2f,
                (w + dw) / 2f, (h + dh) / 2f), paint);
        src.recycle();
        return out;
    }
}
