package com.hms.home.data;

import java.util.ArrayList;
import java.util.List;

/** The list the player is currently walking through (channels of a category, or episodes of a season). */
public final class PlayQueue {
    public static List<Item> items = new ArrayList<>();
    public static int index;

    public static void set(List<Item> l, int i) {
        items = new ArrayList<>(l);
        index = Math.max(0, Math.min(i, items.size() - 1));
    }
}
