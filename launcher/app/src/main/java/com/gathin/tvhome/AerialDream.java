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

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(true);
        setContentView(R.layout.dream_aerial);
        player = new AerialPlayer(this, (TextureView) findViewById(R.id.aerialVideo),
                (TextView) findViewById(R.id.aerialLabel), null);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        player.start();
    }

    @Override
    public void onDreamingStopped() {
        player.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (player != null) player.stop();
        super.onDetachedFromWindow();
    }
}
