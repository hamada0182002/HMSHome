package com.hms.home.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.hms.home.R;
import com.hms.home.data.ImageLoader;
import com.hms.home.data.Item;
import com.hms.home.data.Net;
import com.hms.home.data.Store;
import com.hms.home.data.Xtream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.json.JSONArray;
import org.json.JSONObject;

/** Movie or series page. Series: seasons + episodes with saved progress. */
public class DetailActivity extends BaseActivity {
    private Item item;
    private boolean series;
    private TextView meta, plot, btnPlay, btnFav;
    private final Map<String, List<Item>> seasons = new LinkedHashMap<>();
    private List<String> seasonKeys = new ArrayList<>();
    private TextAdapter seasonAd, epAd;
    private int curSeason = 0;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try { item = Item.fromJson(new JSONObject(getIntent().getStringExtra("item"))); } catch (Exception e) { finish(); return; }
        series = item.type.equals("series");
        setContentView(R.layout.activity_detail);
        ImageLoader.load((ImageView) findViewById(R.id.poster), item.icon, Ui.dp(this, 220), Ui.dp(this, 330));
        ((TextView) findViewById(R.id.title)).setText(item.name);
        meta = findViewById(R.id.meta); plot = findViewById(R.id.plot);
        meta.setText(item.extra);
        LinearLayout btns = findViewById(R.id.buttons);
        btnPlay = Ui.button(this, "PLAY");
        btnFav = Ui.button(this, "");
        btns.addView(btnPlay); btns.addView(btnFav);
        updateFav();
        btnFav.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { Store.toggleFav(item); updateFav(); } });
        btnPlay.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { playMain(); } });
        btnPlay.requestFocus();

        if (series) setupSeries(); else loadMovie();
    }

    @Override protected void onResume() { super.onResume(); refreshProgress(); }

    private void updateFav() { btnFav.setText(Store.isFav(item) ? "\u2605 In favorites" : "\u2606 Add to favorites"); }

    private void refreshProgress() {
        if (item == null) return;
        if (!series) {
            long p = Store.getPosition(item);
            btnPlay.setText(p > 0 ? "RESUME " + Ui.fmt(p) : "PLAY");
        } else if (epAd != null) { showEpisodes(); }
    }

    private void playMain() {
        if (!series) { Nav.play(this, item); return; }
        if (seasons.isEmpty()) return;
        for (int s = 0; s < seasonKeys.size(); s++) {
            List<Item> eps = seasons.get(seasonKeys.get(s));
            for (int i = 0; i < eps.size(); i++) if (Store.getPosition(eps.get(i)) > 0) { playEpisode(s, i); return; }
        }
        playEpisode(0, 0);
    }

    private void playEpisode(int season, int idx) {
        com.hms.home.data.PlayQueue.set(seasons.get(seasonKeys.get(season)), idx);
        startActivity(new android.content.Intent(this, com.hms.home.player.PlayerActivity.class));
    }

    private void loadMovie() {
        run(new Callable<JSONObject>() {
            @Override public JSONObject call() throws Exception { return Xtream.vodInfo(item.id); }
        }, new Net.Ok<JSONObject>() {
            @Override public void ok(JSONObject o) {
                JSONObject i = o.optJSONObject("info");
                JSONObject md = o.optJSONObject("movie_data");
                if (md != null && !md.optString("container_extension").isEmpty()) item.ext = md.optString("container_extension");
                if (i == null) return;
                plot.setText(i.optString("plot", i.optString("description", "")));
                String m = join(i.optString("releasedate", i.optString("releaseDate", "")), i.optString("genre", ""), i.optString("duration", ""),
                        i.optString("rating", "").isEmpty() ? "" : "\u2605" + i.optString("rating"));
                if (!m.isEmpty()) meta.setText(m);
                String cover = i.optString("movie_image", "");
                if (!cover.isEmpty()) ImageLoader.load((ImageView) findViewById(R.id.poster), cover, Ui.dp(DetailActivity.this, 220), Ui.dp(DetailActivity.this, 330));
            }
        }, new Net.Fail() { @Override public void fail(Exception e) { plot.setText(Net.msg(e)); } });
    }

    private static String join(String... p) {
        StringBuilder sb = new StringBuilder();
        for (String s : p) if (s != null && !s.isEmpty() && !s.equals("null")) { if (sb.length() > 0) sb.append("  •  "); sb.append(s); }
        return sb.toString();
    }

    private void setupSeries() {
        RecyclerView sr = findViewById(R.id.seasons), er = findViewById(R.id.episodes);
        sr.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        er.setLayoutManager(new LinearLayoutManager(this));
        seasonAd = new TextAdapter(true, new TextAdapter.Listener() {
            @Override public void click(TextAdapter.Row r, int pos) { curSeason = pos; seasonAd.setSelected(pos); showEpisodes(); }
            @Override public void focus(TextAdapter.Row r, int pos) { }
        });
        epAd = new TextAdapter(false, new TextAdapter.Listener() {
            @Override public void click(TextAdapter.Row r, int pos) { playEpisode(curSeason, pos); }
            @Override public void focus(TextAdapter.Row r, int pos) { }
        });
        sr.setAdapter(seasonAd); er.setAdapter(epAd);
        sr.setVisibility(View.VISIBLE); er.setVisibility(View.VISIBLE);
        plot.setText("Loading episodes…");
        run(new Callable<JSONObject>() {
            @Override public JSONObject call() throws Exception { return Xtream.seriesInfo(item.id); }
        }, new Net.Ok<JSONObject>() {
            @Override public void ok(JSONObject o) { parseSeries(o); }
        }, new Net.Fail() { @Override public void fail(Exception e) { plot.setText(Net.msg(e)); } });
    }

    private void parseSeries(JSONObject o) {
        JSONObject i = o.optJSONObject("info");
        if (i != null) {
            plot.setText(i.optString("plot", ""));
            String m = join(i.optString("releaseDate", i.optString("releasedate", "")), i.optString("genre", ""),
                    i.optString("rating", "").isEmpty() ? "" : "\u2605" + i.optString("rating"));
            if (!m.isEmpty()) meta.setText(m);
        } else plot.setText("");
        JSONObject eps = o.optJSONObject("episodes");
        seasons.clear(); seasonKeys = new ArrayList<>();
        if (eps != null) {
            java.util.Iterator<String> it = eps.keys();
            List<String> keys = new ArrayList<>();
            while (it.hasNext()) keys.add(it.next());
            Collections.sort(keys, new Comparator<String>() {
                @Override public int compare(String a, String b) { try { return Integer.parseInt(a) - Integer.parseInt(b); } catch (Exception e) { return a.compareTo(b); } }
            });
            for (String k : keys) {
                JSONArray arr = eps.optJSONArray(k);
                if (arr == null) continue;
                List<Item> l = new ArrayList<>();
                for (int n = 0; n < arr.length(); n++) {
                    JSONObject e = arr.optJSONObject(n);
                    if (e == null) continue;
                    Item ep = new Item();
                    ep.type = "episode"; ep.id = e.optString("id");
                    ep.sid = item.id; ep.sname = item.name; ep.sicon = item.icon;
                    ep.ext = e.optString("container_extension", "mp4");
                    String num = e.optString("episode_num");
                    String t = e.optString("title", "");
                    ep.name = item.name + "  S" + k + "E" + num;
                    ep.extra = t.isEmpty() ? "Episode " + num : t;
                    JSONObject inf = e.optJSONObject("info");
                    ep.icon = inf != null && !inf.optString("movie_image").isEmpty() ? inf.optString("movie_image") : item.icon;
                    if (!ep.id.isEmpty()) l.add(ep);
                }
                if (!l.isEmpty()) { seasons.put(k, l); seasonKeys.add(k); }
            }
        }
        if (seasonKeys.isEmpty()) { plot.setText("مفيش حلقات متاحة للمسلسل ده"); return; }
        List<TextAdapter.Row> sr = new ArrayList<>();
        for (String k : seasonKeys) sr.add(new TextAdapter.Row("Season " + k, null, k));
        seasonAd.set(sr);
        curSeason = 0;
        String focusEp = getIntent().getStringExtra("focusEp");
        int focusIdx = -1;
        if (focusEp != null) {
            for (int s = 0; s < seasonKeys.size() && focusIdx < 0; s++) {
                List<Item> l = seasons.get(seasonKeys.get(s));
                for (int n = 0; n < l.size(); n++) if (l.get(n).id.equals(focusEp)) { curSeason = s; focusIdx = n; break; }
            }
        }
        seasonAd.setSelected(curSeason);
        showEpisodes();
        refreshProgress();
        if (focusIdx >= 0) {
            getIntent().removeExtra("focusEp");
            FocusNav.focusPos((RecyclerView) findViewById(R.id.episodes), focusIdx);   // open on the episode being watched: next / previous are one key away
        }
    }

    private void showEpisodes() {
        if (seasonKeys.isEmpty()) return;
        List<TextAdapter.Row> r = new ArrayList<>();
        for (Item ep : seasons.get(seasonKeys.get(curSeason))) {
            long p = Store.getPosition(ep);
            String label = ep.name.substring(ep.name.lastIndexOf("  S") + 2);
            r.add(new TextAdapter.Row(label + "  -  " + ep.extra, p > 0 ? "Resume " + Ui.fmt(p) : null, ep));
        }
        epAd.set(r);
    }
}
