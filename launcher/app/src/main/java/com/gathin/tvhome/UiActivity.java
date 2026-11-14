package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;

/**
 * Local patch: the base of every launcher screen except the clock and Aerial screensavers. It gives the screen the
 * launcher's own language (Lang) and the font the user chose (UiFonts), so layouts and styles can name them as
 * theme attributes (?attr/uiFontMedium, and so on).
 */
abstract class UiActivity extends Activity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    @Override
    protected void onApplyThemeResource(Resources.Theme theme, int resid, boolean first) {
        super.onApplyThemeResource(theme, resid, first);
        theme.applyStyle(UiFonts.style(UiFonts.current(getBaseContext())), true);
    }
}
