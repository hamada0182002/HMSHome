package com.hms.home.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Tracks;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.trackselection.TrackSelectionOverride;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.DefaultTimeBar;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.ui.TimeBar;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.hms.home.R;
import com.hms.home.data.Item;
import com.hms.home.data.Net;
import com.hms.home.data.PlayQueue;
import com.hms.home.data.Store;
import com.hms.home.data.Xtream;
import com.hms.home.ui.TextAdapter;
import com.hms.home.ui.Ui;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** The one internal player: live channels, movies and episodes. */
public class PlayerActivity extends Activity {
    private ExoPlayer player;
    private PlayerView pv;
    private View top, controls, chanPanel, timeRow;
    private TextView status, ptitle, pinfo, tpos, tdur;
    private DefaultTimeBar timebar;
    private TextView bPlay, bBack, bFwd, bAudio, bSubs, bAspect, bChan;
    private RecyclerView chanList;
    private TextAdapter chanAd;
    private final Handler h = new Handler(Looper.getMainLooper());

    private Item cur;
    private int idx;
    private boolean live, failed, tsTried;
    private String liveExt = "m3u8";
    private int retries;
    private long resume = C.TIME_UNSET;
    private int aspect;
    private Net.Task epgTask;
    private boolean scrubbing;

    private static final int[] MODES = {AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_FILL, AspectRatioFrameLayout.RESIZE_MODE_ZOOM};
    private static final String[] MODE_NAMES = {"Fit", "Stretch", "Zoom"};

    private final Runnable hideRx = new Runnable() { @Override public void run() { hideControls(); } };
    private final Runnable retryRx = new Runnable() { @Override public void run() { startCurrent(); } };
    private final Runnable tick = new Runnable() {
        @Override public void run() { updateTime(); h.postDelayed(this, 1000); }
    };
    private final Runnable saveRx = new Runnable() {
        @Override public void run() { saveProgress(); h.postDelayed(this, 15000); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);
        pv = findViewById(R.id.pv); top = findViewById(R.id.top); controls = findViewById(R.id.controls);
        chanPanel = findViewById(R.id.chanPanel); timeRow = findViewById(R.id.timeRow);
        status = findViewById(R.id.status); ptitle = findViewById(R.id.ptitle); pinfo = findViewById(R.id.pinfo);
        tpos = findViewById(R.id.tpos); tdur = findViewById(R.id.tdur); timebar = findViewById(R.id.timebar);
        chanList = findViewById(R.id.chanList);

        aspect = Store.aspect();
        pv.setResizeMode(MODES[aspect]);
        idx = PlayQueue.index;

        LinearLayout row = findViewById(R.id.btnRow);
        bPlay = btn(row, "PAUSE"); bBack = btn(row, "-10s"); bFwd = btn(row, "+10s");
        bAudio = btn(row, "AUDIO"); bSubs = btn(row, "SUBTITLES"); bAspect = btn(row, "ASPECT: " + MODE_NAMES[aspect]); bChan = btn(row, "CHANNELS");
        bPlay.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { togglePlay(); } });
        bBack.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { seekBy(-10000); } });
        bFwd.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { seekBy(10000); } });
        bAudio.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickTrack(C.TRACK_TYPE_AUDIO); } });
        bSubs.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickTrack(C.TRACK_TYPE_TEXT); } });
        bAspect.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            aspect = (aspect + 1) % MODES.length; pv.setResizeMode(MODES[aspect]); Store.setAspect(aspect);
            bAspect.setText("ASPECT: " + MODE_NAMES[aspect]);
        } });
        bChan.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showChannels(); } });

        timebar.addListener(new TimeBar.OnScrubListener() {
            @Override public void onScrubStart(TimeBar t, long pos) { scrubbing = true; }
            @Override public void onScrubMove(TimeBar t, long pos) { tpos.setText(Ui.fmt(pos)); }
            @Override public void onScrubStop(TimeBar t, long pos, boolean canceled) {
                scrubbing = false;
                if (!canceled && player != null) player.seekTo(pos);
            }
        });

        chanList.setLayoutManager(new LinearLayoutManager(this));
        chanAd = new TextAdapter(false, new TextAdapter.Listener() {
            @Override public void click(TextAdapter.Row r, int pos) { hideChannels(); startItem(pos); }
            @Override public void focus(TextAdapter.Row r, int pos) { }
        });
        chanList.setAdapter(chanAd);
    }

    private TextView btn(LinearLayout row, String s) {
        TextView t = Ui.button(this, s);
        row.addView(t);
        return t;
    }

    // ---------------- lifecycle: one player instance only ----------------
    @Override protected void onStart() {
        super.onStart();
        if (PlayQueue.items.isEmpty()) { finish(); return; }
        initPlayer();
        startItem(Math.min(idx, PlayQueue.items.size() - 1));
        h.post(tick);
        h.postDelayed(saveRx, 15000);
    }

    @Override protected void onStop() {
        saveProgress();
        h.removeCallbacksAndMessages(null);
        if (epgTask != null) epgTask.cancel();
        releasePlayer();
        super.onStop();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.immersive(getWindow());
    }

    private void initPlayer() {
        if (player != null) return;
        DefaultHttpDataSource.Factory hf = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(10000).setReadTimeoutMs(15000).setAllowCrossProtocolRedirects(true)
                .setUserAgent(com.hms.home.data.Net.ua());
        DefaultLoadControl lc = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 30000, 2000, 4000)
                .setTargetBufferBytes(12 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false).build();
        DefaultRenderersFactory rf = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);
        player = new ExoPlayer.Builder(this, rf)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(hf))
                .setLoadControl(lc).build();
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !Store.subsDefault()).build());
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int st) {
                if (st == Player.STATE_BUFFERING) showStatus("Buffering…");
                else if (st == Player.STATE_READY) { status.setVisibility(View.GONE); retries = 0; failed = false; }
                else if (st == Player.STATE_ENDED) onEnded();
            }
            @Override public void onIsPlayingChanged(boolean p) { bPlay.setText(p ? "PAUSE" : "PLAY"); }
            @Override public void onPlayerError(PlaybackException e) { handleError(e); }
        });
        pv.setPlayer(player);
    }

    private void releasePlayer() {
        if (player != null) { pv.setPlayer(null); player.release(); player = null; }
    }

    // ---------------- playback ----------------
    private void startItem(int i) {
        idx = i;
        PlayQueue.index = i;
        cur = PlayQueue.items.get(i);
        live = cur.type.equals("live");
        retries = 0; failed = false; tsTried = false; liveExt = "m3u8";
        resume = live ? C.TIME_UNSET : Store.getPosition(cur);
        ptitle.setText(cur.name);
        pinfo.setText(cur.type.equals("episode") ? cur.extra : "");
        int vod = live ? View.GONE : View.VISIBLE;
        timeRow.setVisibility(vod); bBack.setVisibility(vod); bFwd.setVisibility(vod);
        bChan.setVisibility(live && PlayQueue.items.size() > 1 ? View.VISIBLE : View.GONE);
        if (live) loadEpg();
        flashTop();
        startCurrent();
    }

    private void startCurrent() {
        if (player == null || cur == null) return;
        if (!live && player.getCurrentPosition() > 0 && failed) resume = player.getCurrentPosition();
        failed = false;
        showStatus("Loading…");
        player.setMediaItem(MediaItem.fromUri(Xtream.streamUrl(cur, liveExt)), live ? C.TIME_UNSET : resume);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void handleError(PlaybackException e) {
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && player != null) {
            player.seekToDefaultPosition(); player.prepare(); return;
        }
        if (live && !tsTried && liveExt.equals("m3u8")) { tsTried = true; liveExt = "ts"; failed = true; showStatus("Trying another stream format…"); h.postDelayed(retryRx, 500); return; }
        failed = true;
        if (retries < 3) {
            retries++;
            showStatus(reason(e) + "\nإعادة المحاولة (" + retries + "/3)…");
            h.postDelayed(retryRx, 3000);
        } else {
            showStatus(reason(e) + "\nاضغط OK لإعادة المحاولة");
        }
    }

    private String reason(PlaybackException e) {
        int g = e.errorCode / 1000;
        if (g == 2) return "تعذر الاتصال بالسيرفر / انقطع الاتصال";
        if (g == 4) return "الجهاز مش بيدعم ترميز الفيديو ده";
        if (g == 3) return "ملف الفيديو غير مدعوم أو تالف";
        return "تعذر تشغيل الفيديو";
    }

    private void onEnded() {
        if (live) return;
        Store.saveProgress(cur, 1, 1);
        if (idx + 1 < PlayQueue.items.size()) startItem(idx + 1); else finish();
    }

    private void saveProgress() {
        if (player == null || cur == null || live) return;
        long d = player.getDuration();
        if (d > 0 && d != C.TIME_UNSET) Store.saveProgress(cur, player.getCurrentPosition(), d);
    }

    private void togglePlay() {
        if (player == null) return;
        if (failed) { retries = 0; h.removeCallbacks(retryRx); startCurrent(); return; }
        player.setPlayWhenReady(!player.getPlayWhenReady());
    }

    private void seekBy(long ms) {
        if (player == null || live) return;
        long d = player.getDuration();
        long t = Math.max(0, player.getCurrentPosition() + ms);
        if (d > 0 && d != C.TIME_UNSET) t = Math.min(t, d - 1000);
        player.seekTo(t);
        updateTime();
        flashTop();
        pinfo.setText((ms > 0 ? ">> " : "<< ") + Ui.fmt(t));
    }

    private void zap(int delta) {
        int n = PlayQueue.items.size();
        if (n < 2) return;
        startItem(((idx + delta) % n + n) % n);
    }

    private void loadEpg() {
        if (epgTask != null) epgTask.cancel();
        final String id = cur.id;
        epgTask = Net.run(new Callable<String[]>() {
            @Override public String[] call() throws Exception { return Xtream.epg(id); }
        }, new Net.Ok<String[]>() {
            @Override public void ok(String[] r) {
                if (!r[0].isEmpty()) pinfo.setText("NOW: " + r[0] + (r[1].isEmpty() ? "" : "     NEXT: " + r[1]));
            }
        }, null);
    }

    // ---------------- tracks ----------------
    private void pickTrack(final int type) {
        if (player == null) return;
        final List<String> names = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        if (type == C.TRACK_TYPE_TEXT) {
            names.add("Off");
            acts.add(new Runnable() { @Override public void run() {
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
            } });
        }
        Tracks tr = player.getCurrentTracks();
        for (Tracks.Group g : tr.getGroups()) {
            if (g.getType() != type) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                Format f = g.getTrackFormat(i);
                String n = f.label != null ? f.label : (f.language != null ? f.language : "Track " + (names.size() + 1));
                if (type == C.TRACK_TYPE_AUDIO && f.channelCount > 0) n += "  (" + f.channelCount + "ch)";
                names.add(n);
                final TrackSelectionOverride ov = new TrackSelectionOverride(g.getMediaTrackGroup(), i);
                acts.add(new Runnable() { @Override public void run() {
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                            .clearOverridesOfType(type).addOverride(ov).setTrackTypeDisabled(type, false).build());
                } });
            }
        }
        if (names.isEmpty() || (type == C.TRACK_TYPE_TEXT && names.size() == 1)) {
            Toast.makeText(this, type == C.TRACK_TYPE_AUDIO ? "مفيش مسارات صوت إضافية" : "مفيش ترجمة متاحة", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle(type == C.TRACK_TYPE_AUDIO ? "Audio" : "Subtitles")
                .setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { if (player != null) acts.get(w).run(); }
                }).show();
    }

    // ---------------- overlays ----------------
    private void showStatus(String s) { status.setText(s); status.setVisibility(View.VISIBLE); }

    private void flashTop() {
        top.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideRx);
        h.postDelayed(hideRx, 4000);
    }

    private void showControls() {
        top.setVisibility(View.VISIBLE);
        controls.setVisibility(View.VISIBLE);
        updateTime();
        bPlay.requestFocus();
        scheduleHide();
    }

    private void hideControls() { controls.setVisibility(View.GONE); top.setVisibility(View.GONE); }

    private void scheduleHide() { h.removeCallbacks(hideRx); h.postDelayed(hideRx, 6000); }

    private void showChannels() {
        List<TextAdapter.Row> rows = new ArrayList<>();
        for (Item it : PlayQueue.items) rows.add(new TextAdapter.Row(it.name, null, it));
        chanAd.set(rows);
        chanAd.setSelected(idx);
        chanPanel.setVisibility(View.VISIBLE);
        hideControls();
        chanList.scrollToPosition(idx);
        chanList.post(new Runnable() { @Override public void run() {
            RecyclerView.ViewHolder vh = chanList.findViewHolderForAdapterPosition(idx);
            if (vh != null) vh.itemView.requestFocus(); else chanList.requestFocus();
        } });
    }

    private void hideChannels() { chanPanel.setVisibility(View.GONE); }

    private void updateTime() {
        if (player == null || live || controls.getVisibility() != View.VISIBLE || scrubbing) return;
        long d = player.getDuration();
        if (d == C.TIME_UNSET || d <= 0) return;
        timebar.setDuration(d);
        timebar.setPosition(player.getCurrentPosition());
        timebar.setBufferedPosition(player.getBufferedPosition());
        tpos.setText(Ui.fmt(player.getCurrentPosition()));
        tdur.setText(Ui.fmt(d));
    }

    // ---------------- remote keys ----------------
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        boolean r = super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN && controls.getVisibility() == View.VISIBLE) scheduleHide();
        return r;
    }

    @Override public boolean onKeyDown(int k, KeyEvent e) {
        if (chanPanel.getVisibility() == View.VISIBLE) return super.onKeyDown(k, e);
        boolean ctrl = controls.getVisibility() == View.VISIBLE;
        switch (k) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE:
                togglePlay(); return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: seekBy(30000); return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND: seekBy(-30000); return true;
            case KeyEvent.KEYCODE_CHANNEL_UP: if (live) { zap(1); return true; } break;
            case KeyEvent.KEYCODE_CHANNEL_DOWN: if (live) { zap(-1); return true; } break;
            case KeyEvent.KEYCODE_MENU:
                if (live && PlayQueue.items.size() > 1) showChannels(); else showControls();
                return true;
            default: break;
        }
        if (!ctrl) {
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                    if (failed) { togglePlay(); return true; }
                    showControls(); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: if (!live) { seekBy(-10000); return true; } break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: if (!live) { seekBy(10000); return true; } break;
                case KeyEvent.KEYCODE_DPAD_UP: if (live) zap(1); else showControls(); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: if (live) zap(-1); else showControls(); return true;
                default: break;
            }
        }
        return super.onKeyDown(k, e);
    }

    @Override public void onBackPressed() {
        if (chanPanel.getVisibility() == View.VISIBLE) { hideChannels(); return; }
        if (controls.getVisibility() == View.VISIBLE) { hideControls(); return; }
        finish();
    }
}
