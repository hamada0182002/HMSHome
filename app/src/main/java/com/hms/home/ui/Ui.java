package com.hms.home.ui;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import com.hms.home.R;

public final class Ui {
    public static int sw(Context c) { return c.getResources().getDisplayMetrics().widthPixels; }
    public static int sh(Context c) { return c.getResources().getDisplayMetrics().heightPixels; }

    /** Rounded solid shape. */
    public static android.graphics.drawable.GradientDrawable rounded(int color, float radiusPx) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** Rounded two-colour diagonal gradient. */
    public static android.graphics.drawable.GradientDrawable gradient(int c1, int c2, float radiusPx) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{c1, c2});
        g.setCornerRadius(radiusPx);
        return g;
    }

    public static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    public static TextView label(Context c, String s, float sp, int colorRes, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(ContextCompat.getColor(c, colorRes));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Hides the Android status + navigation bars (back / home icons) so the launcher is truly full screen. */
    public static void immersive(Window w) {
        w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /** Rounds the corners of a view (or makes it a circle) including its image. */
    public static void clip(View v, final int radiusPx, final boolean oval) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                if (oval) o.setOval(0, 0, view.getWidth(), view.getHeight());
                else o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        });
        v.setClipToOutline(true);
    }

    /** Focusable pill button (white when focused). */
    public static TextView button(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(17);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 22), dp(c, 11), dp(c, 22), dp(c, 11));
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackgroundResource(R.drawable.bg_button);
        t.setTextColor(ContextCompat.getColorStateList(c, R.color.btn_text));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(c, 10), 0);
        t.setLayoutParams(lp);
        return t;
    }

    /** Top menu item (inside the rounded pill). */
    public static TextView navItem(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 22), dp(c, 10), dp(c, 22), dp(c, 10));
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackgroundResource(R.drawable.bg_nav);
        t.setTextColor(ContextCompat.getColorStateList(c, R.color.btn_text));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(c, 2), 0, dp(c, 2), 0);
        t.setLayoutParams(lp);
        return t;
    }

    /** Round search button for the top menu. */
    public static TextView navIcon(Context c) {
        TextView t = navItem(c, "");
        t.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        t.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0);
        t.setContentDescription("Search");
        return t;
    }

    public static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%02d:%02d", m, sec);
    }
}
