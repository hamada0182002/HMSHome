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
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import com.hms.home.R;
import java.util.ArrayList;
import java.util.List;

/**
 * Cards of the home: glass shortcut cards (Live / Movies / Series / Quran) and the favorite-app tiles.
 * Sizes are fractions of the screen so the look is the same on any TV resolution / density.
 */
public class CardAdapter extends RecyclerView.Adapter<CardAdapter.VH> {
    public static final int APP = 1, SHORTCUT = 2;
    private static final int YELLOW = 0xFFFFC21A;

    public static class Card {
        public String title = "", sub = "";
        public Drawable banner, icon;       // app banner (TV) / app icon
        public int iconRes;                 // vector icon for shortcut cards
        public int c1, c2;                  // gradient colours (0 = none)
        public Object data;
        public Card(String title, Object data) { this.title = title; this.data = data; }
        public Card sub(String s) { sub = s; return this; }
        public Card icon(Drawable d) { icon = d; return this; }
        public Card banner(Drawable d) { banner = d; return this; }
        public Card vec(int res) { iconRes = res; return this; }
        public Card colors(int a, int b) { c1 = a; c2 = b; return this; }
    }

    public interface Listener {
        void click(Card c);
        void menu(Card c);
        /** The card got the cursor (used by the home to change the big picture behind). */
        default void focus(Card c) { }
    }

    private final int mode;
    private final Listener l;
    private List<Card> list = new ArrayList<>();

    public CardAdapter(int mode, Listener l) { this.mode = mode; this.l = l; }

    public void set(List<Card> c) { list = c; notifyDataSetChanged(); }
    public void changed(int pos) { notifyItemChanged(pos); }
    public int size() { return list.size(); }

    // ---- sizes ----
    private static final int SHORTCUT_GAP_DP = 14;      // each side of a shortcut card

    public static int width(Context c, int mode) {
        int w = Ui.sw(c);
        if (mode == APP) return Math.round(w * 0.158f);
        float byShare = w * 0.21f;
        float byFit = (w - Ui.dp(c, 64) - Ui.dp(c, 8 * SHORTCUT_GAP_DP)) / 4f;   // four cards + gaps always fit on the screen
        return Math.round(Math.min(byShare, byFit));
    }
    public static int height(Context c, int mode) {
        int w = width(c, mode);
        return mode == APP ? Math.round(w * 0.56f) : Math.round(w * 0.64f);
    }

    class VH extends RecyclerView.ViewHolder {
        final FrameLayout root; final ImageView img, ic; final TextView title, sub;
        View tab, glow; Drawable glassN, glassF;      // shortcut cards only
        VH(FrameLayout v, ImageView img, ImageView ic, TextView title, TextView sub) {
            super(v);
            root = v; this.img = img; this.ic = ic; this.title = title; this.sub = sub;
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
                    if (mode == SHORTCUT) {
                        // lifted + slightly tilted card, yellow tab with a soft glow on top, icon pops
                        float lift = -Ui.dp(x.getContext(), 14);
                        x.animate().scaleX(f ? 1.1f : 1f).scaleY(f ? 1.1f : 1f).translationY(f ? lift : 0f).rotation(f ? -3f : 0f).setDuration(200).start();
                        if (glassN != null) x.setBackground(f ? glassF : glassN);
                        if (tab != null) tab.animate().alpha(f ? 1f : 0f).setDuration(200).start();
                        if (glow != null) glow.animate().alpha(f ? 1f : 0f).setDuration(200).start();
                        ic.animate().scaleX(f ? 1.14f : 1f).scaleY(f ? 1.14f : 1f).setDuration(220).start();
                    } else {
                        x.animate().scaleX(f ? 1.12f : 1f).scaleY(f ? 1.12f : 1f).setDuration(120).start();
                    }
                    if (android.os.Build.VERSION.SDK_INT >= 21) x.setTranslationZ(f ? 24f : 0f);
                    if (f) { int p = getAdapterPosition(); if (p >= 0) l.focus(list.get(p)); }
                }
            });
        }
    }

    @Override public void onAttachedToRecyclerView(RecyclerView rv) { rv.setClipChildren(false); }

    private static GradientDrawable glass(Context c, int top, int bottom, int strokeColor, float strokeDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        g.setCornerRadius(Ui.dp(c, 22));
        g.setStroke(Math.max(1, Ui.dp(c, strokeDp)), strokeColor);
        return g;
    }

    @Override public VH onCreateViewHolder(ViewGroup p, int type) {
        Context c = p.getContext();
        int w = width(c, mode), h = height(c, mode);
        FrameLayout root = new FrameLayout(c);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(w, h);
        int m = Ui.dp(c, mode == SHORTCUT ? 12 : 6);
        int mx = mode == SHORTCUT ? Ui.dp(c, SHORTCUT_GAP_DP) : m;
        lp.setMargins(mx, m, mx, m);
        root.setLayoutParams(lp);
        root.setFocusable(true);
        root.setClickable(true);
        root.setLongClickable(true);
        root.setClipChildren(true);
        Ui.clip(root, Ui.dp(c, mode == SHORTCUT ? 22 : 12), false);

        ImageView img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(img, new FrameLayout.LayoutParams(-1, -1));

        ImageView ic = new ImageView(c);
        ic.setScaleType(ImageView.ScaleType.FIT_CENTER);

        TextView title = new TextView(c);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        TextView sub = new TextView(c);
        sub.setMaxLines(2);
        sub.setEllipsize(TextUtils.TruncateAt.END);

        if (mode == SHORTCUT) {
            // title top-left, big icon on the right, small line at the bottom-left (like the playbox design)
            int s = Math.round(h * 0.52f);
            FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(s, s, Gravity.END | Gravity.CENTER_VERTICAL);
            ip.setMargins(0, Ui.dp(c, 10), Ui.dp(c, 16), 0);
            root.addView(ic, ip);

            title.setTextSize(22); title.setTextColor(Color.WHITE);
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            tp.setMargins(Ui.dp(c, 20), Ui.dp(c, 22), Ui.dp(c, 10), 0);
            root.addView(title, tp);

            sub.setTextSize(12); sub.setTextColor(0xFFC3D2D8);
            FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(Math.round(w * 0.52f), -2, Gravity.BOTTOM | Gravity.START);
            sp.setMargins(Ui.dp(c, 20), 0, 0, Ui.dp(c, 18));
            root.addView(sub, sp);

            // yellow tab + glow (visible only while the card has the cursor)
            View glow = new View(c);
            GradientDrawable gg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x66FFC21A, 0x00FFC21A});
            glow.setBackground(gg);
            glow.setAlpha(0f);
            root.addView(glow, new FrameLayout.LayoutParams(Math.round(w * 0.5f), Ui.dp(c, 54), Gravity.TOP | Gravity.CENTER_HORIZONTAL));
            View tab = new View(c);
            GradientDrawable tg = new GradientDrawable();
            tg.setColor(YELLOW);
            float r = Ui.dp(c, 4);
            tg.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
            tab.setBackground(tg);
            tab.setAlpha(0f);
            root.addView(tab, new FrameLayout.LayoutParams(Math.round(w * 0.3f), Ui.dp(c, 7), Gravity.TOP | Gravity.CENTER_HORIZONTAL));

            VH vh = new VH(root, img, ic, title, sub);
            vh.tab = tab; vh.glow = glow;
            vh.glassN = glass(c, 0x402C4E58, 0x70437F8B, 0x2EFFFFFF, 1);
            vh.glassF = glass(c, 0x66376C79, 0xB04FA3B1, 0x80FFFFFF, 1.5f);
            root.setBackground(vh.glassN);
            return vh;
        }

        // APP tile: label under the icon (only used when there is no TV banner)
        root.addView(ic, new FrameLayout.LayoutParams(-1, -1));
        title.setTextSize(12); title.setTextColor(0xFF1B1F2A); title.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        tp.setMargins(Ui.dp(c, 6), 0, Ui.dp(c, 6), Ui.dp(c, 6));
        root.addView(title, tp);

        // focus ring on top (follows the parent's focus state)
        View ring = new View(c);
        ring.setBackgroundResource(R.drawable.bg_focus_ring);
        ring.setDuplicateParentStateEnabled(true);
        root.addView(ring, new FrameLayout.LayoutParams(-1, -1));
        return new VH(root, img, ic, title, sub);
    }

    @Override public void onBindViewHolder(VH vh, int pos) {
        Card k = list.get(pos);
        Context c = vh.root.getContext();
        vh.title.setText(k.title);

        if (mode == SHORTCUT) {
            vh.sub.setText(k.sub);
            vh.sub.setVisibility(k.sub == null || k.sub.isEmpty() ? View.GONE : View.VISIBLE);
            vh.ic.clearColorFilter();
            if (k.iconRes != 0) vh.ic.setImageResource(k.iconRes); else vh.ic.setImageDrawable(null);
            vh.root.setBackground(vh.root.hasFocus() ? vh.glassF : vh.glassN);
            return;
        }

        vh.ic.setImageDrawable(null);
        vh.ic.setPadding(0, 0, 0, 0);
        vh.ic.clearColorFilter();
        int h = height(c, mode);
        if (k.c1 != 0 || k.c2 != 0) {
            // gradient card ("All apps")
            vh.root.setBackground(Ui.gradient(k.c1, k.c2, Ui.dp(c, 12)));
            vh.img.setImageDrawable(null); vh.img.setTag(null);
            if (k.iconRes != 0) {
                vh.ic.setImageResource(k.iconRes);
                vh.ic.setColorFilter(0xFFFFFFFF);
                int s = Math.round(h * 0.40f);
                vh.ic.setLayoutParams(new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
            }
            vh.title.setTextColor(Color.WHITE);
            vh.title.setVisibility(View.VISIBLE);
            return;
        }

        vh.root.setBackgroundColor(0xFFFFFFFF);
        vh.img.setTag(null);
        if (k.banner != null) {
            vh.img.setImageDrawable(k.banner);                 // TV banner fills the tile (like Netflix / Prime)
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
