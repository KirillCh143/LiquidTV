package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;

/**
 * Local patch: the launcher's font is its own setting (Launcher settings, Personalization > Font). All of them are
 * bundled in the app (res/font), so they are there on any Android build. The clock and Aerial screensavers keep
 * Outfit whatever is chosen. See {@link UiActivity}, which applies the choice as a theme overlay.
 */
final class UiFonts {
    static final String KEY = "launcher_font";
    static final String OUTFIT = "outfit";

    /** Code and the font's name. */
    static final String[][] LIST = {
            {OUTFIT, "Outfit"},
            {"roboto", "Roboto"},
            {"nunito", "Nunito"},
            {"rubik", "Rubik"},
            {"roboto_slab", "Roboto Slab"},
            {"ubuntu", "Ubuntu"},
    };

    private UiFonts() {}

    static String current(Context context) {
        SharedPreferences p = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        String saved = p.getString(KEY, OUTFIT);
        for (String[] f : LIST) if (f[0].equals(saved)) return saved;
        return OUTFIT;
    }

    static void set(Context context, String code) {
        context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit().putString(KEY, code).apply();
    }

    static String nameOf(String code) {
        for (String[] f : LIST) if (f[0].equals(code)) return f[1];
        return code;
    }

    /** The overlay style that defines the five uiFont* attributes for this font. */
    static int style(String code) {
        switch (code) {
            case "roboto": return R.style.UiFont_Roboto;
            case "nunito": return R.style.UiFont_Nunito;
            case "rubik": return R.style.UiFont_Rubik;
            case "roboto_slab": return R.style.UiFont_RobotoSlab;
            case "ubuntu": return R.style.UiFont_Ubuntu;
            default: return R.style.UiFont_Outfit;
        }
    }

    /** The font's medium weight, to show its name in its own letters. */
    static Typeface sample(Context context, String code) {
        switch (code) {
            case "roboto": return context.getResources().getFont(R.font.roboto_medium);
            case "nunito": return context.getResources().getFont(R.font.nunito_medium);
            case "rubik": return context.getResources().getFont(R.font.rubik_medium);
            case "roboto_slab": return context.getResources().getFont(R.font.robotoslab_medium);
            case "ubuntu": return context.getResources().getFont(R.font.ubuntu_medium);
            default: return UiFont.get(context, R.string.font_medium);
        }
    }
}
