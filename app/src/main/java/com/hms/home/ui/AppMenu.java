package com.hms.home.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import com.hms.home.data.AppsManager;
import com.hms.home.data.Store;
import java.util.ArrayList;
import java.util.List;

/** Options dialog for one app (favorites add/remove/reorder, app info). */
public final class AppMenu {
    public static void show(final Activity a, final AppsManager.App app, final Runnable done) {
        final boolean fav = Store.isFavApp(app.pkg);
        final List<String> o = new ArrayList<>();
        o.add(fav ? "Remove from favorites" : "Add to favorites");
        if (fav) { o.add("Move earlier"); o.add("Move later"); }
        o.add("App info");
        new AlertDialog.Builder(a).setTitle(app.label).setItems(o.toArray(new String[0]), new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                String s = o.get(w);
                if (s.startsWith("Add") || s.startsWith("Remove")) Store.toggleFavApp(app.pkg);
                else if (s.equals("Move earlier")) Store.moveFavApp(app.pkg, -1);
                else if (s.equals("Move later")) Store.moveFavApp(app.pkg, 1);
                else {
                    try { a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg))); } catch (Exception ignored) { }
                }
                if (done != null) done.run();
            }
        }).show();
    }
}
