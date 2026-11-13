package com.hms.home.data;

import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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

    public static HttpURLConnection open(String u) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "HMSHome/1.0");
        int code = c.getResponseCode();
        if (code >= 400) { c.disconnect(); throw new IOException("HTTP " + code); }
        return c;
    }

    public static String readAll(String u) throws IOException {
        HttpURLConnection c = open(u);
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
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.ConnectException) return "تعذر الاتصال بالسيرفر - اتأكد من الإنترنت ومن عنوان السيرفر";
        if (e instanceof IOException && m.startsWith("HTTP")) return "السيرفر رجّع خطأ (" + m + ")";
        if (e instanceof org.json.JSONException) return "رد السيرفر مش مفهوم";
        return m.isEmpty() ? "حصل خطأ غير متوقع" : m;
    }
}
