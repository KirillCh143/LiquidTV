package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;

/** tvOS-style stepped slider: a grey track, a white pill thumb and a dot under every step. Left/Right move the
 *  thumb one step (and are consumed at the ends, so focus never wanders off while adjusting). */
public final class StepSlider extends View {
    public interface Listener { void onStep(int index); }
    public interface Formatter { String format(int index); }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density = getResources().getDisplayMetrics().density;
    private int count = 2;
    private int index;
    private Listener listener;
    private Formatter formatter;
    /** The thumb is full size while the slider has focus and 40% smaller without it. */
    private static final float IDLE_SCALE = 0.6f;
    private float thumbScale = IDLE_SCALE;
    private android.animation.ValueAnimator scaleAnim;

    public StepSlider(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setClickable(true);
    }

    public void setSteps(int count, int index) {
        this.count = Math.max(2, count);
        this.index = Math.max(0, Math.min(this.count - 1, index));
        invalidate();
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        if (scaleAnim != null) scaleAnim.cancel();
        scaleAnim = android.animation.ValueAnimator.ofFloat(thumbScale, gainFocus ? 1f : IDLE_SCALE);
        scaleAnim.setDuration(140);
        scaleAnim.addUpdateListener(a -> {
            thumbScale = (Float) a.getAnimatedValue();
            invalidate();
        });
        scaleAnim.start();
    }

    public void setFormatter(Formatter formatter) { this.formatter = formatter; invalidate(); }

    public void setListener(Listener listener) { this.listener = listener; }

    private boolean move(int delta) {
        int next = Math.max(0, Math.min(count - 1, index + delta));
        if (next != index) {
            index = next;
            invalidate();
            if (listener != null) listener.onStep(index);
        }
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return move(rtl ? 1 : -1);
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return move(rtl ? -1 : 1);
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float thumbW = 46 * density, thumbH = 28 * density, trackH = 8 * density;
        // The track ends sit inside the view by this much, so the thumb (and its value) can overhang the ends.
        float inset = 30 * density;
        float left = getPaddingLeft() + inset, right = getWidth() - getPaddingRight() - inset;
        float cy = 54 * density;
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        // The dots (and the thumb, which sits on them) cover 98% of the track, centred: the end dots stay under it.
        float trackLeft = left, trackRight = right;
        float squeeze = (right - left) * 0.01f;
        left += squeeze;
        right -= squeeze;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x66FFFFFF);
        rect.set(trackLeft, cy - trackH / 2f, trackRight, cy + trackH / 2f);
        canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, paint);
        paint.setColor(0x99FFFFFF);
        for (int i = 0; i < count; i++) {
            float f = i / (float) (count - 1);
            float x = left + (rtl ? 1 - f : f) * (right - left);
            canvas.drawCircle(x, cy + thumbH / 2f + 9 * density, 1.6f * density, paint);
        }
        float f = index / (float) (count - 1);
        float x = left + (rtl ? 1 - f : f) * (right - left);
        paint.setColor(0xFFFFFFFF);
        float w = thumbW * thumbScale, h = thumbH * thumbScale;
        rect.set(x - w / 2f, cy - h / 2f, x + w / 2f, cy + h / 2f);
        canvas.drawRoundRect(rect, h / 2f, h / 2f, paint);
        if (formatter != null) {
            if (valueFace == null) valueFace = UiFont.get(getContext(), R.string.font_medium);
            paint.setTypeface(valueFace);
            paint.setFakeBoldText(true);
            paint.setTextSize(17 * density);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(0xFFFFFFFF);
            canvas.drawText(formatter.format(index), x, cy - thumbH / 2f - 10 * density, paint);
            paint.setTypeface(null);
            paint.setFakeBoldText(false);
            paint.setTextAlign(Paint.Align.LEFT);
        }
    }
    private android.graphics.Typeface valueFace;
}
