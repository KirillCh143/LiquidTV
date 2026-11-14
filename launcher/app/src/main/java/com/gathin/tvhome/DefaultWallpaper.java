package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** The built-in wallpaper ("Blur Wave"), kept as a 5 KB SVG in assets/ and drawn once into a small JPEG in the app's
 *  files folder, which every screen then treats like any other wallpaper file. It shows until a wallpaper is
 *  downloaded or picked, and again when the wallpaper settings are reset. */
final class DefaultWallpaper {
    static final String CHOICE = "default";
    private static final String FILE = "default-wallpaper-v3.png";
    /** Full HD, lossless (PNG): the pictures are soft gradients, which JPEG and half-size renders show as bands. */
    private static final int W = 1920, H = 1080;

    /** More built-in pictures, SVGs in assets/wallpapers/, drawn once into a JPEG like the first one: {choice id, English name}. */
    static final String[][] EXTRA = {{"bauhaus", "Bauhaus"}, {"dusk", "Dusk"}, {"lagoon", "Lagoon"},
            {"spectrum", "Spectrum"}};

    private DefaultWallpaper() {}

    /** True for the choice of the built-in picture or of one of the EXTRA ones. */
    static boolean isChoice(String choice) {
        if (CHOICE.equals(choice)) return true;
        for (String[] e : EXTRA) if (e[0].equals(choice)) return true;
        return false;
    }

    /** The picture for a built-in choice (the first one for null or an unknown id), copied out of assets once. */
    static File fileFor(Context ctx, String choice) {
        for (String[] e : EXTRA) {
            if (!e[0].equals(choice)) continue;
            File file = new File(ctx.getFilesDir(), "builtin-" + e[0] + "-v3.png");
            if (file.isFile() && file.length() > 0) return file;
            try (InputStream in = ctx.getAssets().open("wallpapers/" + e[0] + ".svg")) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) bytes.write(buf, 0, n);
                // Full HD: these have sharp edges, which a half-size picture would blur.
                Bitmap bitmap = SvgWallpaper.render(new String(bytes.toByteArray(), "UTF-8"), 1920, 1080, null);
                try (FileOutputStream out = new FileOutputStream(file)) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);   // lossless: a JPEG bands and blocks soft gradients
                }
                bitmap.recycle();
                return file;
            } catch (Exception ex) {
                file.delete();
                return file(ctx);
            }
        }
        return file(ctx);
    }

    /** The rendered picture, drawn on first use; null if the asset cannot be read. */
    static File file(Context ctx) {
        File file = new File(ctx.getFilesDir(), FILE);
        if (file.isFile() && file.length() > 0) return file;
        try (InputStream in = ctx.getAssets().open("default_wallpaper.svg")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) bytes.write(buf, 0, n);
            Bitmap bitmap = SvgWallpaper.render(new String(bytes.toByteArray(), "UTF-8"), W, H, 0xFF050F2C);
            try (FileOutputStream out = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);   // lossless: a JPEG bands and blocks soft gradients
            }
            bitmap.recycle();
            return file;
        } catch (Exception e) {
            file.delete();
            return null;
        }
    }
}
