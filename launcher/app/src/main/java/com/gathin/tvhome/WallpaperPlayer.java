package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Outline;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays and rotates a theme's local wallpaper images while Home is visible. */
final class WallpaperPlayer {
    private final FrameLayout root;
    private final ImageView image;
    /**
     * Local patch (not an upstream config option — AGENTS.md's "no blur" hard rule is about PR contributions,
     * not Kirill's own build): a second copy of the wallpaper, blurred with a hardware RenderEffect and clipped
     * to the dock's current rounded rect, so the dark dock band shows a real frosted-glass blur instead of a
     * flat tint. API 31+ only (RenderEffect), and only for dock_backdrop "blur" or "liquid" (config.xml);
     * null and inert on "color", below API 31, or with wallpapers off.
     */
    private final ImageView blurImage;
    private final RectF dockMaskRect = new RectF();
    private float dockMaskRadius;
    /** Local patch: the same blur layer behind the top-bar pills (Inputs, Wi-Fi, Settings...), clipped to the
     *  whole row's own rect instead of the dock's — a second independent mask on the same dock_backdrop choice. */
    private final ImageView buttonsBlurImage;
    private final RectF buttonsMaskRect = new RectF();
    private float buttonsMaskRadius;
    /** Local patch: dock_backdrop "liquid" (API 33+, RuntimeShader) — the AGSL effect chained onto each blur
     *  layer's own RenderEffect, re-targeted to each layer's own rect whenever it moves (setDockMask/
     *  setButtonsMask). Null when the tier is not "liquid", the device is below API 33, or the shader failed to
     *  compile at runtime (AGSL only compiles on-device, so a mistake here cannot show up at build time —
     *  everything falls back to the blur layer alone instead of crashing or leaving a half-built effect).
     */
    private final RuntimeShader dockLiquidShader, buttonsLiquidShader;
    /** Local patch: the vibrancy+blur effect alone (no lens shader), shared by both layers — kept so a mask
     *  update can rebuild the chain on a fresh RenderEffect (see setDockMask/setButtonsMask): a RuntimeShader's
     *  setFloatUniform calls did not reliably reach an already-assigned RenderEffect chain on every device tried,
     *  so the chain is rebuilt and reassigned on every move instead of relying on that alone. */
    private final RenderEffect blurEffectBase;
    private final int backgroundColor;
    private final List<File> files;
    private final long interval;
    private final String order;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    /** config wallpaper_true_color: ARGB_8888 instead of RGB_565. */
    private final Bitmap.Config bitmapConfig;
    private final int height;
    private final float density;
    /** wallpaper_dim as an ARGB black, painted into each picture once instead of a full-screen shade view. */
    private final int dimColor;
    /** The picture on screen, released once the next one has been drawn. */
    private Bitmap current;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";
    /** wallpaper_captions: the shown picture's name from wallpapers/captions.txt; null when off or there are none. */
    private final TextView caption;
    private final Map<String, String> names;
    /** wallpaper_moods: the collection's groups by time of day; empty rotates through all of it. */
    private final Map<String, Set<String>> moods;
    /** config.xml latitude and longitude for the sun's times, and today's {sunrise, sunset} once worked out. */
    private final String latitude, longitude;
    private LocalDate sunDay;
    private int[] sun;

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        backgroundColor = config.backgroundColor;
        files = new ArrayList<>(config.wallpapers);
        interval = config.wallpaperRotationMinutes * 60_000L;
        bitmapConfig = config.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        // The dim is painted into the picture (decode), so no full-screen shade layer is drawn on every frame.
        dimColor = config.wallpaperDim > 0 ? (config.wallpaperDim * 255 / 100) << 24 : 0;
        order = config.wallpaperOrder;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        moods = config.wallpaperMoods;
        latitude = moods.isEmpty() ? "" : context.getString(R.string.latitude);
        longitude = moods.isEmpty() ? "" : context.getString(R.string.longitude);
        // Playback state belongs to one collection. Changing the collection starts from its first
        // picture instead of resuming a filename that belonged to the previous one.
        String collection = collectionKey(files);
        if (!collection.equals(preferences.getString("collection", ""))) {
            SharedPreferences.Editor reset = preferences.edit().putString("collection", collection);
            // A picture that is still in the new collection stays; one that is not is replaced from it.
            if (WallpaperRotation.find(files, preferences.getString("image", "")) == null) {
                reset.remove("image").remove("changed");
            }
            reset.apply();
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        density = metrics.density;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        names = config.wallpaperCaptions && config.wallpaperEnabled && !files.isEmpty()
                ? WallpaperCaptions.read(files.get(0).getParentFile()) : Collections.emptyMap();
        caption = names.isEmpty() ? null : makeCaption(context, root);
        boolean wantBlur = !Config.BACKDROP_COLOR.equals(config.dockBackdrop) && config.wallpaperEnabled
                && !files.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
        boolean wantLiquid = wantBlur && Config.BACKDROP_LIQUID.equals(config.dockBackdrop)
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
        blurImage = wantBlur ? new ImageView(context) : null;
        buttonsBlurImage = wantBlur ? new ImageView(context) : null;
        RuntimeShader dockShader = null, buttonsShader = null;
        if (wantBlur) {
            // Local patch: vibrancy (a saturation lift) then a blur — the same recipe as Kyant0/AndroidLiquidGlass's
            // own glass-bottom-bar tutorial (kyant.gitbook.io/backdrop/tutorials/glass-bottom-bar, 1.3x/4dp),
            // tuned on a real TV. A flat white lift is mixed in on top of the saturation (not just
            // DockDecoration's own white gradient painted over the blur) so the glass itself reads lighter
            // over a bright wallpaper too, where a translucent overlay alone barely shows.
            ColorMatrix vibrancyMatrix = new ColorMatrix();
            vibrancyMatrix.setSaturation(1.1f);
            ColorMatrix whiteLift = new ColorMatrix(new float[]{
                    1, 0, 0, 0, 16,
                    0, 1, 0, 0, 16,
                    0, 0, 1, 0, 16,
                    0, 0, 0, 1, 0});
            vibrancyMatrix.postConcat(whiteLift);
            RenderEffect vibrancyEffect = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(vibrancyMatrix));
            float sigma = Config.dockBlur(context) * metrics.density;   // Launcher settings, 0 to 20 (dp)
            RenderEffect blurEffect = sigma > 0f
                    ? RenderEffect.createBlurEffect(sigma, sigma, vibrancyEffect, Shader.TileMode.CLAMP)
                    : vibrancyEffect;   // 0: no blur, the colour correction alone
            dockShader = wantLiquid ? newLiquidShader() : null;
            setUpBlurLayer(blurImage, blurEffect, dockShader, true);
            buttonsShader = wantLiquid ? newLiquidShader() : null;
            setUpBlurLayer(buttonsBlurImage, blurEffect, buttonsShader, false);
            blurEffectBase = blurEffect;
        } else {
            blurEffectBase = null;
        }
        dockLiquidShader = dockShader;
        buttonsLiquidShader = buttonsShader;
        if (config.wallpaperEnabled && !files.isEmpty()) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (blurImage != null) root.addView(blurImage, 1, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (buttonsBlurImage != null) root.addView(buttonsBlurImage, blurImage != null ? 2 : 1,
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            showFirst();
        }
    }

    /** A fresh RuntimeShader from LiquidGlassShader.SOURCE, or null if it failed to compile on this device —
     *  AGSL only compiles at runtime, so a mistake here (or a GPU/driver that claims API 33 but does not really
     *  support it) cannot be caught at build time; the blur layer's own RenderEffect still works without it. */
    private static RuntimeShader newLiquidShader() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null;
        try {
            return new RuntimeShader(LiquidGlassShader.SOURCE);
        } catch (Exception | LinkageError e) {
            Log.w("TvHome.Wallpaper", "liquid glass shader unavailable, falling back to plain blur: " + e);
            return null;
        }
    }

    /** One blur layer (dock or the top-bar pills): the hardware blur, chained with the liquid shader when one
     *  compiled, and clipped to whichever rect/radius this layer tracks (dock's own fields, or the buttons'). */
    private void setUpBlurLayer(ImageView view, RenderEffect blurEffect, RuntimeShader shader, boolean dock) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;   // always true here (wantBlur already checked it)
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        RenderEffect effect = blurEffect;
        if (shader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                effect = RenderEffect.createChainEffect(
                        RenderEffect.createRuntimeShaderEffect(shader, "content"), blurEffect);
            } catch (Exception e) {
                Log.w("TvHome.Wallpaper", "liquid glass effect chain failed, falling back to plain blur: " + e);
                effect = blurEffect;
            }
        }
        view.setRenderEffect(effect);
        view.setClipToOutline(true);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline outline) {
                RectF rect = dock ? dockMaskRect : buttonsMaskRect;
                float radius = dock ? dockMaskRadius : buttonsMaskRadius;
                if (rect.isEmpty()) {
                    outline.setAlpha(0f);
                    return;
                }
                outline.setRoundRect(Math.round(rect.left), Math.round(rect.top),
                        Math.round(rect.right), Math.round(rect.bottom), radius);
            }
        });
    }

    /** Whether the dock and the top-bar pills can show a real blur (API 31+, dock_backdrop blur or liquid,
     *  wallpapers on): MainActivity only bothers tracking their on-screen rects when this is true. */
    boolean hasDockBlur() {
        return blurImage != null;
    }

    /** The picture currently on screen, for a caller elsewhere that wants its own independent glass layer on
     *  the same wallpaper (MainActivity's Settings grid, one per tile) — null before the first decode finishes,
     *  or with wallpapers off. */
    Bitmap currentBitmap() {
        return current;
    }

    /** The dock band's current rounded rect, in this root's own coordinates, so its blur layer is clipped to
     *  match it exactly as the dock moves (scroll, a focused tile's overshoot, column/row changes). */
    void setDockMask(float left, float top, float right, float bottom, float radius) {
        if (blurImage == null) return;
        dockMaskRect.set(left, top, right, bottom);
        dockMaskRadius = radius;
        if (dockLiquidShader != null) {
            updateLiquidUniforms(dockLiquidShader, dockMaskRect, radius, 2f * density);
            refreshLiquidEffect(blurImage, dockLiquidShader);
        }
        blurImage.invalidateOutline();
        blurImage.invalidate();
    }

    /** Local patch: the top-bar pills' row, as one combined rect — the same idea as setDockMask, for the second
     *  blur layer behind Inputs, Wi-Fi, Settings and the rest. */
    void setButtonsMask(float left, float top, float right, float bottom, float radius) {
        if (buttonsBlurImage == null) return;
        buttonsMaskRect.set(left, top, right, bottom);
        buttonsMaskRadius = radius;
        if (buttonsLiquidShader != null) {
            updateLiquidUniforms(buttonsLiquidShader, buttonsMaskRect, radius, 1f * density);
            refreshLiquidEffect(buttonsBlurImage, buttonsLiquidShader);
        }
        buttonsBlurImage.invalidateOutline();
        buttonsBlurImage.invalidate();
    }

    /** Local patch: LiquidGlassShader.SOURCE's uniforms, re-targeted to a layer's current rect — the edge
     *  refraction has to track the dock or the buttons row exactly as they move, the same as the outline clip.
     *  Past the tutorial's own 16dp/32dp (kyant.gitbook.io/backdrop/tutorials/glass-bottom-bar, {@code lens(16f.dp,
     *  32f.dp)}), tuned on a real TV; LiquidGlassShader's own easing was flattened too (see circleMap there).
     *  refractionHeight is its own value per layer (setDockMask/setButtonsMask) — the buttons row is a much
     *  smaller shape than the dock, so the same band width read differently on each. */
    private void updateLiquidUniforms(RuntimeShader shader, RectF rect, float radius, float refractionHeight) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;   // always true here (shader is only ever non-null above it)
        shader.setFloatUniform("rectMin", rect.left, rect.top);
        shader.setFloatUniform("rectMax", rect.right, rect.bottom);
        shader.setFloatUniform("cornerRadius", radius);
        shader.setFloatUniform("refractionHeight", refractionHeight);
        shader.setFloatUniform("refractionAmount", 34f * density);
    }

    /** Local patch: rebuilds the lens-chained-on-blur RenderEffect from blurEffectBase and reassigns it, so the
     *  uniforms updateLiquidUniforms just set are the ones actually in force on the next frame — relying only on
     *  a mutated RuntimeShader's uniforms reaching an already-assigned RenderEffect was not dependable. */
    private void refreshLiquidEffect(ImageView view, RuntimeShader shader) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;   // always true here (shader is only ever non-null above it)
        try {
            view.setRenderEffect(RenderEffect.createChainEffect(
                    RenderEffect.createRuntimeShaderEffect(shader, "content"), blurEffectBase));
        } catch (Exception e) {
            Log.w("TvHome.Wallpaper", "liquid glass effect refresh failed, keeping the last one: " + e);
        }
    }

    /**
     * The picture Home opens with is decoded right here, on the main thread, as the single wallpaper always was:
     * Home's first frame already has it instead of drawing the plain colour and the picture popping in later.
     * Only the later changes of a rotation decode in the background.
     */
    private void showFirst() {
        String previous = preferences.getString("image", "");
        List<File> playable = playable();
        File first = WallpaperRotation.find(playable, previous);
        if (first == null) first = WallpaperRotation.pick(playable, previous, order, random);
        while (first != null) {
            Bitmap bitmap = decode(first);
            if (bitmap != null) {
                show(bitmap);
                // The picture covers the whole screen: the root's plain colour under it is overdraw.
                root.setBackground(null);
                displayed = first.getName();
                preferences.edit().putString("path", first.getAbsolutePath()).apply();   // for RecentAppsOverlay's backdrop
                showCaption(false);
                if (!displayed.equals(previous)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                return;
            }
            files.remove(first);   // unreadable or over 4K: try another, or keep the plain colour
            playable = playable();
            first = WallpaperRotation.pick(playable, previous, order, random);
        }
    }

    /** Local patch: the exact picture on screen right now, so AppLibraryActivity's own background can match it
     *  precisely instead of guessing which one Home picked. Null before the first picture is shown. */
    File currentFile() {
        for (File f : files) if (f.getName().equals(displayed)) return f;
        return null;
    }

    void start() {
        active = true;
        update();
    }

    void stop() {
        active = false;
    }

    /** Drops the wallpaper texture when Home is hidden and Android asks the process to free memory. */
    void releaseForMemoryPressure() {
        if (current == null) return;
        image.setImageDrawable(null);
        if (blurImage != null) blurImage.setImageDrawable(null);
        if (buttonsBlurImage != null) buttonsBlurImage.setImageDrawable(null);
        // The renderer may still have a queued frame that references this hardware bitmap. Drop our references and
        // let Android reclaim its native buffer after rendering releases it instead of recycling it mid-frame.
        current = null;
        displayed = "";
        root.setBackgroundColor(backgroundColor);
    }

    /** The pictures the rotation picks from now: wallpaper_moods' group for the time of day, or the whole collection. */
    private List<File> playable() {
        if (moods.isEmpty()) return files;
        LocalDateTime now = LocalDateTime.now();
        if (!now.toLocalDate().equals(sunDay)) {
            sunDay = now.toLocalDate();
            sun = WallpaperMood.sun(sunDay, latitude, longitude, ZoneId.systemDefault());
        }
        return WallpaperMood.select(files, moods, WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(), sun[0], sun[1]));
    }

    void close() {
        active = false;
        closed = true;
        decoder.shutdownNow();
    }

    void update() {
        if (!active || closed || loading || files.isEmpty()) return;
        String previous = preferences.getString("image", "");
        // A picture outside the time of day's group (the group changed) is replaced at once, not after the interval.
        List<File> playable = playable();
        File selected = WallpaperRotation.find(playable, previous);
        long changed = preferences.getLong("changed", 0);
        // The minute tick can land a moment before the interval is up, which would wait a whole extra minute.
        if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis() + 15_000, interval)) {
            selected = WallpaperRotation.pick(playable, previous, order, random);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        File next = selected;
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                show(bitmap);
                displayed = next.getName();
                preferences.edit().putString("path", next.getAbsolutePath()).apply();
                showCaption(true);
                if (!displayed.equals(previous) || changed == 0) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
            });
        });
    }

    /**
     * Small light text under the top-right buttons, above everything else and never focusable. It sits where the
     * top bar ends in every layout, so it does not cover the clock, the stats line or the tiles.
     */
    private static TextView makeCaption(Context context, FrameLayout root) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTextColor(0xD9FFFFFF);
        text.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(Math.round(520 * dp));
        text.setFocusable(false);
        text.setAlpha(0f);
        try {
            text.setTypeface(UiFont.get(context, R.string.font_regular));
        } catch (Exception ignored) {
            // the system font is fine
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        params.topMargin = Math.round(110 * dp);
        params.setMarginEnd(Math.round(62 * dp));
        root.addView(text, params);
        return text;
    }

    /** The displayed picture's name, faded across a change; a picture without a name hides the caption. */
    private void showCaption(boolean fade) {
        if (caption == null) return;
        String name = names.get(displayed);
        caption.animate().cancel();
        if (name == null) {
            caption.setAlpha(0f);
            return;
        }
        caption.setContentDescription(name);
        if (!fade || caption.getAlpha() == 0f) {
            caption.setText(name);
            caption.setAlpha(1f);
            return;
        }
        caption.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            caption.setText(name);
            caption.animate().alpha(1f).setDuration(350).start();
        }).start();
    }

    /** Names plus sizes: enough to notice a different collection without hashing every image. */
    private static String collectionKey(List<File> images) {
        StringBuilder key = new StringBuilder();
        for (File file : images) key.append(file.getName()).append(':').append(file.length()).append(';');
        return key.toString();
    }

    /** How long a new picture takes to appear over the old one. */
    private static final int FADE_MS = 900;

    /** Puts a picture on screen and releases the previous one once it is no longer drawn. A change from one picture
     *  to another fades the new one in over the old; the first picture appears at once. */
    private void show(Bitmap bitmap) {
        Bitmap old = current;
        current = bitmap;
        // The wallpaper covers the whole root; avoid drawing the fallback colour under it.
        root.setBackground(null);
        if (old != null && old != bitmap && image.getDrawable() != null) {
            fade(image, old, bitmap);
            if (blurImage != null) fade(blurImage, old, bitmap);
            if (buttonsBlurImage != null) fade(buttonsBlurImage, old, bitmap);
            image.postDelayed(old::recycle, FADE_MS + 400);
            return;
        }
        image.setImageBitmap(bitmap);
        if (blurImage != null) blurImage.setImageBitmap(bitmap);
        if (buttonsBlurImage != null) buttonsBlurImage.setImageBitmap(bitmap);
        if (old != null && old != bitmap) image.postDelayed(old::recycle, 500);
    }

    private void fade(ImageView view, Bitmap from, Bitmap to) {
        android.content.res.Resources res = root.getResources();
        android.graphics.drawable.TransitionDrawable fade = new android.graphics.drawable.TransitionDrawable(
                new android.graphics.drawable.Drawable[]{new android.graphics.drawable.BitmapDrawable(res, from),
                        new android.graphics.drawable.BitmapDrawable(res, to)});
        view.setImageDrawable(fade);
        fade.startTransition(FADE_MS);
        // When the fade is over, hold the new picture alone, so the old one can be released.
        view.postDelayed(() -> { if (current == to) view.setImageBitmap(to); }, FADE_MS + 100);
    }

    private Bitmap decode(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, this.width, this.height);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, this.width, this.height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            // The built-in gradients are decoded in full colour whatever wallpaper_true_color says: 16 bits turn their
            // soft transitions into visible bands; photographs hide that, so they keep the lighter format.
            boolean gradient = file.getName().startsWith("builtin-") || file.getName().startsWith("default-wallpaper");
            options.inPreferredConfig = gradient ? Bitmap.Config.ARGB_8888 : bitmapConfig;
            options.inMutable = dimColor != 0;
            Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
            if (decoded == null) return null;
            if (dimColor != 0) new Canvas(decoded).drawColor(dimColor);
            // One copy in GPU memory instead of a software bitmap plus its texture; the decode stays off the UI
            // thread for rotations, and Home's return no longer uploads 4-8 MB to the GPU.
            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
            if (gpu == null) return decoded;
            decoded.recycle();
            return gpu;
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
