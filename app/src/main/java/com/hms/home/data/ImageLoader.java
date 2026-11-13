package com.hms.home.data;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import okhttp3.Response;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tiny image loader: small RAM cache (6 MB), disk cache (max ~40 MB), down-sampled RGB_565 decode. */
public final class ImageLoader {
    private static LruCache<String, Bitmap> mem;
    private static File dir;
    private static final ExecutorService EX = Executors.newFixedThreadPool(2);
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final ColorDrawable PH = new ColorDrawable(0xFF2E2820);

    public static void init(Context c) {
        mem = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
            @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
        };
        dir = new File(c.getCacheDir(), "img");
        dir.mkdirs();
        EX.execute(new Runnable() { @Override public void run() { trim(); } });
    }

    private static void trim() {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        long total = 0;
        for (File f : fs) total += f.length();
        if (total < 40L * 1024 * 1024) return;
        Arrays.sort(fs, new Comparator<File>() { @Override public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); } });
        for (File f : fs) { total -= f.length(); f.delete(); if (total < 25L * 1024 * 1024) break; }
    }

    public static void clear() {
        if (mem != null) mem.evictAll();
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return String.valueOf(s.hashCode()); }
    }

    public static void load(ImageView v, String url, int reqW, int reqH) { load(v, url, reqW, reqH, false); }

    /**
     * hq = true: full-colour (ARGB_8888) decode for big hero banners (RGB_565 shows banding).
     * The RAM cache key includes the size, so a small thumbnail of the same picture is never reused for a big card.
     */
    public static void load(final ImageView v, final String url, final int reqW, final int reqH, final boolean hq) {
        final String key = url + "|" + reqW + "x" + reqH + (hq ? "|q" : "");
        v.setTag(key);
        if (url == null || url.isEmpty() || !url.startsWith("http")) { v.setImageDrawable(PH); return; }
        Bitmap b = mem.get(key);
        if (b != null) { v.setImageBitmap(b); return; }
        v.setImageDrawable(PH);
        EX.execute(new Runnable() {
            @Override public void run() {
                if (!key.equals(v.getTag())) return;
                final Bitmap bm = fetch(url, reqW, reqH, hq);
                if (bm == null) return;
                mem.put(key, bm);
                H.post(new Runnable() { @Override public void run() { if (key.equals(v.getTag())) v.setImageBitmap(bm); } });
            }
        });
    }

    private static Bitmap fetch(String url, int w, int h, boolean hq) {
        try {
            File f = new File(dir, hash(url));
            if (!f.exists()) {
                try (Response resp = Net.openWeb(url, Net.UA, "image/*,*/*", 25)) {
                    InputStream in = resp.body().byteStream();
                    File tmp = new File(dir, hash(url) + ".t");
                    OutputStream out = new FileOutputStream(tmp);
                    try { byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); } finally { out.close(); }
                    tmp.renameTo(f);
                }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int s = 1;
            while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            o.inPreferredConfig = hq ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable t) { return null; }
    }
}
