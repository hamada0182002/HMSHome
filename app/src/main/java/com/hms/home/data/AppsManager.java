package com.hms.home.data;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Finds every installed app that can be launched (normal + TV launcher entries). */
public final class AppsManager {
    public static class App { public String pkg, label; public Intent launch; public Drawable icon; }

    private static List<App> cache;

    public static void invalidate() { cache = null; }

    /** Call from a background thread (loads icons). */
    public static synchronized List<App> load(Context ctx) {
        if (cache != null) return cache;
        PackageManager pm = ctx.getPackageManager();
        Map<String, App> map = new LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(ctx.getPackageName()) || map.containsKey(pkg)) continue;
                App a = new App();
                a.pkg = pkg;
                a.label = String.valueOf(ri.loadLabel(pm));
                a.icon = ri.loadIcon(pm);
                a.launch = new Intent(Intent.ACTION_MAIN).addCategory(cat)
                        .setClassName(pkg, ri.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                map.put(pkg, a);
            }
        }
        List<App> l = new ArrayList<>(map.values());
        final Collator col = Collator.getInstance();
        Collections.sort(l, new Comparator<App>() {
            @Override public int compare(App a, App b) { return col.compare(a.label, b.label); }
        });
        cache = l;
        return l;
    }

    public static App find(List<App> l, String pkg) {
        for (App a : l) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    public static boolean launch(Context c, App a) {
        try { c.startActivity(new Intent(a.launch)); Store.pushRecent(a.pkg); return true; }
        catch (ActivityNotFoundException | SecurityException e) { return false; }
    }

    public static IntentFilter changeFilter() {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        return f;
    }
}
