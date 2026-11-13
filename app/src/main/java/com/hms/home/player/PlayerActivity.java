package com.hms.home.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
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
import com.google.android.exoplayer2.video.VideoSize;
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
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * The one internal player (live, movies, episodes) - same behaviour and look as HMS Player:
 * gradient controls (top bar / centre transport / seek bar), auto-hide, auto-reconnect, resume,
 * next / previous, speed, audio + subtitle tracks, aspect modes, "next episode" in continue watching.
 */
public class PlayerActivity extends Activity {
    private ExoPlayer player;
    private PlayerView pv;
    private FrameLayout root, seekBox;
    private View overlay, failBox, chanPanel, spinner;
    private LinearLayout topBar, center, failBtns;
    private TextView ptitle, pinfo, psize, tpos, tdur, liveBadge, liveIdx, toastTv, msgTv, failMsg;
    private DefaultTimeBar timebar;
    private FrameLayout bBack, bAudio, bSubs, bAspect, bSpeed, bChan, bPrev, bRew, bPlay, bFwd, bNext;
    private ImageView playIcon;
    private TextView bRetry, bFailBack;
    private RecyclerView chanList;
    private TextAdapter chanAd;
    private final Handler h = new Handler(Looper.getMainLooper());

    private Item cur;
    private int idx;
    private boolean live, failed, everPlayed, buffering, show, retryPending, scrubbing;
    private String message;
    private int retries, aspect;
    private long stableSince, lastSave, pos, dur, pendingResume = C.TIME_UNSET;
    private float speed = 1f;
    private Net.Task epgTask;

    private static final String LIVE_EXT = "m3u8";   // /live/user/pass/id.m3u8
    private static final int[] MODES = {AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_FILL, AspectRatioFrameLayout.RESIZE_MODE_ZOOM};
    private static final String[] MODE_NAMES = {"الأصلي (ملائم)", "ملء الشاشة (تمديد)", "تكبير (قص الأطراف)"};
    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};

    // ---------------- timers ----------------
    private final Runnable hideRx = new Runnable() { @Override public void run() { if (show && player != null && player.isPlaying()) hideOverlay(); } };
    private final Runnable toastRx = new Runnable() { @Override public void run() { toastTv.setVisibility(View.GONE); } };
    private final Runnable focusPlayRx = new Runnable() { @Override public void run() { if (show) bPlay.requestFocus(); } };
    private final Runnable focusTopRx = new Runnable() { @Override public void run() { if (show) bAudio.requestFocus(); } };
    private final Runnable retryRx = new Runnable() {
        @Override public void run() { retryPending = false; load(cur, pendingResume); }
    };
    private final Runnable tickRx = new Runnable() {
        @Override public void run() { tick(); h.postDelayed(this, 500); }
    };

    // ---------------- build the screen ----------------
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);
        root = findViewById(R.id.root);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        pv = findViewById(R.id.pv);
        overlay = findViewById(R.id.overlay);
        topBar = findViewById(R.id.topBar);
        center = findViewById(R.id.center);
        failBox = findViewById(R.id.failBox);
        failBtns = findViewById(R.id.failBtns);
        chanPanel = findViewById(R.id.chanPanel);
        spinner = findViewById(R.id.spinner);
        ((ProgressBar) spinner).setIndeterminateTintList(ColorStateList.valueOf(0xFF1F7AF5));
        ptitle = findViewById(R.id.ptitle); pinfo = findViewById(R.id.pinfo); psize = findViewById(R.id.psize);
        tpos = findViewById(R.id.tpos); tdur = findViewById(R.id.tdur);
        liveBadge = findViewById(R.id.liveBadge); liveIdx = findViewById(R.id.liveIdx);
        toastTv = findViewById(R.id.toast); msgTv = findViewById(R.id.msg); failMsg = findViewById(R.id.failMsg);
        timebar = findViewById(R.id.timebar); seekBox = findViewById(R.id.seekBox);
        timebar.setFocusable(false);                       // the box around it takes the focus (left / right = seek)
        chanList = findViewById(R.id.chanList);

        aspect = Store.aspect();
        pv.setResizeMode(MODES[aspect]);
        idx = PlayQueue.index;

        // top bar: back | title | size | audio, subtitles, aspect, speed, channels
        bBack = iconBtn(R.drawable.ic_p_back, 44, false, "Back", null);
        topBar.addView(bBack, 0);
        bAudio = iconBtn(R.drawable.ic_p_audio, 44, false, "Audio", null);
        bSubs = iconBtn(R.drawable.ic_p_subs, 44, false, "Subtitles", null);
        bAspect = iconBtn(R.drawable.ic_p_aspect, 44, false, "Aspect ratio", null);
        bSpeed = iconBtn(R.drawable.ic_p_speed, 44, false, "Speed", null);
        bChan = iconBtn(R.drawable.ic_p_list, 44, false, "Channels", null);
        for (View v : new View[]{bAudio, bSubs, bAspect, bSpeed, bChan}) { topBar.addView(v); ((LinearLayout.LayoutParams) v.getLayoutParams()).leftMargin = Ui.dp(this, 10); }

        // centre transport: previous | -10 | play/pause | +10 | next
        bPrev = iconBtn(R.drawable.ic_p_prev, 54, false, "Previous", null);
        bRew = iconBtn(R.drawable.ic_p_replay, 60, false, "Back 10 seconds", "10");
        bPlay = iconBtn(R.drawable.ic_p_play, 78, true, "Play / Pause", null);
        bFwd = iconBtn(R.drawable.ic_p_forward, 60, false, "Forward 10 seconds", "10");
        bNext = iconBtn(R.drawable.ic_p_next, 54, false, "Next", null);
        playIcon = (ImageView) bPlay.getChildAt(0);
        for (View v : new View[]{bPrev, bRew, bPlay, bFwd, bNext}) {
            center.addView(v);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
            lp.leftMargin = lp.rightMargin = Ui.dp(this, 13);
        }

        bBack.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { close(); } });
        bAudio.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickTrack(C.TRACK_TYPE_AUDIO); } });
        bSubs.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickTrack(C.TRACK_TYPE_TEXT); } });
        bAspect.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { cycleResize(); } });
        bSpeed.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickSpeed(); } });
        bChan.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showChannels(); } });
        bPrev.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { prev(); } });
        bNext.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { next(); } });
        bRew.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { seekBy(-10000); } });
        bFwd.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { seekBy(10000); } });
        bPlay.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { toggle(); } });

        bRetry = Ui.button(this, "إعادة المحاولة");
        bFailBack = Ui.button(this, "رجوع");
        failBtns.addView(bRetry); failBtns.addView(bFailBack);
        bRetry.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { retryNow(); } });
        bFailBack.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { close(); } });

        timebar.addListener(new TimeBar.OnScrubListener() {
            @Override public void onScrubStart(TimeBar t, long p) { scrubbing = true; poke(); }
            @Override public void onScrubMove(TimeBar t, long p) { tpos.setText(Ui.fmt(p)); poke(); }
            @Override public void onScrubStop(TimeBar t, long p, boolean canceled) {
                scrubbing = false;
                if (!canceled && player != null) player.seekTo(p);
            }
        });

        chanList.setLayoutManager(new LinearLayoutManager(this));
        chanAd = new TextAdapter(false, new TextAdapter.Listener() {
            @Override public void click(TextAdapter.Row r, int p) { hideChannels(); play(p); }
            @Override public void focus(TextAdapter.Row r, int p) { }
        });
        chanList.setAdapter(chanAd);

        // touch: tap = show / hide controls, double tap = +-10 s
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { if (show) hideOverlay(); else showOverlay(false); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (!live) {
                    boolean fwd = e.getX() > root.getWidth() / 2f;
                    seekBy(fwd ? 10000 : -10000);
                    flash((fwd ? "+10 " : "-10 ") + "ث");
                }
                return true;
            }
        });
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) { return gd.onTouchEvent(e); }
        });
        showOverlay(true);   // like HMS Player: controls are visible when a video opens
    }

    /** Round icon button (focus = blue disc + white ring). [badge] = small text inside (the "10" of -10 / +10). */
    private FrameLayout iconBtn(int res, int sizeDp, boolean filled, String desc, String badge) {
        FrameLayout f = new FrameLayout(this);
        f.setFocusable(true);
        f.setClickable(true);
        f.setContentDescription(desc);
        f.setBackgroundResource(filled ? R.drawable.bg_pbtn_filled : R.drawable.bg_pbtn);
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pd = Ui.dp(this, sizeDp * 0.24f);
        iv.setPadding(pd, pd, pd, pd);
        f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        if (badge != null) {
            TextView t = new TextView(this);
            t.setText(badge); t.setTextColor(Color.WHITE); t.setTextSize(10); t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            t.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
            lp.topMargin = Ui.dp(this, sizeDp * 0.07f);
            f.addView(t, lp);
        }
        f.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, sizeDp), Ui.dp(this, sizeDp)));
        f.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                float s = hasFocus ? 1.12f : 1f;
                v.animate().scaleX(s).scaleY(s).setDuration(120).start();
            }
        });
        return f;
    }

    // ---------------- lifecycle: one player instance only ----------------
    @Override protected void onStart() {
        super.onStart();
        if (PlayQueue.items.isEmpty()) { finish(); return; }
        initPlayer();
        play(Math.min(idx, PlayQueue.items.size() - 1));
        h.post(tickRx);
    }

    /** Leaving the screen / screen off: save and really close the connection (a paused player keeps its socket open). */
    @Override protected void onStop() {
        tick();
        saveNow();
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
                .setUserAgent(Net.UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000);
        DefaultLoadControl lc = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(20000, 60000, 2000, 5000).build();
        DefaultRenderersFactory rf = new DefaultRenderersFactory(this)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setEnableDecoderFallback(true);
        player = new ExoPlayer.Builder(this, rf)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(hf))
                .setLoadControl(lc)
                .setHandleAudioBecomingNoisy(true)
                .build();
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !Store.subsDefault()).build());
        if (speed != 1f) player.setPlaybackSpeed(speed);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int st) {
                buffering = st == Player.STATE_BUFFERING;
                if (st == Player.STATE_READY) { message = null; failed = false; }
                if (st == Player.STATE_ENDED) onEnded();
                updateOverlays();
            }
            @Override public void onIsPlayingChanged(boolean p) {
                playIcon.setImageResource(p ? R.drawable.ic_p_pause : R.drawable.ic_p_play);
                if (p) { everPlayed = true; stableSince = System.currentTimeMillis(); }
                poke();
            }
            @Override public void onPlayerError(PlaybackException e) { handleError(e); }
            @Override public void onVideoSizeChanged(VideoSize v) {
                psize.setText(v.width > 0 ? v.width + "×" + v.height : "");
            }
        });
        pv.setPlayer(player);
    }

    private void releasePlayer() {
        if (player != null) { pv.setPlayer(null); player.release(); player = null; }
    }

    // ---------------- playback ----------------
    /** Plays item [i] of the queue. A movie / episode resumes from the saved position. */
    private void play(int i) {
        h.removeCallbacks(retryRx);
        saveNow();
        idx = i;
        PlayQueue.index = i;
        cur = PlayQueue.items.get(i);
        live = cur.type.equals("live");
        retries = 0; everPlayed = false; failed = false; retryPending = false; message = null; pos = 0; dur = 0;
        long start = C.TIME_UNSET;
        if (!live) { long p = Store.getPosition(cur); if (p > 15000) start = Math.max(0, p - 3000); }
        ptitle.setText(cur.name);
        pinfo.setText(cur.type.equals("episode") ? cur.extra : "");
        psize.setText("");
        liveIdx.setText((idx + 1) + " / " + PlayQueue.items.size());
        updateButtons();
        if (live) loadEpg();
        updateOverlays();
        load(cur, start);
    }

    private void updateButtons() {
        int vod = live ? View.GONE : View.VISIBLE;
        bPrev.setVisibility(idx > 0 ? View.VISIBLE : View.GONE);
        bNext.setVisibility(idx < PlayQueue.items.size() - 1 ? View.VISIBLE : View.GONE);
        bRew.setVisibility(vod); bFwd.setVisibility(vod); bSpeed.setVisibility(vod);
        bChan.setVisibility(live && PlayQueue.items.size() > 1 ? View.VISIBLE : View.GONE);
        int liveV = live ? View.VISIBLE : View.GONE;
        liveBadge.setVisibility(liveV); liveIdx.setVisibility(liveV);
        tpos.setVisibility(vod); tdur.setVisibility(vod); seekBox.setVisibility(vod);
        if (live) { seekBox.setFocusable(false); } else seekBox.setFocusable(true);
    }

    private void load(Item it, long startMs) {
        if (player == null || it == null) return;
        player.setMediaItem(MediaItem.fromUri(Xtream.streamUrl(it, LIVE_EXT)), startMs);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void retryNow() {
        h.removeCallbacks(retryRx);
        retryPending = false; retries = 0; failed = false; message = null;
        updateOverlays();
        load(cur, live || pos < 5000 ? C.TIME_UNSET : pos - 3000);
    }

    private void handleError(PlaybackException e) {
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && player != null) {
            player.seekToDefaultPosition(); player.prepare(); return;
        }
        reconnect(e);
    }

    /** Auto-reconnect: 2 tries for a movie that never started, 8 otherwise (wait 1.5 s x attempt, max 15 s). */
    private void reconnect(PlaybackException e) {
        if (player == null || retryPending) return;
        retries++;
        int limit = (!everPlayed && !live) ? 2 : 8;
        String code = e == null ? "?" : e.getErrorCodeName();
        if (retries > limit) {
            failed = true;
            message = !everPlayed && !live
                    ? "الملف مش شغال (" + code + "). غالباً عدد الأجهزة المسموح بيها في اشتراكك اتملى، أو الملف معطّل، أو صيغته مش مدعومة."
                    : "تعذّر تشغيل البث (" + code + "). اتأكد من الإنترنت واضغط إعادة المحاولة.";
            updateOverlays();
            return;
        }
        message = "انقطع البث... بعيد الاتصال تلقائياً (محاولة " + retries + ")";
        pendingResume = live || pos < 5000 ? C.TIME_UNSET : pos - 3000;
        retryPending = true;
        updateOverlays();
        h.postDelayed(retryRx, Math.min(1500L * retries, 15000L));
    }

    private void onEnded() {
        if (live) { reconnect(null); return; }
        long d = player.getDuration();
        if (d != C.TIME_UNSET && player.getCurrentPosition() < d - 30000 && retries < 3) { reconnect(null); return; }
        markDone();
        if (idx + 1 < PlayQueue.items.size() && !PlayQueue.items.get(idx + 1).type.equals("live")) play(idx + 1);
        else close();
    }

    /** Finished: leaves "continue watching"; the next episode takes its place so it shows up there. */
    private void markDone() {
        if (cur == null) return;
        Store.saveProgress(cur, Math.max(dur, 1), Math.max(dur, 1));   // > 95 % = removes the entry
        if (idx + 1 < PlayQueue.items.size()) {
            Item n = PlayQueue.items.get(idx + 1);
            if (n.type.equals("episode") && !Store.hasProgress(n)) Store.touchProgress(n);
        }
    }

    private void saveNow() {
        if (cur == null || live || dur <= 0) return;
        if (pos > (long) (dur * 0.95)) markDone();
        else if (pos > 10000) Store.saveProgress(cur, pos, dur);
    }

    /** ~2x per second */
    private void tick() {
        if (player == null) return;
        pos = Math.max(0, player.getCurrentPosition());
        long d = player.getDuration();
        dur = d == C.TIME_UNSET || d < 0 ? 0 : d;
        long now = System.currentTimeMillis();
        if (player.isPlaying() && now - stableSince > 20000) retries = 0;
        if (!live && now - lastSave > 5000) { lastSave = now; saveNow(); }
        if (show && !live && !scrubbing && dur > 0) {
            timebar.setDuration(dur);
            timebar.setPosition(pos);
            timebar.setBufferedPosition(player.getBufferedPosition());
            tpos.setText(Ui.fmt(pos));
            tdur.setText(Ui.fmt(dur));
        } else if (show && !live && dur <= 0) tdur.setText("--:--");
    }

    private void next() { if (idx < PlayQueue.items.size() - 1) play(idx + 1); }
    private void prev() { if (idx > 0) play(idx - 1); }

    private void toggle() {
        if (player == null) return;
        if (player.isPlaying()) player.pause();
        else {
            if (player.getPlaybackState() == Player.STATE_IDLE) player.prepare();
            player.play();
        }
    }

    private void playNow() {
        if (player == null) return;
        if (player.getPlaybackState() == Player.STATE_IDLE) { retries = 0; failed = false; player.prepare(); }
        player.play();
    }

    private void seekBy(long ms) {
        if (player == null || live) return;
        long d = player.getDuration();
        long max = d == C.TIME_UNSET || d <= 0 ? Long.MAX_VALUE : d;
        player.seekTo(Math.max(0, Math.min(player.getCurrentPosition() + ms, max)));
        tick();
    }

    private void changeSpeed(float s) {
        speed = s;
        if (player != null) player.setPlaybackSpeed(s);
        flash("السرعة " + s + "x");
    }

    private void cycleResize() {
        aspect = (aspect + 1) % MODES.length;
        pv.setResizeMode(MODES[aspect]);
        Store.setAspect(aspect);
        flash(MODE_NAMES[aspect]);
    }

    private void close() {
        tick();
        saveNow();
        finish();
    }

    private void loadEpg() {
        if (epgTask != null) epgTask.cancel();
        final String id = cur.id;
        epgTask = Net.run(new Callable<String[]>() {
            @Override public String[] call() throws Exception { return Xtream.epg(id); }
        }, new Net.Ok<String[]>() {
            @Override public void ok(String[] r) {
                if (live && cur != null && cur.id.equals(id) && !r[0].isEmpty())
                    pinfo.setText("NOW: " + r[0] + (r[1].isEmpty() ? "" : "     NEXT: " + r[1]));
            }
        }, null);
    }

    // ---------------- dialogs: tracks + speed ----------------
    private static String trackLabel(Format f, int n) {
        List<String> parts = new ArrayList<>();
        if (f.label != null && !f.label.isEmpty()) parts.add(f.label);
        if (f.language != null && !f.language.equals("und")) {
            String lang = Locale.forLanguageTag(f.language).getDisplayLanguage(new Locale("ar"));
            if (!lang.isEmpty()) parts.add(lang);
        }
        if (f.channelCount > 0) parts.add(f.channelCount + "ch");
        if (parts.isEmpty()) return "مسار " + n;
        StringBuilder sb = new StringBuilder();
        for (String s : parts) { if (sb.length() > 0) sb.append(" • "); sb.append(s); }
        return sb.toString();
    }

    private void pickTrack(final int type) {
        if (player == null) return;
        final boolean text = type == C.TRACK_TYPE_TEXT;
        final List<String> names = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        boolean disabled = player.getTrackSelectionParameters().disabledTrackTypes.contains(type);
        int checked = -1;
        boolean anySelected = false;
        List<Tracks.Group> groups = new ArrayList<>();
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) if (g.getType() == type && g.isSupported()) groups.add(g);
        for (Tracks.Group g : groups) for (int i = 0; i < g.length; i++) if (g.isTrackSelected(i)) anySelected = true;
        if (text) {
            names.add("إيقاف");
            acts.add(new Runnable() { @Override public void run() {
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
            } });
            if (disabled || !anySelected) checked = 0;
        }
        int n = 0;
        for (final Tracks.Group g : groups) {
            for (int i = 0; i < g.length; i++) {
                n++;
                names.add(trackLabel(g.getTrackFormat(i), n));
                if (g.isTrackSelected(i) && !disabled) checked = names.size() - 1;
                final TrackSelectionOverride ov = new TrackSelectionOverride(g.getMediaTrackGroup(), i);
                acts.add(new Runnable() { @Override public void run() {
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                            .setTrackTypeDisabled(type, false).setOverrideForType(ov).build());
                } });
            }
        }
        if (groups.isEmpty() && !text) { flash("مفيش مسارات صوت متاحة"); return; }
        if (groups.isEmpty()) { flash("مفيش ترجمة متاحة"); return; }
        new AlertDialog.Builder(this, R.style.HmsDialog).setTitle(text ? "الترجمة" : "الصوت")
                .setSingleChoiceItems(names.toArray(new String[0]), checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { if (player != null) acts.get(w).run(); d.dismiss(); }
                }).show();
    }

    private void pickSpeed() {
        String[] names = new String[SPEEDS.length];
        int checked = 2;
        for (int i = 0; i < SPEEDS.length; i++) {
            names[i] = SPEEDS[i] == 1f ? "عادية (1x)" : SPEEDS[i] + "x";
            if (SPEEDS[i] == speed) checked = i;
        }
        new AlertDialog.Builder(this, R.style.HmsDialog).setTitle("سرعة التشغيل")
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { changeSpeed(SPEEDS[w]); d.dismiss(); }
                }).show();
    }

    // ---------------- overlays ----------------
    private void flash(String t) {
        toastTv.setText(t);
        toastTv.setVisibility(View.VISIBLE);
        h.removeCallbacks(toastRx);
        h.postDelayed(toastRx, 1100);
    }

    /** message pill (reconnecting), failure card (retry / back) and the buffering spinner. */
    private void updateOverlays() {
        boolean card = failed && message != null;
        failBox.setVisibility(card ? View.VISIBLE : View.GONE);
        if (card) {
            failMsg.setText(message);
            hideOverlay();
            bRetry.requestFocus();
        }
        msgTv.setText(message == null ? "" : message);
        msgTv.setVisibility(message != null && !failed ? View.VISIBLE : View.GONE);
        spinner.setVisibility(buffering && message == null && !failed ? View.VISIBLE : View.GONE);
    }

    private void showOverlay(boolean focusPlay) {
        show = true;
        if (overlay.getVisibility() != View.VISIBLE) {
            overlay.setAlpha(0f);
            overlay.setVisibility(View.VISIBLE);
            overlay.animate().alpha(1f).setDuration(180).start();
        }
        tick();
        poke();
        if (focusPlay) { h.removeCallbacks(focusPlayRx); h.postDelayed(focusPlayRx, 120); }
    }

    private void hideOverlay() {
        show = false;
        h.removeCallbacks(hideRx);
        h.removeCallbacks(focusPlayRx);
        overlay.animate().cancel();
        overlay.setVisibility(View.GONE);
    }

    /** Any key / touch: the controls stay up another 4.5 s (only while playing). */
    private void poke() {
        h.removeCallbacks(hideRx);
        if (show) h.postDelayed(hideRx, 4500);
    }

    private void showChannels() {
        List<TextAdapter.Row> rows = new ArrayList<>();
        for (Item it : PlayQueue.items) rows.add(new TextAdapter.Row(it.name, null, it));
        chanAd.set(rows);
        chanAd.setSelected(idx);
        chanPanel.setVisibility(View.VISIBLE);
        hideOverlay();
        chanList.scrollToPosition(idx);
        chanList.post(new Runnable() { @Override public void run() {
            RecyclerView.ViewHolder vh = chanList.findViewHolderForAdapterPosition(idx);
            if (vh != null) vh.itemView.requestFocus(); else chanList.requestFocus();
        } });
    }

    private void hideChannels() { chanPanel.setVisibility(View.GONE); }

    // ---------------- remote keys (same rules as HMS Player) ----------------
    private static boolean inside(View parent, View child) {
        while (child != null) {
            if (child == parent) return true;
            android.view.ViewParent p = child.getParent();
            child = p instanceof View ? (View) p : null;
        }
        return false;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e);
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (down) poke();
        if (chanPanel.getVisibility() == View.VISIBLE || failBox.getVisibility() == View.VISIBLE) return super.dispatchKeyEvent(e);
        View f = getCurrentFocus();

        // seek bar focused: left / right = back / forward, holding the key speeds it up
        if (show && !live && f == seekBox && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (down) {
                int rc = e.getRepeatCount();
                long step = rc >= 12 ? 60000L : rc >= 4 ? 30000L : 10000L;
                boolean back = k == KeyEvent.KEYCODE_DPAD_LEFT;
                seekBy(back ? -step : step);
                flash((back ? "-" : "+") + (step / 1000) + " ث");
            }
            return true;
        }
        if (!down) return super.dispatchKeyEvent(e);

        boolean topFocused = show && f != null && inside(topBar, f);
        // LIVE: up / down always switch the channel (key repeat ignored so the server isn't hammered)
        if (live && !topFocused && (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN)) {
            if (e.getRepeatCount() == 0) {
                if (k == KeyEvent.KEYCODE_DPAD_UP) prev(); else next();
                showOverlay(false);
            }
            return true;
        }
        // LIVE: MENU = jump to the top buttons (audio / subtitles / size), since up / down change channel
        if (live && k == KeyEvent.KEYCODE_MENU) {
            showOverlay(false);
            h.removeCallbacks(focusTopRx);
            h.postDelayed(focusTopRx, 200);
            return true;
        }
        switch (k) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: toggle(); return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY: playNow(); return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: if (player != null) player.pause(); return true;
            case KeyEvent.KEYCODE_MEDIA_STOP: close(); return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND: if (!live) seekBy(-30000); return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: if (!live) seekBy(30000); return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT: case KeyEvent.KEYCODE_CHANNEL_UP: next(); return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: case KeyEvent.KEYCODE_CHANNEL_DOWN: prev(); return true;
            default: break;
        }
        if (!show) {
            // controls hidden: the remote acts directly
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    toggle(); showOverlay(true); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: if (!live) seekBy(-10000); showOverlay(true); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: if (!live) seekBy(10000); showOverlay(true); return true;
                case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_MENU:
                    showOverlay(true); return true;
                default: break;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    /** TV remote: first Back hides the controls (if visible), second Back leaves the channel / movie. */
    @Override public void onBackPressed() {
        if (chanPanel.getVisibility() == View.VISIBLE) { hideChannels(); return; }
        if (failBox.getVisibility() != View.VISIBLE && show) { hideOverlay(); return; }
        close();
    }
}
