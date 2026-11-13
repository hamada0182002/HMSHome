package com.hms.home.data;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Everything saved locally (SharedPreferences). */
public final class Store {
    private static SharedPreferences p;
    private static String sS, sU, sP; // session login (not saved)

    public static void init(Context c) { p = c.getApplicationContext().getSharedPreferences("hms", Context.MODE_PRIVATE); }

    // ---- accounts (several Xtream subscriptions, like HMS Player) ----
    private static List<Account> accCache;

    /** Moves the old single-login keys (srv / usr / pwd) into the accounts list once. */
    private static void migrate() {
        if (!p.contains("usr") || p.contains("accounts")) return;
        String s = p.getString("srv", ""), u = Crypt.dec(p.getString("usr", "")), pw = Crypt.dec(p.getString("pwd", ""));
        List<Account> l = new ArrayList<>();
        if (!s.isEmpty() && !u.isEmpty()) {
            Account a = new Account(java.util.UUID.randomUUID().toString(), hostOf(s), s, u, pw);
            l.add(a);
            writeAccounts(l);
            p.edit().putString("active", a.id).apply();
        }
        p.edit().remove("srv").remove("usr").remove("pwd").apply();
    }

    private static String hostOf(String s) {
        try { String h = android.net.Uri.parse(s).getHost(); return h == null ? "IPTV" : h; } catch (Exception e) { return "IPTV"; }
    }

    public static synchronized List<Account> accounts() {
        if (accCache != null) return accCache;
        migrate();
        List<Account> l = new ArrayList<>();
        try {
            // the whole list (including passwords) is stored encrypted
            JSONArray a = new JSONArray(Crypt.dec(p.getString("accounts", "")));
            for (int i = 0; i < a.length(); i++) l.add(Account.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) { }
        accCache = l;
        return l;
    }

    private static void writeAccounts(List<Account> l) {
        JSONArray a = new JSONArray();
        for (Account x : l) a.put(x.toJson());
        p.edit().putString("accounts", Crypt.enc(a.toString())).apply();
        accCache = new ArrayList<>(l);
    }

    public static synchronized Account active() {
        List<Account> l = accounts();
        String id = p.getString("active", null);
        for (Account a : l) if (a.id.equals(id)) return a;
        return l.isEmpty() ? null : l.get(0);
    }

    public static void setActive(Account a) { p.edit().putString("active", a == null ? null : a.id).apply(); }

    public static synchronized void saveAccount(Account a, boolean activate) {
        List<Account> l = new ArrayList<>(accounts());
        int i = -1;
        for (int k = 0; k < l.size(); k++) if (l.get(k).id.equals(a.id)) i = k;
        if (i >= 0) l.set(i, a); else l.add(a);
        writeAccounts(l);
        Account act = active();
        if (activate || act == null || act.id.equals(a.id)) setActive(a);
    }

    public static synchronized void deleteAccount(String id) {
        List<Account> l = new ArrayList<>();
        for (Account a : accounts()) if (!a.id.equals(id)) l.add(a);
        Account act = active();
        writeAccounts(l);
        if (act != null && act.id.equals(id)) setActive(l.isEmpty() ? null : l.get(0));
    }

    // Convenience for the active account (used by Xtream)
    public static String server() { Account a = active(); return a == null ? "" : a.server; }
    public static String user() { Account a = active(); return a == null ? "" : a.user; }
    public static String pass() { Account a = active(); return a == null ? "" : a.pass; }
    public static boolean hasLogin() { return active() != null; }

    // ---- simple string lists ----
    private static List<String> readList(String k) {
        List<String> l = new ArrayList<>();
        try { JSONArray a = new JSONArray(p.getString(k, "[]")); for (int i = 0; i < a.length(); i++) l.add(a.getString(i)); }
        catch (Exception ignored) { }
        return l;
    }
    private static void writeList(String k, List<String> l) { p.edit().putString(k, new JSONArray(l).toString()).apply(); }

    // ---- favorite apps ----
    public static List<String> favApps() { return readList("fav_apps"); }
    public static boolean isFavApp(String pkg) { return favApps().contains(pkg); }
    public static boolean toggleFavApp(String pkg) {
        List<String> l = favApps();
        boolean now;
        if (l.contains(pkg)) { l.remove(pkg); now = false; } else { l.add(pkg); now = true; }
        writeList("fav_apps", l);
        return now;
    }
    public static void moveFavApp(String pkg, int delta) {
        List<String> l = favApps();
        int i = l.indexOf(pkg), j = i + delta;
        if (i < 0 || j < 0 || j >= l.size()) return;
        Collections.swap(l, i, j);
        writeList("fav_apps", l);
    }
    public static void clearFavApps() { p.edit().remove("fav_apps").apply(); }

    // ---- recent apps ----
    public static List<String> recentApps() { return readList("recent_apps"); }
    public static void pushRecent(String pkg) {
        List<String> l = recentApps();
        l.remove(pkg);
        l.add(0, pkg);
        while (l.size() > 12) l.remove(l.size() - 1);
        writeList("recent_apps", l);
    }

    // ---- favorite channels / movies / series ----
    public static List<Item> favItems(String type) {
        List<Item> l = new ArrayList<>();
        try { JSONArray a = new JSONArray(p.getString("fav_" + type, "[]")); for (int i = 0; i < a.length(); i++) l.add(Item.fromJson(a.getJSONObject(i))); }
        catch (Exception ignored) { }
        return l;
    }
    public static boolean isFav(Item it) {
        for (Item x : favItems(it.type)) if (x.id.equals(it.id)) return true;
        return false;
    }
    public static boolean toggleFav(Item it) {
        List<Item> l = favItems(it.type);
        boolean found = false;
        for (Iterator<Item> i = l.iterator(); i.hasNext(); ) { if (i.next().id.equals(it.id)) { i.remove(); found = true; } }
        if (!found) l.add(it);
        JSONArray a = new JSONArray();
        for (Item x : l) a.put(x.toJson());
        p.edit().putString("fav_" + it.type, a.toString()).apply();
        return !found;
    }
    public static void clearFavItems() { p.edit().remove("fav_live").remove("fav_movie").remove("fav_series").apply(); }

    // ---- watch progress ----
    public static void saveProgress(Item it, long pos, long dur) {
        try {
            JSONObject all = new JSONObject(p.getString("prog", "{}"));
            if (dur > 0 && pos > dur * 0.95) all.remove(it.key());
            else if (pos >= 10000) {
                JSONObject o = new JSONObject();
                o.put("p", pos); o.put("d", dur); o.put("t", System.currentTimeMillis()); o.put("i", it.toJson());
                all.put(it.key(), o);
            } else return;
            while (all.length() > 30) {
                String oldest = null; long ot = Long.MAX_VALUE;
                for (Iterator<String> k = all.keys(); k.hasNext(); ) {
                    String key = k.next(); long t = all.getJSONObject(key).optLong("t");
                    if (t < ot) { ot = t; oldest = key; }
                }
                all.remove(oldest);
            }
            p.edit().putString("prog", all.toString()).apply();
        } catch (Exception ignored) { }
    }
    public static long getPosition(Item it) {
        try { return new JSONObject(p.getString("prog", "{}")).getJSONObject(it.key()).optLong("p"); }
        catch (Exception e) { return 0; }
    }
    public static List<Item> continueWatching() {
        final List<long[]> times = new ArrayList<>();
        final List<Item> items = new ArrayList<>();
        try {
            JSONObject all = new JSONObject(p.getString("prog", "{}"));
            List<JSONObject> os = new ArrayList<>();
            for (Iterator<String> k = all.keys(); k.hasNext(); ) os.add(all.getJSONObject(k.next()));
            Collections.sort(os, new Comparator<JSONObject>() {
                @Override public int compare(JSONObject a, JSONObject b) { return Long.compare(b.optLong("t"), a.optLong("t")); }
            });
            for (JSONObject o : os) items.add(Item.fromJson(o.getJSONObject("i")));
        } catch (Exception ignored) { }
        return items;
    }

    // ---- settings ----
    /** 0 = normal, 1 = large (default), 2 = extra large */
    public static int fontLevel() { return p.getInt("font", 1); }
    public static void setFontLevel(int v) { p.edit().putInt("font", v).apply(); }
    public static float fontScale() { int l = fontLevel(); return l == 0 ? 1.0f : l == 1 ? 1.2f : 1.4f; }
    public static int aspect() { return p.getInt("aspect", 0); }
    public static void setAspect(int v) { p.edit().putInt("aspect", v).apply(); }
    public static boolean subsDefault() { return p.getBoolean("subs", false); }
    public static void setSubsDefault(boolean v) { p.edit().putBoolean("subs", v).apply(); }
}
