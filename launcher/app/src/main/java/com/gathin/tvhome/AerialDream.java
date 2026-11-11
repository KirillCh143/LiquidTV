package com.gathin.tvhome;

import android.service.dreams.DreamService;
import android.view.TextureView;
import android.widget.TextView;

/**
 * Local patch, not upstream: the system screensaver that streams Apple TV's aerial videos, like Lampa's (see
 * AerialPlayer). Pick it in the TV's screensaver settings, or `settings put secure screensaver_components
 * com.gathin.tvhome/.AerialDream` over adb. Any key wakes the TV.
 */
public class AerialDream extends DreamService {

    private AerialPlayer player;
    /** Launcher settings, "Screensaver type" = Clock: the offline clock on the wallpaper instead of the video. */
    private LiquidClockLayout clock;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(true);
        if (LiquidClockLayout.selected(this)) {
            clock = new LiquidClockLayout(this);
            setContentView(clock);
            return;
        }
        setContentView(R.layout.dream_aerial);
        player = new AerialPlayer(this, (TextureView) findViewById(R.id.aerialVideo),
                (TextView) findViewById(R.id.aerialLabel), null);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (clock != null) clock.start(); else player.start();
    }

    @Override
    public void onDreamingStopped() {
        if (clock != null) clock.stop(); else player.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (clock != null) clock.stop();
        if (player != null) player.stop();
        super.onDetachedFromWindow();
    }
}
