package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The built-in wallpaper ("Blur Wave"), kept as a 5 KB SVG in assets/ and drawn once into a small JPEG in the app's
 *  files folder, which every screen then treats like any other wallpaper file. It shows until a wallpaper is
 *  downloaded or picked, and again when the wallpaper settings are reset. */
final class DefaultWallpaper {
    static final String CHOICE = "default";
    private static final String FILE = "default-wallpaper-v1.jpg";
    /** Rendered at half of 1080p: the picture is soft gradients, so the screen scales it up without any loss. */
    private static final int W = 960, H = 540;

    private DefaultWallpaper() {}

    /** The rendered picture, drawn on first use; null if the asset cannot be read. */
    static File file(Context ctx) {
        File file = new File(ctx.getFilesDir(), FILE);
        if (file.isFile() && file.length() > 0) return file;
        try (InputStream in = ctx.getAssets().open("default_wallpaper.svg")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) bytes.write(buf, 0, n);
            Bitmap bitmap = render(new String(bytes.toByteArray(), "UTF-8").replaceAll("\\s+", " "));
            try (FileOutputStream out = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
            }
            bitmap.recycle();
            return file;
        } catch (Exception e) {
            file.delete();
            return null;
        }
    }

    private static Bitmap render(String svg) {
        Matcher box = Pattern.compile("viewBox=\"0 0 ([\\d.]+) ([\\d.]+)\"").matcher(svg);
        float vw = 1707, vh = 825;
        if (box.find()) { vw = Float.parseFloat(box.group(1)); vh = Float.parseFloat(box.group(2)); }
        Matcher sigmaMatch = Pattern.compile("stdDeviation=\"([\\d.]+)\"").matcher(svg);
        float sigma = sigmaMatch.find() ? Float.parseFloat(sigmaMatch.group(1)) : 0;
        Bitmap bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(0xFF050F2C);
        // Cover the screen, centred.
        float scale = Math.max(W / vw, H / vh);
        canvas.translate((W - vw * scale) / 2f, (H - vh * scale) / 2f);
        canvas.scale(scale, scale);
        Matcher paths = Pattern.compile("<path d=\"([^\"]+)\" fill=\"url\\(#(\\w+)\\)\" opacity=\"([\\d.]+)\"( filter=)?").matcher(svg);
        while (paths.find()) {
            Path path = parse(paths.group(1));
            RectF b = new RectF();
            path.computeBounds(b, true);
            Matcher grad = Pattern.compile("<linearGradient id=\"" + paths.group(2) + "\" x1=\"([^\"]+)\" x2=\"([^\"]+)\" y1=\"([^\"]+)\" y2=\"([^\"]+)\"[^>]*>(.*?)</linearGradient>").matcher(svg);
            if (!grad.find()) continue;
            List<Integer> colors = new ArrayList<>();
            List<Float> offsets = new ArrayList<>();
            Matcher stop = Pattern.compile("stop-color=\"(#\\w+)\" offset=\"([\\d.]+)\"").matcher(grad.group(5));
            while (stop.find()) {
                colors.add(android.graphics.Color.parseColor(stop.group(1)));
                offsets.add(Float.parseFloat(stop.group(2)));
            }
            int[] c = new int[colors.size()];
            float[] o = new float[c.length];
            for (int i = 0; i < c.length; i++) { c[i] = colors.get(i); o[i] = offsets.get(i); }
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setShader(new LinearGradient(b.left + Float.parseFloat(grad.group(1)) * b.width(),
                    b.top + Float.parseFloat(grad.group(3)) * b.height(),
                    b.left + Float.parseFloat(grad.group(2)) * b.width(),
                    b.top + Float.parseFloat(grad.group(4)) * b.height(), c, o, Shader.TileMode.CLAMP));
            paint.setAlpha(Math.round(Float.parseFloat(paths.group(3)) * 255));
            // SVG's feGaussianBlur sigma to BlurMaskFilter's radius (Skia: sigma = 0.57735 * radius + 0.5).
            if (paths.group(4) != null && sigma > 0) {
                paint.setMaskFilter(new BlurMaskFilter((sigma - 0.5f) / 0.57735f, BlurMaskFilter.Blur.NORMAL));
            }
            canvas.drawPath(path, paint);
        }
        return bitmap;
    }

    /** The absolute M, L, C and Z commands this SVG uses. */
    private static Path parse(String d) {
        Path path = new Path();
        Matcher t = Pattern.compile("[MLCZ]|-?[\\d.]+").matcher(d);
        List<String> tokens = new ArrayList<>();
        while (t.find()) tokens.add(t.group());
        for (int i = 0; i < tokens.size(); ) {
            String cmd = tokens.get(i++);
            switch (cmd) {
                case "M": path.moveTo(f(tokens, i), f(tokens, i + 1)); i += 2; break;
                case "L": path.lineTo(f(tokens, i), f(tokens, i + 1)); i += 2; break;
                case "C": path.cubicTo(f(tokens, i), f(tokens, i + 1), f(tokens, i + 2), f(tokens, i + 3),
                        f(tokens, i + 4), f(tokens, i + 5)); i += 6; break;
                default: path.close();
            }
        }
        return path;
    }

    private static float f(List<String> tokens, int i) {
        return Float.parseFloat(tokens.get(i));
    }
}
