package com.hms.home.ui;

import android.app.Activity;
import android.content.Intent;
import com.hms.home.data.Item;
import com.hms.home.data.PlayQueue;
import com.hms.home.data.Store;
import com.hms.home.player.PlayerActivity;
import java.util.Collections;

public final class Nav {
    public static void section(Activity a, String s) {
        Intent i;
        switch (s) {
            case "live": case "movie": case "series":
                if (!Store.hasLogin()) { i = new Intent(a, LoginActivity.class); break; }
                i = new Intent(a, BrowseActivity.class).putExtra("type", s); break;
            case "apps": i = new Intent(a, AppsActivity.class); break;
            case "settings": i = new Intent(a, SettingsActivity.class); break;
            case "search": i = new Intent(a, SearchActivity.class); break;
            case "login": i = new Intent(a, LoginActivity.class); break;
            default: return;
        }
        a.startActivity(i);
    }

    /** Android's own settings (not this app's settings). */
    public static void deviceSettings(Activity a) { systemScreen(a, android.provider.Settings.ACTION_SETTINGS); }

    /** Wi-Fi / network settings. */
    public static void wifiSettings(Activity a) { systemScreen(a, android.provider.Settings.ACTION_WIFI_SETTINGS); }

    private static void systemScreen(Activity a, String action) {
        try { a.startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; }
        catch (Exception ignored) { }
        try { a.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { android.widget.Toast.makeText(a, "إعدادات الجهاز مش متاحة على الجهاز ده", android.widget.Toast.LENGTH_LONG).show(); }
    }

    /** Opens details for movies/series, plays channels/episodes directly. */
    public static void open(Activity a, Item it) {
        if (it.type.equals("movie") || it.type.equals("series")) {
            a.startActivity(new Intent(a, DetailActivity.class).putExtra("item", it.toJson().toString()));
        } else play(a, it);
    }

    /** "Continue watching": an episode reopens its series page (to pick the next / previous episode); the rest resumes directly. */
    public static void resume(Activity a, Item it) {
        if (it.type.equals("episode") && !it.sid.isEmpty()) {
            Item s = new Item();
            s.type = "series"; s.id = it.sid; s.name = it.sname.isEmpty() ? it.name : it.sname; s.icon = it.sicon;
            a.startActivity(new Intent(a, DetailActivity.class).putExtra("item", s.toJson().toString()).putExtra("focusEp", it.id));
        } else play(a, it);
    }

    public static void play(Activity a, Item it) {
        PlayQueue.set(Collections.singletonList(it), 0);
        a.startActivity(new Intent(a, PlayerActivity.class));
    }
}
