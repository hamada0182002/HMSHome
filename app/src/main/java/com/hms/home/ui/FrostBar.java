package com.hms.home.ui;

import android.content.Context;
import android.content.res.ColorStateList;
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
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.hms.home.R;

/**
 * Top bar = frosted white glass (like the web dashboard header): whatever scrolls under it shows through,
 * strongly blurred, with a light white tint. Clear at the top of the screen, fades in gradually while scrolling.
 * Icons/text stay dark. The Wi-Fi icon is blue while connected.
 * Works on every Android version: the strip under the bar is drawn into a tiny bitmap and stretched back up (= blur).
 */
public class FrostBar extends LinearLayout {
    private static final int SCALE = 12;               // the bigger, the softer the blur
    private static final int BASE = 0xFFEEF2F7;        // colour of the screen background behind the content
    private static final float RAMP_DP = 90f;          // how far you scroll (dp) until the glass is fully there
    private static final int TINT = 0x8C;              // white tint at full glass (~55%): lower = more of the content shows
    private static final float SOLID_UNTIL = 0.8f;     // the lowest part of the bar melts out softly
    private static final int INK = 0xFF1B1F2A;
    private static final int WIFI_ON = 0xFF1F7AF5;     // connected = blue

    private View source;
    private Bitmap bmp, bmp2;
    private Canvas bc, bc2;
    private float amount;                              // 0 = clear bar (not scrolled) ... 1 = full glass
    private boolean wifiOn, themed;
    private final Paint pBmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint pFill = new Paint();
    private final Paint pMask = new Paint();
    private final Rect dst = new Rect();
    private final Rect tmp = new Rect();

    public FrostBar(Context c) { super(c); init(); }
    public FrostBar(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        setWillNotDraw(false);
        pMask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    /** Height of the bar (what the scrolling content must be pushed below). */
    public int barHeight() { return getHeight(); }

    /** The scrolling content that goes under the bar. */
    public void attach(final View src) {
        source = src;
        src.getViewTreeObserver().addOnScrollChangedListener(new android.view.ViewTreeObserver.OnScrollChangedListener() {
            @Override public void onScrollChanged() { refresh(); }
        });
    }

    /** Wi-Fi connected -> blue icon. */
    public void setWifiOn(boolean on) {
        if (wifiOn == on && themed) return;
        wifiOn = on;
        applyIcons();
    }

    public void refresh() {
        if (source == null) return;
        if (!themed) applyIcons();
        float ramp = RAMP_DP * getResources().getDisplayMetrics().density;
        float t = Math.max(0f, Math.min(1f, source.getScrollY() / ramp));
        float a = t * t * (3f - 2f * t);               // smoothstep: eases in and out
        if (Math.abs(a - amount) > 0.002f) { amount = a; invalidate(); }
    }

    private void tintButton(int id, int normal, int focused) {
        View v = findViewById(id);
        if (!(v instanceof ImageView)) return;
        ((ImageView) v).setImageTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}}, new int[]{focused, normal}));
    }

    private void applyIcons() {
        themed = true;
        tintButton(R.id.btnWifi, wifiOn ? WIFI_ON : INK, wifiOn ? WIFI_ON : INK);
        tintButton(R.id.btnDevice, INK, INK);
        tintButton(R.id.btnApp, INK, INK);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        pMask.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF}, new float[]{0f, SOLID_UNTIL, 1f}, Shader.TileMode.CLAMP));
    }

    @Override protected void dispatchDraw(Canvas c) {
        int bar = getHeight();
        if (amount > 0.01f && source != null && getWidth() > 0 && bar > 0) {
            int w = Math.max(2, getWidth() / SCALE), h = Math.max(2, bar / SCALE);
            if (bmp == null || bmp.getWidth() != w || bmp.getHeight() != h) {
                if (bmp != null) bmp.recycle();
                if (bmp2 != null) bmp2.recycle();
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                bmp2 = Bitmap.createBitmap(Math.max(1, w / 2), Math.max(1, h / 2), Bitmap.Config.ARGB_8888);
                bc = new Canvas(bmp);
                bc2 = new Canvas(bmp2);
            }
            bmp.eraseColor(BASE);
            bc.save();
            bc.scale(1f / SCALE, 1f / SCALE);
            bc.translate(-getLeft(), -getTop());
            try { source.draw(bc); } catch (Throwable ignored) { }
            bc.restore();
            // second blur pass: shrink once more and stretch back, so the blur is smooth instead of blocky
            tmp.set(0, 0, bmp2.getWidth(), bmp2.getHeight());
            bc2.drawBitmap(bmp, null, tmp, pBmp);
            tmp.set(0, 0, w, h);
            bmp.eraseColor(BASE);
            bc.drawBitmap(bmp2, null, tmp, pBmp);

            dst.set(0, 0, getWidth(), bar);
            int sc = c.saveLayerAlpha(0, 0, getWidth(), bar, (int) (255 * amount), Canvas.ALL_SAVE_FLAG);
            c.drawBitmap(bmp, null, dst, pBmp);                     // blurred content
            pFill.setColor((TINT << 24) | 0xFFFFFF);
            c.drawRect(dst, pFill);                                 // white glass tint
            c.drawRect(dst, pMask);                                 // melt out softly at the bottom edge
            c.restoreToCount(sc);
        }
        super.dispatchDraw(c);
    }
}
