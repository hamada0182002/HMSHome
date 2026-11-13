package com.hms.home.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.hms.home.R;
import com.hms.home.data.Net;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The Holy Quran (audio): reciters on one side, surahs on the other, a "now playing" card on top.
 * Same data source as the web version (mp3quran.net) but with the launcher's look and remote-control focus.
 */
public class QuranActivity extends BaseActivity {
    private static final String API = "https://www.mp3quran.net/api/v3/reciters?language=ar";

    private static final String[] NAMES = ("الفاتحة,البقرة,آل عمران,النساء,المائدة,الأنعام,الأعراف,الأنفال,التوبة,يونس,هود,يوسف,الرعد,إبراهيم,الحجر,النحل,الإسراء,الكهف,مريم,طه,"
            + "الأنبياء,الحج,المؤمنون,النور,الفرقان,الشعراء,النمل,القصص,العنكبوت,الروم,لقمان,السجدة,الأحزاب,سبأ,فاطر,يس,الصافات,ص,الزمر,غافر,"
            + "فصلت,الشورى,الزخرف,الدخان,الجاثية,الأحقاف,محمد,الفتح,الحجرات,ق,الذاريات,الطور,النجم,القمر,الرحمن,الواقعة,الحديد,المجادلة,الحشر,الممتحنة,"
            + "الصف,الجمعة,المنافقون,التغابن,الطلاق,التحريم,الملك,القلم,الحاقة,المعارج,نوح,الجن,المزمل,المدثر,القيامة,الإنسان,المرسلات,النبأ,النازعات,عبس,"
            + "التكوير,الانفطار,المطففين,الانشقاق,البروج,الطارق,الأعلى,الغاشية,الفجر,البلد,الشمس,الليل,الضحى,الشرح,التين,العلق,القدر,البينة,الزلزلة,العاديات,"
            + "القارعة,التكاثر,العصر,الهمزة,الفيل,قريش,الماعون,الكوثر,الكافرون,النصر,المسد,الإخلاص,الفلق,الناس").split(",");

    /** Popular reciters first (matched against the normalized name). */
    private static final String[] FAV = {"المعيقلي", "العفاسي", "عبدالباسط", "الحصري", "المنشاوي", "السديس", "الشريم", "الغامدي", "الدوسري",
            "الطبلاوي", "القطامي", "العجمي", "محمدايوب", "محمدجبريل"};

    private static final String[][] FALLBACK = {
            {"f1", "ماهر المعيقلي", "https://server12.mp3quran.net/maher/"},
            {"f2", "محمد الطبلاوي", "https://server12.mp3quran.net/tblawi/"},
            {"f3", "محمد أيوب", "https://server8.mp3quran.net/ayyub/"},
            {"f4", "محمد جبريل", "https://server8.mp3quran.net/jbrl/"},
            {"f5", "أكرم العلاقمي", "https://server9.mp3quran.net/akrm/"}};

    private static class Rec {
        String id, name, server;
        List<Integer> surahs = new ArrayList<>();
    }

    private static class Prog {
        Rec rec; int n; long t;
    }

    private final List<Rec> recs = new ArrayList<>();
    private Rec curRec;                 // reciter whose surahs are shown
    private Rec playRec; private int playN;
    private RecyclerView recRv, surRv;
    private TextAdapter recAd, surAd;
    private TextView status, nowName, nowSub, tCur, tDur, btnPlay, surLabel;
    private ProgressBar bar;
    private View playerCard;
    private ExoPlayer exo;
    private boolean triedHttp;
    private long lastSave;
    private SharedPreferences sp;
    private final Handler h = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (exo != null) {
                long d = exo.getDuration(), p = exo.getCurrentPosition();
                if (d < 0) d = 0;
                tCur.setText(Ui.fmt(p)); tDur.setText(Ui.fmt(d));
                bar.setProgress(d > 0 ? (int) Math.min(1000, p * 1000 / d) : 0);
                if (System.currentTimeMillis() - lastSave > 5000) { lastSave = System.currentTimeMillis(); saveProg(); }
            }
            h.postDelayed(this, 500);
        }
    };

    // ---------------- small helpers ----------------
    private static String norm(String t) { return t == null ? "" : t.replaceAll("[أإآ]", "ا").replaceAll("[\\sـ\\u064B-\\u0652]", ""); }
    private static String pad(int n) { return String.format("%03d", n); }

    private TextView pill(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(16);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 10));
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackgroundResource(R.drawable.bg_button);
        t.setTextColor(ContextCompat.getColorStateList(this, R.color.btn_text));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(Ui.dp(this, 5), Ui.dp(this, 4), Ui.dp(this, 5), Ui.dp(this, 4));
        t.setLayoutParams(lp);
        return t;
    }

    private RecyclerView list(TextAdapter ad) {
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setClipToPadding(false);
        rv.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 24));
        rv.setAdapter(ad);
        return rv;
    }

    private LinearLayout column(String label, RecyclerView rv, TextView[] labelOut) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.label(this, label, 17, R.color.d_ink2, true);
        t.setPadding(Ui.dp(this, 6), 0, 0, Ui.dp(this, 2));
        col.addView(t, new LinearLayout.LayoutParams(-1, -2));
        col.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1f));
        if (labelOut != null) labelOut[0] = t;
        return col;
    }

    // ---------------- screen ----------------
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawableResource(R.drawable.bg_home_dark);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sp = getSharedPreferences("quran", Context.MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 48), Ui.dp(this, 22), Ui.dp(this, 48), 0);
        root.setClipChildren(false);

        // title row
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.label(this, "القرآن الكريم", 28, R.color.white, true);
        head.addView(title, new LinearLayout.LayoutParams(-2, -2));
        status = Ui.label(this, "", 15, R.color.d_ink2, false);
        status.setGravity(Gravity.END);
        status.setPadding(Ui.dp(this, 16), 0, 0, 0);
        head.addView(status, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // now-playing card (above the lists so UP from the first row reaches the controls)
        playerCard = buildPlayer();
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2);
        pp.topMargin = Ui.dp(this, 10);
        root.addView(playerCard, pp);
        playerCard.setVisibility(View.GONE);

        // two columns: reciters | surahs
        recAd = new TextAdapter(false, false, new TextAdapter.Listener() {
            @Override public void click(TextAdapter.Row r, int pos) { onReciter(r); }
            @Override public void focus(TextAdapter.Row r, int pos) { }
        });
        surAd = new TextAdapter(false, false, new TextAdapter.Listener() {
            @Override public void click(TextAdapter.Row r, int pos) {
                if (r.data instanceof Integer && curRec != null) play(curRec, (Integer) r.data, 0);
            }
            @Override public void focus(TextAdapter.Row r, int pos) { }
        });
        recRv = list(recAd);
        surRv = list(surAd);
        TextView[] lab = new TextView[1];
        LinearLayout c1 = column("القرّاء", recRv, null);
        LinearLayout c2 = column("السور", surRv, lab);
        surLabel = lab[0];
        LinearLayout cols = new LinearLayout(this);
        cols.setClipChildren(false);
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, -1, 1f);
        l1.rightMargin = Ui.dp(this, 14);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, -1, 1f);
        l2.leftMargin = Ui.dp(this, 14);
        cols.addView(c1, l1);
        cols.addView(c2, l2);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, 0, 1f);
        cp.topMargin = Ui.dp(this, 8);
        root.addView(cols, cp);
        setContentView(root);

        List<TextAdapter.Row> hint = new ArrayList<>();
        hint.add(new TextAdapter.Row("اختر القارئ لعرض السور", "", null));
        surAd.set(hint);
        loadRecs();
    }

    private View buildPlayer() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_apps_bar);
        card.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 8));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        nowName = Ui.label(this, "", 24, R.color.white, true);
        nowSub = Ui.label(this, "", 16, R.color.d_ink2, false);
        nowSub.setPadding(Ui.dp(this, 16), 0, 0, 0);
        top.addView(nowName, new LinearLayout.LayoutParams(-2, -2));
        top.addView(nowSub, new LinearLayout.LayoutParams(-2, -2));
        card.addView(top, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout prog = new LinearLayout(this);
        prog.setGravity(Gravity.CENTER_VERTICAL);
        tCur = Ui.label(this, "00:00", 14, R.color.d_ink2, false);
        tDur = Ui.label(this, "00:00", 14, R.color.d_ink2, false);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.getProgressDrawable().setColorFilter(ContextCompat.getColor(this, R.color.h_yellow), android.graphics.PorterDuff.Mode.SRC_IN);
        prog.addView(tCur, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, Ui.dp(this, 8), 1f);
        bl.setMargins(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        prog.addView(bar, bl);
        prog.addView(tDur, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, -2);
        pl.topMargin = Ui.dp(this, 6);
        card.addView(prog, pl);

        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.CENTER);
        TextView prev = pill("السورة السابقة"), back = pill("- 10 ث"), next = pill("السورة التالية"), fwd = pill("+ 10 ث");
        btnPlay = pill("إيقاف");
        prev.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { step(-1); } });
        next.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { step(1); } });
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { seek(-10000); } });
        fwd.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { seek(10000); } });
        btnPlay.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { toggle(); } });
        btns.addView(prev); btns.addView(back); btns.addView(btnPlay); btns.addView(fwd); btns.addView(next);
        card.addView(btns, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    // ---------------- reciters ----------------
    private void loadRecs() {
        status.setText("جاري تحميل القرّاء...");
        List<Rec> cached = readCache();
        if (cached != null) { recs.clear(); recs.addAll(order(cached)); showRecs(); return; }
        run(new Callable<List<Rec>>() {
            @Override public List<Rec> call() throws Exception {
                String body = Net.readWeb(API, Net.UA, "application/json");
                return parse(new JSONObject(body).optJSONArray("reciters"));
            }
        }, new Net.Ok<List<Rec>>() {
            @Override public void ok(List<Rec> r) {
                if (r.isEmpty()) { useFallback(); return; }
                writeCache(r);
                recs.clear(); recs.addAll(order(r)); showRecs();
            }
        }, new Net.Fail() {
            @Override public void fail(Exception e) { useFallback(); }
        });
    }

    private void useFallback() {
        recs.clear();
        for (String[] f : FALLBACK) {
            Rec r = new Rec(); r.id = f[0]; r.name = f[1]; r.server = f[2];
            for (int i = 1; i <= 114; i++) r.surahs.add(i);
            recs.add(r);
        }
        showRecs();
    }

    /** Picks the best recording (complete, Hafs, murattal) of every reciter that has a server. */
    private List<Rec> parse(JSONArray rs) {
        List<Rec> out = new ArrayList<>();
        if (rs == null) return out;
        for (int i = 0; i < rs.length(); i++) {
            JSONObject r = rs.optJSONObject(i);
            if (r == null) continue;
            JSONArray ms = r.optJSONArray("moshaf");
            if (ms == null) continue;
            JSONObject best = null; int bestSc = -1;
            for (int j = 0; j < ms.length(); j++) {
                JSONObject m = ms.optJSONObject(j);
                if (m == null || m.optString("server").isEmpty()) continue;
                int tot = m.optInt("surah_total", 0);
                String nm = m.optString("name");
                int sc = tot + (tot >= 114 ? 2000 : 0) + (nm.contains("حفص") ? 1000 : 0) + (nm.contains("مرتل") ? 500 : 0);
                if (sc > bestSc) { bestSc = sc; best = m; }
            }
            if (best == null) continue;
            Rec rec = new Rec();
            rec.id = String.valueOf(r.optInt("id"));
            rec.name = r.optString("name");
            String s = best.optString("server").replaceFirst("^http:", "https:");
            if (!s.endsWith("/")) s += "/";
            rec.server = s;
            for (String p : best.optString("surah_list").split(",")) {
                try { int n = Integer.parseInt(p.trim()); if (n >= 1 && n <= 114) rec.surahs.add(n); } catch (NumberFormatException ignored) { }
            }
            if (!rec.surahs.isEmpty()) out.add(rec);
        }
        return out;
    }

    private List<Rec> order(List<Rec> in) {
        final java.util.Map<Rec, Integer> rank = new java.util.HashMap<>();
        for (Rec r : in) {
            String n = norm(r.name); int k = 99;
            for (int i = 0; i < FAV.length; i++) if (n.contains(FAV[i])) { k = i; break; }
            rank.put(r, k);
        }
        List<Rec> out = new ArrayList<>(in);
        Collections.sort(out, new Comparator<Rec>() {   // stable: same rank keeps the server's order
            @Override public int compare(Rec a, Rec b) { return rank.get(a) - rank.get(b); }
        });
        return out;
    }

    private List<Rec> readCache() {
        try {
            JSONObject o = new JSONObject(sp.getString("recs", "null"));
            if (System.currentTimeMillis() - o.optLong("t") > 7L * 86400000L) return null;
            JSONArray a = o.getJSONArray("d");
            List<Rec> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.getJSONObject(i);
                Rec r = new Rec(); r.id = j.optString("i"); r.name = j.optString("n"); r.server = j.optString("s");
                for (String p : j.optString("l").split(",")) { try { r.surahs.add(Integer.parseInt(p)); } catch (NumberFormatException ignored) { } }
                if (!r.surahs.isEmpty()) out.add(r);
            }
            return out.isEmpty() ? null : out;
        } catch (Exception e) { return null; }
    }

    private void writeCache(List<Rec> l) {
        try {
            JSONArray a = new JSONArray();
            for (Rec r : l) {
                StringBuilder sb = new StringBuilder();
                for (int n : r.surahs) { if (sb.length() > 0) sb.append(','); sb.append(n); }
                a.put(new JSONObject().put("i", r.id).put("n", r.name).put("s", r.server).put("l", sb.toString()));
            }
            sp.edit().putString("recs", new JSONObject().put("t", System.currentTimeMillis()).put("d", a).toString()).apply();
        } catch (Exception ignored) { }
    }

    private Prog readProg() {
        String s = sp.getString("prog", null);
        if (s == null) return null;
        try {
            String[] p = s.split("\\|");
            for (Rec r : recs) if (r.id.equals(p[0])) {
                Prog g = new Prog(); g.rec = r; g.n = Integer.parseInt(p[1]); g.t = Long.parseLong(p[2]);
                return g;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void saveProg() {
        if (playRec == null || exo == null) return;
        sp.edit().putString("prog", playRec.id + "|" + playN + "|" + Math.max(0, exo.getCurrentPosition())).apply();
    }

    private void showRecs() {
        status.setText("");
        List<TextAdapter.Row> rows = new ArrayList<>();
        Prog p = readProg();
        if (p != null) rows.add(new TextAdapter.Row("▶ متابعة: سورة " + NAMES[p.n - 1] + " - " + p.rec.name, Ui.fmt(p.t), p));
        for (Rec r : recs) rows.add(new TextAdapter.Row(r.name, r.surahs.size() < 114 ? r.surahs.size() + " سورة" : "", r));
        recAd.set(rows);
        FocusNav.focusPos(recRv, 0);
    }

    private void onReciter(TextAdapter.Row r) {
        if (r.data instanceof Prog) {
            Prog p = (Prog) r.data;
            showSurahs(p.rec, false);
            play(p.rec, p.n, p.t);
        } else if (r.data instanceof Rec) {
            showSurahs((Rec) r.data, true);
        }
    }

    private void showSurahs(Rec rec, boolean focus) {
        curRec = rec;
        surLabel.setText("السور - " + rec.name);
        List<TextAdapter.Row> rows = new ArrayList<>();
        int sel = -1;
        for (int i = 0; i < rec.surahs.size(); i++) {
            int n = rec.surahs.get(i);
            if (rec == playRec && n == playN) sel = i;
            rows.add(new TextAdapter.Row(n + ".  سورة " + NAMES[n - 1], "", n));
        }
        surAd.setSelected(-1);
        surAd.set(rows);
        surAd.setSelected(sel);
        if (focus) FocusNav.focusPos(surRv, Math.max(0, sel));
    }

    // ---------------- playback ----------------
    private final Player.Listener pl = new Player.Listener() {
        @Override public void onPlaybackStateChanged(int s) {
            if (s == Player.STATE_BUFFERING) status.setText("جاري التحميل...");
            else if (s == Player.STATE_READY) status.setText("");
            else if (s == Player.STATE_ENDED) { if (!step(1)) status.setText("انتهت السور المتاحة لهذا القارئ"); }
        }
        @Override public void onIsPlayingChanged(boolean playing) { btnPlay.setText(playing ? "إيقاف" : "تشغيل"); }
        @Override public void onPlayerError(PlaybackException e) {
            // old TVs sometimes reject the https certificate: retry the same file over http once
            if (!triedHttp && playRec != null && playRec.server.startsWith("https:")) {
                triedHttp = true;
                open("http:" + playRec.server.substring(6) + pad(playN) + ".mp3", 0);
                return;
            }
            status.setText("تعذر تشغيل هذه السورة. جرب سورة أو قارئ آخر.");
        }
    };

    private void open(String url, long startMs) {
        if (exo == null) { exo = new ExoPlayer.Builder(this).build(); exo.addListener(pl); h.post(tick); }
        exo.setMediaItem(MediaItem.fromUri(url), startMs);
        exo.prepare();
        exo.setPlayWhenReady(true);
        status.setText("جاري التحميل...");
    }

    private void play(Rec r, int n, long startMs) {
        playRec = r; playN = n; triedHttp = false;
        open(r.server + pad(n) + ".mp3", startMs);
        playerCard.setVisibility(View.VISIBLE);
        nowName.setText("سورة " + NAMES[n - 1]);
        nowSub.setText(r.name + "  •  " + n + " / 114");
        if (curRec == r) {
            int idx = r.surahs.indexOf(n);
            surAd.setSelected(idx);
        }
        saveProg();
    }

    private boolean step(int d) {
        if (playRec == null) return false;
        int i = playRec.surahs.indexOf(playN) + d;
        if (i < 0 || i >= playRec.surahs.size()) return false;
        play(playRec, playRec.surahs.get(i), 0);
        return true;
    }

    private void toggle() {
        if (exo == null) return;
        if (exo.isPlaying() || exo.getPlayWhenReady()) exo.setPlayWhenReady(false); else exo.setPlayWhenReady(true);
    }

    private void seek(long ms) {
        if (exo == null) return;
        long t = exo.getCurrentPosition() + ms, d = exo.getDuration();
        if (t < 0) t = 0;
        if (d > 0 && t > d - 1000) t = d - 1000;
        exo.seekTo(t);
    }

    // ---------------- remote control ----------------
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: toggle(); return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY: if (exo != null) exo.setPlayWhenReady(true); return true;
                case KeyEvent.KEYCODE_MEDIA_PAUSE: case KeyEvent.KEYCODE_MEDIA_STOP: if (exo != null) exo.setPlayWhenReady(false); return true;
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: seek(30000); return true;
                case KeyEvent.KEYCODE_MEDIA_REWIND: seek(-30000); return true;
                case KeyEvent.KEYCODE_MEDIA_NEXT: step(1); return true;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS: step(-1); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (FocusNav.listOf(getCurrentFocus()) == recRv && curRec != null) {
                        int idx = playRec == curRec ? Math.max(0, curRec.surahs.indexOf(playN)) : 0;
                        FocusNav.focusPos(surRv, idx);
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (FocusNav.listOf(getCurrentFocus()) == surRv) { backToReciters(); return true; }
                    break;
                default: break;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private void backToReciters() {
        int idx = 0;
        if (curRec != null) for (int i = 0; i < recAd.size(); i++) if (recAd.get(i).data == curRec) { idx = i; break; }
        FocusNav.focusPos(recRv, idx);
    }

    @Override public void onBackPressed() {
        if (FocusNav.listOf(getCurrentFocus()) == surRv) backToReciters();
        else super.onBackPressed();
    }

    @Override protected void onStop() {
        saveProg();
        if (exo != null) exo.setPlayWhenReady(false);
        super.onStop();
    }

    @Override protected void onDestroy() {
        h.removeCallbacks(tick);
        if (exo != null) { exo.release(); exo = null; }
        super.onDestroy();
    }
}
