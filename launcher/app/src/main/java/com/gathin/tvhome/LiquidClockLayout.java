package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.PorterDuffXfermode;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.Calendar;

/**
 * Local patch: the offline screensaver, Home's current wallpaper with a big clock whose digits are liquid glass —
 * the dock's own recipe (vibrancy, blur from Launcher settings, the lens) applied to the digits through a mask.
 * No network. Shown by AerialActivity and AerialDream when "Screensaver type" is "Clock". Below API 33 (no
 * RuntimeShader) the digits are plain translucent white.
 */
final class LiquidClockLayout extends FrameLayout {
    static final String KIND_KEY = "screensaver_kind";
    static final String KIND_AERIAL = "aerial", KIND_CLOCK = "clock";

    /** The chosen screensaver type; the streaming one until the menu says otherwise. */
    static boolean selected(Context context) {
        return KIND_CLOCK.equals(context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getString(KIND_KEY, KIND_AERIAL));
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ImageView wallpaper;
    private final GlassLayer layer;
    private final ImageView glass;
    /** A soft dark copy of the digits under the glass, a little lower: the digits' light shadow. */
    private final ImageView shadow;
    /** The date: plain white text with a light shadow, not glass (too thin for the lens). */
    private final android.widget.TextView dateView;
    private final float density;
    private final boolean is24;
    private final RenderEffect blurBase;
    private Bitmap picture;
    private Bitmap mask;
    private String shownTime = "";
    /** Bumped by stop(): a decode that started before it drops its result. Main thread only. */
    private int generation;
    private boolean started;
    private final Runnable tickLater = this::tick;

    LiquidClockLayout(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        is24 = DateFormat.is24HourFormat(context);
        setBackgroundColor(0xFF000000);
        wallpaper = new ImageView(context);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(wallpaper, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        shadow = new ImageView(context);
        shadow.setScaleType(ImageView.ScaleType.FIT_XY);
        shadow.setColorFilter(0x55000000, android.graphics.PorterDuff.Mode.SRC_IN);   // the digits' shape in black at 33%
        shadow.setTranslationY(5f * density);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            shadow.setRenderEffect(RenderEffect.createBlurEffect(18f * density, 18f * density, Shader.TileMode.CLAMP));
        }
        addView(shadow, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        glass = new ImageView(context);
        glass.setScaleType(ImageView.ScaleType.CENTER_CROP);
        layer = new GlassLayer(context);
        layer.addView(glass, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(layer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        dateView = new android.widget.TextView(context);
        dateView.setTypeface(UiFont.get(context, R.string.font_medium));
        dateView.setTextColor(0xE6FFFFFF);
        dateView.setIncludeFontPadding(false);
        dateView.setSingleLine(true);
        dateView.setShadowLayer(10f * density, 0, 2f * density, 0x55000000);
        addView(dateView, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        // "Dock panel opacity" (Launcher settings) darkens the digits the way it darkens the dock's band.
        int opacity = 0;
        boolean light = false;
        try {
            Config config = Config.load(context);
            opacity = config.dockOpacity;
            light = Config.dockLightGlass(config);   // dock_glass on the "liquid" backdrop: the dock's white band
        } catch (RuntimeException ignored) {
            // no tint
        }
        layer.setTint(light, opacity);
        blurBase = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? blurRecipe(context) : null;
    }

    /** The dock's own: a saturation lift and a white lift, then the Launcher settings' blur. */
    private RenderEffect blurRecipe(Context context) {
        ColorMatrix vibrancy = new ColorMatrix();
        vibrancy.setSaturation(1.1f);
        vibrancy.postConcat(new ColorMatrix(new float[]{
                1, 0, 0, 0, 16,
                0, 1, 0, 0, 16,
                0, 0, 1, 0, 16,
                0, 0, 0, 1, 0}));
        RenderEffect color = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(vibrancy));
        float sigma = Config.dockBlur(context) * density;
        return sigma > 0f ? RenderEffect.createBlurEffect(sigma, sigma, color, Shader.TileMode.CLAMP) : color;
    }

    void start() {
        started = true;
        if (picture == null) loadPicture();
        tick();
    }

    void stop() {
        started = false;
        generation++;
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        shownTime = "";   // the mask is the screen's size
        if (started) tick();
    }

    /** What Home shows right now: the file its player last put up, else the theme's picture, else the built-in one. */
    private File currentWallpaper() {
        Context context = getContext();
        String path = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE).getString("path", null);
        if (path != null && new File(path).isFile()) return new File(path);
        try {
            Config config = Config.load(context);
            if (config.wallpaper != null) return config.wallpaper;
            if (!config.wallpapers.isEmpty()) return config.wallpapers.get(0);
        } catch (RuntimeException ignored) {
            // fall through to the built-in picture
        }
        return DefaultWallpaper.file(context);
    }

    private void loadPicture() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels, gen = generation;
        new Thread(() -> {
            File file = currentWallpaper();
            Bitmap bitmap = file == null ? null : ClockScene.decode(file, w, h, Bitmap.Config.ARGB_8888);
            if (bitmap == null) return;
            handler.post(() -> {
                if (gen != generation) return;
                picture = bitmap;
                wallpaper.setImageBitmap(bitmap);
                glass.setImageBitmap(bitmap);
                shownTime = "";
                tick();
            });
        }, "clock-wallpaper").start();
    }

    /** Draws the time when it changed, and schedules the next minute. */
    private void tick() {
        handler.removeCallbacks(tickLater);   // only the minute timer: a finished picture waiting in the queue stays
        if (!started) return;
        Calendar now = Calendar.getInstance();
        String time = String.format(is24 ? "%02d:%02d" : "%d:%02d",
                is24 ? now.get(Calendar.HOUR_OF_DAY) : (now.get(Calendar.HOUR) == 0 ? 12 : now.get(Calendar.HOUR)),
                now.get(Calendar.MINUTE));
        String ampm = is24 ? "" : (now.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM");
        String date = dateText(now);
        String key = time + ampm + date;
        if (!key.equals(shownTime) && getWidth() > 0 && picture != null) {
            shownTime = key;
            drawMask(time, ampm, date);
        }
        long wait = 60_000L - (now.get(Calendar.SECOND) * 1000L + now.get(Calendar.MILLISECOND)) + 50;
        handler.postDelayed(tickLater, wait);
    }

    /** "11 Ноября 2026": the day, the month's name with a capital, the year, in the TV's language. */
    private String dateText(Calendar now) {
        String text = new java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.getDefault()).format(now.getTime());
        int space = text.indexOf(' ');
        if (space < 0 || space + 1 >= text.length()) return text;
        return text.substring(0, space + 1) + Character.toUpperCase(text.charAt(space + 1)) + text.substring(space + 2);
    }

    /** SF Pro Rounded Semibold, digits and colon only (assets/fonts/sfpro-rounded-clock.otf); null when that file is
     *  not in the build, which then draws the launcher's own face. */
    private android.graphics.Typeface clockFace() {
        try {
            return android.graphics.Typeface.createFromAsset(getContext().getAssets(), "fonts/sfpro-rounded-clock.otf");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void drawMask(String time, String ampm, String date) {
        int w = getWidth(), h = getHeight();
        Bitmap next = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        Canvas canvas = new Canvas(next);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(0xFFFFFFFF);
        android.graphics.Typeface rounded = clockFace();
        text.setTypeface(rounded != null ? rounded : UiFont.get(getContext(), R.string.font_medium));
        text.setFakeBoldText(rounded == null);   // the launcher's own medium face needs help to be this heavy
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(h * 0.38f);
        // AM/PM sits to the right of the time at its top edge, a fifth of its size; the time stays centred.
        Paint small = new Paint(text);
        float gap = h * 0.02f;
        float timeWidth = text.measureText(time);
        float ampmWidth = 0;
        if (!ampm.isEmpty()) {
            small.setTextSize(text.getTextSize() * 0.2f);
            ampmWidth = gap + small.measureText(ampm);
        }
        float most = Math.max(timeWidth + ampmWidth * 2, 1);   // the time centred means room for the label on both sides
        if (most > w * 0.84f) {
            float shrink = w * 0.84f / most;
            text.setTextSize(text.getTextSize() * shrink);
            small.setTextSize(small.getTextSize() * shrink);
            gap *= shrink;
            timeWidth *= shrink;
        }
        android.graphics.Rect digits = new android.graphics.Rect();
        text.getTextBounds("0", 0, 1, digits);
        float capHeight = -digits.top;
        Paint datePaint = new Paint(text);
        datePaint.setTextSize(text.getTextSize() * 0.12f);
        Paint.FontMetrics dm = datePaint.getFontMetrics();
        float dateHeight = dm.descent - dm.ascent;
        float block = capHeight + h * 0.05f + dateHeight;
        float top = h / 2f - block / 2f;            // the digits' top edge
        float baseline = top + capHeight;
        float left = (w - timeWidth) / 2f;
        canvas.drawText(time, left, baseline, text);
        if (!ampm.isEmpty()) {
            Rect smallBounds = new Rect();
            small.getTextBounds("M", 0, 1, smallBounds);
            canvas.drawText(ampm, left + timeWidth + gap, top - smallBounds.top, small);
        }
        // The date is a plain view under the digits' block, centred.
        dateView.setText(date);
        dateView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, datePaint.getTextSize());
        dateView.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST));
        dateView.setTranslationX((w - dateView.getMeasuredWidth()) / 2f);
        dateView.setTranslationY(baseline + h * 0.05f);
        mask = next;
        layer.setGradient(top, baseline);
        layer.setMask(next);
        shadow.setImageBitmap(next);
        applyEffect(next);
        layer.invalidate();
    }

    /** The dock's lens on the digits: the shader needs the mask for the glyph outline, so it is rebuilt with it. */
    private void applyEffect(Bitmap maskBitmap) {
        glass.setRenderEffect(null);
        if (blurBase == null) return;
        RenderEffect effect = blurBase;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                RuntimeShader shader = new RuntimeShader(TextGlassShader.SOURCE);
                shader.setInputShader("mask", new BitmapShader(maskBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                // An edge band of 1dp (the dock uses 2dp), the peak displacement 34dp as at the dock.
                shader.setFloatUniform("refractionHeight", 1f * density);
                shader.setFloatUniform("refractionAmount", 34f * density);
                effect = RenderEffect.createChainEffect(
                        RenderEffect.createRuntimeShaderEffect(shader, "content"), blurBase);
            } catch (Exception | LinkageError e) {
                Log.w("TvHome.Clock", "glass text shader unavailable, blur only: " + e);
            }
        }
        glass.setRenderEffect(effect);
    }

    /** Holds the glass picture and keeps only what lies under the digits (the mask drawn DST_IN over it). */
    private static final class GlassLayer extends FrameLayout {
        private final Paint keep = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Bitmap mask;
        /** The dock band's own tint, laid over the glass: dark (#141416) at the dock opacity, or with dock_glass the
         *  dock's white gradient (32% fading to 18% down the digits) at that opacity. */
        private boolean light;
        private int opacityPercent;
        private float gradientTop, gradientBottom;

        GlassLayer(Context context) {
            super(context);
            keep.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        }

        void setMask(Bitmap mask) {
            this.mask = mask;
        }

        void setTint(boolean light, int opacityPercent) {
            this.light = light;
            this.opacityPercent = opacityPercent;
        }

        /** The digits' top and baseline, where the light tint's gradient starts and ends. */
        void setGradient(float top, float bottom) {
            gradientTop = top;
            gradientBottom = bottom;
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            if (mask == null) return;
            int saved = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
            super.dispatchDraw(canvas);
            int alpha = Math.round(opacityPercent * 2.55f);
            if (alpha != 0) {
                if (light) {
                    Paint white = new Paint();
                    white.setShader(new android.graphics.LinearGradient(0, gradientTop, 0, gradientBottom,
                            0x52FFFFFF, 0x2EFFFFFF, Shader.TileMode.CLAMP));
                    white.setAlpha(alpha);   // scales the gradient, as the dock band's own alpha does
                    canvas.drawRect(0, 0, getWidth(), getHeight(), white);
                } else {
                    canvas.drawColor((alpha << 24) | 0x141416);
                }
            }
            canvas.drawBitmap(mask, 0, 0, keep);
            canvas.restoreToCount(saved);
        }
    }
}
