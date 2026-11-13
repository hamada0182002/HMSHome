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

    /** Opens details for movies/series, plays channels/episodes directly. */
    public static void open(Activity a, Item it) {
        if (it.type.equals("movie") || it.type.equals("series")) {
            a.startActivity(new Intent(a, DetailActivity.class).putExtra("item", it.toJson().toString()));
        } else play(a, it);
    }

    public static void play(Activity a, Item it) {
        PlayQueue.set(Collections.singletonList(it), 0);
        a.startActivity(new Intent(a, PlayerActivity.class));
    }
}
