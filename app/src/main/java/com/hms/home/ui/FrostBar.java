package com.hms.home.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Top bar that looks like frosted glass: when the screen is scrolled, whatever is under the bar
 * (cards, posters) shows through it softly blurred. Works on every Android version: the strip under
 * the bar is drawn into a tiny bitmap and stretched back up, which is what blurs it.
 */
public class FrostBar extends LinearLayout {
    private static final int SCALE = 10;               // the bigger, the softer
    private static final int BASE = 0xFFEEF2F7;        // colour of the screen background behind the content
    private View source;
    private Bitmap bmp;
    private Canvas bc;
    private float amount;                              // 0 = clear bar (not scrolled) ... 1 = full glass
    private final Paint pBmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint pFill = new Paint();
    private final Rect dst = new Rect();

    public FrostBar(Context c) { super(c); init(); }
    public FrostBar(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() { setWillNotDraw(false); }

    /** The scrolling content that goes under the bar. */
    public void attach(final View src) {
        source = src;
        src.getViewTreeObserver().addOnScrollChangedListener(new android.view.ViewTreeObserver.OnScrollChangedListener() {
            @Override public void onScrollChanged() { refresh(); }
        });
    }

    public void refresh() {
        if (source == null) return;
        float a = Math.min(1f, source.getScrollY() / Math.max(1f, getHeight() * 0.5f));
        amount = a;
        invalidate();
    }

    @Override protected void dispatchDraw(Canvas c) {
        if (amount > 0.01f && source != null && getWidth() > 0 && getHeight() > 0) {
            int w = Math.max(1, getWidth() / SCALE), h = Math.max(1, getHeight() / SCALE);
            if (bmp == null || bmp.getWidth() != w || bmp.getHeight() != h) {
                if (bmp != null) bmp.recycle();
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                bc = new Canvas(bmp);
            }
            bmp.eraseColor(BASE);
            bc.save();
            bc.scale(1f / SCALE, 1f / SCALE);
            bc.translate(-getLeft(), -getTop());
            try { source.draw(bc); } catch (Throwable ignored) { }
            bc.restore();
            dst.set(0, 0, getWidth(), getHeight());
            c.drawBitmap(bmp, null, dst, pBmp);
            pFill.setColor(((int) (0xA6 * amount) << 24) | 0xFFFFFF);          // white glass tint
            c.drawRect(dst, pFill);
            pFill.setColor(((int) (0x1A * amount) << 24));                       // thin edge line
            c.drawRect(0, getHeight() - 1, getWidth(), getHeight(), pFill);
        }
        super.dispatchDraw(c);
    }
}
