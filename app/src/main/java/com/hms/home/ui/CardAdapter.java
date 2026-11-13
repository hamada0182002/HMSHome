package com.hms.home.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import com.hms.home.R;
import com.hms.home.data.ImageLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * Cards of the new launcher home (hero banners, app tiles, shortcut tiles).
 * Sizes are fractions of the screen so the look is the same on any TV resolution / density.
 */
public class CardAdapter extends RecyclerView.Adapter<CardAdapter.VH> {
    public static final int HERO = 0, APP = 1, SHORTCUT = 2;

    public static class Card {
        public String title = "", sub = "", badge = "", img = "";
        public Drawable banner, icon;       // app banner (TV) / app icon
        public int iconRes;                 // vector icon for shortcut cards
        public int c1, c2;                  // gradient colours (0 = none)
        public Object data;
        public Card(String title, Object data) { this.title = title; this.data = data; }
        public Card sub(String s) { sub = s; return this; }
        public Card badge(String s) { badge = s; return this; }
        public Card img(String s) { img = s; return this; }
        public Card icon(Drawable d) { icon = d; return this; }
        public Card banner(Drawable d) { banner = d; return this; }
        public Card vec(int res) { iconRes = res; return this; }
        public Card colors(int a, int b) { c1 = a; c2 = b; return this; }
    }

    public interface Listener {
        void click(Card c);
        void menu(Card c);
    }

    private final int mode;
    private final Listener l;
    private List<Card> list = new ArrayList<>();

    public CardAdapter(int mode, Listener l) { this.mode = mode; this.l = l; }

    public void set(List<Card> c) { list = c; notifyDataSetChanged(); }
    public int size() { return list.size(); }

    // ---- sizes ----
    public static int width(Context c, int mode) {
        int w = Ui.sw(c);
        return mode == HERO ? Math.round(w * 0.40f) : mode == APP ? Math.round(w * 0.158f) : Math.round(w * 0.165f);
    }
    public static int height(Context c, int mode) {
        int w = width(c, mode);
        return mode == HERO ? Math.round(w * 0.60f) : mode == APP ? Math.round(w * 0.56f) : Math.round(w * 0.50f);
    }

    class VH extends RecyclerView.ViewHolder {
        final FrameLayout root; final ImageView img, ic; final TextView title, sub, badge; final View shade;
        VH(FrameLayout v, ImageView img, ImageView ic, TextView title, TextView sub, TextView badge, View shade) {
            super(v);
            root = v; this.img = img; this.ic = ic; this.title = title; this.sub = sub; this.badge = badge; this.shade = shade;
            v.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) { int p = getAdapterPosition(); if (p >= 0) l.click(list.get(p)); } });
            v.setOnLongClickListener(new View.OnLongClickListener() { @Override public boolean onLongClick(View x) { int p = getAdapterPosition(); if (p >= 0) l.menu(list.get(p)); return true; } });
            v.setOnKeyListener(new View.OnKeyListener() {
                @Override public boolean onKey(View x, int code, KeyEvent e) {
                    if (code == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) { int p = getAdapterPosition(); if (p >= 0) l.menu(list.get(p)); return true; }
                    return code == KeyEvent.KEYCODE_MENU;
                }
            });
            v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View x, boolean f) {
                    float sc = f ? (mode == HERO ? 1.05f : 1.12f) : 1f;
                    x.animate().scaleX(sc).scaleY(sc).setDuration(120).start();
                    if (android.os.Build.VERSION.SDK_INT >= 21) x.setTranslationZ(f ? 24f : 0f);
                }
            });
        }
    }

    @Override public void onAttachedToRecyclerView(RecyclerView rv) { rv.setClipChildren(false); }

    @Override public VH onCreateViewHolder(ViewGroup p, int type) {
        Context c = p.getContext();
        int w = width(c, mode), h = height(c, mode);
        int radius = Ui.dp(c, mode == HERO ? 16 : 12);

        FrameLayout root = new FrameLayout(c);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(w, h);
        int m = Ui.dp(c, mode == HERO ? 8 : 6);
        lp.setMargins(m, m, m, m);
        root.setLayoutParams(lp);
        root.setFocusable(true);
        root.setClickable(true);
        root.setLongClickable(true);
        root.setClipChildren(true);
        Ui.clip(root, radius, false);

        ImageView img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(img, new FrameLayout.LayoutParams(-1, -1));

        // bottom shade (hero) so the title is readable on any picture
        View shade = new View(c);
        GradientDrawable sg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xE6000000});
        shade.setBackground(sg);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, Math.round(h * 0.62f), Gravity.BOTTOM);
        root.addView(shade, sp);

        ImageView ic = new ImageView(c);
        ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(ic, new FrameLayout.LayoutParams(-1, -1));

        TextView title = new TextView(c);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setMaxLines(mode == HERO ? 2 : 1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        TextView sub = new TextView(c);
        sub.setMaxLines(1);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        TextView badge = new TextView(c);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextSize(11);
        badge.setTextColor(0xFF1B1F2A);
        badge.setBackground(Ui.rounded(0xF2FFFFFF, Ui.dp(c, 6)));
        badge.setPadding(Ui.dp(c, 8), Ui.dp(c, 3), Ui.dp(c, 8), Ui.dp(c, 3));
        badge.setSingleLine(true);

        if (mode == HERO) {
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 14), Ui.dp(c, 12));
            title.setTextSize(17); title.setTextColor(Color.WHITE);
            sub.setTextSize(13); sub.setTextColor(0xFFD9DEE8);
            col.addView(title); col.addView(sub);
            root.addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            bp.setMargins(0, Ui.dp(c, 10), Ui.dp(c, 10), 0);
            root.addView(badge, bp);
        } else if (mode == APP) {
            // used only when there is no TV banner: label under the icon
            title.setTextSize(12); title.setTextColor(0xFF1B1F2A); title.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            tp.setMargins(Ui.dp(c, 6), 0, Ui.dp(c, 6), Ui.dp(c, 6));
            root.addView(title, tp);
        } else {
            title.setTextSize(16); title.setTextColor(Color.WHITE);
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
            tp.setMargins(Ui.dp(c, 14), 0, Ui.dp(c, 10), Ui.dp(c, 12));
            root.addView(title, tp);
        }

        // focus ring on top (follows the parent's focus state)
        View ring = new View(c);
        ring.setBackgroundResource(R.drawable.bg_focus_ring);
        ring.setDuplicateParentStateEnabled(true);
        root.addView(ring, new FrameLayout.LayoutParams(-1, -1));

        return new VH(root, img, ic, title, sub, badge, shade);
    }

    @Override public void onBindViewHolder(VH vh, int pos) {
        Card k = list.get(pos);
        Context c = vh.root.getContext();
        int w = width(c, mode), h = height(c, mode);
        vh.title.setText(k.title);
        vh.sub.setText(k.sub);
        vh.sub.setVisibility(k.sub == null || k.sub.isEmpty() ? View.GONE : View.VISIBLE);
        boolean hasBadge = k.badge != null && !k.badge.isEmpty();
        vh.badge.setText(k.badge);
        vh.badge.setVisibility(mode == HERO && hasBadge ? View.VISIBLE : View.GONE);
        vh.ic.setImageDrawable(null);
        vh.ic.setPadding(0, 0, 0, 0);
        vh.ic.clearColorFilter();

        if (k.c1 != 0 || k.c2 != 0) {
            // gradient card (shortcuts, "all apps", hero fallbacks)
            vh.root.setBackground(Ui.gradient(k.c1, k.c2, Ui.dp(c, mode == HERO ? 16 : 12)));
            vh.img.setImageDrawable(null); vh.img.setTag(null);
            vh.shade.setVisibility(View.GONE);
            if (k.iconRes != 0) {
                vh.ic.setImageResource(k.iconRes);
                vh.ic.setColorFilter(0xFFFFFFFF);
                int s = Math.round(h * (mode == SHORTCUT ? 0.34f : 0.40f));
                vh.ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
                if (mode == SHORTCUT) {
                    // icon on the right, label bottom-left
                    vh.ic.setLayoutParams(new FrameLayout.LayoutParams(s, s, Gravity.TOP | Gravity.END));
                    ((FrameLayout.LayoutParams) vh.ic.getLayoutParams()).setMargins(0, Ui.dp(c, 12), Ui.dp(c, 14), 0);
                } else {
                    vh.ic.setLayoutParams(new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
                }
            }
            vh.title.setTextColor(Color.WHITE);
            vh.title.setVisibility(View.VISIBLE);
            return;
        }

        vh.root.setBackgroundColor(mode == APP ? 0xFFFFFFFF : 0xFF2A3140);
        if (mode == HERO) {
            vh.shade.setVisibility(View.VISIBLE);
            vh.ic.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
            ImageLoader.load(vh.img, k.img, w, h);
            return;
        }
        // APP tile
        vh.shade.setVisibility(View.GONE);
        vh.img.setTag(null);
        if (k.banner != null) {
            vh.img.setImageDrawable(k.banner);                 // TV banner fills the tile (like Netflix / Prime in the design)
            vh.title.setVisibility(View.GONE);
        } else {
            vh.img.setImageDrawable(null);
            int s = Math.round(h * 0.50f);
            FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(s, s, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            ip.setMargins(0, Math.round(h * 0.10f), 0, 0);
            vh.ic.setLayoutParams(ip);
            vh.ic.setImageDrawable(k.icon);
            vh.title.setVisibility(View.VISIBLE);
            vh.title.setTextColor(0xFF1B1F2A);
        }
    }

    @Override public int getItemCount() { return list.size(); }
}
