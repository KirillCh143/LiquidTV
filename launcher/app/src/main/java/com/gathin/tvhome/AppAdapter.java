package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock (favourites, fixed order,
 * then empty slots up to a full row, see {@link DockSlots}), then the module cards the owner chose to show
 * (by label), the rest is alphabetical.
 */
public class AppAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for a D-pad direction key on a tile. Return true when handled (focus stays put). */
        boolean onTileVerticalKey(int position, int keyCode);

        void onLibraryRequested();
        void onLibraryFocused();
        /** Hold OK on a tile: the activity shows the tile menu (move, remove from Home, app info). */
        void onTileLongPress(int position, View card);
        void onLibraryUp();
    }

    static final class Entry {
        static final int APP = 0, HOME_SETTINGS = 1, CARD = 2, EMPTY = 3;

        final int kind;
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        /** Text under the tile when it is focused in the grid: the label, plus the caption for a card. */
        final String name;
        /** Ambient glow colour; 0 until sampled from the banner, or the card's accent from the start. */
        int ambient;
        /** uniform_tiles card fill, top and bottom colour; null until sampled from the icon. */
        int[] backdrop;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this(APP, label, component, banner, icon, null, 0);
        }

        Entry(int kind, String label, ComponentName component, Drawable banner, Drawable icon, String caption, int accent) {
            this.kind = kind;
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
            this.name = caption == null ? label : label + " \u00B7 " + caption;
            this.ambient = accent;
        }
    }

    static final float FOCUS_SCALE = 1.26f;
    /** Local patch: an unfocused tile's own resting size, up from 1f (tileWidthPx already leaves room for
     *  FOCUS_SCALE, so this still fits inside the cell/gutter without touching neighbours). */
    static final float REST_SCALE = 1.05f;
    /** Design size of a tile (dp); tiles shrink from here when the columns don't fit, see tileWidthPx. */
    static final float DESIGN_W_DP = 189f, DESIGN_H_DP = 114f;

    /**
     * Tile width for a grid of this inner width: the cell minus room for the focus zoom and the glass
     * highlight, capped at the design size so 4 columns on a 1080p TV look exactly as before.
     */
    static int tileWidthPx(int innerWidthPx, int columns, float density) {
        int design = Math.round(DESIGN_W_DP * density);
        if (innerWidthPx <= 0 || columns <= 0) return design;
        int cell = innerWidthPx / columns;
        // Local patch: the tile is 80% of its cell (was: the cell minus the focus zoom's room), so tiles are
        // wider and the gaps between them narrower; a focused tile (x1.26) still clears its neighbours.
        int fit = Math.round(cell * 0.8047f);   // = 239 px at a 297 px cell: tile size as before, 46 px between tiles
        return Math.max(Math.round(80 * density), Math.min(design, fit));
    }

    static int tileHeightPx(int tileWidthPx) {
        return Math.round(tileWidthPx * DESIGN_H_DP / DESIGN_W_DP);
    }

    /** Height of one grid item: the tile, the name label under it and the item's own padding. */
    static int itemHeightPx(int tileHeightPx, float density) {
        return itemHeightPx(tileHeightPx, true, density);
    }

    /** The same for either layout: the plain grid (dock_row false) uses the compact spacing. */
    static int itemHeightPx(int tileHeightPx, boolean dockRow, float density) {
        return tileHeightPx + Math.round((dockRow ? ITEM_EXTRA_DP : COMPACT_ITEM_EXTRA_DP) * density);
    }

    /**
     * dock_row false: 4dp padding above and below, a 4dp gap and a 20dp name label. The plain grid fits more
     * rows on the screen, and with item_app.xml's roomier spacing the tiles came out small with wide gaps
     * between them.
     */
    static final float COMPACT_ITEM_EXTRA_DP = 4 + 4 + 4 + 20;

    /** item_app.xml: 8dp padding above and below, a 7dp gap and the 22dp name label under the tile. */
    static final float ITEM_EXTRA_DP = 45;
    /** DockDecoration's offsets above and below the dock row. */
    static final float DOCK_EXTRA_DP = 6 + 12;
    /** Room kept under the dock row (MainActivity places the dock with the same). */
    static final float BOTTOM_ROOM_DP = 7.9f;   // local patch: was 30; the dock sits 22dp lower in all (+10%, +20%, -10% of its height)
    /** dock_row false: room above the first row for its zoom and glass (MainActivity places the grid with it). */
    static final float TOP_ROOM_DP = 12;

    private final Context ctx;
    private final boolean dockTitle;
    /** The owner's saved order (reorder button, Order): key -> rank. Empty when there is none or it no longer applies. */
    private Map<String, Integer> savedRanks = Collections.emptyMap();
    /** Dock size recorded with the saved order (Order.docked), or -1: then dock_apps says which tiles are docked. */
    private int savedDocked = -1;
    /** Reorder mode: no empty dock slots, so every position holds a tile that can move. */
    private boolean reordering;
    private final TileListener listener;
    private final int dockSize;
    /** config dock_row: false makes the dock apps the first tiles of one plain grid (names shown, no empty slots). */
    private final boolean dockRow;
    /** config single_row: tiles sit in one sideways row, each as wide as a grid column would be. */
    private final boolean singleRow;
    /** config always_show_names: the alpha of an unfocused tile's name (0 hides it). */
    private final float restNameAlpha;
    /** cinematic_home: only then does a launch record the last-opened app the shelf uses. */
    private final boolean cinematic;
    /** Dock order (config.xml dock_apps, or a theme's config.json). */
    private final List<String> favourites;
    /** Grid apps fill the dock slots dock_apps leaves free (config.xml, fill_dock). */
    private final boolean fillDock;
    /** Dock apps found at the last reload, and the empty slots after them. */
    private int docked, emptySlots;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    /** Apps pre-seeded into the App library's "added" set (config.xml library_apps); empty by default. */
    private final Set<String> libraryPreload;
    private final boolean animations;
    private final boolean showLibrary;
    /** Packages with a leanback entry at the last reload(): their tiles launch with the leanback category. */
    private Set<String> leanbackPackages = new HashSet<>();
    private final boolean showHomeSettings;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): no overshoot, shorter fades. */
    private final boolean reducedMotion;
    /** The focus zoom may overshoot (config.xml, focus_overshoot). */
    private final boolean focusOvershoot;
    /** Package to the action its tile sends instead of the start screen (config.xml, launch_actions). */
    private final Map<String, String> launchActions;
    /** Hold OK opens the activity's tile menu (config.xml, tile_menu); false opens App info, as before. */
    private final boolean tileMenu;
    /** Tile names and bundled banners that replace the app's own (config.xml, app_labels and app_banners). */
    private final Map<String, String> appLabels, appBanners;
    /** Every app tile is its icon on a card tinted from that icon, never its banner (config.xml, uniform_tiles). */
    private final boolean uniformTiles;
    /** Local patch: true for Home's own adapter (locks the grid to dock_apps, see reload()); false for
     *  AppLibraryActivity's adapter, which shows every app — the dock still leads, sorted first as always. */
    private final boolean lockToDock;
    /** Local patch (tvOS reference screenshot): a drop shadow under every tile, via elevation — card's existing
     *  outline provider (its rounded-rect) shapes it. Off for Home (unchanged look); on for AppLibraryActivity. */
    private final boolean tileShadow;
    /** Local patch: AppLibraryActivity's own "show hidden library apps" choice (Launcher settings) — meaningless
     *  when lockToDock, since Home never consults the hidden set at all. */
    private final boolean showHiddenLibraryApps;
    private final List<Entry> items = new ArrayList<>();

    /** Monochrome: the focused tile's outline is a plain 1dp white stroke at 15%, no gradient, glow or shader. */
    private final boolean plainRim;
    /** Frosted ice: the focused tile's outline is the gradient stroke alone, without the inner-glow shader. */
    private final boolean strokeOnlyRim;

    AppAdapter(Context ctx, TileListener listener, int dockSize, Config cfg, boolean lockToDock, boolean tileShadow,
               boolean showHiddenLibraryApps) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.lockToDock = lockToDock;
        this.showHiddenLibraryApps = showHiddenLibraryApps;
        this.tileShadow = tileShadow;
        this.favourites = cfg.dockApps;
        this.fillDock = cfg.fillDock;
        this.hidden = new HashSet<>(cfg.hiddenApps);
        this.libraryPreload = new HashSet<>(cfg.libraryApps);
        this.animations = cfg.animations;
        this.plainRim = Config.BACKDROP_COLOR.equals(cfg.dockBackdrop);
        this.strokeOnlyRim = Config.BACKDROP_BLUR.equals(cfg.dockBackdrop);
        this.showHomeSettings = cfg.showHomeSettingsShortcut;
        // Local patch: the library tile at the end of the grid is off; the top-bar library_button stays as the
        // only way in, since cfg.appLibrary itself (tile_menu's Remove from Home, the top-bar button) stays on.
        this.showLibrary = false;
        this.reducedMotion = cfg.reducedMotion;
        this.focusOvershoot = cfg.focusOvershoot;
        this.dockTitle = cfg.dockTitle;
        this.dockRow = cfg.dockRow;
        this.singleRow = cfg.singleRow;
        this.restNameAlpha = cfg.alwaysShowNames ? 0.75f : 0f;
        this.cinematic = cfg.cinematicHome;
        this.launchActions = cfg.launchActions;
        this.tileMenu = cfg.tileMenu;
        this.appLabels = cfg.appLabels;
        this.appBanners = cfg.appBanners;
        this.uniformTiles = cfg.uniformTiles;
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    /**
     * Rebuilds the tiles. A shown module card stands in for every tile of its package (same component,
     * so focus follows it); hidden_apps wins over a card, and a card's package in dock_apps takes that slot.
     */
    void reload(List<Cards.Card> cards, List<ResolveInfo> leanback) {
        PackageManager pm = ctx.getPackageManager();
        // The LEANBACK query is shared with the module-card check (MainActivity); copy it before adding
        // library apps so the caller's list stays as it was.
        List<ResolveInfo> found = new ArrayList<>(leanback);
        Set<String> tvPackages = new HashSet<>();
        for (ResolveInfo ri : found) if (ri.activityInfo != null) tvPackages.add(ri.activityInfo.packageName);
        leanbackPackages = tvPackages;
        // Local patch: every installed app is a candidate, on both screens now — Home (lockToDock) to let the
        // dock auto-fill past dock_apps up to dockSize, and so the owner's own saved order (a drag-reorder in
        // the library, AppLibraryActivity) can place any app there; the library screen as its whole catalogue,
        // as it always was. The sort below (saved order, then dock_apps, then alphabetical) and, for lockToDock,
        // the truncation after it decide which ones make the cut — a config dock_apps member always does
        // (isDocked's favourites fallback).
        Set<String> seenPkgs = new HashSet<>(tvPackages);
        for (ResolveInfo ri : pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            if (ri.activityInfo != null && seenPkgs.add(ri.activityInfo.packageName)) found.add(ri);
        }
        Map<String, Cards.Card> cardByPkg = new HashMap<>();
        for (Cards.Card c : cards) cardByPkg.put(c.pkg, c);
        // Local patch: custom names (library_names.txt) apply everywhere, Home's dock included — a rename
        // follows the app regardless of lockToDock. The hidden set (library_hidden.txt) only ever matters to
        // AppLibraryActivity's own catalogue; Home's lockToDock branch above already ignores it entirely.
        Map<String, String> customNames = LibraryNames.load(ctx);
        Set<String> hiddenLib = lockToDock ? Collections.emptySet() : LibraryApps.loadHidden(ctx);
        List<Entry> fresh = new ArrayList<>();
        // Some system apps declare two leanback entry points under one name (Sony's Timers & Clock on the
        // BRAVIA VU31), which would show as two identical tiles: keep the first per package and label.
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || !ri.activityInfo.exported) continue;
            ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
            if (lockToDock) {
                // Local patch: hidden_apps still keeps an app off Home, same as off the library, unless it is an
                // explicit dock_apps member (the owner asked for it by name, so it always shows regardless —
                // #162's fillDock gap is moot the same way, there is never a gap it needs to fill from outside
                // dock_apps any more). Who actually ends up in the dock itself — dock_apps, or whatever the
                // owner last dragged there in the library — is the sort below and the truncation after it, not
                // this filter: it only decides who is even a candidate.
                if (!favourites.contains(pkg) && hidden.contains(pkg)) continue;
            } else if (hidden.contains(pkg) || (hiddenLib.contains(pkg) && !showHiddenLibraryApps)) {
                // AppLibraryActivity's adapter: every app, dock-first, except hidden_apps' (config) and
                // library_hidden.txt's (the "Hide app" tile action, Launcher settings' show-hidden choice)
                // deliberate exclusions. Unlike Home it ignores the on-device added/removed history
                // (LibraryApps) — a full catalogue, like the dialog this screen replaced used to query for itself.
                continue;
            }
            Cards.Card card = cardByPkg.get(pkg);
            if (card != null && !card.component.equals(component)) continue;   // the card stands in for this tile
            // Same cleaner as the card prompt: an app label with a bidi override or line separator must not
            // redraw the tile name as something else (#117). customNames (a library rename) wins over
            // appLabels (config.xml/theme) — the owner's own explicit choice over a shipped default.
            String label = card != null ? card.label : Cards.cleanLabel(
                    customNames.containsKey(pkg) ? customNames.get(pkg)
                            : appLabels.containsKey(pkg) ? appLabels.get(pkg) : String.valueOf(ri.loadLabel(pm)), pkg);
            if (!seen.add(pkg + '\n' + label)) continue;
            Drawable banner = null;
            // uniform_tiles draws the icon, so an app's banner is never loaded. A module card keeps its banner.
            if (!(uniformTiles && card == null)) {
                banner = bundledBanner(appBanners.get(pkg));
                if (banner == null) banner = loadBanner(pm, ri);
            }
            // The icon is only drawn (and sampled for the glow) when there is no banner, so a tile with a
            // banner skips loading and decoding it.
            Drawable icon = banner == null ? ri.loadIcon(pm) : null;
            if (card != null) {
                fresh.add(new Entry(Entry.CARD, label, component, banner, icon, card.caption, card.accent));
            } else {
                fresh.add(new Entry(label, component, banner, icon));
            }
        }
        Collections.sort(fresh, (a, b) -> {
            // The owner's own order first; tiles it does not know (new installs) follow by the rule below.
            int saved = Order.compare(savedRanks, a.component.flattenToShortString(), b.component.flattenToShortString());
            if (saved != 0) return saved;
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            // Cards come right after the configured dock apps, before the alphabetical grid.
            boolean ca = a.kind == Entry.CARD, cb = b.kind == Entry.CARD;
            if (ca != cb) return ca ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
        // Local patch: the dock is exactly dockSize places now, whatever "Apps per row" is set to (or fewer only
        // if the device truly has fewer apps than that) — truncate the fully sorted catalogue instead of
        // filtering it earlier, so dock_apps, the owner's own saved order (a drag-reorder in the library,
        // AppLibraryActivity) and the alphabetical fallback all compete for the same dockSize slots. Truncating
        // before the Default-home tile below, not after, keeps that tile outside the count it would otherwise
        // be cut off by.
        candidateCount = fresh.size();
        if (lockToDock && fresh.size() > dockSize) fresh.subList(dockSize, fresh.size()).clear();
        // Retain the optional Default-home tile until dependent tile-ordering work is merged.
        if (showHomeSettings
                && pm.resolveActivity(new Intent(Settings.ACTION_HOME_SETTINGS), 0) != null) {
            fresh.add(new Entry(Entry.HOME_SETTINGS, ctx.getString(R.string.home_settings),
                    new ComponentName(ctx, HomeSettingsActivity.class), null,
                    ctx.getDrawable(R.drawable.ic_home_settings), null, 0));
        }
        if (lockToDock) {
            docked = Math.min(fresh.size(), dockSize);
            emptySlots = 0;
        } else {
            docked = 0;
            while (docked < fresh.size() && isDocked(fresh.get(docked))) docked++;
            emptySlots = dockRow && !reordering ? DockSlots.empty(docked, dockSize, fresh.size(), fillDock) : 0;
            for (int i = 0; i < emptySlots; i++) {
                fresh.add(docked + i, new Entry(Entry.EMPTY, "", new ComponentName(ctx, "empty-dock-slot-" + i),
                        null, null, null, 0));
            }
        }
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
    }

    /** How many apps there were before the dock took its dockSize (see reload). */
    private int candidateCount;

    /** Home only: there are more apps than the dock holds, so the apps grid below it has something to show. */
    boolean hasMoreAppsThanDock() {
        return lockToDock && candidateCount > dockSize;
    }

    /** A banner_* drawable bundled with the launcher, or null for none or a name that is not one. */
    private Drawable bundledBanner(String name) {
        if (name == null || !name.startsWith("banner_")) return null;
        int id = ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
        return id != 0 ? ctx.getDrawable(id) : null;
    }

    private Drawable loadBanner(PackageManager pm, ResolveInfo ri) {
        int id = ri.activityInfo.banner;
        if (id == 0) id = ri.activityInfo.applicationInfo.banner;
        Drawable banner = null;
        if (id != 0) {
            try {
                Resources resources = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                // Keep at least xhdpi detail rather than downsampling artwork before the focus zoom.
                int density = Math.max(DisplayMetrics.DENSITY_XHIGH, ctx.getResources().getDisplayMetrics().densityDpi);
                banner = resources.getDrawableForDensity(id, density, null);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            }
        }
        if (banner == null) banner = ri.activityInfo.loadBanner(pm);
        // A square "banner" counts as none: the tile shows the app's icon and name instead of a blur.
        return banner != null && isBannerShaped(banner.getIntrinsicWidth(), banner.getIntrinsicHeight()) ? banner : null;
    }

    /**
     * Some apps declare a small square image as their banner (Rockchip's Media Center ships a 64 px one). Cropped
     * over a 16:9 tile it is a blur. A drawable with no intrinsic size (a colour, a shape) still fills the tile.
     */
    static boolean isBannerShaped(int width, int height) {
        return width <= 0 || height <= 0 || width >= height * 1.4f;
    }

    /** Lets go of every tile and its artwork (Home is in the background and memory is short). */
    void clear() {
        items.clear();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == 1) {
            View row = LayoutInflater.from(ctx).inflate(R.layout.row_app_library, parent, false);
            View button = row.findViewById(R.id.btnLibrary);
            button.setOnClickListener(v -> listener.onLibraryRequested());
            button.setOnFocusChangeListener((v, focused) -> { if (focused) listener.onLibraryFocused(); });
            button.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP) listener.onLibraryUp();
                return key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                        || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
            });
            return new RecyclerView.ViewHolder(row) { };
        }
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        float density = parent.getResources().getDisplayMetrics().density;
        int width = parent.getWidth() > 0 ? parent.getWidth() : parent.getMeasuredWidth();
        int inner = width - parent.getPaddingLeft() - parent.getPaddingRight();
        int w = tileWidthPx(inner, dockSize, density), h = tileHeightPx(w);
        View card = v.findViewById(R.id.card);
        ViewGroup.LayoutParams cardLp = card.getLayoutParams();
        cardLp.width = w;
        cardLp.height = h;
        card.setLayoutParams(cardLp);
        View name = v.findViewById(R.id.name);
        ViewGroup.MarginLayoutParams nameLp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
        nameLp.width = w;
        nameLp.topMargin = h + Math.round((dockRow ? 7 : 4) * density);
        if (!dockRow) {
            // Compact spacing (COMPACT_ITEM_EXTRA_DP): 4dp item padding and a 20dp name instead of 8dp and 22dp.
            nameLp.height = Math.round(20 * density);
            int pad = Math.round(4 * density);
            v.setPadding(v.getPaddingLeft(), pad, v.getPaddingRight(), pad);
        }
        name.setLayoutParams(nameLp);
        // A sideways row would stretch a match_parent item across the whole screen: give it a grid column's width.
        if (singleRow) v.getLayoutParams().width = inner / dockSize;
        if (uniformTiles) {
            // tvOS-style tile: a large centred mark, no caption inside the card.
            View icon = v.findViewById(R.id.icon);
            FrameLayout.LayoutParams iconLp = (FrameLayout.LayoutParams) icon.getLayoutParams();
            iconLp.width = iconLp.height = Math.round(h * ICON_SHARE);
            iconLp.bottomMargin = 0;
            icon.setLayoutParams(iconLp);
        }
        return new Holder(v, listener, animations, reducedMotion, restNameAlpha, focusOvershoot, plainRim, strokeOnlyRim);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (isLibraryAt(position)) return;
        Holder h = (Holder) holder;
        Entry e = items.get(position);
        if (e.kind == Entry.EMPTY) {
            // A free dock slot keeps its place in the row and the band, but shows nothing and takes no focus.
            h.card.setVisibility(View.INVISIBLE);
            h.card.setFocusable(false);
            h.name.setVisibility(View.INVISIBLE);
            h.banner.setImageDrawable(null);
            h.icon.setImageDrawable(null);
            h.card.setOnClickListener(null);
            h.card.setOnLongClickListener(null);
            return;
        }
        h.card.setVisibility(View.VISIBLE);
        h.card.setFocusable(true);
        h.card.setElevation(tileShadow ? 12f * ctx.getResources().getDisplayMetrics().density : 0f);
        h.name.setText(e.name);
        // The dock has no labels, unless always_show_names asks for them (DockDecoration then makes room in the band).
        // In a single row every tile is the dock's kind: the big title above names it.
        boolean dockKind = dockRow && (singleRow || position < dockSize);
        h.name.setVisibility(dockKind && restNameAlpha == 0f ? View.INVISIBLE : View.VISIBLE);
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.name.setAlpha(focused ? 1f : restNameAlpha);
        h.card.setScaleX(focused ? focusScale() : REST_SCALE);
        h.card.setScaleY(focused ? focusScale() : REST_SCALE);
        h.card.setTranslationY(0f);
        h.card.setTranslationZ(0f);
        h.banner.setScaleX(1f);
        h.banner.setScaleY(1f);
        // A fully opaque banner covers the whole card (centerCrop), so the card's own fill under it is drawn for
        // nothing on every frame; keep it under banners with transparent parts and under icon tiles.
        h.card.setBackground(e.banner != null && e.banner.getOpacity() == PixelFormat.OPAQUE ? null : h.cardBackground);
        if (uniformTiles && e.banner == null) {
            if (e.backdrop == null) e.backdrop = backdrop(e.icon, ambientColorAt(position));
            h.card.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, e.backdrop));
        }
        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(uniformTiles ? View.GONE : View.VISIBLE);
        }
        h.card.setOnClickListener(v -> launch(e, v));
        h.card.setOnLongClickListener(v -> {
            if (tileMenu) {
                int pos = h.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) listener.onTileLongPress(pos, v);
                return true;
            }
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
            } catch (ActivityNotFoundException ignored) {
            }
            return true;
        });
    }

    /** Opens the tile's activity by explicit component, with no extras: a card can name nothing else. */
    private void launch(Entry e, View from) {
        if (e.kind == Entry.HOME_SETTINGS) {
            ctx.startActivity(new Intent(ctx, HomeSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        // TV apps launch with the same leanback intent as from the stock launcher or the remote, so an app
        // already running resumes instead of restarting; only phone apps added from the library use plain MAIN.
        if (leanbackPackages.contains(e.pkg)) i.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        String action = launchActions.get(e.pkg);
        if (action != null) {
            // NEW_TASK only: with RESET_TASK_IF_NEEDED, Android just brings a running task of the app to the front
            // ("Activity not started"), so the screen never opens while the app is up, e.g. MagentaTV's boot-time task.
            Intent byAction = new Intent(action).setPackage(e.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (ctx.getPackageManager().resolveActivity(byAction, 0) != null) i = byAction;
        }
        try {
            // tvOS-style: the app's window grows out of the tile (the system plays it; needs no permission).
            android.os.Bundle options = null;
            if (animations && !reducedMotion && from != null && from.getWidth() > 0) {
                options = android.app.ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
                returnPkg = e.pkg;
            }
            ctx.startActivity(i, options);
            if (cinematic) LastOpened.record(ctx, e.pkg);
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    /** The package whose tile was launched last (with the grow-out animation), for {@link #playReturn}. */
    private String returnPkg;

    /** Back on Home after an app: that tile's picture settles in, a short ease from a little smaller. The system
     *  cannot shrink another app's window back into a tile, so this is the return half of the effect. */
    void playReturn(RecyclerView list) {
        String pkg = returnPkg;
        returnPkg = null;
        if (pkg == null || !animations || reducedMotion) return;
        for (int p = 0; p < items.size(); p++) {
            if (!items.get(p).pkg.equals(pkg)) continue;
            RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(p);
            if (!(vh instanceof Holder)) return;
            Holder h = (Holder) vh;
            for (View part : new View[]{h.icon, h.banner, h.label}) {
                if (part.getVisibility() != View.VISIBLE) continue;
                part.setScaleX(0.84f);
                part.setScaleY(0.84f);
                part.setAlpha(0.55f);
                part.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(340)
                        .setInterpolator(new DecelerateInterpolator(1.6f)).start();
            }
            return;
        }
    }

    @Override
    public int getItemCount() {
        return items.size() + (showLibrary ? 1 : 0);
    }

    int appCount() { return items.size(); }

    boolean isLibraryAt(int position) { return showLibrary && position == items.size(); }

    @Override public int getItemViewType(int position) { return isLibraryAt(position) ? 1 : 0; }

    boolean containsPackage(String pkg) {
        for (Entry entry : items) if (entry.pkg.equals(pkg)) return true;
        return false;
    }

    @Override
    public long getItemId(int position) {
        return isLibraryAt(position) ? Long.MIN_VALUE : items.get(position).component.flattenToShortString().hashCode();
    }

    /** Uses this saved order and dock size from the next reload() (Order.ranks, Order.docked). */
    void setSavedOrder(Map<String, Integer> ranks, int docked) {
        savedRanks = ranks;
        savedDocked = docked;
    }

    /**
     * A tile in the dock: one of the first savedDocked tiles of the saved order when one was recorded, so an app
     * the owner moved into the dock stays there (#499); else a dock_apps entry.
     */
    private boolean isDocked(Entry e) {
        if (savedDocked < 0) return favourites.contains(e.pkg);
        Integer rank = savedRanks.get(e.component.flattenToShortString());
        return rank != null && rank < savedDocked;
    }

    /** Reorder mode on or off; takes effect at the next reload() (empty dock slots are left out while on). */
    void setReordering(boolean on) {
        reordering = on;
    }

    boolean hasEmptySlots() {
        return emptySlots > 0;
    }

    /** Tiles that can be moved: everything but the "Default home" tile, which always stays last. */
    int movableCount() {
        int n = items.size();
        return n > 0 && items.get(n - 1).kind == Entry.HOME_SETTINGS ? n - 1 : n;
    }

    /** Keys of the movable tiles in their current order, for Order (empty dock slots are not tiles). */
    List<String> movableKeys() {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < movableCount(); i++) {
            Entry e = items.get(i);
            if (e.kind != Entry.EMPTY) keys.add(e.component.flattenToShortString());
        }
        return keys;
    }

    /** Moves one tile; the others shift by one. Stable ids keep the moved tile's view (and its focus). */
    void move(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= movableCount() || to >= movableCount()) return;
        items.add(to, items.remove(from));
        notifyItemMoved(from, to);
        // The dock row hides names by position: rebind the tiles whose side of the dock edge changed.
        if (from < dockSize || to < dockSize) {
            int lo = Math.min(from, to), hi = Math.max(from, to);
            notifyItemRangeChanged(lo, hi - lo + 1);
        }
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).component.flattenToShortString() : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).component.flattenToShortString().equals(key)) return i;
        }
        return -1;
    }

    /** Dock slots that hold an app: the whole dock unless some slots are empty. */
    int dockFilled() {
        return emptySlots > 0 ? docked : dockSize;
    }

    /** A position focus can land on: an empty dock slot gives the last dock app. */
    int focusablePosition(int position) {
        return DockSlots.focusable(position, docked, emptySlots);
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    String packageAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).pkg : null;
    }

    /** The tile's artwork, its banner or else its icon, for the cinematic shelf's backdrop (HeroArt). */
    Drawable artworkAt(int position) {
        if (position < 0 || position >= items.size()) return null;
        Entry e = items.get(position);
        return e.banner != null ? e.banner : e.icon;
    }

    /** A focused tile's own scale: with animations off there is no zoom, so this is REST_SCALE too — every
     *  glow-sizing call site (MainActivity, AppLibraryActivity) reads this one value, not FOCUS_SCALE directly. */
    float focusScale() {
        return !animations ? REST_SCALE : FOCUS_SCALE;
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry; a card's accent when it set one. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) {
            e.ambient = uniformTiles && e.banner == null ? dominantColor(e.icon) : vividColor(e.banner != null ? e.banner : e.icon);
        }
        return e.ambient;
    }

    /** Share of the tile height the icon takes in uniform_tiles mode (onCreateViewHolder). */
    private static final float ICON_SHARE = 0.62f;

    /**
     * The colour covering most of an icon, for a card that should read as that app: a small saturated badge
     * (a red "new" corner on a blue icon) must not win, as it can with {@link #vividColor}. Grey if none.
     */
    private static int dominantColor(Drawable live) {
        if (live == null) return 0xFF6E6E73;
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 24;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, n, n);
        d.draw(new Canvas(bmp));
        d.setBounds(saved);
        float[] weight = new float[12];
        float[][] sum = new float[12][3];
        float[] hsv = new float[3];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                if (hsv[1] < 0.3f || hsv[2] < 0.25f) continue;   // white, grey and black carry no hue
                int bin = (int) (hsv[0] / 30f) % 12;
                float w = hsv[1] * hsv[2];
                weight[bin] += w;
                sum[bin][0] += Color.red(p) * w;
                sum[bin][1] += Color.green(p) * w;
                sum[bin][2] += Color.blue(p) * w;
            }
        }
        bmp.recycle();
        int best = 0;
        for (int i = 1; i < 12; i++) if (weight[i] > weight[best]) best = i;
        if (weight[best] < n * n * 0.03f) return 0xFF6E6E73;   // a monochrome icon, like a grey gear
        float w = weight[best];
        return Color.rgb((int) (sum[best][0] / w), (int) (sum[best][1] / w), (int) (sum[best][2] / w));
    }

    /**
     * The uniform tile's fill. Where the icon sits on a flat colour of its own (a white or coloured rounded
     * square), the card takes that colour, so the mark reads as filling the tile as on tvOS. Otherwise a
     * gradient of the icon's main colour, deepened so the mark stands out. The card clips the corners.
     */
    private static int[] backdrop(Drawable live, int fallback) {
        int[] plate = iconPlate(live);
        if (plate != null) return plate;
        float[] hsv = new float[3];
        Color.colorToHSV(fallback, hsv);
        // Yellow darkened turns olive: lean it to amber instead.
        if (hsv[0] > 42f && hsv[0] < 70f && hsv[1] > 0.3f) hsv[0] = 36f;
        hsv[2] = Math.min(hsv[2], 0.62f);
        int top = Color.HSVToColor(hsv);
        hsv[2] *= 0.6f;
        return new int[]{top, Color.HSVToColor(hsv)};
    }

    /**
     * The plate an icon's artwork sits on (a flat or vertically graded rounded square), carried out to the
     * card's top and bottom edges so the mark reads as filling the tile; null when the icon has no
     * opaque plate (a bare mark on transparency).
     */
    private static int[] iconPlate(Drawable live) {
        if (live == null) return null;
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 48;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, n, n);
        d.draw(new Canvas(bmp));
        d.setBounds(saved);
        // Just inside a rounded square's corners; one side may go a step further in, past a corner badge.
        // An icon padded on both sides is a small plate in the middle, which no fill would meet.
        int a = plateColor(bmp, 7, n), b = plateColor(bmp, n - 8, n);
        int topRow = 7, bottomRow = n - 8;
        if (a == 0 && b != 0) a = plateColor(bmp, topRow = 10, n);
        else if (b == 0 && a != 0) b = plateColor(bmp, bottomRow = n - 11, n);
        bmp.recycle();
        if (a == 0 || b == 0) return null;
        // The sampled rows sit at these heights of the tile; extend the line through them to its edges. A row
        // taken further in sits on artwork-side plate, so its slope is less certain: cap how far it reaches.
        float margin = (1f - ICON_SHARE) / 2f;
        float yA = margin + ICON_SHARE * topRow / n, yB = margin + ICON_SHARE * bottomRow / n;
        float t0 = -yA / (yB - yA), t1 = (1f - yA) / (yB - yA);
        if (topRow != 7 || bottomRow != n - 8) {
            t0 = Math.max(-0.35f, t0);
            t1 = Math.min(1.35f, t1);
        }
        return new int[]{mix(a, b, t0), mix(a, b, t1)};
    }

    /** {@link #plateColor(int[])} of row y, between the same insets as the row's distance from the edge. */
    private static int plateColor(Bitmap bmp, int y, int n) {
        int inset = Math.min(y, n - 1 - y);
        int[] row = new int[n - 2 * inset];
        bmp.getPixels(row, 0, row.length, inset, y, row.length, 1);
        return plateColor(row);
    }

    /** The colour most of a row shares, ignoring artwork that crosses it; 0 if the row is not mostly plate. */
    private static int plateColor(int[] row) {
        int[] r = new int[row.length], g = new int[row.length], b = new int[row.length];
        int opaque = 0;
        for (int p : row) {
            if (Color.alpha(p) < 240) continue;
            r[opaque] = Color.red(p);
            g[opaque] = Color.green(p);
            b[opaque] = Color.blue(p);
            opaque++;
        }
        if (opaque < row.length * 0.9f) return 0;
        int mr = median(r, opaque), mg = median(g, opaque), mb = median(b, opaque);
        long sr = 0, sg = 0, sb = 0;
        int close = 0;
        for (int i = 0; i < opaque; i++) {
            if (Math.abs(r[i] - mr) + Math.abs(g[i] - mg) + Math.abs(b[i] - mb) >= 48) continue;
            sr += r[i];
            sg += g[i];
            sb += b[i];
            close++;
        }
        if (close < row.length * 0.7f) return 0;
        return Color.rgb((int) (sr / close), (int) (sg / close), (int) (sb / close));
    }

    private static int median(int[] values, int count) {
        int[] sorted = Arrays.copyOf(values, count);
        Arrays.sort(sorted);
        return sorted[count / 2];
    }

    /** a + (b - a) * t per channel, clamped; t outside 0..1 extrapolates. */
    private static int mix(int a, int b, float t) {
        return Color.rgb(clamp(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                clamp(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                clamp(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        int[] px = new int[n * n];
        bmp.getPixels(px, 0, n, 0, 0, n, n);
        bmp.recycle();
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int p : px) {
            int pr = (p >> 16) & 0xFF, pg = (p >> 8) & 0xFF, pb = p & 0xFF;
            r += pr;
            g += pg;
            b += pb;
            int max = Math.max(pr, Math.max(pg, pb));
            int min = Math.min(pr, Math.min(pg, pb));
            float sat = max == 0 ? 0f : (max - min) / (float) max;
            float val = max / 255f;
            float score = sat * Math.min(1f, val * 1.4f);   // saturated and not too dark
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    /** Local patch: the corner radius of a tile at its layout size, in px. A focused tile (zoomed x FOCUS_SCALE)
     *  shows 46 px corners; an unfocused one (x REST_SCALE) shows 46 * REST_SCALE / FOCUS_SCALE, i.e. proportionally less. */
    /** Tile corner radius in px (Config.tileRadius, set whenever Config loads). */
    static volatile int tileRadiusPx = Config.DEFAULT_TILE_RADIUS;

    static float cornerRadius(int tileWidthPx, float density) {
        return tileRadiusPx;
    }

    /** The focused tile's edge light: an inner glow (a wide, blurred white stroke on the edge, clipped to the tile, so
     *  the light fades inwards like Photoshop's Inner Glow; Android 9+) under a 1dp rim. Both are bright at the top
     *  left and fade towards the bottom right, along a 60 degree line (the unscaled tile; the focus zoom grows them). */
    /** Comparison switch: the inner glow as an AGSL shader (Android 13+, distance to the rounded edge) instead of the
     *  blurred stroke. Older versions always use the blurred stroke. */
    static final boolean GLOW_SHADER = true;

    /** The glow as AGSL: brightness from the distance to the tile's rounded edge, falling off over {@code depth}, scaled
     *  along the 60 degree line by four stops (alpha at 0, 35, 75 and 100%). */
    private static final String GLOW_AGSL = ""
            + "uniform float2 center;\n"
            + "uniform float2 halfSize;\n"
            + "uniform float radius;\n"
            + "uniform float depth;\n"
            + "uniform float falloff;\n"
            + "uniform float2 dir;\n"
            + "uniform float reach;\n"
            + "uniform float4 stops;\n"
            + "half4 main(float2 fragCoord) {\n"
            + "    float2 p = fragCoord - center;\n"
            + "    float2 q = abs(p) - halfSize + radius;\n"
            + "    float d = -(length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius);\n"   // px inside the edge
            + "    if (d < 0.0) return half4(0.0);\n"
            + "    float t = 1.0 - clamp(d / depth, 0.0, 1.0);\n"
            + "    float glow = pow(t, falloff);\n"
            + "    float g = clamp(dot(p, dir) / reach * 0.5 + 0.5, 0.0, 1.0);\n"
            + "    float a = g < 0.35 ? mix(stops.x, stops.y, g / 0.35)\n"
            + "            : g < 0.75 ? mix(stops.y, stops.z, (g - 0.35) / 0.4)\n"
            + "            : mix(stops.z, stops.w, (g - 0.75) / 0.25);\n"
            + "    float v = glow * a;\n"
            + "    return half4(v, v, v, v);\n"   // premultiplied white
            + "}\n";

    /** Monochrome's outline: one 1dp stroke in GlassRim.SOLID (white, 15%), nothing else. */
    private static final class PlainRimDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect = new android.graphics.RectF();
        private final float density;

        PlainRimDrawable(float density) {
            this.density = density;
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(density);
            paint.setColor(GlassRim.SOLID);
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            if (b.isEmpty()) return;
            float inset = density / 2f;
            rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
            float radius = Math.max(0f, cornerRadius(b.width(), density) - inset);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter filter) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private static final class RimDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint glow = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect = new android.graphics.RectF();
        private final android.graphics.RectF edge = new android.graphics.RectF();
        private final android.graphics.Path clip = new android.graphics.Path();
        private final float stroke;
        private final boolean hasGlow = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;
        private final boolean useShader = GLOW_SHADER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
        private final float density;
        private android.graphics.RuntimeShader glowShader;

        private final boolean strokeOnly;

        RimDrawable(float density, boolean strokeOnly) {
            this.density = density;
            this.strokeOnly = strokeOnly;
            stroke = 1f * density;
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            glow.setStyle(android.graphics.Paint.Style.STROKE);
            glow.setStrokeWidth(2f * density);
            if (hasGlow) glow.setMaskFilter(new android.graphics.BlurMaskFilter(5f * density, android.graphics.BlurMaskFilter.Blur.NORMAL));
        }

        private android.graphics.LinearGradient gradient(android.graphics.Rect b, int[] colors) {
            double angle = Math.toRadians(60);   // measured from the horizontal, downwards
            float dx = (float) Math.cos(angle), dy = (float) Math.sin(angle);
            float cx = b.exactCenterX(), cy = b.exactCenterY();
            float reach = (b.width() * Math.abs(dx) + b.height() * Math.abs(dy)) / 2f;   // the line spans the whole tile
            return new android.graphics.LinearGradient(cx - dx * reach, cy - dy * reach, cx + dx * reach, cy + dy * reach,
                    colors, new float[]{0f, 0.35f, 0.75f, 1f}, android.graphics.Shader.TileMode.CLAMP);
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            if (b.isEmpty()) return;
            float radius = cornerRadius(b.width(), 1f);
            if (strokeOnly) {
                // the gradient stroke below is all there is: no glow, no shader
            } else if (useShader) {
                drawShaderGlow(canvas, b, radius);
            } else if (hasGlow) {
                edge.set(b.left, b.top, b.right, b.bottom);
                clip.rewind();
                clip.addRoundRect(edge, radius, radius, android.graphics.Path.Direction.CW);
                glow.setShader(gradient(b, new int[]{0x66FFFFFF, 0x1AFFFFFF, 0x00FFFFFF, 0x33FFFFFF}));
                int saved = canvas.save();
                canvas.clipPath(clip);
                canvas.drawRoundRect(edge, radius, radius, glow);
                canvas.restoreToCount(saved);
            }
            float half = stroke / 2f;
            rect.set(b.left + half, b.top + half, b.right - half, b.bottom - half);
            paint.setShader(gradient(b, new int[]{0x7AFFFFFF, 0x0DFFFFFF, 0x00FFFFFF, 0x33FFFFFF}));
            float r = Math.max(0f, radius - half);
            canvas.drawRoundRect(rect, r, r, paint);
        }

        @android.annotation.TargetApi(Build.VERSION_CODES.TIRAMISU)
        private void drawShaderGlow(android.graphics.Canvas canvas, android.graphics.Rect b, float radius) {
            if (glowShader == null) glowShader = new android.graphics.RuntimeShader(GLOW_AGSL);
            double angle = Math.toRadians(60);
            float dx = (float) Math.cos(angle), dy = (float) Math.sin(angle);
            glowShader.setFloatUniform("center", b.exactCenterX(), b.exactCenterY());
            glowShader.setFloatUniform("halfSize", b.width() / 2f, b.height() / 2f);
            glowShader.setFloatUniform("radius", radius);
            glowShader.setFloatUniform("depth", 6f * density);      // how far the light reaches in
            glowShader.setFloatUniform("falloff", 2f);              // 1 = linear, higher = tighter to the edge
            glowShader.setFloatUniform("dir", dx, dy);
            glowShader.setFloatUniform("reach", b.width() * Math.abs(dx) + b.height() * Math.abs(dy));
            glowShader.setFloatUniform("stops", 0.40f, 0.10f, 0f, 0.20f);
            shaderPaint.setShader(glowShader);
            canvas.drawRoundRect(b.left, b.top, b.right, b.bottom, radius, radius, shaderPaint);
        }

        private final android.graphics.Paint shaderPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); glow.setAlpha(alpha); shaderPaint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); glow.setColorFilter(filter); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        /** item_app.xml's tile_bg: the dark card behind an icon tile, or behind a banner that has transparent parts. */
        final Drawable cardBackground;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;
        final boolean animations;
        final boolean reducedMotion;
        /** Alpha of the name while the tile is not focused (always_show_names). */
        final float restNameAlpha;
        final boolean overshoot;

        Holder(View v, TileListener listener, boolean animations,
               boolean reducedMotion, float restNameAlpha, boolean overshoot, boolean plainRim, boolean strokeOnlyRim) {
            super(v);
            card = v.findViewById(R.id.card);
            cardBackground = card.getBackground();
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);
            this.animations = animations;
            this.reducedMotion = reducedMotion;
            this.restNameAlpha = restNameAlpha;
            this.overshoot = overshoot;

            final float density = v.getResources().getDisplayMetrics().density;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    // 16dp at the design width, scaled down with the tile so small tiles keep the same shape.
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius(view.getWidth(), density));
                }
            });
            card.setClipToOutline(true);
            // tvOS-style rim on the focused tile: a thin stroke whose light fades down the tile.
            android.graphics.drawable.StateListDrawable rim = new android.graphics.drawable.StateListDrawable();
            rim.addState(new int[]{android.R.attr.state_focused}, plainRim ? new PlainRimDrawable(density) : new RimDrawable(density, strokeOnlyRim));
            rim.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(0));
            if (Config.tileOutline(v.getContext())) card.setForeground(rim);   // Launcher settings, "App outline"
            // Local patch: AppLibraryActivity's drop shadow (tileShadow, see onBindViewHolder) read as too
            // strong/hard-edged against its light wallpaper+wash background at the system's default shadow
            // colour — a lighter, more diffuse shadow colour instead, elevation unchanged. No effect on Home
            // (elevation stays 0 there), and harmless pre-API 28 (just keeps the system default colour).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                card.setOutlineAmbientShadowColor(0x62000000);
                card.setOutlineSpotShadowColor(0x7E000000);
            }

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                view.animate().cancel();
                banner.animate().cancel();
                name.animate().cancel();
                float scale = !animations ? REST_SCALE : FOCUS_SCALE;
                float s = focused ? scale : REST_SCALE;
                if (!animations) {
                    view.setScaleX(s);
                    view.setScaleY(s);
                    view.setTranslationY(0f);
                    view.setTranslationZ(0f);
                    banner.setScaleX(1f);
                    banner.setScaleY(1f);
                    name.setAlpha(focused ? 1f : restNameAlpha);
                    name.setTranslationY(0f);
                } else if (reducedMotion) {
                    // A short zoom with no hardware layer: building a layer for so short an animation costs more
                    // frames than it saves. The name just appears instead of fading.
                    view.animate().scaleX(s).scaleY(s)
                            .translationY(0f)
                            .translationZ(0f)
                            .setDuration(focused ? Motion.REDUCED_MS : 60)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                    name.setAlpha(focused ? 1f : restNameAlpha);
                    // Same place as the full motion's slide ends: the layout's -6dp is only its starting point, and
                    // left there the name overlaps the zoomed tile.
                    // Only on focus: snapping it back to -6dp while it fades out would jump it up over the tile.
                    if (focused) name.setTranslationY(density * 4f);
                } else {
                    view.animate().scaleX(s).scaleY(s)
                            .translationY(0f)
                            .translationZ(0f)
                            .setDuration(focused ? 260 : 180)
                            .setInterpolator(focused && !overshoot ? new DecelerateInterpolator(1.5f)
                                    : new OvershootInterpolator(focused ? 1.6f : 0f))
                            .withLayer().start();
                    name.animate().alpha(focused ? 1f : restNameAlpha)
                            .translationY(focused ? density * 4f : -density * 6f)
                            .setDuration(220).start();
                }
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
