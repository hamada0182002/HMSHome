package com.hms.home.ui;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.hms.home.BuildConfig;
import com.hms.home.R;
import com.hms.home.data.AppsManager;
import com.hms.home.data.ImageLoader;
import com.hms.home.data.Store;
import com.hms.home.data.Xtream;
import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends BaseActivity {
    private TextAdapter ad;
    private static final String[] ASPECT = {"Fit", "Stretch", "Zoom"};

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 48), Ui.dp(this, 30), Ui.dp(this, 48), Ui.dp(this, 20));
        TextView title = Ui.label(this, "SETTINGS", 28, R.color.bronze, true);
        root.addView(title);
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        ad = new TextAdapter(false, new TextAdapter.Listener() {
            @Override public void click(TextAdapter.Row r, int pos) { act((String) r.data); }
            @Override public void focus(TextAdapter.Row r, int pos) { }
        });
        rv.setAdapter(ad);
        root.addView(rv, new android.widget.LinearLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    @Override protected void onResume() { super.onResume(); build(); }

    private void build() {
        List<TextAdapter.Row> r = new ArrayList<>();
        r.add(new TextAdapter.Row("IPTV account", Store.hasLogin() ? Store.user() + " @ " + Store.server() + (Store.isSaved() ? "" : "  (session only)") : "Not logged in", "account"));
        if (Store.hasLogin()) r.add(new TextAdapter.Row("Log out of IPTV", "Removes saved login", "logout"));
        r.add(new TextAdapter.Row("Reload IPTV data", "Re-downloads channel / movie / series index next time", "reload"));
        r.add(new TextAdapter.Row("Default aspect ratio", ASPECT[Store.aspect()], "aspect"));
        r.add(new TextAdapter.Row("Default subtitles", Store.subsDefault() ? "On" : "Off", "subs"));
        r.add(new TextAdapter.Row("Manage favorite apps", "Open APPS (hold OK on an app)", "apps"));
        r.add(new TextAdapter.Row("Clear favorites", "Apps + channels + movies + series", "clearfav"));
        r.add(new TextAdapter.Row("Clear cache", "Images + IPTV index", "cache"));
        r.add(new TextAdapter.Row("Set HMS HOME as default home", "Pick \"HMS HOME\" then \"Always\"", "home"));
        r.add(new TextAdapter.Row("About", "HMS HOME " + BuildConfig.VERSION_NAME + "  •  com.hms.home  •  Android " + Build.VERSION.RELEASE + "  •  " + Build.CPU_ABI, "about"));
        ad.set(r);
    }

    private void act(String id) {
        switch (id) {
            case "account": startActivity(new Intent(this, LoginActivity.class)); break;
            case "logout": Store.logout(); Xtream.clearCache(); HomeActivity.invalidateMemo(); toast("تم تسجيل الخروج"); build(); break;
            case "reload": Xtream.clearCache(); HomeActivity.invalidateMemo(); toast("هيتم تحميل بيانات IPTV من جديد"); break;
            case "aspect": Store.setAspect((Store.aspect() + 1) % 3); build(); break;
            case "subs": Store.setSubsDefault(!Store.subsDefault()); build(); break;
            case "apps": startActivity(new Intent(this, AppsActivity.class)); break;
            case "clearfav":
                new AlertDialog.Builder(this).setTitle("Clear all favorites?").setPositiveButton("Clear", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { Store.clearFavApps(); Store.clearFavItems(); toast("تم المسح"); }
                }).setNegativeButton("Cancel", null).show();
                break;
            case "cache": ImageLoader.clear(); Xtream.clearCache(); HomeActivity.invalidateMemo(); toast("تم مسح الكاش"); break;
            case "home":
                try { startActivity(new Intent(Settings.ACTION_HOME_SETTINGS)); }
                catch (Exception e) {
                    Intent i = new Intent(Intent.ACTION_MAIN); i.addCategory(Intent.CATEGORY_HOME); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                }
                break;
            default: break;
        }
    }
}
