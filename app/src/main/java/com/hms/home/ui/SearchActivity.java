package com.hms.home.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.widget.EditText;
import android.widget.TextView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.hms.home.R;
import com.hms.home.data.Item;
import com.hms.home.data.Net;
import com.hms.home.data.Store;
import com.hms.home.data.Xtream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

public class SearchActivity extends BaseActivity {
    private TextView status;
    private TileAdapter ad;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Runnable pending;
    private Net.Task task;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_search);
        status = findViewById(R.id.status);
        EditText q = findViewById(R.id.q);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int span = Math.max(2, ((int) (dm.widthPixels / dm.density) - 72) / (130 + 24));
        RecyclerView grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, span));
        ad = new TileAdapter(130, 195, true, new TileAdapter.SimpleListener() {
            @Override public void click(TileAdapter.Tile t, int pos) { Nav.open(SearchActivity.this, (Item) t.data); }
        });
        grid.setAdapter(ad);
        if (!Store.hasLogin()) { status.setText("سجّل دخول IPTV الأول"); return; }
        status.setText("اكتب حرفين على الأقل");
        q.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) {
                if (pending != null) h.removeCallbacks(pending);
                final String s = e.toString().trim();
                pending = new Runnable() { @Override public void run() { search(s); } };
                h.postDelayed(pending, 600);
            }
        });
    }

    @Override protected void onDestroy() { h.removeCallbacksAndMessages(null); super.onDestroy(); }

    private void search(final String q) {
        if (task != null) task.cancel();
        if (q.length() < 2) { ad.set(new ArrayList<TileAdapter.Tile>()); status.setText("اكتب حرفين على الأقل"); return; }
        status.setText("Searching… (أول مرة بيحمّل الفهرس وممكن ياخد وقت)");
        task = run(new Callable<List<Item>>() {
            @Override public List<Item> call() throws Exception {
                List<Item> all = new ArrayList<>();
                all.addAll(Xtream.search("live", q, 40));
                all.addAll(Xtream.search("movie", q, 40));
                all.addAll(Xtream.search("series", q, 40));
                return all;
            }
        }, new Net.Ok<List<Item>>() {
            @Override public void ok(List<Item> l) {
                List<TileAdapter.Tile> t = new ArrayList<>();
                for (Item it : l) {
                    String tag = it.type.equals("live") ? "LIVE" : it.type.equals("movie") ? "MOVIE" : "SERIES";
                    t.add(new TileAdapter.Tile(it.name, tag + (it.extra.isEmpty() ? "" : "  " + it.extra), it.icon, null, it));
                }
                ad.set(t);
                status.setText(l.isEmpty() ? "No results" : l.size() + " results");
            }
        }, new Net.Fail() { @Override public void fail(Exception e) { status.setText(Net.msg(e)); } });
    }
}
