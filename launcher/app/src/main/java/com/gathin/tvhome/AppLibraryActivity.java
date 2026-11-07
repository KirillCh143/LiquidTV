package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Local patch, not an upstream feature: a tvOS-style "all apps" screen. Home is locked to dock_apps
 * (AppAdapter.reload, lockToDock) with nothing below the dock any more, so this full-screen grid is the only
 * way to reach every other installed app — opened from MainActivity.onLibraryRequested (the top-bar library
 * button, and Down from any dock tile, since there is nowhere else for Down to go).
 *
 * Reuses AppAdapter and DockDecoration exactly as Home does (lockToDock off here), so the dock band and tile
 * look match Home precisely, just with every other app in the rows below it instead of nothing. Tapping a tile
 * always opens it; a non-dock tile's menu also has "Move" (onTileLongPress, requestMove/dispatchKeyEvent),
 * which picks the tile up right there and lets the arrows carry it anywhere in this whole grid — including into
 * the dock row, the only "add to Home" this screen offers, since dock_apps itself stays config-only. OK or Back
 * drops it and saves an Order (endReorderSession); Home reads that Order back (AppAdapter.reload's lockToDock
 * branch truncates the owner's resulting order to dockSize), so the dock always matches whatever ended up in
 * this screen's own first `columns` places.
 *
 * Background is Home's own wallpaper, blurred, with a 20%-white wash over it (runEntrance) — never a flat
 * colour. The entrance slides the dock up from Home's own on-screen position (EXTRA_DOCK_Y) while the rest of
 * the grid fades in under it and the blur/wash settle in, all in one motion; Up from the dock row or the row
 * right under it (onTileVerticalKey), or Back, plays the same motion in reverse and returns to Home (runExit).
 */
public class AppLibraryActivity extends Activity implements AppAdapter.TileListener {
    static final String EXTRA_WALLPAPER = "com.gathin.tvhome.extra.LIBRARY_WALLPAPER";
    static final String EXTRA_DOCK_Y = "com.gathin.tvhome.extra.LIBRARY_DOCK_Y";
    /** Local patch: the dock position Down was pressed from (MainActivity.onLibraryRequested) — the entrance
     *  focuses the app below it in the second row instead of always the first dock tile (focusAfterEntrance). */
    static final String EXTRA_FROM_COLUMN = "com.gathin.tvhome.extra.LIBRARY_FROM_COLUMN";
    /** Local patch: the column (lastFocusedPosition % columns) under whatever was focused when runExit fired —
     *  MainActivity.onActivityResult refocuses that dock tile, which may not be the one Down was pressed from
     *  (EXTRA_FROM_COLUMN) any more if the owner moved around the grid before leaving. */
    static final String EXTRA_EXIT_COLUMN = "com.gathin.tvhome.extra.LIBRARY_EXIT_COLUMN";
    private static final long TRANSITION_MS = 380;
    private static final float SCRIM_ALPHA = 0.1f;
    /** wallpaperBlur's own blur radius (dp) — dockGlass matches it so the dock reads as the same backdrop, just
     *  as glass, instead of a sharper "hole" standing out against the rest of the heavily blurred screen. */
    private static final float BACKDROP_BLUR_SIGMA_DP = 36f;

    private RecyclerView grid;
    private AppAdapter adapter;
    private FrameLayout root;
    private GlassView glass;
    private ImageView wallpaperSharp, wallpaperBlur;
    private View scrim;
    /** Local patch: the dock's own blurred/liquid-glass layer (setUpDockGlass), matching Home's dock look —
     *  null content until loadWallpaper decodes a bitmap, inert (no RenderEffect) below API 31 or dock_backdrop
     *  "color". */
    private ImageView dockGlass;
    private final RectF dockGlassRect = new RectF();
    private float dockGlassRadius;
    /** The vibrancy+blur RenderEffect alone, rebuilt into a fresh chain with dockLiquidShader on every mask
     *  move (refreshDockGlass) — see WallpaperPlayer.blurEffectBase for why the chain is rebuilt, not mutated. */
    private RenderEffect dockBlurEffectBase;
    /** Null when dock_backdrop isn't "liquid", below API 33, or the shader failed to compile on this device. */
    private RuntimeShader dockLiquidShader;
    private int columns;
    private boolean rtl;
    private float density;
    private boolean glassShown;
    private int homeDockY;
    private boolean exiting;
    private Config cfg;
    private List<ResolveInfo> leanback;
    /** Local patch: apps hidden by the "Hide app" tile action (LibraryApps.loadHidden), kept in sync in-place
     *  so showActions doesn't need to re-read the file on every long press. */
    private Set<String> hiddenSet;
    /** Local patch: Launcher settings' "Show hidden library apps" choice (LauncherSettingsActivity) — read
     *  once here, since this screen is a fresh onCreate every time it opens; no live-reload needed. */
    private boolean showHidden;
    /** Local patch: a rename, hide/unhide or uninstall happened — MainActivity.onActivityResult rebuilds
     *  Home's tiles for it (a rename can change a dock app's own label) when this screen closes. */
    private boolean changed;
    /** Local patch: refocus target for reloadLibrary after a tile action changes the catalogue under it. */
    private int lastFocusedPosition;
    private PopupWindow openPopup;
    /** Local patch: "Move" (onTileLongPress/requestMove) is carrying this tile; dispatchKeyEvent routes every
     *  key to moveBy/endReorderSession instead of the grid's own navigation while it is set. */
    private boolean reordering;
    private String movingKey;
    /** Local patch: the full catalogue's order when the carried tile was picked up, to skip saving when a
     *  session ends without anything actually moving. */
    private List<String> orderAtStart;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_app_library);
        cfg = Config.load(this);
        columns = Math.max(1, cfg.columns);
        rtl = getResources().getBoolean(R.bool.rtl_app_grid);
        density = getResources().getDisplayMetrics().density;
        root = findViewById(R.id.root);
        glass = findViewById(R.id.glass);
        wallpaperSharp = findViewById(R.id.wallpaperSharp);
        wallpaperBlur = findViewById(R.id.wallpaperBlur);
        scrim = findViewById(R.id.scrim);
        dockGlass = findViewById(R.id.dockGlass);
        loadWallpaper();

        hiddenSet = new HashSet<>(LibraryApps.loadHidden(this));
        showHidden = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getBoolean("library_show_hidden", false);

        grid = findViewById(R.id.libraryGrid);
        grid.setLayoutManager(new GridLayoutManager(this, columns));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        // Same band look as Home's own dock (opacity and light-glass gating both read from cfg), so switching
        // dock_opacity or dock_backdrop in Launcher settings carries over to this screen too.
        boolean dockGlassReady = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && !Config.BACKDROP_COLOR.equals(cfg.dockBackdrop);
        // Local patch: the rect is grid's resting (translationY 0) position, with its own current translationY
        // subtracted back out — DockDecoration.onDraw only fires on an actual relayout, not on every frame of
        // the hardware translationY slide (runEntrance/runExit), so a rect that included whatever translationY
        // happened to be live at that one draw would stay stuck there for the whole animation. dockGlass instead
        // gets the same translationY as grid, every frame, for free (see runEntrance/runExit), so this fixed
        // rect is all it ever needs.
        DockDecoration.MaskListener dockMask = dockGlassReady ? (left, top, right, bottom, radius) -> {
            int[] gridLoc = new int[2];
            grid.getLocationInWindow(gridLoc);
            int[] rootLoc = new int[2];
            root.getLocationInWindow(rootLoc);
            float dx = gridLoc[0] - rootLoc[0], dy = (gridLoc[1] - rootLoc[1]) - grid.getTranslationY();
            dockGlassRect.set(left + dx, top + dy, right + dx, bottom + dy);
            dockGlassRadius = radius;
            refreshDockGlass();
            dockGlass.invalidateOutline();
            dockGlass.invalidate();
        } : null;
        if (cfg.dockRow) grid.addItemDecoration(new DockDecoration(columns, density, false, cfg.alwaysShowNames,
                cfg.dockOpacity, Config.dockLightGlass(cfg), cfg.dockEvenPadding, false, false, dockMask));
        adapter = new AppAdapter(this, this, columns, cfg, false, true, showHidden);
        // Local patch: this screen's own adapter instance is fresh every time it opens, with no memory of a
        // previous session's drag-reorder (endReorderSession) — without reading the saved order back here too,
        // it would keep sorting by dock_apps and alphabetical order alone, showing a stale layout until the
        // owner moved something again (and that move, starting from the stale layout, would itself overwrite
        // the file and lose whatever an earlier session had placed).
        Order savedOrder = Order.load(Order.file(this));
        adapter.setSavedOrder(Order.ranks(savedOrder, cfg.dockApps), Order.docked(savedOrder, cfg.dockApps));
        grid.setAdapter(adapter);

        leanback = getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        adapter.reload(Collections.emptyList(), leanback);

        homeDockY = getIntent().getIntExtra(EXTRA_DOCK_Y, Integer.MIN_VALUE);
        grid.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                grid.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                runEntrance();
            }
        });

        grid.post(() -> {
            focusTile(entranceFocusPosition());
            // Android can focus the first focusable view on its own as soon as the window gets focus, before
            // this point — if that raced with the card's own layout, the glass may have sized itself off a
            // not-yet-measured card. One more pass, after everything has definitely settled, corrects it.
            grid.postDelayed(() -> {
                View focused = grid.findFocus();
                RecyclerView.ViewHolder vh = focused == null ? null : grid.findContainingViewHolder(focused);
                if (vh != null) onTileFocused(vh.getBindingAdapterPosition(), vh.itemView.findViewById(R.id.card));
            }, 120);
        });
    }

    private boolean resumedOnce;

    /** Local patch: App info and Uninstall (onTileLongPress) both leave for a system Activity and come back
     *  here — an uninstall in particular needs this screen's own catalogue rebuilt to drop the tile. An actual
     *  uninstall is a real package change, so MainActivity already notices and rebuilds Home on its own next
     *  resume (packagesChangedSinceRebuild) — no need to set changed here too. Skipped on the very first
     *  resume (onCreate just loaded it) and while the exit animation is already running. */
    @Override
    protected void onResume() {
        super.onResume();
        if (resumedOnce && !exiting) reloadLibrary(lastFocusedPosition);
        resumedOnce = true;
    }

    /** The first dock tile's own on-screen Y — not the grid view's, which moves relative to its content as
     *  paddingTop changes (#162-ish trap: a grid-view-level anchor drifts out of sync with the actual tiles
     *  whenever padding is retuned, like the paddingTop fix in activity_app_library.xml). Falls back to the
     *  grid's own position if position 0 is not bound yet (should not happen by the time this is called, but
     *  cheap to guard). */
    private int firstCardScreenY() {
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(0);
        View ref = vh != null ? vh.itemView.findViewById(R.id.card) : grid;
        int[] loc = new int[2];
        ref.getLocationOnScreen(loc);
        return loc[1];
    }

    /**
     * Local patch (Kirill's design, not upstream): the dock slides up from exactly where Home's own sits
     * (homeDockY, Home's grid on screen at the moment the button or Down was pressed) to this screen's own
     * layout position; the rows below the dock — invisible until now — fade in under it as it settles, and the
     * blur + white wash fade in over the same span. Only the tiles already laid out in the first frame animate;
     * ones scrolled into view later just appear, the normal way.
     */
    private void runEntrance() {
        float deltaY = homeDockY != Integer.MIN_VALUE ? homeDockY - firstCardScreenY() : 0f;
        grid.setTranslationY(deltaY);
        dockGlass.setTranslationY(deltaY);
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            RecyclerView.ViewHolder vh = grid.getChildViewHolder(child);
            if (vh.getBindingAdapterPosition() >= columns) child.setAlpha(0f);
        }
        grid.animate().translationY(0f).setDuration(TRANSITION_MS)
                .setInterpolator(new DecelerateInterpolator(1.4f)).start();
        // Local patch: dockGlass (the dock's blur/liquid layer) has no decoration of its own to redraw each
        // frame, so it just rides grid's exact translationY instead — a second animator with the same target,
        // duration and interpolator, not a shared listener (see the mask comment above). Its own alpha fades in
        // alongside wallpaperBlur's, instead of popping in already fully formed the instant the grid lays out —
        // the dock's own backdrop was the one part of this screen that didn't cross-fade in with everything else.
        dockGlass.animate().alpha(1f).translationY(0f).setDuration(TRANSITION_MS)
                .setInterpolator(new DecelerateInterpolator(1.4f)).start();
        wallpaperBlur.animate().alpha(1f).setDuration(TRANSITION_MS).withEndAction(() -> wallpaperSharp.setVisibility(View.GONE)).start();
        scrim.animate().alpha(SCRIM_ALPHA).setDuration(TRANSITION_MS).start();
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            RecyclerView.ViewHolder vh = grid.getChildViewHolder(child);
            if (vh.getBindingAdapterPosition() >= columns) {
                child.animate().alpha(1f).setStartDelay(90).setDuration(TRANSITION_MS - 90).start();
            }
        }
    }

    /**
     * Local patch: the exact reverse of runEntrance, for Up from the dock row (onTileVerticalKey — nowhere
     * else for Up to go) and Back (onBackPressed) alike. The dock slides back down to Home's own position
     * while the other rows fade out under it, the blur gives way to the sharp picture again (shown once more
     * before the blurred copy fades, mirroring how runEntrance hides it only once the blur is fully in) and the
     * white wash clears, then the activity finishes — overridePendingTransition suppresses the system's own
     * animation so only this motion shows, same as the entrance (MainActivity.onLibraryRequested).
     */
    private void runExit() {
        if (exiting) return;
        exiting = true;
        float deltaY = homeDockY != Integer.MIN_VALUE ? homeDockY - firstCardScreenY() : 0f;
        wallpaperSharp.setVisibility(View.VISIBLE);
        grid.animate().translationY(deltaY).setDuration(TRANSITION_MS)
                .setInterpolator(new AccelerateInterpolator(1.4f)).start();
        dockGlass.animate().alpha(0f).translationY(deltaY).setDuration(TRANSITION_MS)
                .setInterpolator(new AccelerateInterpolator(1.4f)).start();
        wallpaperBlur.animate().alpha(0f).setDuration(TRANSITION_MS).start();
        scrim.animate().alpha(0f).setDuration(TRANSITION_MS).start();
        glass.animate().alpha(0f).setDuration(TRANSITION_MS).start();
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            RecyclerView.ViewHolder vh = grid.getChildViewHolder(child);
            if (vh.getBindingAdapterPosition() >= columns) {
                child.animate().alpha(0f).setDuration(TRANSITION_MS - 90).start();
            }
        }
        // Local patch: wherever focus actually ended up, not necessarily the column Down was pressed from
        // (EXTRA_FROM_COLUMN) — the owner may have moved around the grid before leaving.
        Intent result = new Intent().putExtra(EXTRA_EXIT_COLUMN, lastFocusedPosition % columns);
        grid.postDelayed(() -> {
            setResult(changed ? RESULT_OK : RESULT_CANCELED, result);
            finish();
            overridePendingTransition(0, 0);
        }, TRANSITION_MS);
    }

    @Override
    public void onBackPressed() {
        if (reordering) endReorderSession();
        else runExit();
    }

    /**
     * Local patch: every key while a tile is carried (reordering) goes here instead of the grid's own
     * navigation — arrows move the carried tile (moveBy), OK or Back drops it where it is and saves the order
     * (endReorderSession). Mirrors MainActivity's own reorder mode, just simpler: one tile, started already
     * carried (requestMove), no separate pick-up step and no "carry another one next" loop.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!reordering) return super.dispatchKeyEvent(event);
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int code = event.getKeyCode();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) moveBy(code);
                return true;
            default:
                boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                        || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
                boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
                if (ok || back) {
                    if (!down) endReorderSession();
                    return true;
                }
                return super.dispatchKeyEvent(event);
        }
    }

    /** Local patch: "Move" in a tile's menu — picks it up right there; the arrows then carry it anywhere in
     *  this screen's whole grid, dock row included (dispatchKeyEvent/moveBy), until OK or Back drops it. */
    private void requestMove(String key) {
        int pos = adapter.positionOf(key);
        if (pos < 0) return;
        orderAtStart = adapter.movableKeys();
        movingKey = key;
        reordering = true;
        adapter.setReordering(true);
        // Every position must hold a tile that can move: drop the empty dock slots (fill_dock false) first,
        // same as beginReorder on Home — reloadLibrary already focuses once that settles.
        if (adapter.hasEmptySlots()) reloadLibrary(pos);
        else focusTile(pos);
        dimOthers();
        Toast.makeText(this, R.string.reorder_hint, Toast.LENGTH_LONG).show();
    }

    /**
     * Moves the carried tile one step, exactly like MainActivity's own moveBy: Left and Right wrap across rows
     * like a list (mirrored under rtl_app_grid), Up and Down move a whole row, and Down into a short last row
     * lands on its last tile. Nothing moves past either end.
     */
    private void moveBy(int keyCode) {
        int from = adapter.positionOf(movingKey);
        int n = adapter.movableCount();
        if (from < 0 || from >= n) {
            endReorderSession();
            return;
        }
        int to;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) to = rtl ? from + 1 : from - 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) to = rtl ? from - 1 : from + 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) to = from - columns;
        else if (from + columns < n) to = from + columns;
        else to = from / columns < (n - 1) / columns ? n - 1 : from;
        if (to < 0 || to >= n || to == from) return;
        keepScroll(() -> adapter.move(from, to));
        refocusAfterLayout(to);
    }

    /** Applies an adapter change without the grid jumping, same trick as MainActivity's own keepScroll: pin the
     *  first visible row at its current offset so the move only shifts rows after it, not the scroll itself. */
    private void keepScroll(Runnable change) {
        GridLayoutManager lm = (GridLayoutManager) grid.getLayoutManager();
        int first = lm.findFirstVisibleItemPosition();
        View firstView = first != RecyclerView.NO_POSITION ? lm.findViewByPosition(first) : null;
        int offset = firstView == null ? 0 : lm.getDecoratedTop(firstView) - grid.getPaddingTop();
        change.run();
        if (firstView != null) lm.scrollToPositionWithOffset(first, offset);
    }

    /** Once the grid has applied the move, put focus on the tile at pos — same frame-by-frame wait as
     *  MainActivity's refocusAfterLayout, since a fixed-size grid applies adapter changes on the next frame. */
    private void refocusAfterLayout(int pos) {
        grid.postOnAnimation(new Runnable() {
            int frames;

            @Override
            public void run() {
                if ((grid.hasPendingAdapterUpdates() || grid.isLayoutRequested()) && ++frames < 10) {
                    grid.postOnAnimation(this);
                    return;
                }
                focusTile(pos);
                dimOthers();
            }
        });
    }

    /** While a tile is carried, every other tile is dimmed so it stands out; otherwise all are full (tileAlpha). */
    private void dimOthers() {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View item = grid.getChildAt(i);
            item.setAlpha(tileAlpha(grid.getChildAdapterPosition(item)));
        }
    }

    private float tileAlpha(int pos) {
        if (!reordering) return 1f;
        return pos != RecyclerView.NO_POSITION && movingKey != null && movingKey.equals(adapter.keyAt(pos)) ? 1f : 0.45f;
    }

    /** Ends the session and saves the order if the carried tile actually moved since requestMove. */
    private void endReorderSession() {
        if (!reordering) return;
        int finalPos = adapter.positionOf(movingKey);
        reordering = false;
        movingKey = null;
        adapter.setReordering(false);
        dimOthers();
        List<String> keys = adapter.movableKeys();
        List<String> before = orderAtStart;
        orderAtStart = null;
        if (!keys.equals(before)) {
            Order order = new Order(Order.dockKey(cfg.dockApps), keys);
            if (Order.save(Order.file(this), order)) {
                adapter.setSavedOrder(Order.ranks(order, cfg.dockApps), Order.docked(order, cfg.dockApps));
                changed = true;
            } else {
                Toast.makeText(this, R.string.reorder_not_saved, Toast.LENGTH_SHORT).show();
            }
        }
        if (adapter.hasEmptySlots()) reloadLibrary(finalPos);
    }

    /** Home's exact wallpaper file (EXTRA_WALLPAPER), decoded sharp for the first frame and, API 31+, a second
     *  hardware-blurred copy that runEntrance fades in over it. No file, or wallpapers off: the fallback colour
     *  (activity_app_library.xml) shows through both ImageViews untouched. */
    private void loadWallpaper() {
        String path = getIntent().getStringExtra(EXTRA_WALLPAPER);
        if (path == null) return;
        Bitmap bitmap = decodeForScreen(this, new File(path));
        if (bitmap == null) return;
        wallpaperSharp.setImageBitmap(bitmap);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wallpaperBlur.setImageBitmap(bitmap);
            float sigma = BACKDROP_BLUR_SIGMA_DP * density;
            wallpaperBlur.setRenderEffect(RenderEffect.createBlurEffect(sigma, sigma, Shader.TileMode.CLAMP));
        } else {
            // No RenderEffect this far back: the sharp picture stays under the white wash alone, never blurred.
            wallpaperBlur.setImageBitmap(bitmap);
        }
        setUpDockGlass(bitmap);
    }

    /** Local patch: the dock's own blurred/liquid-glass layer, the same recipe as WallpaperPlayer's dock blur on
     *  Home (vibrancy, a tight blur, the liquid shader when dock_backdrop is "liquid") — a second full-screen
     *  copy of this same bitmap, clipped to the band's rect by the DockDecoration MaskListener wired in onCreate.
     *  Below API 31, or dock_backdrop "color", dockGlass just stays an empty ImageView (no RenderEffect, no
     *  outline clip, invisible). */
    private void setUpDockGlass(Bitmap bitmap) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        if (Config.BACKDROP_COLOR.equals(cfg.dockBackdrop)) return;
        dockGlass.setImageBitmap(bitmap);
        ColorMatrix vibrancyMatrix = new ColorMatrix();
        vibrancyMatrix.setSaturation(1.2f);
        // Local patch: the same white wash as scrim (SCRIM_ALPHA), as a proper alpha blend (scale down, then
        // add the washed-in white) rather than wallpaperPlayer's flat +32 offset on Home — Home's dock sits on
        // the bare wallpaper, nothing else washes it, but here the wash is what makes the rest of the screen
        // read as "this same backdrop, softened"; without it dockGlass's own sharper, more saturated blur
        // looked like a hole cut through the washed-out background around it.
        ColorMatrix whiteWash = new ColorMatrix(new float[]{
                1 - SCRIM_ALPHA, 0, 0, 0, 255 * SCRIM_ALPHA,
                0, 1 - SCRIM_ALPHA, 0, 0, 255 * SCRIM_ALPHA,
                0, 0, 1 - SCRIM_ALPHA, 0, 255 * SCRIM_ALPHA,
                0, 0, 0, 1, 0});
        vibrancyMatrix.postConcat(whiteWash);
        RenderEffect vibrancyEffect = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(vibrancyMatrix));
        // Matches wallpaperBlur's own radius (BACKDROP_BLUR_SIGMA_DP) for the same reason: dockGlass needs to
        // look like the same soft backdrop as the rest of the screen, with vibrancy/liquid refraction doing the
        // work of reading as "glass", not a sharper blur standing out against a much softer one.
        float sigma = BACKDROP_BLUR_SIGMA_DP * density;
        dockBlurEffectBase = RenderEffect.createBlurEffect(sigma, sigma, vibrancyEffect, Shader.TileMode.CLAMP);
        if (Config.BACKDROP_LIQUID.equals(cfg.dockBackdrop) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                dockLiquidShader = new RuntimeShader(LiquidGlassShader.SOURCE);
            } catch (Exception | LinkageError e) {
                Log.w("TvHome.Library", "liquid glass shader unavailable, falling back to plain blur: " + e);
            }
        }
        dockGlass.setRenderEffect(dockBlurEffectBase);
        dockGlass.setClipToOutline(true);
        dockGlass.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline outline) {
                if (dockGlassRect.isEmpty()) {
                    outline.setAlpha(0f);
                    return;
                }
                outline.setRoundRect(Math.round(dockGlassRect.left), Math.round(dockGlassRect.top),
                        Math.round(dockGlassRect.right), Math.round(dockGlassRect.bottom), dockGlassRadius);
            }
        });
    }

    /** Local patch: re-targets dockLiquidShader's uniforms to dockGlassRect and rebuilds the chain onto it —
     *  called on every DockDecoration mask move (onCreate's dockMask). A mutated shader's uniforms did not
     *  reliably reach an already-assigned RenderEffect chain on every device tried (WallpaperPlayer found the
     *  same), so the chain is rebuilt and reassigned here instead of relying on that alone. */
    private void refreshDockGlass() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;   // always true here (dockMask is wired only when dockGlassReady)
        if (dockBlurEffectBase == null) return;
        RenderEffect effect = dockBlurEffectBase;
        if (dockLiquidShader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            dockLiquidShader.setFloatUniform("rectMin", dockGlassRect.left, dockGlassRect.top);
            dockLiquidShader.setFloatUniform("rectMax", dockGlassRect.right, dockGlassRect.bottom);
            dockLiquidShader.setFloatUniform("cornerRadius", dockGlassRadius);
            dockLiquidShader.setFloatUniform("refractionHeight", 2f * density);
            dockLiquidShader.setFloatUniform("refractionAmount", 46f * density);
            try {
                effect = RenderEffect.createChainEffect(
                        RenderEffect.createRuntimeShaderEffect(dockLiquidShader, "content"), dockBlurEffectBase);
            } catch (Exception e) {
                Log.w("TvHome.Library", "liquid glass effect chain failed, falling back to plain blur: " + e);
            }
        }
        dockGlass.setRenderEffect(effect);
    }

    static Bitmap decodeForScreen(android.content.Context context, File file) {
        try {
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            int screenW = metrics.widthPixels, screenH = metrics.heightPixels;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, screenW, screenH);
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, screenW, screenH);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
            if (decoded == null) return null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
                if (gpu != null) {
                    decoded.recycle();
                    return gpu;
                }
            }
            return decoded;
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }

    @Override
    public void onTileFocused(int position, View card) {
        lastFocusedPosition = position;
        if (card.getWidth() == 0) {
            card.post(() -> { if (card.isFocused() && card.getWidth() > 0) onTileFocused(position, card); });
            return;
        }
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;
        float scale = adapter.focusScale();
        float w = card.getWidth() * scale + 2 * pad;
        float h = card.getHeight() * scale + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;
        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        lp.width = (int) w;
        lp.height = (int) h;
        glass.setLayoutParams(lp);
        glass.setRadius(AppAdapter.cornerRadius(card.getWidth(), density) * scale);
        if (!glassShown) {
            glassShown = true;
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(150).start();
        } else {
            glass.animate().translationX(x).translationY(y).alpha(1f).setDuration(150).start();
        }
    }

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.appCount();
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int rowCount = position < columns ? Math.min(count, adapter.dockFilled()) : count;
            return GridNavigation.atHorizontalEdge(position, rowCount, columns, rtl, keyCode == KeyEvent.KEYCODE_DPAD_LEFT);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            // Local patch: the dock row itself, and the row right under it too — Up from either one exits
            // straight back to Home, rather than stopping focus on the dock row first and needing a second Up.
            if (position < columns * 2) {
                runExit();
                return true;
            }
            focusTile(position - columns);
            return true;
        }
        int lastRowStart = ((count - 1) / columns) * columns;
        if (position >= lastRowStart) return true;   // nothing below the last row
        focusTile(Math.min(position + columns, count - 1));
        return true;
    }

    /** Local patch: Down from a dock tile (EXTRA_FROM_COLUMN) lands on the app right below it in the second
     *  row, not the first dock tile — the same column, clamped to the second row's own last app when it is
     *  shorter than the dock (e.g. a 5-wide dock with only 7 apps installed: columns 2-4 have nothing under
     *  them, so Down from any of them lands on the 7th app, under the dock's 2nd). The top-bar button has no
     *  dock column of its own (EXTRA_FROM_COLUMN absent) and keeps landing on the first dock tile. */
    private int entranceFocusPosition() {
        int fromColumn = getIntent().getIntExtra(EXTRA_FROM_COLUMN, -1);
        int count = adapter.appCount();
        if (fromColumn < 0 || columns >= count) return 0;
        int rowTwoEnd = Math.min(columns + columns, count);
        return Math.min(columns + fromColumn, rowTwoEnd - 1);
    }

    private void focusTile(int position) {
        int count = adapter.appCount();
        if (count == 0) return;
        int pos = adapter.focusablePosition(Math.max(0, Math.min(position, count - 1)));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            vh.itemView.findViewById(R.id.card).requestFocus();
            return;
        }
        grid.scrollToPosition(pos);
        grid.post(() -> {
            RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(pos);
            if (h != null) h.itemView.findViewById(R.id.card).requestFocus();
        });
    }

    // This screen has no top bar and no library-of-a-library: these three only fire for AppAdapter's own
    // library footer tile, which lockToDock-false still never shows (AppAdapter.showLibrary is hardcoded off).
    @Override public void onLibraryRequested() { }
    @Override public void onLibraryFocused() { }
    @Override public void onLibraryUp() { }

    /**
     * Local patch: Rename, Move (any tile, dock or not — picks it up for the arrows to carry anywhere in this
     * grid, requestMove), Hide/Unhide (not for a dock tile — Home shows it regardless, so hiding it from this
     * catalogue would only be confusing) and, where the system resolves them, Uninstall and App info. Same
     * glass-popup pattern as MainActivity's own tile menu (glassPopup/addMenuRow below), just anchored to this
     * screen's own root instead of Home's.
     */
    @Override
    public void onTileLongPress(int position, View card) {
        String pkg = adapter.packageAt(position);
        if (pkg == null || pkg.equals(getPackageName())) return;
        String label = adapter.labelAt(position);
        boolean isDock = position < columns;
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel);
        addMenuRow(rows, popup, R.string.library_rename, () -> showRenameDialog(pkg, label, position));
        String key = adapter.keyAt(position);
        if (key != null) addMenuRow(rows, popup, R.string.tile_move, () -> requestMove(key));
        if (!isDock) {
            boolean hidden = hiddenSet.contains(pkg);
            addMenuRow(rows, popup, hidden ? R.string.library_unhide : R.string.library_hide,
                    () -> toggleHidden(pkg, !hidden, position));
        }
        Intent uninstall = SystemSettings.uninstall(this, pkg);
        if (uninstall != null) addMenuRow(rows, popup, R.string.library_uninstall, () -> openExternal(uninstall, label));
        Intent info = SystemSettings.appInfo(this, pkg);
        if (info != null) addMenuRow(rows, popup, R.string.library_app_info, () -> openExternal(info, label));
        showMenu(popup, panel, card);
    }

    private void openExternal(Intent intent, String label) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, getString(R.string.cant_open, label), Toast.LENGTH_SHORT).show();
        }
    }

    private void showRenameDialog(String pkg, String currentLabel, int position) {
        EditText input = new EditText(this);
        input.setText(currentLabel);
        input.setHint(R.string.library_rename_hint);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        int pad = Math.round(20 * density);
        input.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
                .setTitle(R.string.library_rename)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (LibraryNames.save(this, pkg, input.getText().toString())) {
                        changed = true;
                        reloadLibrary(position);
                    } else {
                        Toast.makeText(this, R.string.library_save_failed, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void toggleHidden(String pkg, boolean hide, int position) {
        if (!LibraryApps.setHidden(this, pkg, hide)) {
            Toast.makeText(this, R.string.library_save_failed, Toast.LENGTH_LONG).show();
            return;
        }
        if (hide) hiddenSet.add(pkg); else hiddenSet.remove(pkg);
        changed = true;
        reloadLibrary(position);
    }

    /** Rebuilds the catalogue after a tile action (rename, hide/unhide) changes what it should show, and
     *  refocuses as close to where the owner was as the new, possibly shorter, list still allows. */
    private void reloadLibrary(int refocusPosition) {
        adapter.reload(Collections.emptyList(), leanback);
        grid.post(() -> focusTile(Math.min(refocusPosition, Math.max(0, adapter.appCount() - 1))));
    }

    private PopupWindow glassPopup(View panel) {
        PopupWindow popup = new PopupWindow(panel, (int) (220 * density), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> { if (openPopup == popup) openPopup = null; });
        openPopup = popup;
        return popup;
    }

    private void addMenuRow(LinearLayout rows, PopupWindow popup, int title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        // The OK-up after opening the menu must not activate its first row.
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pressed[0] = true;
                return true;
            }
            boolean activate = pressed[0];
            pressed[0] = false;
            if (activate) {
                popup.dismiss();
                action.run();
            }
            return true;
        });
        rows.addView(row);
    }

    /** Drops the menu under the tile when it fits there, or above it otherwise — same rule as MainActivity's
     *  own tile menu, since a library tile can sit anywhere on screen, not only near the bottom. */
    private void showMenu(PopupWindow popup, View panel, View card) {
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (220 * density), panel.getMeasuredWidth()));
        int[] at = new int[2];
        card.getLocationInWindow(at);
        int gap = (int) (10 * density), height = panel.getMeasuredHeight();
        if (at[1] + card.getHeight() + gap + height <= root.getHeight()) {
            panel.setAlpha(0f);
            panel.setPivotY(0f);
            panel.setScaleY(0.92f);
            panel.setTranslationY(-8 * density);
            popup.showAsDropDown(card, 0, gap, Gravity.START);
        } else {
            panel.setAlpha(0f);
            panel.setPivotY(height);
            panel.setScaleY(0.92f);
            panel.setTranslationY(8 * density);
            popup.showAtLocation(root, Gravity.NO_GRAVITY, at[0], Math.max(0, at[1] - gap - height));
        }
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }
}
