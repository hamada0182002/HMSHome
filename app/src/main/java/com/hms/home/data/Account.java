package com.hms.home.data;

import org.json.JSONObject;

/** One Xtream subscription (same fields as HMS Player's Account). */
public final class Account {
    public final String id, name, server, user, pass;

    public Account(String id, String name, String server, String user, String pass) {
        this.id = id; this.name = name; this.server = server; this.user = user; this.pass = pass;
    }

    public JSONObject toJson() {
        try {
            return new JSONObject().put("id", id).put("name", name).put("server", server).put("user", user).put("pass", pass);
        } catch (Exception e) { return new JSONObject(); }
    }

    public static Account fromJson(JSONObject o) {
        return new Account(o.optString("id"), o.optString("name"), o.optString("server"), o.optString("user"), o.optString("pass"));
    }
}
