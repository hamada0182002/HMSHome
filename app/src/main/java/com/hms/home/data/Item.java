package com.hms.home.data;

import org.json.JSONException;
import org.json.JSONObject;

/** One channel / movie / series / episode. type = live | movie | series | episode */
public class Item {
    public String type = "", id = "", name = "", icon = "", ext = "", cat = "", extra = "";
    public long added;

    public String key() { return type + ":" + id; }

    public JSONObject toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("t", type); o.put("i", id); o.put("n", name); o.put("c", icon);
            o.put("e", ext); o.put("x", extra); o.put("a", added); o.put("g", cat);
            return o;
        } catch (JSONException e) { return new JSONObject(); }
    }

    public static Item fromJson(JSONObject o) {
        Item it = new Item();
        it.type = o.optString("t"); it.id = o.optString("i"); it.name = o.optString("n");
        it.icon = o.optString("c"); it.ext = o.optString("e"); it.extra = o.optString("x");
        it.added = o.optLong("a"); it.cat = o.optString("g");
        return it;
    }
}
