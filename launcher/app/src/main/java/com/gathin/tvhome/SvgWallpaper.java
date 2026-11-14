package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small SVG painter for the built-in wallpapers (a few KB each instead of a picture): g, use, path
 * (M L H V C Q T A Z, absolute), circle, linear gradients, opacity, fill-opacity, matrix/translate/scale transforms
 * and a Gaussian-blur filter. The SVG is drawn once, covering the bitmap like a wallpaper.
 */
final class SvgWallpaper {
    private static final class Node {
        final String name;
        final Map<String, String> attrs = new HashMap<>();
        final List<Node> kids = new ArrayList<>();
        Node(String name) { this.name = name; }
        String get(String key) { return attrs.get(key); }
    }

    private final Map<String, Node> ids = new HashMap<>();
    /** The blur radius is in output pixels, so it follows the output size (it was tuned on a 960 px wide render). */
    private float blurFactor = 1f;

    private SvgWallpaper() {}

    /** The picture, w x h; {@code background} overrides the page colour the SVG names in its style (null: use that). */
    static Bitmap render(String svg, int w, int h, Integer background) throws Exception {
        XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
        p.setInput(new StringReader(svg));
        SvgWallpaper r = new SvgWallpaper();
        Node root = null;
        List<Node> stack = new ArrayList<>();
        for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
            if (e == XmlPullParser.START_TAG) {
                Node n = new Node(p.getName());
                for (int i = 0; i < p.getAttributeCount(); i++) n.attrs.put(p.getAttributeName(i), p.getAttributeValue(i));
                String id = n.get("id");
                if (id != null) r.ids.put(id, n);
                if (stack.isEmpty()) root = n; else stack.get(stack.size() - 1).kids.add(n);
                stack.add(n);
            } else if (e == XmlPullParser.END_TAG) {
                stack.remove(stack.size() - 1);
            }
        }
        if (root == null) throw new IllegalArgumentException("no svg");
        float vw = 1707, vh = 825;
        String box = root.get("viewBox");
        if (box != null) {
            String[] v = box.trim().split("[ ,]+");
            if (v.length == 4) { vw = Float.parseFloat(v[2]); vh = Float.parseFloat(v[3]); }
        }
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(background != null ? background : pageColor(root.get("style")));
        r.blurFactor = w / 960f;
        float scale = Math.max(w / vw, h / vh);
        canvas.translate((w - vw * scale) / 2f, (h - vh * scale) / 2f);
        canvas.scale(scale, scale);
        r.draw(canvas, root);
        return bitmap;
    }

    private static int pageColor(String style) {
        if (style != null) {
            Matcher m = Pattern.compile("background:[^;]*?rgb\\((\\d+),\\s*(\\d+),\\s*(\\d+)\\)").matcher(style);
            if (m.find()) return Color.rgb(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        }
        return Color.BLACK;
    }

    private void draw(Canvas canvas, Node n) {
        switch (n.name) {
            case "svg":
                for (Node k : n.kids) draw(canvas, k);
                break;
            case "g": {
                canvas.save();
                canvas.concat(transform(n));
                for (Node k : n.kids) draw(canvas, k);
                canvas.restore();
                break;
            }
            case "use": {
                String href = n.get("href") != null ? n.get("href") : n.get("xlink:href");
                Node target = href == null ? null : ids.get(href.replace("#", ""));
                if (target == null) break;
                canvas.save();
                canvas.concat(transform(n));
                draw(canvas, target);
                canvas.restore();
                break;
            }
            case "path": {
                Path path = parsePath(n.get("d"));
                RectF b = new RectF();
                path.computeBounds(b, true);
                Paint paint = paintFor(n, b);
                if (paint != null) canvas.drawPath(path, paint);
                break;
            }
            case "circle": {
                float cx = num(n.get("cx")), cy = num(n.get("cy")), r = num(n.get("r"));
                Paint paint = paintFor(n, new RectF(cx - r, cy - r, cx + r, cy + r));
                canvas.save();
                canvas.concat(transform(n));
                if (paint != null) canvas.drawCircle(cx, cy, r, paint);
                canvas.restore();
                break;
            }
            default: // defs, gradients and filters are only looked up, never drawn directly
        }
    }

    private static float num(String s) {
        return s == null ? 0 : Float.parseFloat(s);
    }

    private Paint paintFor(Node n, RectF bounds) {
        String fill = n.get("fill");
        if (fill == null || fill.equals("none")) return null;
        float alpha = n.get("opacity") == null ? 1f : Float.parseFloat(n.get("opacity"));
        if (n.get("fill-opacity") != null) alpha *= Float.parseFloat(n.get("fill-opacity"));
        // Dither: a gradient drawn into 8 bits per channel without it shows as bands.
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        if (fill.startsWith("url(")) {
            Node g = ids.get(fill.replaceAll("url\\(['\"]?#|['\"]?\\)", ""));
            if (g == null || !g.name.equals("linearGradient")) return null;
            List<Integer> colors = new ArrayList<>();
            List<Float> offsets = new ArrayList<>();
            for (Node stop : g.kids) {
                if (!stop.name.equals("stop")) continue;
                colors.add(Color.parseColor(stop.get("stop-color")));
                offsets.add(Float.parseFloat(stop.get("offset")));
            }
            int[] c = new int[colors.size()];
            float[] o = new float[c.length];
            for (int i = 0; i < c.length; i++) { c[i] = colors.get(i); o[i] = offsets.get(i); }
            float x1 = g.get("x1") == null ? 0 : num(g.get("x1")), x2 = g.get("x2") == null ? 1 : num(g.get("x2"));
            float y1 = num(g.get("y1")), y2 = g.get("y2") == null ? 0 : num(g.get("y2"));
            paint.setShader(new LinearGradient(bounds.left + x1 * bounds.width(), bounds.top + y1 * bounds.height(),
                    bounds.left + x2 * bounds.width(), bounds.top + y2 * bounds.height(), c, o, Shader.TileMode.CLAMP));
        } else {
            paint.setColor(Color.parseColor(fill));
        }
        paint.setAlpha(Math.round(alpha * 255));
        String filter = n.get("filter");
        if (filter != null) {
            Node f = ids.get(filter.replaceAll("url\\(['\"]?#|['\"]?\\)", ""));
            Node blur = f == null || f.kids.isEmpty() ? null : f.kids.get(0);
            float sigma = blur == null ? 0 : num(blur.get("stdDeviation"));
            // SVG's feGaussianBlur sigma to BlurMaskFilter's radius (Skia: sigma = 0.57735 * radius + 0.5).
            if (sigma > 0) paint.setMaskFilter(new BlurMaskFilter((sigma - 0.5f) / 0.57735f * blurFactor, BlurMaskFilter.Blur.NORMAL));
        }
        return paint;
    }

    private static final Pattern NUMBER = Pattern.compile("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?");

    /** The transform attribute, or the CSS transform in style when only that is given. */
    private static Matrix transform(Node n) {
        String t = n.get("transform");
        if (t == null && n.get("style") != null) {
            Matcher m = Pattern.compile("transform:\\s*([a-z]+\\([^)]*\\))").matcher(n.get("style"));
            if (m.find()) t = m.group(1);
        }
        Matrix result = new Matrix();
        if (t == null) return result;
        Matcher m = Pattern.compile("([a-z]+)\\(([^)]*)\\)").matcher(t);
        while (m.find()) {
            List<Float> v = new ArrayList<>();
            Matcher num = NUMBER.matcher(m.group(2));
            while (num.find()) v.add(Float.parseFloat(num.group()));
            Matrix step = new Matrix();
            switch (m.group(1)) {
                case "matrix":
                    if (v.size() == 6) step.setValues(new float[]{v.get(0), v.get(2), v.get(4), v.get(1), v.get(3), v.get(5), 0, 0, 1});
                    break;
                case "translate": step.setTranslate(v.get(0), v.size() > 1 ? v.get(1) : 0); break;
                case "scale": step.setScale(v.get(0), v.size() > 1 ? v.get(1) : v.get(0)); break;
                default:
            }
            result.preConcat(step);
        }
        return result;
    }

    private static Path parsePath(String d) {
        Path path = new Path();
        if (d == null) return path;
        Matcher t = Pattern.compile("[MLHVCQTAZ]|" + NUMBER.pattern()).matcher(d);
        List<String> tk = new ArrayList<>();
        while (t.find()) tk.add(t.group());
        float cx = 0, cy = 0, sx = 0, sy = 0, qx = 0, qy = 0;
        boolean prevQuad = false;
        String cmd = "";
        for (int i = 0; i < tk.size(); ) {
            if (Character.isLetter(tk.get(i).charAt(0))) cmd = tk.get(i++);
            else if (cmd.equals("M")) cmd = "L";
            boolean quad = false;
            switch (cmd) {
                case "M": cx = sx = n(tk, i); cy = sy = n(tk, i + 1); path.moveTo(cx, cy); i += 2; break;
                case "L": cx = n(tk, i); cy = n(tk, i + 1); path.lineTo(cx, cy); i += 2; break;
                case "H": cx = n(tk, i); path.lineTo(cx, cy); i += 1; break;
                case "V": cy = n(tk, i); path.lineTo(cx, cy); i += 1; break;
                case "C": path.cubicTo(n(tk, i), n(tk, i + 1), n(tk, i + 2), n(tk, i + 3), n(tk, i + 4), n(tk, i + 5));
                    cx = n(tk, i + 4); cy = n(tk, i + 5); i += 6; break;
                case "Q": qx = n(tk, i); qy = n(tk, i + 1); path.quadTo(qx, qy, n(tk, i + 2), n(tk, i + 3));
                    cx = n(tk, i + 2); cy = n(tk, i + 3); quad = true; i += 4; break;
                case "T": {
                    // The control point is the previous one mirrored through the current point.
                    float rx = prevQuad ? 2 * cx - qx : cx, ry = prevQuad ? 2 * cy - qy : cy;
                    qx = rx; qy = ry;
                    path.quadTo(qx, qy, n(tk, i), n(tk, i + 1));
                    cx = n(tk, i); cy = n(tk, i + 1); quad = true; i += 2; break;
                }
                case "A": arc(path, cx, cy, n(tk, i), n(tk, i + 1), n(tk, i + 2), n(tk, i + 3) != 0, n(tk, i + 4) != 0,
                        n(tk, i + 5), n(tk, i + 6));
                    cx = n(tk, i + 5); cy = n(tk, i + 6); i += 7; break;
                default: path.close(); cx = sx; cy = sy; // Z
            }
            prevQuad = quad;
        }
        return path;
    }

    private static float n(List<String> tk, int i) {
        return Float.parseFloat(tk.get(i));
    }

    /** An SVG elliptical arc (endpoint form) as cubic segments of at most a quarter turn each. */
    private static void arc(Path path, float x0, float y0, float rx, float ry, float rotation, boolean large, boolean sweep,
                            float x, float y) {
        if (rx == 0 || ry == 0 || (x0 == x && y0 == y)) { path.lineTo(x, y); return; }
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        double phi = Math.toRadians(rotation), cos = Math.cos(phi), sin = Math.sin(phi);
        double dx = (x0 - x) / 2.0, dy = (y0 - y) / 2.0;
        double x1 = cos * dx + sin * dy, y1 = -sin * dx + cos * dy;
        double lambda = x1 * x1 / (rx * rx) + y1 * y1 / (ry * ry);
        if (lambda > 1) { double s = Math.sqrt(lambda); rx *= s; ry *= s; }
        double num = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1;
        double den = rx * rx * y1 * y1 + ry * ry * x1 * x1;
        double coef = (large == sweep ? -1 : 1) * Math.sqrt(Math.max(0, num / den));
        double cxp = coef * rx * y1 / ry, cyp = -coef * ry * x1 / rx;
        double cx = cos * cxp - sin * cyp + (x0 + x) / 2.0, cy = sin * cxp + cos * cyp + (y0 + y) / 2.0;
        double start = Math.atan2((y1 - cyp) / ry, (x1 - cxp) / rx);
        double end = Math.atan2((-y1 - cyp) / ry, (-x1 - cxp) / rx);
        double delta = end - start;
        if (sweep && delta < 0) delta += 2 * Math.PI;
        else if (!sweep && delta > 0) delta -= 2 * Math.PI;
        int segments = (int) Math.ceil(Math.abs(delta) / (Math.PI / 2) - 1e-9);
        double step = delta / segments, k = 4.0 / 3.0 * Math.tan(step / 4);
        double a = start;
        for (int i = 0; i < segments; i++) {
            double b = a + step;
            double ca = Math.cos(a), sa = Math.sin(a), cb = Math.cos(b), sb = Math.sin(b);
            double[] p = {ca - k * sa, sa + k * ca, cb + k * sb, sb - k * cb, cb, sb};
            float[] o = new float[6];
            for (int j = 0; j < 6; j += 2) {
                double ex = rx * p[j], ey = ry * p[j + 1];
                o[j] = (float) (cos * ex - sin * ey + cx);
                o[j + 1] = (float) (sin * ex + cos * ey + cy);
            }
            path.cubicTo(o[0], o[1], o[2], o[3], o[4], o[5]);
            a = b;
        }
    }
}
