package com.hms.home.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import com.hms.home.R;
import com.hms.home.data.ImageLoader;
import java.util.ArrayList;
import java.util.List;

/** One adapter for every poster / logo / app-icon card in the app. */
public class TileAdapter extends RecyclerView.Adapter<TileAdapter.VH> {
    public static class Tile {
        public String title, sub, img; public Drawable icon; public Object data;
        public Tile(String title, String sub, String img, Drawable icon, Object data) { this.title = title; this.sub = sub; this.img = img; this.icon = icon; this.data = data; }
    }

    public interface Listener {
        void click(Tile t, int pos);
        void menu(Tile t, int pos);
        void focus(Tile t, int pos);
    }

    public static abstract class SimpleListener implements Listener {
        @Override public void menu(Tile t, int pos) { }
        @Override public void focus(Tile t, int pos) { }
    }

    private final int w, h;
    private final boolean iconMode;
    private final Listener l;
    private List<Tile> list = new ArrayList<>();

    public TileAdapter(int wDp, int hDp, boolean iconMode, Listener l) { this.w = wDp; this.h = hDp; this.iconMode = iconMode; this.l = l; }

    public void set(List<Tile> t) { list = t; notifyDataSetChanged(); }
    public int size() { return list.size(); }

    class VH extends RecyclerView.ViewHolder {
        ImageView img; TextView title, sub;
        VH(View v) {
            super(v);
            img = v.findViewById(R.id.img); title = v.findViewById(R.id.title); sub = v.findViewById(R.id.sub);
            v.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) { int p = getAdapterPosition(); if (p >= 0) l.click(list.get(p), p); } });
            v.setOnLongClickListener(new View.OnLongClickListener() { @Override public boolean onLongClick(View x) { int p = getAdapterPosition(); if (p >= 0) l.menu(list.get(p), p); return true; } });
            v.setOnKeyListener(new View.OnKeyListener() {
                @Override public boolean onKey(View x, int code, KeyEvent e) {
                    if (code == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) { int p = getAdapterPosition(); if (p >= 0) l.menu(list.get(p), p); return true; }
                    return code == KeyEvent.KEYCODE_MENU;
                }
            });
            v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View x, boolean f) {
                    float sc = f ? 1.1f : 1f;
                    x.animate().scaleX(sc).scaleY(sc).setDuration(120).start();
                    int p = getAdapterPosition(); if (f && p >= 0) l.focus(list.get(p), p);
                }
            });
        }
    }

    @Override public void onAttachedToRecyclerView(RecyclerView rv) { rv.setClipChildren(false); }

    @Override public VH onCreateViewHolder(ViewGroup p, int type) {
        Context c = p.getContext();
        View v = LayoutInflater.from(c).inflate(R.layout.item_tile, p, false);
        ImageView img = v.findViewById(R.id.img);
        ViewGroup.LayoutParams lp = img.getLayoutParams();
        lp.width = Ui.dp(c, w); lp.height = Ui.dp(c, h);
        TextView title = v.findViewById(R.id.title), sub = v.findViewById(R.id.sub);
        if (iconMode && w == h) {
            // app icon: round, Google TV style
            int pd = Ui.dp(c, w * 0.2f);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setBackgroundResource(R.drawable.bg_icon_circle);
            img.setPadding(pd, pd, pd, pd);
            Ui.clip(img, 0, true);
            title.setGravity(Gravity.CENTER_HORIZONTAL);
            sub.setGravity(Gravity.CENTER_HORIZONTAL);
        } else if (iconMode) {
            // channel logo: light rounded card
            int pd = Ui.dp(c, 12);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setBackgroundResource(R.drawable.bg_logo);
            img.setPadding(pd, pd, pd, pd);
            Ui.clip(img, Ui.dp(c, 16), false);
        } else {
            // poster: rounded corners with a white frame on focus
            int pd = Ui.dp(c, 3);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setBackgroundResource(R.drawable.bg_poster);
            img.setPadding(pd, pd, pd, pd);
            Ui.clip(img, Ui.dp(c, 16), false);
        }
        return new VH(v);
    }

    @Override public void onBindViewHolder(VH vh, int pos) {
        Tile t = list.get(pos);
        vh.title.setText(t.title);
        boolean hasSub = t.sub != null && !t.sub.isEmpty();
        vh.sub.setText(hasSub ? t.sub : "");
        vh.sub.setVisibility(hasSub ? View.VISIBLE : View.GONE);
        if (t.icon != null) { vh.img.setTag(null); vh.img.setImageDrawable(t.icon); }
        else ImageLoader.load(vh.img, t.img, Ui.dp(vh.img.getContext(), w), Ui.dp(vh.img.getContext(), h));
    }

    @Override public int getItemCount() { return list.size(); }
}
