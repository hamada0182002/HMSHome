package com.hms.home.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.hms.home.R;
import com.hms.home.data.AppsManager;
import com.hms.home.data.Item;
import com.hms.home.data.Net;
import com.hms.home.data.Store;
import com.hms.home.data.Xtream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class HomeActivity extends BaseActivity {
    private LinearLayout rows;
    private Row rFav, rRecent, rCont, rLive, rMovies, rSeries;
    private static List<Item> mMovies, mSeries;
    private static long memoAt;
    private boolean receiverOn;

    public static void invalidateMemo() { mMovies = null; mSeries = null; memoAt = 0; }

    private final BroadcastReceiver pkgRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { AppsManager.invalidate(); refresh(); }
    };

    private class Row {
        final TextView title; final RecyclerView rv; final TileAdapter ad;
        Row(String t, int w, int h, boolean icon, TileAdapter.Listener l) {
            title = Ui.label(HomeActivity.this, t, 18, R.color.beige, true);
            title.setPadding(Ui.dp(HomeActivity.this, 6), Ui.dp(HomeActivity.this, 14), 0, Ui.dp(HomeActivity.this, 2));
            rv = new RecyclerView(HomeActivity.this);
            rv.setLayoutManager(new LinearLayoutManager(HomeActivity.this, LinearLayoutManager.HORIZONTAL, false));
            ad = new TileAdapter(w, h, icon, l);
            rv.setAdapter(ad);
            rv.setClipToPadding(false);
            rows.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            rows.addView(rv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            show(false);
        }
        void show(boolean s) { int v = s ? View.VISIBLE : View.GONE; title.setVisibility(v); rv.setVisibility(v); }
        void set(List<TileAdapter.Tile> l) { ad.set(l); show(!l.isEmpty()); }
    }

    private final TileAdapter.Listener tl = new TileAdapter.Listener() {
        @Override public void click(TileAdapter.Tile t, int pos) {
            Object d = t.data;
            if (d instanceof AppsManager.App) {
                if (!AppsManager.launch(HomeActivity.this, (AppsManager.App) d)) toast("تعذر فتح التطبيق");
            } else if (d instanceof Item) {
                Item it = (Item) d;
                if (t.sub != null && t.sub.startsWith("Resume")) Nav.play(HomeActivity.this, it); else Nav.open(HomeActivity.this, it);
            } else if (d instanceof String) Nav.section(HomeActivity.this, (String) d);
        }
        @Override public void menu(TileAdapter.Tile t, int pos) {
            if (t.data instanceof AppsManager.App) AppMenu.show(HomeActivity.this, (AppsManager.App) t.data, new Runnable() { @Override public void run() { refresh(); } });
            else if (t.data instanceof Item) {
                Item it = (Item) t.data;
                boolean f = Store.toggleFav(it);
                Toast.makeText(HomeActivity.this, f ? "Added to favorites" : "Removed from favorites", Toast.LENGTH_SHORT).show();
                refresh();
            }
        }
        @Override public void focus(TileAdapter.Tile t, int pos) { }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_home);
        rows = findViewById(R.id.rows);
        LinearLayout nav = findViewById(R.id.nav);
        String[][] items = {{"HOME", ""}, {"LIVE TV", "live"}, {"MOVIES", "movie"}, {"SERIES", "series"}, {"APPS", "apps"}, {"SEARCH", "search"}, {"SETTINGS", "settings"}};
        for (final String[] it : items) {
            TextView t = Ui.navItem(this, it[0]);
            if (it[1].isEmpty()) t.setSelected(true);
            else t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { Nav.section(HomeActivity.this, it[1]); } });
            nav.addView(t);
        }
        rFav = new Row("MY APPS", 110, 110, true, tl);
        rCont = new Row("CONTINUE WATCHING", 130, 195, false, tl);
        rLive = new Row("LIVE TV", 150, 90, true, tl);
        rMovies = new Row("NEW MOVIES", 130, 195, false, tl);
        rSeries = new Row("SERIES", 130, 195, false, tl);
        rRecent = new Row("RECENT APPS", 110, 110, true, tl);
    }

    @Override protected void onStart() {
        super.onStart();
        if (!receiverOn) { registerReceiver(pkgRx, AppsManager.changeFilter()); receiverOn = true; }
        refresh();
    }

    @Override protected void onStop() {
        cancelAll();
        if (receiverOn) { unregisterReceiver(pkgRx); receiverOn = false; }
        super.onStop();
    }

    @Override public void onBackPressed() { /* we are the launcher: never close */ }

    private TileAdapter.Tile sectionTile(String title, String sub, String data) {
        Drawable d = ContextCompat.getDrawable(this, R.drawable.ic_launcher);
        return new TileAdapter.Tile(title, sub, null, d, data);
    }

    private List<TileAdapter.Tile> itemTiles(List<Item> l, boolean resume) {
        List<TileAdapter.Tile> t = new ArrayList<>();
        for (Item it : l) {
            String sub = resume ? "Resume " + Ui.fmt(Store.getPosition(it)) : it.extra;
            t.add(new TileAdapter.Tile(it.name, sub, it.icon, null, it));
        }
        return t;
    }

    private void refresh() {
        cancelAll();
        run(new java.util.concurrent.Callable<List<AppsManager.App>>() {
            @Override public List<AppsManager.App> call() { return AppsManager.load(getApplicationContext()); }
        }, new Net.Ok<List<AppsManager.App>>() {
            @Override public void ok(List<AppsManager.App> apps) {
                List<TileAdapter.Tile> f = new ArrayList<>();
                for (String p : Store.favApps()) { AppsManager.App a = AppsManager.find(apps, p); if (a != null) f.add(new TileAdapter.Tile(a.label, "", null, a.icon, a)); }
                f.add(sectionTile("Add apps", "Open APPS", "apps"));
                rFav.set(f);
                List<TileAdapter.Tile> r = new ArrayList<>();
                for (String p : Store.recentApps()) { AppsManager.App a = AppsManager.find(apps, p); if (a != null) r.add(new TileAdapter.Tile(a.label, "", null, a.icon, a)); }
                rRecent.set(r);
            }
        }, null);

        rCont.set(itemTiles(Store.continueWatching(), true));

        if (!Store.hasLogin()) {
            List<TileAdapter.Tile> l = new ArrayList<>();
            l.add(sectionTile("IPTV Login", "Add your Xtream account", "login"));
            rLive.set(l);
            rMovies.set(new ArrayList<TileAdapter.Tile>());
            rSeries.set(new ArrayList<TileAdapter.Tile>());
            return;
        }
        List<TileAdapter.Tile> live = new ArrayList<>();
        live.add(sectionTile("LIVE TV", "All channels", "live"));
        live.addAll(itemTiles(Store.favItems("live"), false));
        rLive.set(live);

        if (mMovies != null && System.currentTimeMillis() - memoAt < 600000) { fillIptv(); return; }
        run(new java.util.concurrent.Callable<List<List<Item>>>() {
            @Override public List<List<Item>> call() throws Exception {
                Xtream.ensureAll("movie", false);
                Xtream.ensureAll("series", false);
                return Arrays.asList(Xtream.latest("movie", 20), Xtream.latest("series", 20));
            }
        }, new Net.Ok<List<List<Item>>>() {
            @Override public void ok(List<List<Item>> r) { mMovies = r.get(0); mSeries = r.get(1); memoAt = System.currentTimeMillis(); fillIptv(); }
        }, new Net.Fail() {
            @Override public void fail(Exception e) { Toast.makeText(HomeActivity.this, Net.msg(e), Toast.LENGTH_LONG).show(); }
        });
    }

    private void fillIptv() {
        rMovies.set(itemTiles(mMovies, false));
        rSeries.set(itemTiles(mSeries, false));
    }
}
