package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    private SharedPreferences choices;
    private TextView columnsChoice, dockOpacityChoice, dockBlurChoice;
    /** 0 to 100 in steps of 10. */
    private static final int[] DOCK_OPACITIES = {0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
    /** The Gaussian blur's sigma (dp), 0 to 20 in steps of 1. */
    private int dockBlur;
    private static final String[] BACKDROPS = {Config.BACKDROP_COLOR, Config.BACKDROP_BLUR, Config.BACKDROP_LIQUID};
    private TextView dockBackdropChoice;
    private final TextView[] backdropChoices = new TextView[BACKDROPS.length];
    private TextView wallpaperChoice;
    private TextView animationsChoice;
    private TextView followThemeChoice;
    private TextView openOnStartChoice;
    /** Local patch, not upstream: whether AppLibraryActivity's catalogue shows apps hidden with its own
     *  "Hide app" tile action (library_hidden.txt) — off by default, so a hide stays hidden until asked for. */
    private TextView libraryShowHiddenChoice;
    /** Slideshow parameters; stored only, nothing reads them yet. */
    private TextView slideshowChoice, slideshowIntervalChoice, slideshowYes, slideshowNo, slideshowCollectionChoice, screensaverChoice;
    /** What the two tiles of the shared two-choice sub-page mean: 0 Slideshow Yes/No, 1 Screensaver type. */
    private int twoChoiceMode;
    /** The wallpaper sub-page is picking the slideshow collection (many checks) rather than one wallpaper. */
    private boolean wallpaperMulti;
    private static final String SLIDESHOW_SET_KEY = "slideshow_set";
    private boolean slideshow;
    private int slideshowMinutes;
    private static final int[] SLIDESHOW_MINUTES = {1, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60};
    private int columns;
    private int dockOpacity;
    private String dockBackdrop;
    private boolean animations;
    private boolean libraryShowHidden;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        columns = config.columns;
        dockOpacity = config.dockOpacity;
        dockBlur = Config.dockBlur(this);
        dockBackdrop = config.dockBackdrop;
        animations = config.animations;
        // Local patch, not upstream: AppLibraryActivity-only, read fresh there every time it opens — no
        // Config.java field, no changed()/recreate() needed when it flips.
        libraryShowHidden = choices.getBoolean("library_show_hidden", false);

        columnsChoice = findViewById(R.id.columnsChoice);
        columnsChoice.setOnClickListener(v -> openSub(columnsChoice, R.string.columns_label, R.string.columns_desc, MIN_COLUMNS,
                MAX_COLUMNS - MIN_COLUMNS + 1, columns, i -> {
                    columns = MIN_COLUMNS + i;
                    choices.edit().putInt("columns", columns).apply();
                    changed();
                }, i -> String.valueOf(MIN_COLUMNS + i)));
        dockOpacityChoice = findViewById(R.id.dockOpacityChoice);
        dockOpacityChoice.setOnClickListener(v -> openSub(dockOpacityChoice, R.string.dock_opacity_label, R.string.dock_opacity_desc, 0,
                DOCK_OPACITIES.length, dockOpacity / 10, i -> {
                    dockOpacity = DOCK_OPACITIES[i];
                    choices.edit().putInt(Config.DOCK_OPACITY_KEY, dockOpacity).apply();
                    changed();
                }, i -> DOCK_OPACITIES[i] + "%"));
        dockBlurChoice = findViewById(R.id.dockBlurChoice);
        dockBlurChoice.setOnClickListener(v -> openSub(dockBlurChoice, R.string.dock_blur_label, R.string.dock_blur_desc, 0,
                Config.MAX_DOCK_BLUR + 1, dockBlur, i -> {
                    dockBlur = i;
                    choices.edit().putInt(Config.DOCK_BLUR_KEY, dockBlur).apply();
                    changed();
                }, i -> String.valueOf(i)));
        findViewById(R.id.subBack).setOnClickListener(v -> closeSub());
        githubWallpapersChoice = findViewById(R.id.githubWallpapersChoice);
        githubWallpapersChoice.setOnClickListener(v -> openDownload(githubWallpapersChoice));
        int[] backdropIds = {R.id.backdropColor, R.id.backdropBlur, R.id.backdropLiquid};
        for (int i = 0; i < backdropIds.length; i++) {
            final String value = BACKDROPS[i];
            backdropChoices[i] = findViewById(backdropIds[i]);
            backdropChoices[i].setOnClickListener(v -> {
                if (value.equals(dockBackdrop)) return;
                dockBackdrop = value;
                choices.edit().putString(Config.DOCK_BACKDROP_KEY, value).apply();
                changed();
                refresh();
            });
        }
        dockBackdropChoice = findViewById(R.id.dockBackdropChoice);
        dockBackdropChoice.setOnClickListener(v -> openChoices(dockBackdropChoice, R.string.dock_backdrop_label));
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> openWallpapers(false));
        findViewById(R.id.wallpaperResetChoice).setOnClickListener(v -> deleteGithubWallpapers());
        slideshow = choices.getBoolean("slideshow", false);
        slideshowMinutes = choices.getInt("slideshow_minutes", 1);
        slideshowChoice = findViewById(R.id.slideshowChoice);
        slideshowChoice.setOnClickListener(v -> openSlideshowChoices());
        slideshowYes = findViewById(R.id.slideshowYes);
        slideshowNo = findViewById(R.id.slideshowNo);
        slideshowYes.setOnClickListener(v -> { if (twoChoiceMode == 1) setScreensaver(LiquidClockLayout.KIND_AERIAL); else setSlideshow(true); });
        slideshowNo.setOnClickListener(v -> { if (twoChoiceMode == 1) setScreensaver(LiquidClockLayout.KIND_CLOCK); else setSlideshow(false); });
        screensaverChoice = findViewById(R.id.screensaverChoice);
        screensaverChoice.setOnClickListener(v -> openScreensaverChoices());
        slideshowCollectionChoice = findViewById(R.id.slideshowCollectionChoice);
        slideshowCollectionChoice.setOnClickListener(v -> openWallpapers(true));
        slideshowIntervalChoice = findViewById(R.id.slideshowIntervalChoice);
        slideshowIntervalChoice.setOnClickListener(v -> openSub(slideshowIntervalChoice,
                R.string.slideshow_interval, R.string.slideshow_interval_desc, 0, SLIDESHOW_MINUTES.length,
                slideshowIndex(), i -> {
                    slideshowMinutes = SLIDESHOW_MINUTES[i];
                    choices.edit().putInt("slideshow_minutes", slideshowMinutes).apply();
                    changed();
                }, i -> getString(R.string.slideshow_minutes, SLIDESHOW_MINUTES[i])));
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            choices.edit().putBoolean("animations", animations).apply();
            changed();
            refresh();
        });
        openOnStartChoice = findViewById(R.id.openOnStartChoice);
        if (config.openOnStartChoice) {
            openOnStartChoice.setVisibility(View.VISIBLE);
            openOnStartChoice.setOnClickListener(v -> showOpenOnStartPicker());
        }
        libraryShowHiddenChoice = findViewById(R.id.libraryShowHiddenChoice);
        libraryShowHiddenChoice.setOnClickListener(v -> {
            libraryShowHidden = !libraryShowHidden;
            choices.edit().putBoolean("library_show_hidden", libraryShowHidden).apply();
            refresh();
        });
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove("animations")
                    .remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(Config.DOCK_OPACITY_KEY).remove(Config.DOCK_BACKDROP_KEY).remove(Config.DOCK_BLUR_KEY)
                    .remove(OpenOnStart.KEY).commit();
            Config current = Config.load(this);
            columns = current.columns;
            dockOpacity = current.dockOpacity;
            dockBlur = Config.dockBlur(this);
            dockBackdrop = current.dockBackdrop;
            animations = current.animations;
            changed();
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        setUpNav();
        loadWallpaper();
        refresh();
        columnsChoice.requestFocus();
    }

    /** Home's current wallpaper (EXTRA_WALLPAPER), blurred like the apps grid's background. */
    static final String EXTRA_WALLPAPER = "com.gathin.tvhome.extra.SETTINGS_WALLPAPER";

    private void loadWallpaper() {
        String path = getIntent().getStringExtra(EXTRA_WALLPAPER);
        if (path == null) return;
        android.graphics.Bitmap bitmap = AppLibraryActivity.decodeForScreen(this, new File(path));
        if (bitmap == null) return;
        android.widget.ImageView view = findViewById(R.id.wallpaper);
        view.setImageBitmap(bitmap);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            float sigma = 36f * getResources().getDisplayMetrics().density;
            view.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(sigma, sigma,
                    android.graphics.Shader.TileMode.CLAMP));
        }
    }

    private View[] nav;
    private View[] pages;
    private int page;

    /** The left menu: focusing an entry opens its page. Pages 2 and 3 are empty for now. */
    private void setUpNav() {
        nav = new View[]{findViewById(R.id.navPersonal), findViewById(R.id.navProfile),
                findViewById(R.id.navWallpaper)};
        pages = new View[]{findViewById(R.id.pagePersonal), findViewById(R.id.pageProfile),
                findViewById(R.id.pageWallpaper)};
        for (int i = 0; i < nav.length; i++) {
            final int index = i;
            nav[i].setOnFocusChangeListener((v, has) -> { if (has && !navLock) showPage(index); });
            nav[i].setOnClickListener(v -> showPage(index));
        }
        showPage(0);
    }

    private void showPage(int index) {
        if (subOpen) {
            subOpen = false;
            if (downloadCancel != null) downloadCancel.set(true);
            findViewById(R.id.pageSub).setVisibility(View.GONE);
        }
        page = index;
        ((TextView) findViewById(R.id.pageTitle)).setText(
                new int[]{R.string.nav_personalization, R.string.nav_profile, R.string.nav_screensaver_wallpaper}[index]);
        for (int i = 0; i < nav.length; i++) {
            nav[i].setSelected(i == index);
            pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
    }

    private boolean inNav(View v) {
        for (View n : nav) if (n == v) return true;
        return false;
    }

    /** Right from the menu enters the open page (nothing happens on an empty one); Left from the page's
     *  left edge returns to the menu entry that is open instead of whichever one is nearest. */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (subOpen && e.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == android.view.KeyEvent.ACTION_UP) closeSub();
            return true;
        }
        View f = getCurrentFocus();
        if (nav != null && f != null && e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            int code = e.getKeyCode();
            boolean rtl = getResources().getBoolean(R.bool.rtl_app_grid);
            boolean toContent = code == (rtl ? android.view.KeyEvent.KEYCODE_DPAD_LEFT : android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
            boolean toNav = code == (rtl ? android.view.KeyEvent.KEYCODE_DPAD_RIGHT : android.view.KeyEvent.KEYCODE_DPAD_LEFT);
            if (inNav(f) && toContent) {
                if (page == 0) columnsChoice.requestFocus();
                else if (page == 2) wallpaperChoice.requestFocus();
                return true;
            }
            if (!inNav(f) && !(f instanceof StepSlider) && toNav) {
                View next = f.focusSearch(toNav && !rtl ? View.FOCUS_LEFT : View.FOCUS_RIGHT);
                if (next == null || inNav(next)) {
                    nav[page].requestFocus();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private static final int MIN_COLUMNS = 5, MAX_COLUMNS = 7;
    private View githubWallpapersChoice;
    private java.util.concurrent.atomic.AtomicBoolean downloadCancel;
    private View subRow;
    private boolean subOpen;
    /** Hiding the focused page moves focus to the menu for a moment; that must not switch pages. */
    private boolean navLock;

    private interface Label { String of(int index); }

    /** The sub-page for one numeric setting, in the right pane: a Back row and a stepped slider. */
    private void openSub(View row, int labelRes, int descRes, int base, int steps, int current,
                         StepSlider.Listener apply, Label valueLabel) {
        navLock = true;
        subRow = row;
        subOpen = true;
        findViewById(R.id.subSliderCard).setVisibility(View.VISIBLE);
        findViewById(R.id.subDownloadCard).setVisibility(View.GONE);
        findViewById(R.id.subChoices).setVisibility(View.GONE);
        findViewById(R.id.subSlideshowChoices).setVisibility(View.GONE);
        findViewById(R.id.subWallpaperScroll).setVisibility(View.GONE);
        findViewById(R.id.pagePersonal).setVisibility(View.GONE);
        findViewById(R.id.pageWallpaper).setVisibility(View.GONE);
        findViewById(R.id.pageSub).setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.pageTitle)).setText(labelRes);
        findViewById(R.id.subSection).setVisibility(View.GONE);
        ((TextView) findViewById(R.id.subTitle)).setText(labelRes);
        ((TextView) findViewById(R.id.subDesc)).setText(descRes);
        StepSlider slider = findViewById(R.id.subSlider);
        int start = Math.max(0, Math.min(steps - 1, current - (labelRes == R.string.columns_label ? base : 0)));
        slider.setFormatter(valueLabel::of);
        slider.setSteps(steps, start);
        slider.setListener(apply);
        // Geometry alone sends Down from Back to the menu on the left: wire the sub-page's focus by hand.
        findViewById(R.id.subBack).setNextFocusDownId(R.id.subSlider);
        slider.setNextFocusUpId(R.id.subBack);
        slider.setNextFocusDownId(R.id.subSlider);
        slider.requestFocus();
        navLock = false;
    }

    /** The sub-page of a pick-one setting: a Back row and the options stacked as tiles, a check on the chosen one. */
    private void openChoices(View row, int labelRes) {
        navLock = true;
        subRow = row;
        subOpen = true;
        findViewById(R.id.pagePersonal).setVisibility(View.GONE);
        findViewById(R.id.pageSub).setVisibility(View.VISIBLE);
        findViewById(R.id.subSliderCard).setVisibility(View.GONE);
        findViewById(R.id.subDownloadCard).setVisibility(View.GONE);
        findViewById(R.id.subChoices).setVisibility(View.VISIBLE);
        findViewById(R.id.subSlideshowChoices).setVisibility(View.GONE);
        findViewById(R.id.subWallpaperScroll).setVisibility(View.GONE);
        findViewById(R.id.subSection).setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.pageTitle)).setText(labelRes);
        ((TextView) findViewById(R.id.subSection)).setText(labelRes);
        refresh();
        findViewById(R.id.subBack).setNextFocusDownId(backdropChoices[0].getId());
        backdropChoices[0].setNextFocusUpId(R.id.subBack);
        backdropChoices[backdropChoices.length - 1].setNextFocusDownId(backdropChoices[backdropChoices.length - 1].getId());
        for (int i = 0; i < BACKDROPS.length; i++) {
            if (BACKDROPS[i].equals(dockBackdrop)) { backdropChoices[i].requestFocus(); navLock = false; return; }
        }
        backdropChoices[0].requestFocus();
        navLock = false;
    }

    private boolean screensaverIsClock() {
        return LiquidClockLayout.KIND_CLOCK.equals(choices.getString(LiquidClockLayout.KIND_KEY, LiquidClockLayout.KIND_AERIAL));
    }

    private void setScreensaver(String kind) {
        choices.edit().putString(LiquidClockLayout.KIND_KEY, kind).apply();   // read when the screensaver starts
        refresh();
    }

    /** Screensaver type: the same two-tile sub-page as Slideshow, with the video first and the clock second. */
    private void openScreensaverChoices() {
        navLock = true;
        twoChoiceMode = 1;
        subRow = screensaverChoice;
        subOpen = true;
        findViewById(R.id.pageWallpaper).setVisibility(View.GONE);
        findViewById(R.id.pageSub).setVisibility(View.VISIBLE);
        findViewById(R.id.subSliderCard).setVisibility(View.GONE);
        findViewById(R.id.subDownloadCard).setVisibility(View.GONE);
        findViewById(R.id.subChoices).setVisibility(View.GONE);
        findViewById(R.id.subWallpaperScroll).setVisibility(View.GONE);
        findViewById(R.id.subSlideshowChoices).setVisibility(View.VISIBLE);
        findViewById(R.id.subSection).setVisibility(View.VISIBLE);
        slideshowYes.setText(R.string.screensaver_kind_aerial);
        slideshowNo.setText(R.string.screensaver_kind_clock);
        ((TextView) findViewById(R.id.pageTitle)).setText(R.string.screensaver_kind_label);
        ((TextView) findViewById(R.id.subSection)).setText(R.string.screensaver_kind_label);
        refresh();
        findViewById(R.id.subBack).setNextFocusDownId(R.id.slideshowYes);
        slideshowYes.setNextFocusUpId(R.id.subBack);
        slideshowNo.setNextFocusDownId(R.id.slideshowNo);
        (screensaverIsClock() ? slideshowNo : slideshowYes).requestFocus();
        navLock = false;
    }

    private void setSlideshow(boolean on) {
        if (slideshow == on) return;
        slideshow = on;
        choices.edit().putBoolean("slideshow", on).apply();
        changed();
        refresh();
    }

    /** Slideshow: a Back row and the Yes / No tiles, a check on the chosen one (like the dock backdrop). */
    private void openSlideshowChoices() {
        navLock = true;
        subRow = slideshowChoice;
        subOpen = true;
        findViewById(R.id.pageWallpaper).setVisibility(View.GONE);
        findViewById(R.id.pageSub).setVisibility(View.VISIBLE);
        findViewById(R.id.subSliderCard).setVisibility(View.GONE);
        findViewById(R.id.subDownloadCard).setVisibility(View.GONE);
        findViewById(R.id.subChoices).setVisibility(View.GONE);
        findViewById(R.id.subSlideshowChoices).setVisibility(View.VISIBLE);
        findViewById(R.id.subWallpaperScroll).setVisibility(View.GONE);
        findViewById(R.id.subSection).setVisibility(View.VISIBLE);
        twoChoiceMode = 0;
        slideshowYes.setText(R.string.yes);
        slideshowNo.setText(R.string.no);
        ((TextView) findViewById(R.id.pageTitle)).setText(R.string.slideshow_label);
        ((TextView) findViewById(R.id.subSection)).setText(R.string.slideshow_label);
        refresh();
        findViewById(R.id.subBack).setNextFocusDownId(R.id.slideshowYes);
        slideshowYes.setNextFocusUpId(R.id.subBack);
        slideshowNo.setNextFocusDownId(R.id.slideshowNo);
        (slideshow ? slideshowYes : slideshowNo).requestFocus();
        navLock = false;
    }

    private int slideshowIndex() {
        for (int i = 0; i < SLIDESHOW_MINUTES.length; i++) if (SLIDESHOW_MINUTES[i] == slideshowMinutes) return i;
        return 0;
    }

    /** The sub-page that copies the GitHub wallpaper collection onto the TV, with a progress bar. */
    private void openDownload(View row) {
        navLock = true;
        subRow = row;
        subOpen = true;
        findViewById(R.id.pageWallpaper).setVisibility(View.GONE);
        findViewById(R.id.pageSub).setVisibility(View.VISIBLE);
        findViewById(R.id.subSection).setVisibility(View.GONE);
        findViewById(R.id.subSliderCard).setVisibility(View.GONE);
        findViewById(R.id.subChoices).setVisibility(View.GONE);
        findViewById(R.id.subSlideshowChoices).setVisibility(View.GONE);
        findViewById(R.id.subWallpaperScroll).setVisibility(View.GONE);
        findViewById(R.id.subDownloadCard).setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.pageTitle)).setText(R.string.wallpapers_github);
        TextView status = findViewById(R.id.dlStatus);
        android.widget.ProgressBar bar = findViewById(R.id.dlBar);
        bar.setIndeterminate(true);
        status.setText(R.string.dl_connecting);
        findViewById(R.id.subBack).setNextFocusDownId(R.id.subBack);
        findViewById(R.id.subBack).requestFocus();
        navLock = false;
        if (downloadCancel != null) downloadCancel.set(true);
        downloadCancel = new java.util.concurrent.atomic.AtomicBoolean();
        WallpaperDownloader.start(this, downloadCancel, new WallpaperDownloader.Listener() {
            @Override public void onProgress(int done, int total, String name) {
                bar.setIndeterminate(false);
                bar.setMax(total);
                bar.setProgress(done);
                status.setText(getString(R.string.dl_progress, done, total, wallpaperLabel(name)));
            }
            @Override public void onDone(int added, int skipped) {
                bar.setIndeterminate(false);
                bar.setProgress(bar.getMax());
                status.setText(getString(R.string.dl_done, added, skipped));
                if (added > 0) changed();
            }
            @Override public void onError() {
                bar.setIndeterminate(false);
                bar.setProgress(0);
                status.setText(R.string.dl_failed);
            }
        });
    }

    private void closeSub() {
        if (!subOpen) return;
        navLock = true;
        subOpen = false;
        if (downloadCancel != null) downloadCancel.set(true);
        boolean fromWallpapers = subRow == githubWallpapersChoice || subRow == wallpaperChoice || subRow == slideshowCollectionChoice || subRow == screensaverChoice || subRow == slideshowChoice || subRow == slideshowIntervalChoice;
        findViewById(R.id.pageSub).setVisibility(View.GONE);
        findViewById(fromWallpapers ? R.id.pageWallpaper : R.id.pagePersonal).setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.pageTitle)).setText(fromWallpapers ? R.string.nav_screensaver_wallpaper
                : R.string.nav_personalization);
        refresh();
        if (subRow != null) subRow.requestFocus();
        navLock = false;
    }

    @Override
    public void onBackPressed() {
        if (subOpen) closeSub();
        else super.onBackPressed();
    }

    /** A group or picture name in the app's language (res/values-xx/wallpaper_names.xml), as it is when unlisted. */
    private String localName(String name) {
        for (String pair : getResources().getStringArray(R.array.wallpaper_names)) {
            int cut = pair.indexOf('=');
            if (cut > 0 && pair.substring(0, cut).equals(name)) return pair.substring(cut + 1);
        }
        return name;
    }

    private static String builtinName(String choice) {
        for (String[] e : DefaultWallpaper.EXTRA) if (e[0].equals(choice)) return e[1];
        return choice;
    }

    /** Removes what "Download wallpapers from GitHub" put on the TV: the group folders under wallpapers/. Pictures
     *  placed straight in wallpapers/ (by a theme) stay. A picked wallpaper that is gone falls back to the built-in. */
    private void deleteGithubWallpapers() {
        File dir = Config.dir(this);
        File root = dir == null ? null : new File(dir, ThemeFiles.WALLPAPERS);
        File[] entries = root == null ? null : root.listFiles();
        int removed = 0;
        if (entries != null) {
            for (File group : entries) {
                if (!group.isDirectory()) continue;
                File[] files = group.listFiles();
                if (files != null) for (File f : files) if (f.delete()) removed++;
                group.delete();
            }
        }
        if (removed == 0) {
            Toast.makeText(this, R.string.wallpapers_github_none, Toast.LENGTH_SHORT).show();
            return;
        }
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        if (current != null && !"none".equals(current) && !DefaultWallpaper.isChoice(current)
                && WallpaperRotation.find(WallpaperRotation.images(root), current) == null) {
            choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, DefaultWallpaper.CHOICE).apply();
        }
        changed();
        Toast.makeText(this, R.string.wallpapers_github_deleted, Toast.LENGTH_SHORT).show();
        refresh();
    }

    private final List<TextView> wallpaperRows = new ArrayList<>();
    private final java.util.concurrent.ExecutorService thumbs = java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override
    protected void onDestroy() {
        thumbs.shutdownNow();
        super.onDestroy();
    }

    /** The sub-page of the wallpaper: Back, then every picture with a preview, in groups; a green check marks
     *  the one in use. The built-in one comes first; GitHub groups appear only once downloaded. */
    private void openWallpapers(boolean multi) {
        navLock = true;
        wallpaperMulti = multi;
        subRow = multi ? slideshowCollectionChoice : wallpaperChoice;
        subOpen = true;
        findViewById(R.id.pageWallpaper).setVisibility(View.GONE);
        findViewById(R.id.pageSub).setVisibility(View.VISIBLE);
        findViewById(R.id.subSection).setVisibility(View.GONE);
        findViewById(R.id.subSliderCard).setVisibility(View.GONE);
        findViewById(R.id.subDownloadCard).setVisibility(View.GONE);
        findViewById(R.id.subChoices).setVisibility(View.GONE);
        findViewById(R.id.subSlideshowChoices).setVisibility(View.GONE);
        findViewById(R.id.subWallpaperScroll).setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.pageTitle)).setText(multi ? R.string.slideshow_collection : R.string.wallpaper_label);
        LinearLayout list = findViewById(R.id.subWallpaperList);
        // Cut the list at the top and bottom only, so the focused row can grow past the sides.
        android.view.View scroll = findViewById(R.id.subWallpaperScroll);
        scroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int side = (int) (40 * getResources().getDisplayMetrics().density);
            v.setClipBounds(new android.graphics.Rect(-side, 0, r - l + side, b - t));
        });
        list.removeAllViews();
        wallpaperRows.clear();
        float dp = getResources().getDisplayMetrics().density;

        addWallpaperSection(list, getString(R.string.wallpapers_standard), dp);
        addWallpaperRow(list, getString(R.string.wallpaper_default_name), DefaultWallpaper.CHOICE, null, dp);
        for (String[] extra : DefaultWallpaper.EXTRA) addWallpaperRow(list, localName(extra[1]), extra[0], null, dp);

        File dir = Config.dir(this);
        File root = dir == null ? null : new File(dir, ThemeFiles.WALLPAPERS);
        java.util.Map<String, List<File>> groups = new java.util.TreeMap<>();
        List<File> loose = new ArrayList<>();
        for (File image : WallpaperRotation.images(root)) {
            File parent = image.getParentFile();
            if (parent != null && !parent.equals(root)) {
                List<File> group = groups.get(parent.getName());
                if (group == null) groups.put(parent.getName(), group = new ArrayList<>());
                group.add(image);
            } else loose.add(image);
        }
        for (java.util.Map.Entry<String, List<File>> g : groups.entrySet()) {
            addWallpaperSection(list, localName(g.getKey()), dp);
            for (File image : g.getValue()) {
                addWallpaperRow(list, localName(wallpaperLabel(image.getName())), image.getName(), image, dp);
            }
        }
        if (!loose.isEmpty()) {
            addWallpaperSection(list, getString(R.string.wallpapers_other), dp);
            for (File image : loose) addWallpaperRow(list, localName(wallpaperLabel(image.getName())), image.getName(), image, dp);
        }
        markWallpaper();

        TextView first = wallpaperRows.get(0);
        findViewById(R.id.subBack).setNextFocusDownId(first.getId());
        first.setNextFocusUpId(R.id.subBack);
        TextView last = wallpaperRows.get(wallpaperRows.size() - 1);
        last.setNextFocusDownId(last.getId());
        TextView chosen = first;
        for (TextView row : wallpaperRows) if (isChosenWallpaper((String) row.getTag())) chosen = row;
        chosen.requestFocus();
        navLock = false;
    }

    private void addWallpaperSection(LinearLayout list, String title, float dp) {
        TextView section = new TextView(new android.view.ContextThemeWrapper(this, R.style.SettingsSection), null, 0);
        section.setText(title);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (list.getChildCount() == 0 ? 19 * dp : 14 * dp);
        lp.bottomMargin = (int) (2 * dp);
        section.setLayoutParams(lp);
        list.addView(section);
    }

    private void addWallpaperRow(LinearLayout list, String label, String choice, File file, float dp) {
        TextView row = new TextView(new android.view.ContextThemeWrapper(this, R.style.SettingsOptionRow), null, 0);
        row.setId(View.generateViewId());
        row.setText(label);
        row.setTag(choice);
        row.setCompoundDrawablePadding((int) (14 * dp));
        row.setCompoundDrawableTintList(null);
        row.setMinHeight((int) (52 * dp));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (52 * dp));
        rowParams.bottomMargin = (int) (12 * dp) - 12;
        row.setLayoutParams(rowParams);
        row.setOnClickListener(v -> {
            if (wallpaperMulti) {
                java.util.Set<String> set = new java.util.HashSet<>(slideshowSet());
                if (!set.remove(choice)) set.add(choice);
                choices.edit().putStringSet(SLIDESHOW_SET_KEY, set).apply();
                changed();
                markWallpaper();
                return;
            }
            // Picking one wallpaper ends the slideshow.
            slideshow = false;
            choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, choice).putBoolean("slideshow", false).apply();
            changed();
            Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
            markWallpaper();
        });
        list.addView(row);
        wallpaperRows.add(row);
        int w = (int) (64 * dp), h = (int) (36 * dp);
        thumbs.execute(() -> {
            android.graphics.Bitmap thumb = thumbnail(file != null ? file : DefaultWallpaper.fileFor(this, choice), w, h, 5 * dp);
            if (thumb == null) return;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                android.graphics.drawable.Drawable d = new android.graphics.drawable.BitmapDrawable(getResources(), thumb);
                d.setBounds(0, 0, w, h);
                row.setCompoundDrawablesRelative(d, null, row.getCompoundDrawablesRelative()[2], null);
            });
        });
    }

    private java.util.Set<String> slideshowSet() {
        return choices.getStringSet(SLIDESHOW_SET_KEY, java.util.Collections.<String>emptySet());
    }

    private boolean isChosenWallpaper(String choice) {
        if (wallpaperMulti) return slideshowSet().contains(choice);
        if (slideshow) return false;
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        if (current == null) {
            // Nothing picked: Home shows the downloaded collection if there is one, else the built-in picture.
            return DefaultWallpaper.CHOICE.equals(choice) && wallpaperRows.size() > 0
                    && !hasDownloadedWallpapers();
        }
        return current.equals(choice);
    }

    private boolean hasDownloadedWallpapers() {
        File dir = Config.dir(this);
        return dir != null && !WallpaperRotation.images(new File(dir, ThemeFiles.WALLPAPERS)).isEmpty();
    }

    /** Green check on the chosen row, an empty ring on the others. */
    private void markWallpaper() {
        for (TextView row : wallpaperRows) {
            android.graphics.drawable.Drawable[] d = row.getCompoundDrawablesRelative();
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(d[0], null,
                    getDrawable(isChosenWallpaper((String) row.getTag())
                            ? R.drawable.ic_wallpaper_checked : R.drawable.ic_wallpaper_unchecked), null);
            if (d[0] != null) d[0].setBounds(0, 0, d[0].getBounds().width(), d[0].getBounds().height());
        }
    }

    /** A small rounded preview, centre-cropped; null when the picture cannot be read. */
    private static android.graphics.Bitmap thumbnail(File file, int w, int h, float radius) {
        if (file == null) return null;
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(file.getPath(), o);
        if (o.outWidth <= 0) return null;
        o.inSampleSize = Math.max(1, Integer.highestOneBit(Math.max(1, o.outWidth / (w * 2))));
        o.inJustDecodeBounds = false;
        android.graphics.Bitmap src = android.graphics.BitmapFactory.decodeFile(file.getPath(), o);
        if (src == null) return null;
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(out);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG
                | android.graphics.Paint.FILTER_BITMAP_FLAG);
        canvas.drawRoundRect(new android.graphics.RectF(0, 0, w, h), radius, radius, paint);
        paint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
        float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        float dw = src.getWidth() * scale, dh = src.getHeight() * scale;
        canvas.drawBitmap(src, null, new android.graphics.RectF((w - dw) / 2f, (h - dh) / 2f,
                (w + dw) / 2f, (h + dh) / 2f), paint);
        src.recycle();
        return out;
    }

    /** Off, then every app with a TV entry point by name. Saved at once; it is read on the next boot. */
    private void showOpenOnStartPicker() {
        PackageManager pm = getPackageManager();
        List<String[]> apps = new ArrayList<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tv, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            boolean seen = false;
            for (String[] a : apps) seen |= a[1].equals(pkg);
            if (!seen) apps.add(new String[]{r.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        String[] labels = new String[apps.size() + 1];
        labels[0] = getString(R.string.off);
        String current = choices.getString(OpenOnStart.KEY, "");
        int checked = 0;
        for (int i = 0; i < apps.size(); i++) {
            labels[i + 1] = apps.get(i)[0];
            if (apps.get(i)[1].equals(current)) checked = i + 1;
        }
        new AlertDialog.Builder(this, R.style.SettingsDialog)
                .setTitle(R.string.open_on_start_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) choices.edit().remove(OpenOnStart.KEY).apply();
                    else {
                        OpenOnStart.skipThisBoot(this);
                        choices.edit().putString(OpenOnStart.KEY, apps.get(which - 1)[1]).apply();
                    }
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The picked app's name, its package when it is gone, or Off. */
    private String openOnStartLabel() {
        String pkg = choices.getString(OpenOnStart.KEY, "");
        if (pkg.isEmpty()) return getString(R.string.off);
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** A wallpaper file name as shown: no extension, hyphens and underscores read as spaces. */
    static String wallpaperLabel(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        return base.replace('-', ' ').replace('_', ' ');
    }

    /**
     * A choice was saved. Home rebuilds when it next resumes because the change count moved, whether these
     * settings are left with Back or with the remote's Home button (which cancels the activity result).
     */
    private void changed() {
        setResult(RESULT_OK);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void refresh() {
        columnsChoice.setText(getString(R.string.columns_label) + ": " + Math.max(MIN_COLUMNS, Math.min(MAX_COLUMNS, columns)));
        dockOpacityChoice.setText(getString(R.string.dock_opacity_label) + ": " + dockOpacity + "%");
        dockBlurChoice.setText(getString(R.string.dock_blur_label) + ": " + dockBlur);
        String backdropLabel = "";
        for (int i = 0; i < backdropChoices.length; i++) {
            boolean on = BACKDROPS[i].equals(dockBackdrop);
            backdropChoices[i].setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0,
                    on ? R.drawable.ic_settings_check : 0, 0);
            if (on) backdropLabel = backdropChoices[i].getText().toString();
        }
        dockBackdropChoice.setText(getString(R.string.dock_backdrop_label) + ": " + backdropLabel);
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        slideshowCollectionChoice.setText(getString(R.string.slideshow_collection_state, slideshowSet().size()));
        wallpaperChoice.setText(getString(R.string.wallpaper_state,
                slideshow ? getString(R.string.slideshow_label)
                : "none".equals(wallpaperCurrent) ? getString(R.string.wallpaper_none)
                        : DefaultWallpaper.CHOICE.equals(wallpaperCurrent) ? getString(R.string.wallpaper_default_name)
                        : DefaultWallpaper.isChoice(wallpaperCurrent) ? localName(builtinName(wallpaperCurrent))
                        : wallpaperCurrent != null ? localName(wallpaperLabel(wallpaperCurrent))
                        : getString(R.string.wallpaper_theme_default)));
        boolean firstChosen = twoChoiceMode == 1 ? !screensaverIsClock() : slideshow;
        slideshowYes.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0,
                firstChosen ? R.drawable.ic_settings_check : 0, 0);
        slideshowNo.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0,
                firstChosen ? 0 : R.drawable.ic_settings_check, 0);
        screensaverChoice.setText(getString(R.string.screensaver_state,
                getString(screensaverIsClock() ? R.string.screensaver_state_clock : R.string.screensaver_state_aerial)));
        slideshowChoice.setText(getString(R.string.slideshow_state,
                getString(slideshow ? R.string.yes : R.string.no)));
        slideshowIntervalChoice.setText(getString(R.string.slideshow_interval_state,
                getString(R.string.slideshow_minutes, slideshowMinutes)));
        openOnStartChoice.setText(getString(R.string.open_on_start_state, openOnStartLabel()));
        libraryShowHiddenChoice.setText(getString(R.string.library_show_hidden_state,
                getString(libraryShowHidden ? R.string.on : R.string.off)));
        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns")
                || choices.contains("animations")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY) || choices.contains(Config.DOCK_OPACITY_KEY)
                || choices.contains(Config.DOCK_BACKDROP_KEY) || choices.contains(Config.DOCK_BLUR_KEY)
                || choices.contains(OpenOnStart.KEY);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
