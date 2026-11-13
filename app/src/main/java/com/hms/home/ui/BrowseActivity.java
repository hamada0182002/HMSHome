package com.hms.home.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.hms.home.R;
import com.hms.home.data.Item;
import com.hms.home.data.Net;
import com.hms.home.data.PlayQueue;
import com.hms.home.data.Store;
import com.hms.home.data.Xtream;
import com.hms.home.player.PlayerActivity;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** Live TV / Movies / Series: categories on the left, items on the right. */
public class BrowseActivity extends BaseActivity {
    private String type;
    private RecyclerView catsRv, gridRv;
    private TextAdapter catAd;
    private TileAdapter gridAd;
    private TextView status, epg;
    private final Handler h = new Handler(Looper.getMainLooper());
    private List<Xtream.Cat> cats = new ArrayList<>();
    private List<Item> current = new ArrayList<>();
    private Net.Task itemsTask, epgTask;
    private int selCat = -1;
    private Runnable pendingSel, pendingEpg;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        type = getIntent().getStringExtra("type");
        if (type == null) type = "live";
        setContentView(R.layout.activity_browse);
        final boolean live = type.equals("live");
        ((TextView) findViewById(R.id.title)).setText(live ? "LIVE TV" : type.equals("movie") ? "MOVIES" : "SERIES");
        status = findViewById(R.id.status);
        epg = findViewById(R.id.epg);
        LinearLayout sh = findViewById(R.id.searchHolder);
        TextView sb = Ui.button(this, "SEARCH");
        sb.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { Nav.section(BrowseActivity.this, "search"); } });
        sh.addView(sb);

        catsRv = findViewById(R.id.cats);
        catsRv.setLayoutManager(new LinearLayoutManager(this));
        catAd = new TextAdapter(false, new TextAdapter.Listener() {
            @Override public void click(TextAdapter.Row r, final int pos) { cancelPending(); select(pos); findViewById(R.id.grid).requestFocus(); }
            @Override public void focus(TextAdapter.Row r, final int pos) {
                cancelPending();
                pendingSel = new Runnable() { @Override public void run() { select(pos); } };
                h.postDelayed(pendingSel, 450);
            }
        });
        catsRv.setAdapter(catAd);

        int tw = live ? 150 : 130;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int availDp = (int) (dm.widthPixels / dm.density) - 260 - 60;
        int span = Math.max(2, availDp / (tw + 24));
        RecyclerView grid = findViewById(R.id.grid);
        gridRv = grid;
        grid.setLayoutManager(new GridLayoutManager(this, span));
        gridAd = new TileAdapter(tw, live ? 90 : 195, live, new TileAdapter.Listener() {
            @Override public void click(TileAdapter.Tile t, int pos) {
                Item it = (Item) t.data;
                if (it.type.equals("live")) {
                    PlayQueue.set(current, pos);
                    startActivity(new Intent(BrowseActivity.this, PlayerActivity.class));
                } else Nav.open(BrowseActivity.this, it);
            }
            @Override public void menu(TileAdapter.Tile t, int pos) {
                boolean f = Store.toggleFav((Item) t.data);
                Toast.makeText(BrowseActivity.this, f ? "Added to favorites" : "Removed from favorites", Toast.LENGTH_SHORT).show();
                if (selCat == 0) { int s = selCat; selCat = -1; select(s); }
            }
            @Override public void focus(final TileAdapter.Tile t, int pos) {
                if (!type.equals("live")) return;
                if (pendingEpg != null) h.removeCallbacks(pendingEpg);
                pendingEpg = new Runnable() { @Override public void run() { loadEpg((Item) t.data); } };
                h.postDelayed(pendingEpg, 600);
            }
        });
        grid.setAdapter(gridAd);

        status.setText("Loading categories…");
        run(new Callable<List<Xtream.Cat>>() {
            @Override public List<Xtream.Cat> call() throws Exception { return Xtream.categories(type); }
        }, new Net.Ok<List<Xtream.Cat>>() {
            @Override public void ok(List<Xtream.Cat> l) {
                cats = l;
                List<TextAdapter.Row> rows = new ArrayList<>();
                rows.add(new TextAdapter.Row("\u2605 Favorites", null, null));
                for (Xtream.Cat c : l) rows.add(new TextAdapter.Row(c.name, null, c));
                catAd.set(rows);
                select(l.isEmpty() ? 0 : 1);
            }
        }, new Net.Fail() {
            @Override public void fail(Exception e) { status.setText(Net.msg(e)); }
        });
    }

    /** Folder list -> RIGHT = first item of the grid (not the nearest one); grid -> LEFT at the first column = back to the folder. */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int k = e.getKeyCode();
            RecyclerView in = FocusNav.listOf(getCurrentFocus());
            if (in == catsRv && k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (gridAd.size() > 0) { cancelPending(); FocusNav.focusPos(gridRv, 0); }
                return true;
            }
            if (in == gridRv && k == KeyEvent.KEYCODE_DPAD_LEFT) {
                int span = ((GridLayoutManager) gridRv.getLayoutManager()).getSpanCount();
                if (FocusNav.posOf(gridRv, getCurrentFocus()) % span == 0) { FocusNav.focusPos(catsRv, Math.max(0, selCat)); return true; }
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private void cancelPending() { if (pendingSel != null) h.removeCallbacks(pendingSel); }

    @Override protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void select(final int pos) {
        if (pos == selCat) return;
        selCat = pos;
        catAd.setSelected(pos);
        if (itemsTask != null) itemsTask.cancel();
        epg.setText("");
        if (pos == 0) { show(Store.favItems(type)); return; }
        status.setText("Loading…");
        gridAd.set(new ArrayList<TileAdapter.Tile>());
        final String id = cats.get(pos - 1).id;
        itemsTask = run(new Callable<List<Item>>() {
            @Override public List<Item> call() throws Exception { return Xtream.items(type, id); }
        }, new Net.Ok<List<Item>>() {
            @Override public void ok(List<Item> l) { show(l); }
        }, new Net.Fail() {
            @Override public void fail(Exception e) { status.setText(Net.msg(e)); }
        });
    }

    private void show(List<Item> l) {
        current = l;
        status.setText(l.isEmpty() ? (selCat == 0 ? "No favorites yet - hold OK (or MENU) on an item to add it" : "No items") : l.size() + " items  •  hold OK / MENU = favorite");
        List<TileAdapter.Tile> t = new ArrayList<>();
        for (Item it : l) t.add(new TileAdapter.Tile(it.name, it.extra, it.icon, null, it));
        gridAd.set(t);
    }

    private void loadEpg(final Item it) {
        if (epgTask != null) epgTask.cancel();
        epgTask = run(new Callable<String[]>() {
            @Override public String[] call() throws Exception { return Xtream.epg(it.id); }
        }, new Net.Ok<String[]>() {
            @Override public void ok(String[] r) {
                if (r[0].isEmpty() && r[1].isEmpty()) epg.setText("");
                else epg.setText("NOW: " + r[0] + (r[1].isEmpty() ? "" : "     NEXT: " + r[1]));
            }
        }, new Net.Fail() { @Override public void fail(Exception e) { epg.setText(""); } });
    }
}
