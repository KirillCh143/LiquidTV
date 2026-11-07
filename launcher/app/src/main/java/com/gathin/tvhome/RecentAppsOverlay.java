package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RenderEffect;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * recent_apps: the app switcher drawn over whatever app is on screen, as an accessibility overlay window.
 * Cards overlap in a deck: the focused one sits in the middle with its name above it, the ones before it
 * stack behind it on the left. RecentApps feeds it the keys (Left/Right move, Up closes the app, OK opens it,
 * Back closes the switcher), so the window never takes input focus from the app underneath.
 */
final class RecentAppsOverlay {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final PathInterpolator EASE = new PathInterpolator(0.22f, 1f, 0.36f, 1f);
    private static final long MOVE_MS = 380;
    /** AppLibraryActivity's blur radius (dp) and white wash, so both screens share one backdrop. */
    private static final float BACKDROP_BLUR_SIGMA_DP = 36f;
    private static final float SCRIM_ALPHA = 0.1f;

    private static WindowManager wm;
    private static FrameLayout root;
    private static HorizontalScrollView scroll;
    private static LinearLayout row;
    private static View emptyView;
    private static View hintView;
    private static ObjectAnimator scroller;
    private static int focus;
    private static int scrollX;
    private static int cardW;
    private static int screenH;
    private static float density;

    private static final class Item {
        String pkg;
        String label;
        Drawable banner;
        Drawable icon;
    }

    private RecentAppsOverlay() {
    }

    static boolean isShowing() {
        return root != null;
    }

    /** Opens the switcher; the context is the key helper, the only thing allowed to add an accessibility overlay. */
    static void show(Context svc) {
        if (root != null) return;
        WindowManager manager = svc.getSystemService(WindowManager.class);
        if (manager == null) return;
        DisplayMetrics dm = svc.getResources().getDisplayMetrics();
        density = dm.density;
        int screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        FrameLayout layer = build(svc, screenW);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        try {
            manager.addView(layer, lp);
        } catch (RuntimeException failed) {
            return;
        }
        wm = manager;
        root = layer;
        enter();
    }

    static void dismiss() {
        MAIN.removeCallbacksAndMessages(null);
        if (scroller != null) scroller.cancel();
        scroller = null;
        FrameLayout gone = root;
        WindowManager manager = wm;
        root = null;
        wm = null;
        scroll = null;
        row = null;
        emptyView = null;
        hintView = null;
        if (gone != null && manager != null) {
            try {
                manager.removeViewImmediate(gone);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** Keys while the switcher is up. True when consumed; other keys (volume) go on to the system. */
    static boolean onKey(Context svc, KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (!down) dismiss();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) moveFocus(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) moveFocus(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) closeFocused(svc);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!down) activate(svc);
                return true;
            default:
                return false;
        }
    }

    // ---- build ----

    private static FrameLayout build(Context c, int screenW) {
        FrameLayout layer = new FrameLayout(c);
        layer.setClipChildren(false);
        layer.setClipToPadding(false);
        // The same backdrop as the all-apps screen (AppLibraryActivity): Home's wallpaper, blurred (36dp, API 31+),
        // with a 10% white wash over it, on #101419 when there is no wallpaper file.
        layer.setBackgroundColor(0xFF101419);
        Bitmap wallpaper = wallpaper(c, screenW);
        if (wallpaper != null) {
            ImageView picture = new ImageView(c);
            picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
            picture.setImageBitmap(wallpaper);
            if (Build.VERSION.SDK_INT >= 31) {
                float sigma = BACKDROP_BLUR_SIGMA_DP * density;
                picture.setRenderEffect(RenderEffect.createBlurEffect(sigma, sigma, Shader.TileMode.CLAMP));
            }
            layer.addView(picture, new FrameLayout.LayoutParams(-1, -1));
        }
        View wash = new View(c);
        wash.setBackgroundColor(0xFFFFFFFF);
        wash.setAlpha(SCRIM_ALPHA);
        layer.addView(wash, new FrameLayout.LayoutParams(-1, -1));

        TextView title = text(c, c.getString(R.string.recent_apps_title), 20, 0xE6FFFFFF);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tlp.topMargin = dp(34);
        tlp.leftMargin = dp(55);
        layer.addView(title, tlp);

        TextView empty = text(c, c.getString(R.string.recent_apps_empty), 16, 0xB3FFFFFF);
        layer.addView(empty, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        emptyView = empty;

        HorizontalScrollView sv = new HorizontalScrollView(c) {
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
                return 0;
            }
        };
        sv.setHorizontalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        sv.setFocusable(false);
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setClipChildren(false);
        r.setClipToPadding(false);
        sv.addView(r, new FrameLayout.LayoutParams(-2, -2));
        layer.addView(sv, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL));
        scroll = sv;
        row = r;

        TextView hint = text(c, c.getString(R.string.recent_apps_hint), 13, 0x99FFFFFF);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hlp.bottomMargin = dp(40);
        layer.addView(hint, hlp);
        hintView = hint;

        cardW = Math.round(screenW * 0.44f);
        int cardH = cardW * 9 / 16;
        int side = (screenW - cardW) / 2;
        sv.setPadding(side, 0, side, 0);
        r.setPadding(0, dp(72), 0, dp(72));

        List<Item> items = resolve(c);
        for (Item item : items) r.addView(card(c, item, cardH));
        if (!items.isEmpty()) r.addView(closeAll(c));
        boolean none = items.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        hint.setVisibility(none ? View.GONE : View.VISIBLE);
        focus = 0;
        scrollX = 0;
        if (none) MAIN.postDelayed(RecentAppsOverlay::dismiss, 1500);
        return layer;
    }

    private static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp * density);
        return t;
    }

    private static List<Item> resolve(Context c) {
        List<Item> items = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        for (String pkg : RecentApps.list(c)) {
            Item item = resolve(pm, pkg);
            if (item != null) items.add(item);
        }
        return items;
    }

    private static Item resolve(PackageManager pm, String pkg) {
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            List<ResolveInfo> found = pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(cat).setPackage(pkg), 0);
            if (found.isEmpty() || found.get(0).activityInfo == null) continue;
            ResolveInfo ri = found.get(0);
            ActivityInfo ai = ri.activityInfo;
            Item item = new Item();
            item.pkg = pkg;
            item.label = String.valueOf(ri.loadLabel(pm));
            Drawable banner = ai.loadBanner(pm);
            if (banner == null && ai.applicationInfo != null) banner = ai.applicationInfo.loadBanner(pm);
            item.banner = banner;
            item.icon = ri.loadIcon(pm);
            return item;
        }
        return null;
    }

    private static View card(Context c, Item item, int cardH) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        box.setTag(item);

        LinearLayout header = new LinearLayout(c);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setAlpha(0f);
        ImageView badge = new ImageView(c);
        badge.setImageDrawable(item.icon);
        header.addView(badge, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView name = text(c, item.label, 14, Color.WHITE);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(Math.max(dp(80), cardW - dp(60)), -2);
        nlp.leftMargin = dp(10);
        header.addView(name, nlp);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.bottomMargin = dp(12);
        hlp.leftMargin = dp(4);
        box.addView(header, hlp);

        ImageView art = new ImageView(c);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        final float radius = dp(30);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        art.setClipToOutline(true);
        art.setElevation(dp(14));
        art.setImageBitmap(render(item, 640, 360));
        box.addView(art, new LinearLayout.LayoutParams(cardW, cardH));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(Math.round(cardW * -0.06f));
        box.setLayoutParams(lp);
        return box;
    }

    private static View closeAll(Context c) {
        LinearLayout btn = new LinearLayout(c);
        btn.setOrientation(LinearLayout.VERTICAL);
        btn.setGravity(Gravity.CENTER_HORIZONTAL);
        btn.setAlpha(0.7f);
        FrameLayout circle = new FrameLayout(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x33FFFFFF);
        bg.setStroke(Math.max(1, dp(1)), 0x59FFFFFF);
        circle.setBackground(bg);
        circle.setTag(bg);
        TextView x = text(c, "✕", 30, Color.WHITE);
        circle.addView(x, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        btn.addView(circle, new LinearLayout.LayoutParams(dp(84), dp(84)));
        TextView label = text(c, c.getString(R.string.recent_apps_close_all), 14, Color.WHITE);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.topMargin = dp(12);
        btn.addView(label, llp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(38);
        lp.setMarginStart(Math.round(cardW * 0.06f) + dp(40));
        btn.setLayoutParams(lp);
        return btn;
    }

    // ---- focus, deck and scroll ----

    private static int apps() {
        int n = 0;
        for (int i = 0; row != null && i < row.getChildCount(); i++) {
            if (row.getChildAt(i).getTag() instanceof Item) n++;
        }
        return n;
    }

    private static void moveFocus(int delta) {
        if (row == null) return;
        int next = focus + delta;
        if (next < 0 || next >= row.getChildCount()) return;
        focus = next;
        layoutDeck();
    }

    private static void layoutDeck() {
        if (row == null) return;
        int n = apps();
        boolean onClose = focus >= n;
        int anchor = onClose ? Math.max(0, n - 1) : focus;
        float overlap = 0.06f * cardW;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (!(child.getTag() instanceof Item)) {
                boolean on = onClose;
                child.animate().translationX(0f).scaleX(on ? 1.12f : 1f).scaleY(on ? 1.12f : 1f)
                        .alpha(on ? 1f : 0.7f).setDuration(MOVE_MS).setInterpolator(EASE).start();
                View circle = ((ViewGroup) child).getChildAt(0);
                if (circle.getTag() instanceof GradientDrawable) {
                    ((GradientDrawable) circle.getTag()).setColor(on ? 0xF2FFFFFF : 0x33FFFFFF);
                    ((TextView) ((ViewGroup) circle).getChildAt(0)).setTextColor(on ? 0xFF1C1C1E : Color.WHITE);
                }
                continue;
            }
            int rel = i - anchor;
            int dist = Math.abs(rel);
            boolean current = !onClose && dist == 0;
            float x = 0f;
            if (rel < 0) x = (cardW + overlap) * dist - (0.12f * dist + 0.25f) * cardW;
            child.setTranslationZ(((current ? 40 : 20 - Math.min(9, dist) * 2)) * density);
            child.animate().translationX(x).setDuration(MOVE_MS).setInterpolator(EASE).start();
            ((ViewGroup) child).getChildAt(0).setAlpha(current ? 1f : 0f);
        }
        scrollTo(anchor, onClose);
    }

    private static void scrollTo(int anchor, boolean onClose) {
        if (scroll == null || row == null) return;
        int step = cardW + Math.round(cardW * -0.06f);
        int target = anchor * step;
        if (onClose) {
            View btn = row.getChildAt(row.getChildCount() - 1);
            int btnLeft = (row.getChildCount() - 1) * step + Math.round(cardW * 0.06f) + dp(40);
            int btnW = btn.getWidth() > 0 ? btn.getWidth() : dp(84);
            int viewW = scroll.getWidth() > 0 ? scroll.getWidth() : cardW + scroll.getPaddingLeft() * 2;
            target = Math.max(0, btnLeft + btnW / 2 - viewW / 2 + scroll.getPaddingLeft());
        }
        if (scroller != null) scroller.cancel();
        if (Math.abs(target - scrollX) <= 1) {
            scrollX = target;
            scroll.scrollTo(target, 0);
            return;
        }
        scroll.scrollTo(scrollX, 0);
        scroller = ObjectAnimator.ofInt(scroll, "scrollX", scrollX, target);
        scroller.addUpdateListener(a -> scrollX = (Integer) a.getAnimatedValue());
        scroller.setDuration(MOVE_MS);
        scroller.setInterpolator(EASE);
        scroller.start();
    }

    // ---- actions ----

    private static void activate(Context c) {
        if (row == null || row.getChildCount() == 0) return;
        View v = row.getChildAt(Math.min(focus, row.getChildCount() - 1));
        if (v.getTag() instanceof Item) {
            String pkg = ((Item) v.getTag()).pkg;
            dismiss();
            open(c, pkg);
        } else {
            closeAllCards(c);
        }
    }

    private static void open(Context c, String pkg) {
        RecentApps.record(c, pkg);
        PackageManager pm = c.getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
        if (launch == null) return;
        try {
            c.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (RuntimeException ignored) {
        }
    }

    private static void closeFocused(Context c) {
        if (row == null || focus >= apps()) return;
        closeCard(c, row.getChildAt(focus), true);
    }

    private static void closeCard(Context c, View card, boolean refocus) {
        if (row == null || !(card.getTag() instanceof Item)) return;
        Item item = (Item) card.getTag();
        card.setTag(null);
        final int idx = row.indexOfChild(card);
        final int step = cardW + Math.round(cardW * -0.06f);
        card.animate().translationY(-screenH * 0.62f).alpha(0f).setDuration(250)
                .setInterpolator(new AccelerateInterpolator(1.35f))
                .withEndAction(() -> {
                    if (row == null) return;
                    row.removeView(card);
                    if (apps() == 0) {
                        row.removeAllViews();
                        if (hintView != null) hintView.setVisibility(View.GONE);
                        if (emptyView != null) emptyView.setVisibility(View.VISIBLE);
                        MAIN.postDelayed(RecentAppsOverlay::dismiss, 1500);
                        return;
                    }
                    if (refocus) {
                        for (int i = idx; i < row.getChildCount(); i++) {
                            View shifted = row.getChildAt(i);
                            shifted.animate().cancel();
                            shifted.setTranslationX(shifted.getTranslationX() + step);
                        }
                        focus = Math.min(idx, apps() - 1);
                        layoutDeck();
                    }
                }).start();
        kill(c, item.pkg);
    }

    private static void closeAllCards(Context c) {
        if (row == null) return;
        int last = apps() - 1;
        for (int i = last; i >= 0; i--) {
            final View card = row.getChildAt(i);
            MAIN.postDelayed(() -> closeCard(c, card, false), (long) (last - i) * 70L);
        }
    }

    private static void kill(Context c, String pkg) {
        RecentApps.remove(c, pkg);
        ActivityManager am = c.getSystemService(ActivityManager.class);
        if (am != null) {
            try {
                am.killBackgroundProcesses(pkg);
            } catch (SecurityException ignored) {
            }
        }
    }

    private static void enter() {
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(220).setInterpolator(EASE).start();
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            child.setTranslationY(dp(70));
            child.animate().translationY(0f).setStartDelay(i * 35L).setDuration(420)
                    .setInterpolator(new DecelerateInterpolator(1.8f)).start();
        }
        layoutDeck();
    }

    // ---- backdrop ----

    /** Home's current wallpaper (WallpaperPlayer notes its path), cover-scaled to the screen; null without one. */
    private static Bitmap wallpaper(Context c, int screenW) {
        String path = c.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE).getString("path", null);
        if (path == null) return null;
        try {
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, dm.widthPixels, dm.heightPixels);
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, dm.widthPixels, dm.heightPixels);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            return BitmapFactory.decodeFile(path, options);
        } catch (RuntimeException | OutOfMemoryError failed) {
            return null;
        }
    }

    // ---- artwork ----

    private static Bitmap render(Item item, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        if (item.banner != null) {
            int dw = item.banner.getIntrinsicWidth() > 0 ? item.banner.getIntrinsicWidth() : 16;
            int dh = item.banner.getIntrinsicHeight() > 0 ? item.banner.getIntrinsicHeight() : 9;
            float scale = Math.max((float) w / dw, (float) h / dh);
            int sw = Math.round(dw * scale), sh = Math.round(dh * scale);
            Rect old = item.banner.copyBounds();
            item.banner.setBounds((w - sw) / 2, (h - sh) / 2, (w + sw) / 2, (h + sh) / 2);
            item.banner.draw(cv);
            item.banner.setBounds(old);
            return out;
        }
        float[] hsv = new float[3];
        Color.colorToHSV(dominant(item.icon), hsv);
        float sat = hsv[1] < 0.15f ? 0.12f : 0.72f;
        int top = Color.HSVToColor(new float[]{hsv[0], sat * 0.85f, 0.78f});
        int bottom = Color.HSVToColor(new float[]{(hsv[0] + 8f) % 360f, Math.min(1f, sat * 1.1f), 0.42f});
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, top, bottom, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, p);
        if (item.icon != null) {
            int s = Math.round(h * 0.46f);
            Rect old = item.icon.copyBounds();
            item.icon.setBounds((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2);
            item.icon.draw(cv);
            item.icon.setBounds(old);
        }
        return out;
    }

    private static int dominant(Drawable d) {
        if (d == null) return 0xFF2C2C2E;
        Bitmap b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        Rect old = d.copyBounds();
        d.setBounds(0, 0, 16, 16);
        d.draw(new Canvas(b));
        d.setBounds(old);
        long r = 0, g = 0, bl = 0, n = 0;
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                int px = b.getPixel(x, y);
                if (Color.alpha(px) < 128) continue;
                r += Color.red(px);
                g += Color.green(px);
                bl += Color.blue(px);
                n++;
            }
        }
        b.recycle();
        return n == 0 ? 0xFF2C2C2E : Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
    }

    private static int dp(float v) {
        return Math.round(v * density);
    }
}
