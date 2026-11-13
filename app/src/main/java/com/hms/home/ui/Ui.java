package com.hms.home.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import com.hms.home.R;

public final class Ui {
    public static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    public static TextView label(Context c, String s, float sp, int colorRes, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(ContextCompat.getColor(c, colorRes));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Focusable button with bronze focus. */
    public static TextView button(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 18), dp(c, 10), dp(c, 18), dp(c, 10));
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackgroundResource(R.drawable.bg_button);
        t.setTextColor(ContextCompat.getColorStateList(c, R.color.btn_text));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(c, 10), 0);
        t.setLayoutParams(lp);
        return t;
    }

    public static TextView navItem(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 20), dp(c, 8), dp(c, 20), dp(c, 8));
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackgroundResource(R.drawable.bg_nav);
        t.setTextColor(ContextCompat.getColorStateList(c, R.color.btn_text));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(c, 6), 0);
        t.setLayoutParams(lp);
        return t;
    }

    public static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%02d:%02d", m, sec);
    }
}
