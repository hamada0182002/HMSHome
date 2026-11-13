package com.hms.home.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.content.ContextCompat;
import com.hms.home.R;
import java.util.ArrayList;
import java.util.List;

/** Text rows (categories, seasons, episodes, channel list, settings). */
public class TextAdapter extends RecyclerView.Adapter<TextAdapter.VH> {
    public static class Row {
        public String text, sub; public Object data;
        public Row(String t, String s, Object d) { text = t; sub = s; data = d; }
    }
    public interface Listener { void click(Row r, int pos); void focus(Row r, int pos); }

    private List<Row> rows = new ArrayList<>();
    private final Listener l;
    private final boolean horizontal;
    private final boolean light;
    private int selected = -1;

    public TextAdapter(boolean horizontal, Listener l) { this(horizontal, false, l); }
    /** light = true: same look as the home screen (light cards, dark text, blue focus ring). */
    public TextAdapter(boolean horizontal, boolean light, Listener l) { this.horizontal = horizontal; this.light = light; this.l = l; }

    public void set(List<Row> r) { rows = r; notifyDataSetChanged(); }
    public Row get(int i) { return rows.get(i); }
    public void setSelected(int s) { int old = selected; selected = s; if (old >= 0) notifyItemChanged(old); if (s >= 0) notifyItemChanged(s); }
    public int size() { return rows.size(); }

    class VH extends RecyclerView.ViewHolder {
        TextView t, s;
        VH(LinearLayout v, TextView t, TextView s) {
            super(v); this.t = t; this.s = s;
            v.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) { int p = getAdapterPosition(); if (p >= 0) l.click(rows.get(p), p); } });
            v.setOnFocusChangeListener(new View.OnFocusChangeListener() { @Override public void onFocusChange(View x, boolean f) { int p = getAdapterPosition(); if (f && p >= 0 && l != null) l.focus(rows.get(p), p); } });
        }
    }

    @Override public VH onCreateViewHolder(ViewGroup p, int type) {
        Context c = p.getContext();
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(Ui.dp(c, 14), Ui.dp(c, 9), Ui.dp(c, 14), Ui.dp(c, 9));
        v.setFocusable(true); v.setClickable(true);
        v.setBackgroundResource(light ? R.drawable.bg_row_l : R.drawable.bg_card);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(horizontal ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(c, 3), Ui.dp(c, 3), Ui.dp(c, 3), Ui.dp(c, 3));
        v.setLayoutParams(lp);
        TextView t = Ui.label(c, "", 16, light ? R.color.h_ink : R.color.white, false);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView s = Ui.label(c, "", 12, light ? R.color.h_ink2 : R.color.secondary, false);
        s.setSingleLine(true);
        v.addView(t); v.addView(s);
        return new VH(v, t, s);
    }

    @Override public void onBindViewHolder(VH vh, int pos) {
        Row r = rows.get(pos);
        vh.t.setText(r.text);
        vh.t.setTextColor(ContextCompat.getColor(vh.t.getContext(), pos == selected ? (light ? R.color.h_accent : R.color.bronze) : (light ? R.color.h_ink : R.color.white)));
        boolean has = r.sub != null && !r.sub.isEmpty();
        vh.s.setText(has ? r.sub : "");
        vh.s.setVisibility(has ? View.VISIBLE : View.GONE);
    }

    @Override public int getItemCount() { return rows.size(); }
}
