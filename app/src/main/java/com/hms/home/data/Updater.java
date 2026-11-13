package com.hms.home.data;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/** In-app updater: reads the latest GitHub Release, downloads its .apk and opens the installer. */
public final class Updater {
    /**
     * >>> GitHub account / repository that publishes HMS Home releases (must be Public) <<<
     */
    public static final String GITHUB_REPO = "hamada0182002/HMSHome";

    public static class Info { public String version = "", notes = "", apkUrl = ""; public long size; }
    public interface Progress { void percent(int p); }

    public static boolean isNewer(String remote, String local) {
        int[] r = parse(remote), l = parse(local);
        for (int i = 0; i < Math.max(r.length, l.length); i++) {
            int a = i < r.length ? r[i] : 0, b = i < l.length ? l[i] : 0;
            if (a != b) return a > b;
        }
        return false;
    }

    private static int[] parse(String v) {
        String[] p = v.split("\\.");
        int[] o = new int[p.length];
        for (int i = 0; i < p.length; i++) {
            StringBuilder d = new StringBuilder();
            for (char ch : p[i].toCharArray()) if (Character.isDigit(ch)) d.append(ch);
            try { o[i] = d.length() == 0 ? 0 : Integer.parseInt(d.toString()); } catch (Exception e) { o[i] = 0; }
        }
        return o;
    }

    /** null = already on the latest version. */
    public static Info check(String currentVersion) throws Exception {
        String body;
        try {
            body = Net.readAll("https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest", "HMSHome/1.0", "application/vnd.github+json");
        } catch (IOException e) {
            if ("HTTP 404".equals(e.getMessage())) throw new IOException("مفيش إصدار منشور لسه على GitHub (أو اسم المستودع غلط / المستودع مش Public)");
            throw e;
        }
        JSONObject j = new JSONObject(body);
        String tag = j.optString("tag_name").trim();
        while (tag.startsWith("v") || tag.startsWith("V")) tag = tag.substring(1);
        JSONArray assets = j.optJSONArray("assets");
        Info info = new Info();
        long best = -1;
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) continue;
                String name = a.optString("name");
                if (!name.toLowerCase().endsWith(".apk")) continue;
                long score = (!tag.isEmpty() && name.contains(tag)) ? 2 : 1;
                if (score > best) { best = score; info.apkUrl = a.optString("browser_download_url"); info.size = a.optLong("size"); }
            }
        }
        if (tag.isEmpty() || info.apkUrl.isEmpty() || !isNewer(tag, currentVersion)) return null;
        info.version = tag;
        info.notes = j.optString("body").trim();
        return info;
    }

    public static File download(Context ctx, Info info, Progress cb) throws Exception {
        File dir = new File(ctx.getCacheDir(), "updates");
        dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        File out = new File(dir, "HMS-Home-" + info.version + ".apk");
        HttpURLConnection c = Net.open(info.apkUrl, "HMSHome/1.0", "*/*");
        try {
            long total = c.getContentLength() > 0 ? c.getContentLength() : info.size;
            InputStream in = c.getInputStream();
            OutputStream os = new FileOutputStream(out);
            try {
                byte[] buf = new byte[64 * 1024];
                long read = 0; int last = -1, n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    read += n;
                    if (total > 0) {
                        int p = (int) (read * 100 / total);
                        if (p != last) { last = p; if (cb != null) cb.percent(p); }
                    }
                }
            } finally { os.close(); }
        } finally { c.disconnect(); }
        return out;
    }

    /** false = Android needs the user to allow "install unknown apps" first (the settings page is opened). */
    public static boolean install(Context ctx, File f) {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.getPackageManager().canRequestPackageInstalls()) {
            ctx.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return false;
        }
        Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", f);
        ctx.startActivity(new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
        return true;
    }
}
