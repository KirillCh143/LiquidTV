package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.Locale;

/**
 * Local patch: the launcher's language is its own setting (Launcher settings, Personalization > Language), not the
 * system's. Every screen wraps its base context with {@link #wrap}, so strings come from that language's
 * resources. With no choice made yet it follows the system once, when the system language is one of these.
 */
final class Lang {
    static final String KEY = "launcher_language";
    static final String EN = "en", RU = "ru";

    /** Code, locale tag and the language's own name. */
    static final String[][] LIST = {
            {"en", "en", "English"},
            {"ru", "ru", "Русский"},
            {"be", "be", "Беларуская"},
            {"uk", "uk", "Українська"},
            {"kk", "kk", "Қазақша"},
            {"tr", "tr", "Türkçe"},
            {"it", "it", "Italiano"},
            {"de", "de", "Deutsch"},
            {"fr", "fr", "Français"},
            {"es", "es", "Español"},
            {"pt", "pt", "Português"},
            {"zh", "zh-CN", "简体中文"},
            {"ja", "ja", "日本語"},
            {"ko", "ko", "한국어"},
    };

    private Lang() {}

    private static boolean known(String code) {
        for (String[] l : LIST) if (l[0].equals(code)) return true;
        return false;
    }

    /** The language in use: the saved choice, else the system's when it is one of these, else English. */
    static String current(Context context) {
        SharedPreferences p = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        String saved = p.getString(KEY, null);
        if (saved != null && known(saved)) return saved;
        String system = Locale.getDefault().getLanguage();
        return known(system) ? system : EN;
    }

    /** The language's own name, for the settings row. */
    static String nameOf(String code) {
        for (String[] l : LIST) if (l[0].equals(code)) return l[2];
        return code;
    }

    static void set(Context context, String lang) {
        context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit().putString(KEY, lang).apply();
    }

    /** The context whose resources use the launcher's language; call from attachBaseContext. */
    static Context wrap(Context base) {
        String code = current(base);
        String tag = code;
        for (String[] l : LIST) if (l[0].equals(code)) tag = l[1];
        Locale locale = Locale.forLanguageTag(tag);
        Locale.setDefault(locale);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        config.setLayoutDirection(locale);
        return base.createConfigurationContext(config);
    }
}
