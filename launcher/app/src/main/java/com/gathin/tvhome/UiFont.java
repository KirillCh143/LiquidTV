package com.gathin.tvhome;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.util.TypedValue;

/**
 * The typeface behind one of the font_* strings in res/values/fonts.xml, for views built in code. The string
 * points at a bundled font, or, in locales Outfit can't draw (values-vi), names a system family (#833).
 */
final class UiFont {
    private UiFont() {}

    /** The font the user chose (Launcher settings > Font) for this role: uiFontMedium, uiFontRegular and so on. Falls
     *  back to the locale's own font string where the context has no theme with it. */
    static Typeface themed(Context context, int fontAttr, int fallbackString) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(fontAttr, value, true) || value.type != TypedValue.TYPE_STRING) {
            return get(context, fallbackString);
        }
        Resources res = context.getResources();
        String name = String.valueOf(value.string);
        if (name.startsWith("res/")) return res.getFont(value.resourceId);
        return Typeface.create(name, Typeface.NORMAL);
    }

    static Typeface get(Context context, int fontString) {
        Resources res = context.getResources();
        TypedValue value = new TypedValue();
        res.getValue(fontString, value, true);
        String name = String.valueOf(value.string);
        if (name.startsWith("res/")) return res.getFont(value.resourceId);
        return Typeface.create(name, Typeface.NORMAL);
    }
}
