package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Local patch, not upstream: the Settings grid's Screensaver tile. The same streaming aerial screensaver as
 * AerialDream (AerialPlayer), started straight from the launcher: a system screensaver can only be started by the
 * system, which is what the TV's own Screensaver settings page did. Any key returns to where you were. With no video
 * list at all (no cache, no network) it shows the launcher's clock instead.
 */
public class AerialActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }


    private AerialPlayer player;
    /** Launcher settings, "Screensaver type" = Clock: the offline clock on the wallpaper instead of the video. */
    private LiquidClockLayout clock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE);
        if (LiquidClockLayout.selected(this)) {
            clock = new LiquidClockLayout(this);
            setContentView(clock);
            return;
        }
        setContentView(R.layout.dream_aerial);
        player = new AerialPlayer(this, (TextureView) findViewById(R.id.aerialVideo),
                (TextView) findViewById(R.id.aerialLabel), () -> {
                    startActivity(new Intent(this, ClockActivity.class));
                    finish();
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (clock != null) clock.start(); else player.start();
    }

    @Override
    protected void onPause() {
        if (clock != null) clock.stop(); else player.stop();
        super.onPause();
        finish();   // never left behind in the back stack
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // The key that ends the screensaver should not also act on Home, so it is consumed here.
        if (event.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        finish();
        return true;
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
