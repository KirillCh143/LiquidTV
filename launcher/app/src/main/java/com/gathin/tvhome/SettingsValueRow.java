package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.widget.TextView;

/** A settings row whose text is "Label: value": the label sits at the start and the value, dimmer, at the end
 *  (tvOS Launcher's settings look). Text without ": " draws as a plain TextView. */
public final class SettingsValueRow extends TextView {
    private static final String SEPARATOR = ": ";

    public SettingsValueRow(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        CharSequence text = getText();
        int cut = text == null ? -1 : text.toString().indexOf(SEPARATOR);
        if (cut < 0) {
            super.onDraw(canvas);
            return;
        }
        String label = text.subSequence(0, cut).toString();
        String value = text.subSequence(cut + SEPARATOR.length(), text.length()).toString();
        Paint paint = getPaint();
        int color = getCurrentTextColor();
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        float start = getPaddingStart();
        float end = getWidth() - getPaddingEnd();
        float room = end - start;
        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseline = (getHeight() - (fm.descent - fm.ascent)) / 2f - fm.ascent;
        float gap = 16 * getResources().getDisplayMetrics().density;
        float labelWidth = Math.min(paint.measureText(label), room * 0.7f);
        String shownLabel = TextUtils.ellipsize(label, new android.text.TextPaint(paint), labelWidth,
                TextUtils.TruncateAt.END).toString();
        String shownValue = TextUtils.ellipsize(value, new android.text.TextPaint(paint),
                Math.max(0, room - labelWidth - gap), TextUtils.TruncateAt.END).toString();
        paint.setColor(color);
        paint.setTextAlign(rtl ? Paint.Align.RIGHT : Paint.Align.LEFT);
        canvas.drawText(shownLabel, rtl ? end : start, baseline, paint);
        paint.setColor((color & 0x00FFFFFF) | 0xCC000000);
        paint.setTextAlign(rtl ? Paint.Align.LEFT : Paint.Align.RIGHT);
        // The value is regular weight, only the label is bold.
        android.graphics.Typeface label_face = paint.getTypeface();
        boolean fake = paint.isFakeBoldText();
        paint.setTypeface(UiFont.get(getContext(), R.string.font_regular));
        paint.setFakeBoldText(false);
        canvas.drawText(shownValue, rtl ? start : end, baseline, paint);
        paint.setTypeface(label_face);
        paint.setFakeBoldText(fake);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(color);
    }
}
