package com.gathin.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

/**
 * Local patch: the menu that opens when OK is held on an app, on Home and in the apps grid. A large rounded glass
 * panel; the row that has focus is filled with the accent colour. It follows the context menu's own colour scheme
 * (Config.PART_CONTEXT: light, dark, or its own choice in the mixed scheme) and the current backdrop:
 * Monochrome is a flat panel, Frosted ice blurs the wallpaper behind it, Liquid glass adds the refraction at the edges.
 */
final class TileMenu {
    /** The focused row, a coral red. */
    static final int ACCENT = 0xFFF24A5C;
    /** Uninstall, the same red as the Power label. */
    static final int DANGER = 0xFFED3B30;
    static final float RADIUS_DP = 28f;

    final FrameLayout panel;
    /** Where the wallpaper slice goes (MainActivity.applyGlass); null with Monochrome or when no wallpaper is available. */
    final ImageView glass;
    private final LinearLayout rows;
    private final Context context;
    private final boolean dark;
    private final float density;

    /**
     * @param backdrop Config.BACKDROP_COLOR, BACKDROP_BLUR or BACKDROP_LIQUID, as it is really drawn
     * @param withGlass the caller draws a wallpaper slice behind the panel; if not, a firmer wash stands in for it
     */
    TileMenu(Context context, String backdrop, boolean withGlass) {
        this.context = context;
        this.density = context.getResources().getDisplayMetrics().density;
        this.dark = Config.dark(context, Config.PART_CONTEXT);
        final float radius = RADIUS_DP * density;
        panel = new SizedByRows(context);
        // No elevation: the popup window clips its shadow to a rectangle, a dark box around the rounded corners.
        panel.setClipToOutline(true);
        panel.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        boolean flat = Config.BACKDROP_COLOR.equals(backdrop);
        if (withGlass && !flat) {
            glass = new ImageView(context);
            glass.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            panel.addView(glass, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            glass = null;
        }
        View wash = new View(context);
        wash.setBackground(washDrawable(backdrop, withGlass, radius));
        panel.addView(wash, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(8 * density);
        rows.setPadding(pad, pad, pad, pad);
        panel.addView(rows, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** A panel as big as its rows (the last child) alone: the glass and wash layers fill it, and must not size it. */
    private static final class SizedByRows extends FrameLayout {
        SizedByRows(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            View content = getChildAt(getChildCount() - 1);
            content.measure(getChildMeasureSpec(widthSpec, 0, content.getLayoutParams().width),
                    getChildMeasureSpec(heightSpec, 0, content.getLayoutParams().height));
            int width = MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY
                    ? MeasureSpec.getSize(widthSpec) : content.getMeasuredWidth();
            int height = content.getMeasuredHeight();
            if (content.getMeasuredWidth() != width) {
                content.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }
            for (int i = 0; i < getChildCount() - 1; i++) {
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(width, height);
        }
    }

    private Drawable washDrawable(String backdrop, boolean withGlass, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(radius);
        boolean flat = Config.BACKDROP_COLOR.equals(backdrop);
        int color;
        if (flat) color = dark ? 0xF2202024 : 0xF5F4F4F7;
        else if (withGlass) color = dark ? 0x70000000 : 0x78FFFFFF;
        else color = dark ? 0xF21C1C20 : 0xF4F4F4F7;   // no wallpaper behind (the apps grid): nearly solid, tiles must not show through
        d.setColor(color);
        if (flat) return GlassRim.solidOver(d, radius, density);
        // Frosted ice: the gradient outline. Liquid glass with a wallpaper slice draws its own edge.
        boolean liquidWithGlass = Config.BACKDROP_LIQUID.equals(backdrop) && withGlass;
        return liquidWithGlass ? d : GlassRim.over(d, radius, density);
    }

    private int textColor() {
        return dark ? Color.WHITE : 0xFF1C1212;
    }

    /** One row: icon and name. OK-up right after the menu opened (the end of the long press) must not activate it. */
    View addRow(PopupWindow popup, int title, int iconRes, boolean danger, Runnable action) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Math.round(46 * density));
        int side = Math.round(14 * density);
        row.setPadding(side, 0, side, 0);
        row.setFocusable(true);
        row.setClickable(true);
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(ACCENT);
        focused.setCornerRadius(18 * density);
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        row.setBackground(bg);
        ColorStateList colors = new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{Color.WHITE, danger ? DANGER : textColor()});
        if (iconRes != 0) {
            ImageView icon = new ImageView(context);
            icon.setImageResource(iconRes);
            icon.setImageTintList(colors);
            icon.setDuplicateParentStateEnabled(true);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            int size = Math.round(22 * density);
            row.addView(icon, new LinearLayout.LayoutParams(size, size));
        }
        TextView text = new TextView(context);
        text.setText(title);
        text.setTextSize(17);
        text.setSingleLine(true);
        text.setTextColor(colors);
        text.setTypeface(UiFont.themed(context, R.attr.uiFontMedium, R.string.font_medium));
        text.setDuplicateParentStateEnabled(true);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.setMarginStart(iconRes != 0 ? Math.round(12 * density) : 0);
        row.addView(text, textParams);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pressed[0] = true;
                return true;
            }
            boolean activate = pressed[0];
            pressed[0] = false;
            if (activate) {
                popup.dismiss();
                action.run();
            }
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(2 * density);
        rows.addView(row, lp);
        return row;
    }

    /** A thin line between two groups of rows. */
    void addDivider() {
        View line = new View(context);
        line.setBackgroundColor(dark ? 0x33FFFFFF : 0x24000000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(density)));
        int side = Math.round(14 * density), vertical = Math.round(5 * density);
        lp.setMargins(side, vertical, side, vertical);
        rows.addView(line, lp);
    }

    View firstRow() {
        for (int i = 0; i < rows.getChildCount(); i++) {
            if (rows.getChildAt(i).isFocusable()) return rows.getChildAt(i);
        }
        return null;
    }
}
