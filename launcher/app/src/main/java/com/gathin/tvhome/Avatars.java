package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * Local patch: the user's profile (Launcher settings, Profile page): a name, and an emoji avatar that Home shows
 * in the top bar in place of the plain user icon, on a white disc. The emoji are drawn with the system's colour
 * emoji font, so there are no pictures to ship.
 */
final class Avatars {
    static final String NAME_KEY = "profile_name";
    static final String AVATAR_KEY = "profile_avatar";
    static final String DEFAULT_NAME = "User 1";

    /** {emoji, name shown in the picker}. */
    static final String[][] LIST = {
            {cp(0x1F603), "Широкая улыбка"}, {cp(0x1F601), "Сияющая улыбка"}, {cp(0x1F92A), "Безумное лицо"},
            {cp(0x1F920), "Ковбой"}, {cp(0x1F60E), "Тёмные очки"}, {cp(0x1F47D), "Пришелец"}, {cp(0x1F63A), "Весёлый кот"},
            {cp(0x1F9D2, 0x1F3FB), "Ребёнок"}, {cp(0x1F466, 0x1F3FB), "Мальчик"}, {cp(0x1F467, 0x1F3FC), "Девочка"},
            {cp(0x1F9D1, 0x1F3FC), "Человек"}, {cp(0x1F468, 0x1F3FC), "Мужчина"}, {cp(0x1F9D4, 0x1F3FC), "Человек с бородой"},
            {cp(0x1F9D4, 0x1F3FB, 0x200D, 0x2642, 0xFE0F), "Мужчина с бородой"},
            {cp(0x1F9D4, 0x1F3FB, 0x200D, 0x2640, 0xFE0F), "Женщина с бородой"}, {cp(0x1F469, 0x1F3FC), "Женщина"},
            {cp(0x1F436), "Собака"}, {cp(0x1F42F), "Тигр"}, {cp(0x1F43B), "Медведь"}, {cp(0x1F43C), "Панда"},
            {cp(0x1F438), "Лягушка"}, {cp(0x1F383), "Тыква"}, {cp(0x1F33C), "Цветок"}, {cp(0x1F921), "Клоун"},
            {cp(0x1F916), "Робот"}};

    private Avatars() {}

    private static String cp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    static String name(Context context) {
        String name = prefs(context).getString(NAME_KEY, "");
        return name == null || name.trim().isEmpty() ? DEFAULT_NAME : name.trim();
    }

    /** The chosen emoji, or an empty string for the plain user icon. */
    static String avatar(Context context) {
        String avatar = prefs(context).getString(AVATAR_KEY, "");
        return avatar == null ? "" : avatar;
    }

    /** A white disc with the emoji on it. {@code box} is the drawable's own size, {@code disc} the disc's diameter
     *  inside it (centred): Home's user icon is a 30dp box whose visible ring is about 24dp. */
    static final class Face extends Drawable {
        private final String emoji;
        private final int box, disc;
        private final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

        Face(String emoji, int box, int disc) {
            this.emoji = emoji;
            this.box = box;
            this.disc = disc;
            white.setColor(0xFFFFFFFF);
            text.setTextAlign(Paint.Align.LEFT);
            text.setTextSize(disc * 0.64f);
        }

        /** The shift that puts the emoji's drawn pixels in the middle, found by drawing it once off screen. */
        private Float inkDx, inkDy;

        private void measureInk() {
            int side = Math.max(8, Math.round(disc * 2f));
            android.graphics.Bitmap scratch = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(scratch);
            Paint.FontMetrics fm = text.getFontMetrics();
            float baseline = side / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(emoji, side / 4f, baseline, text);
            int[] pixels = new int[side * side];
            scratch.getPixels(pixels, 0, side, 0, 0, side, side);
            int minX = side, maxX = -1, minY = side, maxY = -1;
            for (int y = 0; y < side; y++) {
                for (int x = 0; x < side; x++) {
                    if ((pixels[y * side + x] >>> 24) > 24) {
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }
            }
            scratch.recycle();
            if (maxX < 0) {
                inkDx = 0f;
                inkDy = -(fm.ascent + fm.descent) / 2f;
                return;
            }
            // Drawn at (side/4, baseline) its ink is centred on ((minX+maxX+1)/2, (minY+maxY+1)/2): the shift that puts
            // that point on the disc's middle.
            inkDx = side / 4f - (minX + maxX + 1) / 2f;
            inkDy = baseline - (minY + maxY + 1) / 2f;
        }

        @Override public int getIntrinsicWidth() { return box; }
        @Override public int getIntrinsicHeight() { return box; }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float cx = b.exactCenterX(), cy = b.exactCenterY();
            float scale = b.width() / (float) box;
            canvas.drawCircle(cx, cy, disc * scale / 2f, white);
            // Centre the emoji by the pixels it really covers (measured once), not by its advance width or its text
            // bounds: a colour emoji's bitmap has uneven padding, which left it a pixel off the disc's middle.
            if (inkDx == null) measureInk();
            canvas.save();
            canvas.scale(scale, scale, cx, cy);
            canvas.drawText(emoji, cx + inkDx, cy + inkDy, text);
            canvas.restore();
        }

        @Override public void setAlpha(int alpha) { white.setAlpha(alpha); text.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
