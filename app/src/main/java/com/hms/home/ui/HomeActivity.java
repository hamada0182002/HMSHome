package com.hms.home.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
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

/**
 * The launcher home (light Google-TV-like look):
 *  top bar (clock, Wi-Fi, device settings, HMS settings) -> hero banners -> apps bar -> shortcuts -> poster rows.
 */
public class HomeActivity extends BaseActivity {
    private LinearLayout rows;
    private CardAdapter hero, apps, shortcuts;
    private static final int HERO_PER_ROW = 3;   // big cards: first 3 series + first 3 movies of the chosen folders
    private RecyclerView appsRv, heroRv, shortcutsRv;
    private final java.util.Map<RecyclerView, Integer> lastPos = new java.util.HashMap<>();
    private boolean opening;
    private TileAdapter tCont, tFav, tMovies, tSeries;
    private final List<View[]> titled = new ArrayList<>();   // {title, list} pairs that are hidden when empty
    private TextView tContT, tFavT, tMoviesT, tSeriesT;
    private RecyclerView rCont, rFav, rMovies, rSeries;
    private boolean receiverOn, focusSet;
    private static List<Item> mMovies, mSeries;
    private static long memoAt;
    /** movie id -> wide backdrop url (from get_vod_info); a poster is small and looks pixelated when stretched into a wide card. */
    private static final java.util.Map<String, String> backdrops = new java.util.HashMap<>();

    public static void invalidateMemo() { mMovies = null; mSeries = null; memoAt = 0; }

    private final BroadcastReceiver pkgRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { AppsManager.invalidate(); refresh(); }
    };

    // ---------------- listeners ----------------
    private final CardAdapter.Listener cl = new CardAdapter.Listener() {
        @Override public void click(CardAdapter.Card k) {
            Object d = k.data;
            if (d instanceof AppsManager.App) {
                if (!AppsManager.launch(HomeActivity.this, (AppsManager.App) d)) toast("تعذر فتح التطبيق");
            } else if (d instanceof Item) {
                Item it = (Item) d;
                if (k.sub != null && k.sub.startsWith("Resume")) Nav.resume(HomeActivity.this, it); else Nav.open(HomeActivity.this, it);
            } else if (d instanceof String) Nav.section(HomeActivity.this, (String) d);
        }
        @Override public void menu(CardAdapter.Card k) {
            if (k.data instanceof AppsManager.App) AppMenu.show(HomeActivity.this, (AppsManager.App) k.data, new Runnable() { @Override public void run() { refresh(); } });
        }
    };

    private final TileAdapter.Listener tl = new TileAdapter.Listener() {
        @Override public void click(TileAdapter.Tile t, int pos) {
            Object d = t.data;
            if (d instanceof AppsManager.App) {
                if (!AppsManager.launch(HomeActivity.this, (AppsManager.App) d)) toast("تعذر فتح التطبيق");
            } else if (d instanceof Item) {
                Item it = (Item) d;
                if (t.sub != null && t.sub.startsWith("Resume")) Nav.resume(HomeActivity.this, it);
                else if (it.type.equals("live")) playChannelInFolder(it);
                else Nav.open(HomeActivity.this, it);
            }
        }
        @Override public void menu(TileAdapter.Tile t, int pos) {
            if (!(t.data instanceof Item)) return;
            Item it = (Item) t.data;
            RecyclerView from = FocusNav.listOf(getCurrentFocus());
            if (t.sub != null && t.sub.startsWith("Resume")) {
                Store.removeProgress(it);                       // long press in "Continue watching" = remove it from the list
                Toast.makeText(HomeActivity.this, "Removed from continue watching", Toast.LENGTH_SHORT).show();
            } else {
                boolean f = Store.toggleFav(it);
                Toast.makeText(HomeActivity.this, f ? "Added to favorites" : "Removed from favorites", Toast.LENGTH_SHORT).show();
            }
            refresh();
            restoreFocus(from, pos);
        }
        @Override public void focus(TileAdapter.Tile t, int pos) { }
    };

    /** After a row changed (item removed), put the cursor back on the same spot instead of losing it. */
    private void restoreFocus(RecyclerView rv, int pos) {
        if (rv != null && rv.getAdapter() != null && rv.getAdapter().getItemCount() > 0 && rv.getVisibility() == View.VISIBLE) FocusNav.focusPos(rv, pos);
        else FocusNav.focusPos(shortcutsRv, 0);
    }

    /** Favorite channel: play it inside its own folder so channel up / down walks that folder. */
    private void playChannelInFolder(final Item ch) {
        if (ch.cat == null || ch.cat.isEmpty() || opening) { Nav.play(this, ch); return; }
        opening = true;
        Toast.makeText(this, "…", Toast.LENGTH_SHORT).show();
        run(new java.util.concurrent.Callable<List<Item>>() {
            @Override public List<Item> call() throws Exception { return Xtream.items("live", ch.cat); }
        }, new Net.Ok<List<Item>>() {
            @Override public void ok(List<Item> l) {
                opening = false;
                int idx = -1;
                for (int i = 0; i < l.size(); i++) if (l.get(i).id.equals(ch.id)) { idx = i; break; }
                if (idx < 0) { Nav.play(HomeActivity.this, ch); return; }
                com.hms.home.data.PlayQueue.set(l, idx);
                startActivity(new Intent(HomeActivity.this, com.hms.home.player.PlayerActivity.class));
            }
        }, new Net.Fail() {
            @Override public void fail(Exception e) { opening = false; Nav.play(HomeActivity.this, ch); }
        });
    }

    // ---------------- remote control: every row is its own "tab" ----------------
    private List<RecyclerView> rowOrder() {
        List<RecyclerView> l = new ArrayList<>();
        RecyclerView[] all = {heroRv, appsRv, shortcutsRv, rCont, rMovies, rSeries, rFav};
        for (RecyclerView r : all) if (r != null && r.getVisibility() == View.VISIBLE && r.getAdapter() != null && r.getAdapter().getItemCount() > 0) l.add(r);
        return l;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int k = e.getKeyCode();
            if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (moveRow(getCurrentFocus(), k == KeyEvent.KEYCODE_DPAD_DOWN)) return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    /** UP / DOWN leaves the current row and lands on the next / previous one (on the item last used there). */
    private boolean moveRow(View focus, boolean down) {
        if (focus == null) return false;
        List<RecyclerView> order = rowOrder();
        RecyclerView cur = FocusNav.listOf(focus);
        int idx = cur == null ? -1 : order.indexOf(cur);
        if (idx < 0) {
            // top bar icons: DOWN goes to the first row, UP stays
            if (down && !order.isEmpty() && cur == null) { goRow(order.get(0)); return true; }
            return false;
        }
        lastPos.put(cur, Math.max(0, FocusNav.posOf(cur, focus)));
        int to = down ? idx + 1 : idx - 1;
        if (to >= order.size()) return true;                       // last row: stop
        if (to < 0) { findViewById(R.id.btnApp).requestFocus(); return true; }   // first row: go to the top bar
        goRow(order.get(to));
        return true;
    }

    private void goRow(RecyclerView r) {
        Integer p = lastPos.get(r);
        FocusNav.focusPos(r, p == null ? 0 : p);
    }

    // ---------------- building the screen ----------------
    private RecyclerView horizontalList(RecyclerView.Adapter<?> ad, int padLeftDp) {
        RecyclerView rv = new RecyclerView(this);
        rv.setClipChildren(false);
        rv.setClipToPadding(false);
        rv.setPadding(Ui.dp(this, padLeftDp), Ui.dp(this, 6), Ui.dp(this, 24), Ui.dp(this, 10));
        rv.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rv.setAdapter(ad);
        return rv;
    }

    private TextView sectionTitle(String t) {
        TextView tv = Ui.label(this, t, 20, R.color.h_ink, true);
        tv.setPadding(Ui.dp(this, 56), Ui.dp(this, 10), 0, Ui.dp(this, 0));
        return tv;
    }

    private void bindIcon(int id, final Runnable r, int tint) {
        ImageView v = findViewById(id);
        v.setColorFilter(tint);
        v.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) { r.run(); } });
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawableResource(R.drawable.bg_home);
        setContentView(R.layout.activity_home);
        rows = findViewById(R.id.rows);

        int tint = Color.parseColor("#4A5468");
        bindIcon(R.id.btnWifi, new Runnable() { @Override public void run() { Nav.wifiSettings(HomeActivity.this); } }, tint);
        bindIcon(R.id.btnDevice, new Runnable() { @Override public void run() { Nav.deviceSettings(HomeActivity.this); } }, tint);   // Android settings
        bindIcon(R.id.btnApp, new Runnable() { @Override public void run() { Nav.section(HomeActivity.this, "settings"); } }, tint);  // HMS HOME settings

        // 1) hero banners
        hero = new CardAdapter(CardAdapter.HERO, cl);
        heroRv = horizontalList(hero, 40);
        rows.addView(heroRv, new LinearLayout.LayoutParams(-1, -2));

        // 2) apps bar: rounded translucent container
        apps = new CardAdapter(CardAdapter.APP, cl);
        appsRv = horizontalList(apps, 14);
        appsRv.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundResource(R.drawable.bg_apps_bar);
        bar.setClipChildren(false);
        bar.addView(appsRv, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.setMargins(Ui.dp(this, 48), Ui.dp(this, 6), Ui.dp(this, 48), 0);
        rows.addView(bar, bp);

        // 3) shortcuts (Live TV / Movies / Series / Search / All apps / Accounts)
        shortcuts = new CardAdapter(CardAdapter.SHORTCUT, cl);
        shortcutsRv = horizontalList(shortcuts, 40);
        rows.addView(shortcutsRv, new LinearLayout.LayoutParams(-1, -2));
        List<CardAdapter.Card> sc = new ArrayList<>();
        sc.add(new CardAdapter.Card("Live TV", "live").vec(R.drawable.ic_tv).colors(0xFF5B5CE6, 0xFF8E54E9));
        sc.add(new CardAdapter.Card("Movies", "movie").vec(R.drawable.ic_movie).colors(0xFFE5484D, 0xFFF2994A));
        sc.add(new CardAdapter.Card("Series", "series").vec(R.drawable.ic_series).colors(0xFF0E9F8E, 0xFF38C172));
        sc.add(new CardAdapter.Card("Search", "search").vec(R.drawable.ic_search_white).colors(0xFF2F6BFF, 0xFF4FB3FF));
        sc.add(new CardAdapter.Card("All apps", "apps").vec(R.drawable.ic_apps).colors(0xFF3A4254, 0xFF596277));
        sc.add(new CardAdapter.Card("IPTV accounts", "login").vec(R.drawable.ic_person).colors(0xFF8A5A44, 0xFFC08457));
        shortcuts.set(sc);

        // 4) poster rows (dark text on the light background)
        tCont = new TileAdapter(140, 210, false, true, tl);
        tMovies = new TileAdapter(140, 210, false, true, tl);
        tSeries = new TileAdapter(140, 210, false, true, tl);
        tFav = new TileAdapter(190, 110, true, true, tl);

        tContT = sectionTitle("Continue watching"); rCont = horizontalList(tCont, 48);
        tMoviesT = sectionTitle("New movies"); rMovies = horizontalList(tMovies, 48);
        tSeriesT = sectionTitle("New series"); rSeries = horizontalList(tSeries, 48);
        tFavT = sectionTitle("Favorite channels"); rFav = horizontalList(tFav, 48);
        for (View[] p : new View[][]{{tContT, rCont}, {tMoviesT, rMovies}, {tSeriesT, rSeries}, {tFavT, rFav}}) {
            rows.addView(p[0], new LinearLayout.LayoutParams(-1, -2));
            rows.addView(p[1], new LinearLayout.LayoutParams(-1, -2));
            p[0].setVisibility(View.GONE); p[1].setVisibility(View.GONE);
            titled.add(p);
        }
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

    private void show(View title, View list, boolean s) {
        int v = s ? View.VISIBLE : View.GONE;
        title.setVisibility(v); list.setVisibility(v);
    }

    private List<TileAdapter.Tile> itemTiles(List<Item> l, boolean resume) {
        List<TileAdapter.Tile> t = new ArrayList<>();
        for (Item it : l) {
            String sub = resume ? "Resume " + Ui.fmt(Store.getPosition(it)) : it.extra;
            t.add(new TileAdapter.Tile(it.name, sub, it.icon, null, it));
        }
        return t;
    }

    private CardAdapter.Card appCard(AppsManager.App a) {
        return new CardAdapter.Card(a.label, a).icon(a.icon).banner(a.banner);
    }

    // ---------------- data ----------------
    private void refresh() {
        cancelAll();
        run(new java.util.concurrent.Callable<List<AppsManager.App>>() {
            @Override public List<AppsManager.App> call() { return AppsManager.load(getApplicationContext()); }
        }, new Net.Ok<List<AppsManager.App>>() {
            @Override public void ok(List<AppsManager.App> all) {
                List<CardAdapter.Card> f = new ArrayList<>();
                for (String p : Store.favApps()) { AppsManager.App a = AppsManager.find(all, p); if (a != null) f.add(appCard(a)); }
                if (f.isEmpty()) {
                    // nothing pinned yet: show the first installed apps so the bar is never empty
                    for (int i = 0; i < all.size() && i < 12; i++) f.add(appCard(all.get(i)));
                }
                f.add(new CardAdapter.Card("All apps", "apps").vec(R.drawable.ic_apps).colors(0xFF3A4254, 0xFF596277));
                apps.set(f);
                if (!focusSet) { focusSet = true; appsRv.post(new Runnable() { @Override public void run() {
                    RecyclerView.ViewHolder vh = appsRv.findViewHolderForAdapterPosition(0);
                    if (vh != null) vh.itemView.requestFocus();
                } }); }
            }
        }, null);

        List<Item> cont = Store.continueWatching();
        tCont.set(itemTiles(cont, true));
        show(tContT, rCont, !cont.isEmpty());
        List<TileAdapter.Tile> favCh = itemTiles(Store.favItems("live"), false);
        tFav.set(favCh);
        show(tFavT, rFav, !favCh.isEmpty());

        if (!Store.hasLogin()) {
            List<CardAdapter.Card> h = new ArrayList<>();
            h.add(new CardAdapter.Card("IPTV login", "login").sub("Add your Xtream account").badge("START")
                    .vec(R.drawable.ic_person).colors(0xFF2F6BFF, 0xFF7B5CFF));
            hero.set(h);
            tMovies.set(new ArrayList<TileAdapter.Tile>());
            tSeries.set(new ArrayList<TileAdapter.Tile>());
            show(tMoviesT, rMovies, false);
            show(tSeriesT, rSeries, false);
            return;
        }
        fillHero();

        if (mMovies != null && System.currentTimeMillis() - memoAt < 600000) { fillIptv(); return; }
        run(new java.util.concurrent.Callable<List<List<Item>>>() {
            @Override public List<List<Item>> call() throws Exception {
                return Arrays.asList(Xtream.homeItems("movie", 20), Xtream.homeItems("series", 20));
            }
        }, new Net.Ok<List<List<Item>>>() {
            @Override public void ok(List<List<Item>> r) { mMovies = r.get(0); mSeries = r.get(1); memoAt = System.currentTimeMillis(); fillIptv(); }
        }, new Net.Fail() {
            @Override public void fail(Exception e) { Toast.makeText(HomeActivity.this, Net.msg(e) + "\n[" + Net.detail(e) + "]", Toast.LENGTH_LONG).show(); }
        });
    }

    private void fillIptv() {
        String[] cm = Store.homeCat("movie"), cs = Store.homeCat("series");
        tMoviesT.setText(cm != null && !cm[1].isEmpty() ? cm[1] : "New movies");
        tSeriesT.setText(cs != null && !cs[1].isEmpty() ? cs[1] : "New series");
        tMovies.set(itemTiles(mMovies, false));
        tSeries.set(itemTiles(mSeries, false));
        show(tMoviesT, rMovies, !mMovies.isEmpty());
        show(tSeriesT, rSeries, !mSeries.isEmpty());
        fillHero();
        loadMovieBackdrops();
    }

    /** Fetches the wide picture for the movies shown in the hero (first 8) and refreshes the hero when done. */
    private void loadMovieBackdrops() {
        if (mMovies == null) return;
        final List<Item> todo = new ArrayList<>();
        for (int i = 0; i < mMovies.size() && i < HERO_PER_ROW; i++) {
            Item it = mMovies.get(i);
            if (it.backdrop.isEmpty() && !backdrops.containsKey(it.id)) todo.add(it);
            else if (it.backdrop.isEmpty()) it.backdrop = backdrops.get(it.id);
        }
        if (todo.isEmpty()) return;
        run(new java.util.concurrent.Callable<Boolean>() {
            @Override public Boolean call() {
                for (Item it : todo) {
                    String u = "";
                    try {
                        org.json.JSONObject info = Xtream.vodInfo(it.id).optJSONObject("info");
                        if (info != null) {
                            org.json.JSONArray ba = info.optJSONArray("backdrop_path");
                            if (ba != null && ba.length() > 0) u = ba.optString(0);
                            if (u.isEmpty()) u = info.optString("backdrop");
                        }
                    } catch (Exception ignored) { }
                    backdrops.put(it.id, u);
                    it.backdrop = u;
                }
                return Boolean.TRUE;
            }
        }, new Net.Ok<Boolean>() {
            @Override public void ok(Boolean r) { fillHero(); }
        }, null);
    }

    /** Hero banners: the first few series and movies of the chosen folders (continue watching lives in its own row). */
    private void fillHero() {
        List<CardAdapter.Card> h = new ArrayList<>();
        if (mSeries != null) for (int i = 0; i < mSeries.size() && i < HERO_PER_ROW; i++) {
            Item it = mSeries.get(i);
            h.add(new CardAdapter.Card(it.name, it).sub("HMS IPTV").badge("NEW SERIES").img(it.backdrop.isEmpty() ? it.icon : it.backdrop));
        }
        if (mMovies != null) for (int i = 0; i < mMovies.size() && i < HERO_PER_ROW; i++) {
            Item it = mMovies.get(i);
            h.add(new CardAdapter.Card(it.name, it).sub(it.extra.isEmpty() ? "HMS IPTV" : it.extra).badge("NEW MOVIE").img(it.backdrop.isEmpty() ? it.icon : it.backdrop));
        }
        if (h.isEmpty()) {
            h.add(new CardAdapter.Card("Live TV", "live").sub("All channels").vec(R.drawable.ic_tv).colors(0xFF5B5CE6, 0xFF8E54E9));
            h.add(new CardAdapter.Card("Movies", "movie").sub("Browse the library").vec(R.drawable.ic_movie).colors(0xFFE5484D, 0xFFF2994A));
            h.add(new CardAdapter.Card("Series", "series").sub("Browse the library").vec(R.drawable.ic_series).colors(0xFF0E9F8E, 0xFF38C172));
        }
        hero.set(h);
    }
}
