package com.hms.home.data;

import android.content.Context;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Base64;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import org.json.JSONArray;
import okhttp3.Response;
import org.json.JSONObject;

/** Xtream Codes API client. Big lists are streamed (JsonReader) so memory stays low. */
public final class Xtream {
    public static class Cat { public String id, name; }

    private static File cacheDir;
    private static final long MAX_AGE = 6 * 3600 * 1000L;

    public static void init(Context c) { cacheDir = c.getApplicationContext().getCacheDir(); }

    /** Cleans a server address (same rules as HMS Player's buildAccount). */
    public static String norm(String server) {
        String s = server == null ? "" : server.replaceAll("\\s", "");
        if (s.isEmpty()) return s;
        if (!s.toLowerCase().startsWith("http://") && !s.toLowerCase().startsWith("https://")) s = "http://" + s;
        android.net.Uri uri = android.net.Uri.parse(s);
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (s.contains("?") || path.contains(".php")) s = uri.getScheme() + "://" + uri.getAuthority();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /**
     * Builds an account from what the user typed. Also understands a pasted get.php / player_api.php link
     * (username / password are taken from it when the fields are empty). Same as HMS Player.
     */
    public static Account buildAccount(String id, String name, String server, String user, String pass) {
        String s = server == null ? "" : server.replaceAll("\\s", "");
        String u = user == null ? "" : user.trim();
        String p = pass == null ? "" : pass.trim();
        if (!s.toLowerCase().startsWith("http://") && !s.toLowerCase().startsWith("https://")) s = "http://" + s;
        android.net.Uri uri = android.net.Uri.parse(s);
        if (u.isEmpty()) { String q = uri.getQueryParameter("username"); u = q == null ? "" : q; }
        if (p.isEmpty()) { String q = uri.getQueryParameter("password"); p = q == null ? "" : q; }
        s = norm(s);
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) { String h = android.net.Uri.parse(s).getHost(); n = h == null ? "IPTV" : h; }
        return new Account(id != null ? id : java.util.UUID.randomUUID().toString(), n, s, u, p);
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static String api(String server, String user, String pass, String action, String extra) {
        return norm(server) + "/player_api.php?username=" + enc(user) + "&password=" + enc(pass)
                + (action == null ? "" : "&action=" + action) + (extra == null ? "" : extra);
    }
    private static String api(String action, String extra) { return api(Store.server(), Store.user(), Store.pass(), action, extra); }

    /** Checks the account like HMS Player (30 s). Throws Net.AuthException on wrong login. Returns user_info. */
    public static JSONObject auth(Account a) throws Exception {
        JSONObject o = new JSONObject(Net.readAll(api(a.server, a.user, a.pass, null, null), 30));
        JSONObject ui = o.optJSONObject("user_info");
        if (ui == null) throw new Net.AuthException();
        if ("0".equals(String.valueOf(ui.opt("auth")))) throw new Net.AuthException();
        return ui;
    }

    private static String catAction(String type) {
        return type.equals("live") ? "get_live_categories" : type.equals("movie") ? "get_vod_categories" : "get_series_categories";
    }
    private static String listAction(String type) {
        return type.equals("live") ? "get_live_streams" : type.equals("movie") ? "get_vod_streams" : "get_series";
    }

    public static List<Cat> categories(String type) throws Exception {
        JSONArray a = new JSONArray(Net.readAll(api(catAction(type), null), 60));
        List<Cat> l = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Cat c = new Cat();
            c.id = o.optString("category_id");
            c.name = o.optString("category_name", "?");
            if (!c.id.isEmpty()) l.add(c);
        }
        return l;
    }

    // ---------- streaming parse ----------
    private static String str(JsonReader r) throws IOException {
        JsonToken t = r.peek();
        if (t == JsonToken.NULL) { r.nextNull(); return ""; }
        if (t == JsonToken.BEGIN_ARRAY || t == JsonToken.BEGIN_OBJECT) { r.skipValue(); return ""; }
        if (t == JsonToken.BOOLEAN) return String.valueOf(r.nextBoolean());
        return r.nextString();
    }

    /** First string of a JSON array (or the string itself). */
    private static String firstOf(JsonReader r) throws IOException {
        JsonToken t = r.peek();
        if (t == JsonToken.STRING) return r.nextString();
        if (t != JsonToken.BEGIN_ARRAY) { r.skipValue(); return ""; }
        String out = "";
        r.beginArray();
        while (r.hasNext()) {
            if (out.isEmpty() && r.peek() == JsonToken.STRING) out = r.nextString(); else r.skipValue();
        }
        r.endArray();
        return out;
    }

    private static Item readItem(JsonReader r, String type) throws IOException {
        Item it = new Item();
        it.type = type;
        String rating = "", year = "";
        r.beginObject();
        while (r.hasNext()) {
            String k = r.nextName();
            if (k.equals("stream_id") || k.equals("series_id")) { String v = str(r); if (it.id.isEmpty()) it.id = v; }
            else if (k.equals("name")) it.name = str(r);
            else if (k.equals("stream_icon") || k.equals("cover")) { String v = str(r); if (it.icon.isEmpty()) it.icon = v; }
            else if (k.equals("container_extension")) it.ext = str(r);
            else if (k.equals("category_id")) it.cat = str(r);
            else if (k.equals("added") || k.equals("last_modified")) { try { it.added = Long.parseLong(str(r)); } catch (Exception ignored) { } }
            else if (k.equals("rating")) rating = str(r);
            else if (k.equals("year")) year = str(r);
            else if (k.equals("backdrop_path")) { if (it.backdrop.isEmpty()) it.backdrop = firstOf(r); else r.skipValue(); }
            else r.skipValue();
        }
        r.endObject();
        if (it.id.isEmpty()) return null;
        if (it.ext.isEmpty() && type.equals("movie")) it.ext = "mp4";
        boolean hasRating = !rating.isEmpty() && !rating.equals("0") && !rating.equals("0.0");
        it.extra = (year.isEmpty() ? "" : year) + (hasRating ? (year.isEmpty() ? "" : "  ") + "\u2605" + rating : "");
        return it;
    }

    private static void parse(InputStream in, String type, String q, int limit, List<Item> out) throws IOException {
        JsonReader r = new JsonReader(new InputStreamReader(in, "UTF-8"));
        try {
            r.beginArray();
            while (r.hasNext() && out.size() < limit) {
                Item it = readItem(r, type);
                if (it == null) continue;
                if (q == null || it.name.toLowerCase().contains(q)) out.add(it);
            }
        } catch (IllegalStateException e) {
            throw new IOException("بيانات غير صحيحة من السيرفر");
        } finally { try { r.close(); } catch (Exception ignored) { } }
    }

    public static List<Item> items(String type, String catId) throws Exception {
        List<Item> out = new ArrayList<>();
        try (Response resp = Net.open(api(listAction(type), "&category_id=" + enc(catId)), 120)) {
            parse(resp.body().byteStream(), type, null, 6000, out);
        }
        return out;
    }

    // ---------- disk index of everything (for search + "latest") ----------
    private static File allFile(String type) { return new File(cacheDir, "all_" + type + ".json"); }

    public static void ensureAll(String type, boolean force) throws Exception {
        File f = allFile(type);
        if (!force && f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE) return;
        File tmp = new File(cacheDir, "all_" + type + ".tmp");
        try (Response resp = Net.open(api(listAction(type), null), 300)) {
            InputStream in = resp.body().byteStream();
            OutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[16384]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally { out.close(); }
        }
        if (f.exists()) f.delete();
        tmp.renameTo(f);
    }

    public static List<Item> search(String type, String q, int limit) throws Exception {
        ensureAll(type, false);
        List<Item> out = new ArrayList<>();
        InputStream in = new FileInputStream(allFile(type));
        parse(in, type, q.toLowerCase().trim(), limit, out);
        return out;
    }

    public static List<Item> latest(String type, int n) throws Exception {
        File f = allFile(type);
        if (!f.exists()) return new ArrayList<>();
        PriorityQueue<Item> heap = new PriorityQueue<>(n + 1, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) { return Long.compare(a.added, b.added); }
        });
        JsonReader r = new JsonReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        try {
            r.beginArray();
            while (r.hasNext()) {
                Item it = readItem(r, type);
                if (it == null) continue;
                heap.add(it);
                if (heap.size() > n) heap.poll();
            }
        } catch (IllegalStateException e) {
            throw new IOException("بيانات غير صحيحة");
        } finally { try { r.close(); } catch (Exception ignored) { } }
        List<Item> out = new ArrayList<>(heap);
        Collections.sort(out, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) { return Long.compare(b.added, a.added); }
        });
        return out;
    }

    /** Items for the home "new" rows: the folder chosen in Settings (server order / newest first), else the latest added.
     *  Same title offered twice (HD + SD ...) is shown once here; the server folders themselves are not touched. */
    public static List<Item> homeItems(String type, int n) throws Exception {
        String[] cat = Store.homeCat(type);
        List<Item> l;
        if (cat == null) { ensureAll(type, false); l = latest(type, n * 3); }
        else {
            l = items(type, cat[0]);
            boolean dated = false;
            for (Item it : l) if (it.added > 0) { dated = true; break; }
            if (dated) Collections.sort(l, new Comparator<Item>() {   // stable: equal dates keep the server order
                @Override public int compare(Item a, Item b) { return Long.compare(b.added, a.added); }
            });
        }
        l = dedupeQuality(l);
        return l.size() > n ? new ArrayList<>(l.subList(0, n)) : l;
    }

    private static final java.util.regex.Pattern QUALITY = java.util.regex.Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}])(uhd|fhd|hdtv|hdrip|webrip|web-dl|bluray|brrip|dvdrip|hd|sd|4k|2160p|1080p|720p|576p|480p|360p)(?![\\p{L}\\p{N}])");
    private static final java.util.regex.Pattern JUNK = java.util.regex.Pattern.compile("[^\\p{L}\\p{N}]+");

    /** Title without quality tags / punctuation, lower case: "Show [HD]" and "Show (SD)" give the same key. */
    private static String baseName(String name) {
        String k = JUNK.matcher(QUALITY.matcher(name).replaceAll(" ")).replaceAll(" ").trim().toLowerCase();
        return k.isEmpty() ? name.trim().toLowerCase() : k;
    }

    /** Higher = better picture: 4K > FHD/1080 > HD/720 > unmarked > SD/480. */
    private static int qualityRank(String name) {
        String n = name.toLowerCase();
        if (n.contains("4k") || n.contains("uhd") || n.contains("2160")) return 5;
        if (n.contains("fhd") || n.contains("1080")) return 4;
        if (QUALITY.matcher(n).find() && java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}])(hd|hdtv|720p)(?![\\p{L}\\p{N}])").matcher(n).find()) return 3;
        if (java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}])(sd|480p|360p|576p)(?![\\p{L}\\p{N}])").matcher(n).find()) return 1;
        return 2;
    }

    /** Keeps one entry per title (the best quality one) at the position of its first appearance. */
    public static List<Item> dedupeQuality(List<Item> in) {
        java.util.Map<String, Integer> pos = new java.util.HashMap<>();
        List<Item> out = new ArrayList<>();
        for (Item it : in) {
            String k = baseName(it.name);
            Integer at = pos.get(k);
            if (at == null) { pos.put(k, out.size()); out.add(it); }
            else if (qualityRank(it.name) > qualityRank(out.get(at).name)) out.set(at, it);
        }
        return out;
    }

    public static void clearCache() {
        File[] fs = cacheDir.listFiles();
        if (fs != null) for (File f : fs) if (f.getName().startsWith("all_")) f.delete();
    }

    // ---------- details / epg / urls ----------
    public static JSONObject vodInfo(String id) throws Exception { return new JSONObject(Net.readAll(api("get_vod_info", "&vod_id=" + enc(id)), 30)); }
    public static JSONObject seriesInfo(String id) throws Exception { return new JSONObject(Net.readAll(api("get_series_info", "&series_id=" + enc(id)), 60)); }

    /** returns {now, next} (either may be empty) */
    public static String[] epg(String streamId) throws Exception {
        JSONObject o = new JSONObject(Net.readAll(api("get_short_epg", "&stream_id=" + enc(streamId) + "&limit=2"), 30));
        JSONArray l = o.optJSONArray("epg_listings");
        String[] r = {"", ""};
        if (l == null) return r;
        for (int i = 0; i < Math.min(2, l.length()); i++) {
            String t = l.getJSONObject(i).optString("title");
            try { t = new String(Base64.decode(t, Base64.DEFAULT), "UTF-8"); } catch (Exception ignored) { }
            r[i] = t;
        }
        return r;
    }

    public static String streamUrl(Item it, String liveExt) {
        String b = norm(Store.server()), u = enc(Store.user()), p = enc(Store.pass());
        switch (it.type) {
            case "live": return b + "/live/" + u + "/" + p + "/" + it.id + "." + liveExt;
            case "movie": return b + "/movie/" + u + "/" + p + "/" + it.id + "." + (it.ext.isEmpty() ? "mp4" : it.ext);
            default: return b + "/series/" + u + "/" + p + "/" + it.id + "." + (it.ext.isEmpty() ? "mp4" : it.ext);
        }
    }
}
