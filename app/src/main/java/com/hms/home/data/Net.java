package com.hms.home.data;

import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Background work + HTTP helpers. Nothing here runs on the UI thread except callbacks.
 *
 * The IPTV server is reached EXACTLY like HMS Player does it: OkHttp 4.12, the same browser User-Agent,
 * 15 s connect timeout, redirects (incl. http <-> https) followed. HttpURLConnection (the old stack
 * that was used before) failed on some TV boxes where HMS Player worked.
 */
public final class Net {
    private static final ExecutorService EX = Executors.newFixedThreadPool(3);
    private static final Handler H = new Handler(Looper.getMainLooper());

    public interface Ok<T> { void ok(T r); }
    public interface Fail { void fail(Exception e); }

    public static class Task {
        volatile boolean cancelled;
        public void cancel() { cancelled = true; }
    }

    public static <T> Task run(final Callable<T> c, final Ok<T> ok, final Fail fail) {
        final Task t = new Task();
        EX.execute(new Runnable() {
            @Override public void run() {
                try {
                    if (t.cancelled) return;
                    final T r = c.call();
                    if (!t.cancelled) H.post(new Runnable() { @Override public void run() { if (!t.cancelled && ok != null) ok.ok(r); } });
                } catch (final Exception e) {
                    if (!t.cancelled) H.post(new Runnable() { @Override public void run() { if (!t.cancelled && fail != null) fail.fail(e); } });
                }
            }
        });
        return t;
    }

    /** Same User-Agent HMS Player sends to the IPTV server (a normal browser). */
    public static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    public static String ua() { return UA; }

    /** Thrown for HTTP status >= 400. Message is "HTTP <code>". */
    public static class HttpException extends IOException {
        public final int code;
        public HttpException(int code) { super("HTTP " + code); this.code = code; }
    }

    // ---- IPTV client: plain OkHttp, same as HMS Player ----
    private static final OkHttpClient IPTV = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    // ---- Web client (images / GitHub): same, plus the bundled root certificates for old TVs ----
    private static volatile OkHttpClient web;

    private static OkHttpClient web() {
        OkHttpClient c = web;
        if (c != null) return c;
        synchronized (Net.class) {
            if (web != null) return web;
            OkHttpClient.Builder b = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true);
            try {
                if (Certs.factory() != null && Certs.trustManager() != null) b.sslSocketFactory(Certs.factory(), Certs.trustManager());
            } catch (Exception ignored) { }
            web = b.build();
            return web;
        }
    }

    private static Response exec(OkHttpClient base, String url, int readSec, String ua, String accept) throws IOException {
        OkHttpClient c = base.newBuilder().readTimeout(readSec, TimeUnit.SECONDS).build();
        Request.Builder rb = new Request.Builder().url(url).header("User-Agent", ua);
        if (accept != null) rb.header("Accept", accept);
        Response r = c.newCall(rb.build()).execute();
        if (!r.isSuccessful()) { int code = r.code(); r.close(); throw new HttpException(code); }
        return r;
    }

    /** Opens an IPTV API url. The caller MUST close the response. */
    public static Response open(String url, int readSec) throws IOException { return exec(IPTV, url, readSec, UA, null); }

    /** Opens a web url (images, GitHub). The caller MUST close the response. */
    public static Response openWeb(String url, String ua, String accept, int readSec) throws IOException { return exec(web(), url, readSec, ua, accept); }

    /** Downloads an IPTV API answer as text. */
    public static String readAll(String url, int readSec) throws IOException {
        try (Response r = open(url, readSec)) { return r.body().string(); }
    }

    public static String readWeb(String url, String ua, String accept) throws IOException {
        try (Response r = openWeb(url, ua, accept, 30)) { return r.body().string(); }
    }

    /** User-friendly Arabic message for an error (same cases as HMS Player's friendly()). */
    public static String msg(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (e instanceof AuthException) return "اليوزر أو الباسورد غلط، أو الاشتراك منتهي";
        if (e instanceof java.net.SocketTimeoutException) return "السيرفر اتأخر في الرد. جرب تاني";
        if (e instanceof java.net.UnknownHostException) return "مش قادر يوصل للسيرفر. اتأكد من الرابط ومن الإنترنت";
        if (e instanceof java.net.ConnectException) return "السيرفر مش بيرد على العنوان ده. اتأكد من الرابط والبورت";
        if (e instanceof javax.net.ssl.SSLException) return "مشكلة في شهادة الأمان (https). جرب الرابط بـ http";
        if (e instanceof HttpException) {
            int c = ((HttpException) e).code;
            if (c == 403) return "السيرفر رفض الدخول (HTTP 403) - غالباً الحساب أو الـIP محظور، أو الاشتراك مفتوح على أقصى عدد أجهزة";
            return "السيرفر رجّع خطأ (" + m + ")";
        }
        if (e instanceof org.json.JSONException) return "السيرفر رجّع رد غير مفهوم. اتأكد إنه سيرفر Xtream";
        if (e instanceof IllegalArgumentException) return "رابط السيرفر غير صحيح";
        return m.isEmpty() ? "حصل خطأ غير معروف (" + e.getClass().getSimpleName() + ")" : "حصل خطأ: " + m;
    }

    /** Technical detail line (class + message) so a failure on a TV box can be diagnosed from a photo of the screen. */
    public static String detail(Exception e) {
        Throwable t = e.getCause() != null ? e.getCause() : e;
        String m = t.getMessage() == null ? "" : t.getMessage();
        if (m.length() > 120) m = m.substring(0, 120);
        return t.getClass().getSimpleName() + (m.isEmpty() ? "" : ": " + m);
    }

    /** Wrong user / password. */
    public static class AuthException extends IOException {
        public AuthException() { super("auth"); }
    }
}
