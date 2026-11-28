package com.hms.home.dlna;

/**
 * Link between the DLNA server (network threads) and the player screen (main thread).
 * The player publishes its state here ~2x per second; the server reads it for GetPositionInfo / GetTransportInfo.
 */
public final class DlnaBridge {
    /** Commands the DLNA server sends to the player. Always called on the main thread. */
    public interface Controller {
        void dlnaPlay();
        void dlnaPause();
        void dlnaStop();
        void dlnaSeek(long ms);
        void dlnaSetUri(String uri, String title, boolean image);
        void dlnaSetVolume(int percent);
        void dlnaSetMute(boolean mute);
    }

    public static volatile Controller controller;          // set while a DLNA PlayerActivity is open
    public static volatile String state = "NO_MEDIA_PRESENT"; // PLAYING | PAUSED_PLAYBACK | STOPPED | TRANSITIONING | NO_MEDIA_PRESENT
    public static volatile long posMs, durMs;
    public static volatile int volume = 100;
    public static volatile boolean muted;
    public static volatile String uri = "", meta = "";
    /** Set by the server: tells subscribed phones that the state changed. */
    public static volatile Runnable onChange;

    public static void changed() { Runnable r = onChange; if (r != null) r.run(); }

    private DlnaBridge() { }

    public static void reset() {
        state = "NO_MEDIA_PRESENT"; posMs = 0; durMs = 0; uri = ""; meta = "";
    }
}
