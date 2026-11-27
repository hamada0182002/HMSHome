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
        public int imageRes;                // picture inside a shortcut card (resource)
        public String imageUrl = "";        // ... or a url (latest movie / series)
        public String badge = "";           // small pill at the top-right of a shortcut card
        public Object data;
        public Card(String title, Object data) { this.title = title; this.data = data; }
        public Card sub(String s) { sub = s; return this; }
        public Card icon(Drawable d) { icon = d; return this; }
        public Card banner(Drawable d) { banner = d; return this; }
        public Card vec(int res) { iconRes = res; return this; }
        public Card colors(int a, int b) { c1 = a; c2 = b; return this; }
        public Card image(int res) { imageRes = res; return this; }
        public Card imageUrl(String u) { imageUrl = u == null ? "" : u; return this; }
        public Card badge(String b) { badge = b; return this; }
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
        return Math.round(w * 0.42f);          // wide picture cards: two and a bit on screen, the next one peeks in
    }
    public static int height(Context c, int mode) {
        int w = width(c, mode);
        return mode == APP ? Math.round(w * 0.56f) : Math.round(w * 0.56f);
    }

    class VH extends RecyclerView.ViewHolder {
        final FrameLayout root; final ImageView img, ic; final TextView title, sub;
        View dim; TextView badge;                     // shortcut cards only
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
                        // lifted card: the dark veil over the picture goes away
                        x.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).translationY(f ? -Ui.dp(x.getContext(), 8) : 0f).setDuration(200).start();
                        if (dim != null) dim.animate().alpha(f ? 0f : 1f).setDuration(200).start();
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

    private static void edge(Context c, FrameLayout root, int res, int w, int h, int gravity) {
        View v = new View(c);
        v.setBackgroundResource(res);
        root.addView(v, new FrameLayout.LayoutParams(w, h, gravity));
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
        Ui.clip(root, Ui.dp(c, mode == SHORTCUT ? 16 : 12), false);

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
            // picture inside the card; its four edges fade into dark, a dark veil sits on top until the card gets the cursor
            root.setBackgroundColor(0xFF14232A);
            edge(c, root, R.drawable.fade_left, Math.round(w * 0.16f), -1, Gravity.LEFT);
            edge(c, root, R.drawable.fade_right, Math.round(w * 0.16f), -1, Gravity.RIGHT);
            edge(c, root, R.drawable.fade_top, -1, Math.round(h * 0.26f), Gravity.TOP);
            edge(c, root, R.drawable.fade_bottom, -1, Math.round(h * 0.62f), Gravity.BOTTOM);
            View dim = new View(c);
            dim.setBackgroundColor(0x80000000);
            root.addView(dim, new FrameLayout.LayoutParams(-1, -1));

            title.setTextSize(21); title.setTextColor(Color.WHITE);
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
            tp.setMargins(Ui.dp(c, 18), 0, Ui.dp(c, 12), Ui.dp(c, 38));
            root.addView(title, tp);

            sub.setTextSize(13); sub.setTextColor(0xFFD3DDE2); sub.setMaxLines(1);
            FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(Math.round(w * 0.86f), -2, Gravity.BOTTOM | Gravity.START);
            sp.setMargins(Ui.dp(c, 18), 0, 0, Ui.dp(c, 16));
            root.addView(sub, sp);

            TextView badge = new TextView(c);
            badge.setTextSize(11); badge.setTypeface(Typeface.DEFAULT_BOLD); badge.setTextColor(0xFF1B1F2A);
            badge.setPadding(Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 10), Ui.dp(c, 4));
            badge.setBackground(Ui.rounded(0xEBFFFFFF, Ui.dp(c, 8)));
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            bp.setMargins(0, Ui.dp(c, 12), Ui.dp(c, 12), 0);
            root.addView(badge, bp);

            View ring = new View(c);
            ring.setBackgroundResource(R.drawable.bg_hero_ring);
            ring.setDuplicateParentStateEnabled(true);
            root.addView(ring, new FrameLayout.LayoutParams(-1, -1));

            VH vh = new VH(root, img, ic, title, sub);
            vh.dim = dim; vh.badge = badge;
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
            vh.badge.setText(k.badge);
            vh.badge.setVisibility(k.badge == null || k.badge.isEmpty() ? View.GONE : View.VISIBLE);
            vh.dim.setAlpha(vh.root.hasFocus() ? 0f : 1f);
            if (k.imageRes != 0) { vh.img.setTag(null); vh.img.setImageResource(k.imageRes); }
            else if (k.imageUrl != null && k.imageUrl.startsWith("http")) com.hms.home.data.ImageLoader.load(vh.img, k.imageUrl, width(c, mode), height(c, mode), true);
            else { vh.img.setTag(null); vh.img.setImageDrawable(null); }
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
