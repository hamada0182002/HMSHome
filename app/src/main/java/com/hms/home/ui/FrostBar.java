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
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.hms.home.R;

import java.util.ArrayList;

/**
 * Top bar = frosted glass + soft black shade.
 *  - At the top of the screen it is completely clear (dark icons on the light screen).
 *  - While you scroll down, the cards/posters passing under it show through, blurred (glass), the glass fades in
 *    gradually, and a black shade (darkest at the very top, melting away far below the icons) fades in with it.
 *  - On the dark shade the clock / text / icons turn white so they always stay readable.
 *  - Wi-Fi icon is blue while connected.
 * The bar view is made taller than its icons (EXTRA_DP) so the shade can run well below them.
 */
public class FrostBar extends LinearLayout {
    private static final int SCALE = 10;               // the bigger, the softer the blur
    private static final int BASE = 0xFF1A1D24;        // colour behind the content while blurring
    private static final float RAMP_DP = 110f;         // how far you scroll (dp) until the glass + shade are fully there
    private static final float EXTRA_DP = 110f;        // how far the shade runs below the icons
    private static final int SHADE_TOP = 0xE6000000;   // black, 90% at the very top
    private static final int SHADE_BAR = 0xB3000000;   // black, 70% at the bottom of the icons row
    private static final int SHADE_MID = 0x66000000;   // black, 40% half way down the extra part
    private static final int WHITE = 0xFFFFFFFF;
    private static final int INK = 0xFF1B1F2A;         // icon colour on the light screen
    private static final int WIFI_ON_LIGHT = 0xFF1F7AF5;   // connected, not scrolled
    private static final int WIFI_ON_DARK = 0xFF5AC8FF;    // connected, on the dark shade (brighter blue)

    private View source;
    private Bitmap bmp;
    private Canvas bc;
    private float amount;                              // 0 = clear bar (not scrolled) ... 1 = full glass + shade
    private int extra;                                 // px of shade below the icons row
    private boolean wifiOn, themed;
    private final Paint pBmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint pShade = new Paint();
    private final Paint pMask = new Paint();
    private final Rect dst = new Rect();
    private final ArrayList<TextView> texts = new ArrayList<>();
    private final ArrayList<Integer> textColors = new ArrayList<>();
    private boolean collected;

    public FrostBar(Context c) { super(c); init(); }
    public FrostBar(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        setWillNotDraw(false);
        pMask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        extra = Math.round(EXTRA_DP * getResources().getDisplayMetrics().density);
        setPadding(getPaddingLeft(), getPaddingTop(), getPaddingRight(), getPaddingBottom() + extra);
    }

    /** Height of the icons row only (what the scrolling content must be pushed below). */
    public int barHeight() { return Math.max(0, getHeight() - extra); }

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
        applyTheme();
    }

    public void refresh() {
        if (source == null) return;
        float ramp = RAMP_DP * getResources().getDisplayMetrics().density;
        float t = Math.max(0f, Math.min(1f, source.getScrollY() / ramp));
        float a = t * t * (3f - 2f * t);               // smoothstep: eases in and out
        if (Math.abs(a - amount) > 0.002f || !themed) {
            amount = a;
            applyTheme();
            invalidate();
        }
    }

    // ---------- colours: dark icons on the light screen -> white icons on the dark shade ----------

    private static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        int al = (int) (((a >>> 24)) * (1 - t) + ((b >>> 24)) * t);
        return (al << 24) | (r << 16) | (g << 8) | bl;
    }

    private void collect(View v) {
        if (v instanceof TextView) { texts.add((TextView) v); textColors.add(((TextView) v).getCurrentTextColor()); }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i));
        }
    }

    private void tintButton(int id, int normal, int focused) {
        View v = findViewById(id);
        if (!(v instanceof ImageView)) return;
        ((ImageView) v).setImageTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}}, new int[]{focused, normal}));
    }

    private void applyTheme() {
        themed = true;
        if (!collected) { collected = true; collect(this); }
        for (int i = 0; i < texts.size(); i++) texts.get(i).setTextColor(mix(textColors.get(i), WHITE, amount));
        int icon = mix(INK, WHITE, amount);
        int wifi = wifiOn ? mix(WIFI_ON_LIGHT, WIFI_ON_DARK, amount) : icon;
        tintButton(R.id.btnWifi, wifi, wifiOn ? WIFI_ON_LIGHT : INK);      // focused = white circle behind it -> dark / blue icon
        tintButton(R.id.btnDevice, icon, INK);
        tintButton(R.id.btnApp, icon, INK);
        View usb = findViewById(R.id.icUsb);
        if (usb instanceof ImageView) ((ImageView) usb).setColorFilter(icon);
        View div = findViewById(R.id.wDivider);
        if (div != null) div.setBackgroundColor(mix(0x33000000, 0x66FFFFFF, amount));
    }

    // ---------- drawing ----------

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        int bar = Math.max(1, h - extra);
        // glass mask: opaque over the top half of the icons row, melting out by its bottom
        pMask.setShader(new LinearGradient(0, 0, 0, bar,
                new int[]{WHITE, WHITE, 0x00FFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        // black shade: dark at the top, still there under the icons, melting away far below them
        float f = Math.min(0.95f, bar / (float) Math.max(1, h));
        pShade.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{SHADE_TOP, SHADE_BAR, SHADE_MID, 0x00000000},
                new float[]{0f, f, f + (1f - f) * 0.5f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override protected void dispatchDraw(Canvas c) {
        int bar = barHeight();
        if (amount > 0.01f && source != null && getWidth() > 0 && bar > 0) {
            int w = Math.max(1, getWidth() / SCALE), h = Math.max(1, bar / SCALE);
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
            // 1) glass: the blurred content, faded in by 'amount' and melted out toward the bottom of the icons row
            dst.set(0, 0, getWidth(), bar);
            int sc = c.saveLayerAlpha(0, 0, getWidth(), bar, (int) (255 * amount), Canvas.ALL_SAVE_FLAG);
            c.drawBitmap(bmp, null, dst, pBmp);
            c.drawRect(dst, pMask);
            c.restoreToCount(sc);
            // 2) black shade over it, running well below the icons
            pShade.setAlpha((int) (255 * amount));
            c.drawRect(0, 0, getWidth(), getHeight(), pShade);
        }
        super.dispatchDraw(c);
    }
}
