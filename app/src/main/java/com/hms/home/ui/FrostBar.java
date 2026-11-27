package com.hms.home.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Top bar that looks like frosted glass. While the screen is at the top it is completely clear; as you scroll down the
 * cards and posters passing under it show through, softly blurred, and the glass fades in GRADUALLY (not all at once).
 * The glass also melts away toward its bottom edge, so there is no hard line between the bar and the content.
 * Works on every Android version: the strip under the bar is drawn into a tiny bitmap and stretched back up (= blur).
 */
public class FrostBar extends LinearLayout {
    private static final int SCALE = 10;               // the bigger, the softer the blur
    private static final int BASE = 0xFFEEF2F7;        // colour of the screen background behind the content
    private static final float RAMP_DP = 110f;         // how far you scroll (dp) until the glass is fully there
    private static final int TINT = 0x66;              // white tint strength at full glass (lower = more of the movies shows)
    private static final float SOLID_UNTIL = 0.55f;    // top part of the bar that stays fully glass, the rest fades out

    private View source;
    private Bitmap bmp;
    private Canvas bc;
    private float amount;                              // 0 = clear bar (not scrolled) ... 1 = full glass
    private final Paint pBmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint pFill = new Paint();
    private final Paint pMask = new Paint();
    private final Rect dst = new Rect();

    public FrostBar(Context c) { super(c); init(); }
    public FrostBar(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        setWillNotDraw(false);
        pMask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    /** The scrolling content that goes under the bar. */
    public void attach(final View src) {
        source = src;
        src.getViewTreeObserver().addOnScrollChangedListener(new android.view.ViewTreeObserver.OnScrollChangedListener() {
            @Override public void onScrollChanged() { refresh(); }
        });
    }

    public void refresh() {
        if (source == null) return;
        float ramp = RAMP_DP * getResources().getDisplayMetrics().density;
        float t = Math.max(0f, Math.min(1f, source.getScrollY() / ramp));
        float a = t * t * (3f - 2f * t);               // smoothstep: eases in and out
        if (Math.abs(a - amount) > 0.002f) { amount = a; invalidate(); }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // vertical mask: opaque at the top, fading to nothing at the bottom edge
        pMask.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF},
                new float[]{0f, SOLID_UNTIL, 1f}, Shader.TileMode.CLAMP));
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
            // glass layer: blurred content + white tint, faded in by 'amount' and melted out toward the bottom
            int sc = c.saveLayerAlpha(0, 0, getWidth(), getHeight(), (int) (255 * amount), Canvas.ALL_SAVE_FLAG);
            c.drawBitmap(bmp, null, dst, pBmp);
            pFill.setColor((TINT << 24) | 0xFFFFFF);
            c.drawRect(dst, pFill);
            c.drawRect(dst, pMask);
            c.restoreToCount(sc);
        }
        super.dispatchDraw(c);
    }
}
