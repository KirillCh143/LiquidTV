package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;

/**
 * Local patch: the white outline of the "Frosted ice" (dock_backdrop blur) look — a thin white stroke whose
 * brightness runs along a 60 degree line (turned 180) (bright at both ends, faint between), on the dock, the top-bar pills, the
 * Settings grid's tiles and the screensaver's digits.
 */
final class GlassRim {
    static final float ANGLE_DEGREES = 60f;
    static final float STROKE_DP = 1f;
    /** The Monochrome backdrop's outline: plain white at 15%, no gradient. */
    static final int SOLID = 0x26FFFFFF;
    private static final int[] COLORS = {0x33FFFFFF, 0x0FFFFFFF, 0x0FFFFFFF, 0x73FFFFFF};
    private static final float[] STOPS = {0f, 0.4f, 0.6f, 1f};

    private GlassRim() {}

    /** A gradient across bounds, along ANGLE_DEGREES measured from the x axis, turned 180 degrees. */
    static Shader shader(RectF bounds) {
        return shader(bounds, 0f);
    }

    /** The same, with the line turned by extraDegrees more (the dock's own 220). */
    static Shader shader(RectF bounds, float extraDegrees) {
        double a = Math.toRadians(ANGLE_DEGREES + extraDegrees);
        float dx = (float) -Math.cos(a), dy = (float) Math.sin(a);   // 60 + 180 degrees: down and to the left
        float half = (Math.abs(bounds.width() * dx) + Math.abs(bounds.height() * dy)) / 2f;
        float cx = bounds.centerX(), cy = bounds.centerY();
        return new LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half,
                COLORS, STOPS, Shader.TileMode.CLAMP);
    }

    static Paint strokePaint(float density) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(STROKE_DP * density);
        return paint;
    }

    /** base with the outline over it. */
    static Drawable over(Drawable base, float radiusPx, float density) {
        return over(base, radiusPx, density, 0f);
    }

    /** base with the plain SOLID outline over it. */
    static Drawable solidOver(Drawable base, float radiusPx, float density) {
        Rim rim = new Rim(radiusPx, density, 0f);
        rim.paint.setColor(SOLID);
        rim.solid = true;
        return new LayerDrawable(new Drawable[]{base, rim});
    }

    /** The same, with the gradient's line turned by extraDegrees more. */
    static Drawable over(Drawable base, float radiusPx, float density, float extraDegrees) {
        return new LayerDrawable(new Drawable[]{base, new Rim(radiusPx, density, extraDegrees)});
    }

    private static final class Rim extends Drawable {
        private final float radius;
        private final Paint paint;
        private final RectF box = new RectF();
        private int boxW = -1, boxH = -1;

        private final float extraDegrees;
        boolean solid;

        Rim(float radius, float density, float extraDegrees) {
            this.radius = radius;
            this.extraDegrees = extraDegrees;
            this.paint = strokePaint(density);
        }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            if (b.isEmpty()) return;
            float inset = paint.getStrokeWidth() / 2f;
            box.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
            if (!solid && (b.width() != boxW || b.height() != boxH)) {
                paint.setShader(shader(new RectF(b), extraDegrees));
                boxW = b.width();
                boxH = b.height();
            }
            float r = Math.max(0f, Math.min(radius - inset, Math.min(box.width(), box.height()) / 2f));
            canvas.drawRoundRect(box, r, r, paint);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
