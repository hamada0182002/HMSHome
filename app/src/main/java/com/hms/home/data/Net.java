package com.hms.home.data;

import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Background work + HTTP helpers. Nothing here runs on the UI thread except callbacks. */
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

    /** A normal browser User-Agent: many IPTV panels reject unknown ones (HTTP 403 / empty reply). */
    public static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    public static HttpURLConnection open(String u) throws IOException { return open(u, UA, "*/*"); }

    /**
     * Opens a URL and follows redirects BY HAND, including http -> https and https -> http
     * (HttpURLConnection refuses to follow those on its own, which is what broke some IPTV servers).
     */
    public static HttpURLConnection open(String u, String ua, String accept) throws IOException {
        String cur = u;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            if (c instanceof HttpsURLConnection) {
                SSLSocketFactory f = Certs.factory();
                if (f != null) ((HttpsURLConnection) c).setSSLSocketFactory(f);
            }
            c.setConnectTimeout(12000);
            c.setReadTimeout(25000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", ua);
            c.setRequestProperty("Accept", accept);
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || loc.isEmpty()) throw new IOException("HTTP " + code);
                cur = new URL(new URL(cur), loc).toString();
                continue;
            }
            if (code >= 400) { c.disconnect(); throw new IOException("HTTP " + code); }
            return c;
        }
        throw new IOException("السيرفر بيحوّل الرابط كتير (redirect loop)");
    }

    public static String readAll(String u) throws IOException { return readAll(u, UA, "*/*"); }

    public static String readAll(String u, String ua, String accept) throws IOException {
        HttpURLConnection c = open(u, ua, accept);
        try {
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    /** User-friendly Arabic message for an error. */
    public static String msg(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (e instanceof java.net.SocketTimeoutException) return "انتهت مهلة الاتصال بالسيرفر";
        if (e instanceof javax.net.ssl.SSLException) return "مشكلة في شهادة الأمان (https) - جرّب عنوان السيرفر بـ http بدل https";
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.ConnectException) return "تعذر الاتصال بالسيرفر - اتأكد من الإنترنت ومن عنوان السيرفر";
        if (e instanceof IOException && m.startsWith("HTTP")) return "السيرفر رجّع خطأ (" + m + ")";
        if (e instanceof org.json.JSONException) return "رد السيرفر مش مفهوم";
        return m.isEmpty() ? "حصل خطأ غير متوقع (" + e.getClass().getSimpleName() + ")" : m;
    }
}
