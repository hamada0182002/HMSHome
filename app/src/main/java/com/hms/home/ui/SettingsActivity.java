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
import com.hms.home.data.KeyServer;
import com.hms.home.input.KbHelper;
import com.hms.home.data.Net;
import com.hms.home.data.Updater;
import com.hms.home.data.Store;
import com.hms.home.data.Xtream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

public class SettingsActivity extends BaseActivity {
    private TextAdapter ad;
    private RecyclerView rv;
    private TextView titleTv;
    /** null = main list of groups, otherwise the open group. */
    private String page;
    private int focusRow;
    private static final String[] ASPECT = {"Fit", "Stretch", "Zoom"};
    private static final String[] FONT = {"Normal", "Large", "Extra large"};
    private static final String[] KBMODE = {"Only while typing (saves RAM)", "Always on", "Off"};

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawableResource(R.drawable.bg_home);
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 48), Ui.dp(this, 30), Ui.dp(this, 48), Ui.dp(this, 20));
        titleTv = Ui.label(this, "Settings", 30, R.color.h_ink, true);
        root.addView(titleTv);
        rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        ad = new TextAdapter(false, true, new TextAdapter.Listener() {
            @Override public void click(TextAdapter.Row r, int pos) { focusRow = pos; act((String) r.data); }
            @Override public void focus(TextAdapter.Row r, int pos) { }
        });
        rv.setAdapter(ad);
        root.addView(rv, new android.widget.LinearLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    @Override protected void onResume() { super.onResume(); build(); }

    private static final String[][] GROUPS = {
            {"g_iptv", "IPTV", "Accounts, reload data, what shows on the home"},
            {"g_kb", "Phone keyboard & mouse", "Type and control the box from your phone"},
            {"g_player", "Player & display", "Font size, aspect ratio, subtitles"},
            {"g_apps", "Apps & favorites", "Favorite apps and clearing favorites"},
            {"g_sys", "Device & system", "Android settings, default home, clear cache"},
            {"g_about", "Updates & about", "Check for updates, version info"},
    };

    private static String groupTitle(String id) {
        for (String[] g : GROUPS) if (g[0].equals(id)) return g[1];
        return "";
    }

    private void build() {
        List<TextAdapter.Row> r = new ArrayList<>();
        if (page == null) {
            titleTv.setText("Settings");
            for (String[] g : GROUPS) r.add(new TextAdapter.Row(g[1], g[2], g[0]));
        } else {
            titleTv.setText("Settings  \u203A  " + groupTitle(page));
            switch (page) {
                case "g_iptv":
                    r.add(new TextAdapter.Row("IPTV accounts", Store.hasLogin() ? Store.active().name + "  |  " + Store.user() + " @ " + Store.server().replace("http://", "").replace("https://", "") + "   (" + Store.accounts().size() + ")" : "No account yet - add one", "account"));
                    String[] hm = Store.homeCat("movie"), hs = Store.homeCat("series");
                    r.add(new TextAdapter.Row("Home: new movies from", hm == null ? "Latest added (automatic)" : "Folder: " + hm[1], "homemovie"));
                    r.add(new TextAdapter.Row("Home: new series from", hs == null ? "Latest added (automatic)" : "Folder: " + hs[1], "homeseries"));
                    r.add(new TextAdapter.Row("Reload IPTV data", "Re-downloads channel / movie / series index next time", "reload"));
                    break;
                case "g_kb":
                    r.add(new TextAdapter.Row("QR code", "Type on your phone instead of the remote  \u2022  " + KbHelper.state(this), "kbqr"));
                    r.add(new TextAdapter.Row("Enable keyboard", "Turn on \"HMS Phone Keyboard\" in Android's keyboard list", "kbenable"));
                    r.add(new TextAdapter.Row("Choose keyboard", "Pick \"HMS Phone Keyboard\" as the current keyboard", "kbpick"));
                    r.add(new TextAdapter.Row("Keyboard server", KBMODE[Store.kbMode()] + "  (press OK to change)", "kbmode"));
                    r.add(new TextAdapter.Row("Mouse & remote", com.hms.home.input.MouseService.connected()
                            ? "On  \u2022  open the phone keyboard page and press the mouse button"
                            : "Off  \u2022  press OK, then turn on \"HMS Mouse\" in Accessibility", "mouse"));
                    r.add(new TextAdapter.Row("Reset code", "Creates a new link - the page saved on your phone stops working", "kbreset"));
                    break;
                case "g_player":
                    r.add(new TextAdapter.Row("Font size", FONT[Store.fontLevel()] + "  (press OK to change)", "font"));
                    r.add(new TextAdapter.Row("Default aspect ratio", ASPECT[Store.aspect()], "aspect"));
                    r.add(new TextAdapter.Row("Default subtitles", Store.subsDefault() ? "On" : "Off", "subs"));
                    break;
                case "g_apps":
                    r.add(new TextAdapter.Row("Manage favorite apps", "Open APPS (hold OK on an app)", "apps"));
                    r.add(new TextAdapter.Row("Clear favorites", "Apps + channels + movies + series", "clearfav"));
                    break;
                case "g_sys":
                    r.add(new TextAdapter.Row("Device settings", "Android settings: Wi-Fi, display, sound, apps...", "device"));
                    r.add(new TextAdapter.Row("Set HMS HOME as default home", "Pick \"HMS HOME\" then \"Always\"", "home"));
                    r.add(new TextAdapter.Row("Clear cache", "Images + IPTV index", "cache"));
                    break;
                case "g_about":
                    r.add(new TextAdapter.Row("Check for updates", "Current version " + BuildConfig.VERSION_NAME + "  -  downloads and installs the newest version online", "update"));
                    r.add(new TextAdapter.Row("About", "HMS HOME " + BuildConfig.VERSION_NAME + "  \u2022  com.hms.home  \u2022  Android " + Build.VERSION.RELEASE + "  \u2022  " + Build.CPU_ABI, "about"));
                    break;
                default: break;
            }
        }
        ad.set(r);
        FocusNav.focusPos(rv, focusRow);
    }

    /** BACK inside a group returns to the main list of groups (and not out of settings). */
    @Override public void onBackPressed() {
        if (page != null) {
            int idx = 0;
            for (int i = 0; i < GROUPS.length; i++) if (GROUPS[i][0].equals(page)) idx = i;
            page = null; focusRow = idx; build();
        } else super.onBackPressed();
    }

    private void act(String id) {
        if (id.startsWith("g_")) { page = id; focusRow = 0; build(); return; }
        switch (id) {
            case "account": startActivity(new Intent(this, LoginActivity.class)); break;
            case "reload": Xtream.clearCache(); HomeActivity.invalidateMemo(); toast("هيتم تحميل بيانات IPTV من جديد"); break;
            case "update": checkUpdate(); break;
            case "homemovie": pickHomeCat("movie"); break;
            case "homeseries": pickHomeCat("series"); break;
            case "font": Store.setFontLevel((Store.fontLevel() + 1) % 3); recreate(); break;
            case "aspect": Store.setAspect((Store.aspect() + 1) % 3); build(); break;
            case "subs": Store.setSubsDefault(!Store.subsDefault()); build(); break;
            case "kbqr": showKbQr(); break;
            case "kbenable":
                try { startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)); }
                catch (Exception e) { Nav.deviceSettings(this); }
                break;
            case "kbpick":
                ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker();
                break;
            case "mouse":
                try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception e) { Nav.deviceSettings(this); }
                break;
            case "kbmode": Store.setKbMode((Store.kbMode() + 1) % 3); build(); break;
            case "kbreset":
                new AlertDialog.Builder(this, R.style.HmsDialog).setTitle("Reset keyboard code?")
                        .setMessage("The old link stops working. Show the QR again and save the new page on your phone.")
                        .setPositiveButton("Reset", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) { Store.resetKbToken(); toast("تم تغيير الكود"); showKbQr(); }
                        }).setNegativeButton("Cancel", null).show();
                break;
            case "apps": startActivity(new Intent(this, AppsActivity.class)); break;
            case "clearfav":
                new AlertDialog.Builder(this, R.style.HmsDialog).setTitle("Clear all favorites?").setPositiveButton("Clear", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { Store.clearFavApps(); Store.clearFavItems(); toast("تم المسح"); }
                }).setNegativeButton("Cancel", null).show();
                break;
            case "cache": ImageLoader.clear(); Xtream.clearCache(); HomeActivity.invalidateMemo(); toast("تم مسح الكاش"); break;
            case "device": Nav.deviceSettings(this); break;
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

    /** Fixed QR for the phone keyboard page (same link every time, so the page can be saved on the phone). */
    private void showKbQr() {
        String url = KeyServer.url();
        if (url == null) { toast("مفيش شبكة محلية (Wi-Fi / Ethernet) متوصلة"); return; }
        android.graphics.Bitmap qr;
        try { qr = Ui.qr(url, Math.min(Ui.sh(this) * 55 / 100, Ui.dp(this, 340))); }
        catch (Exception e) { toast("تعذر إنشاء الباركود"); return; }
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(this, 24), Ui.dp(this, 12), Ui.dp(this, 24), Ui.dp(this, 4));
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageBitmap(qr);
        box.addView(iv, new android.widget.LinearLayout.LayoutParams(qr.getWidth(), qr.getHeight()));
        TextView steps = Ui.label(this, "1) وصّل الموبايل بنفس شبكة الواي فاي بتاعة التيفي\n2) صوّر الباركود وافتح الرابط، وبعدين احفظ الصفحة في الشاشة الرئيسية للموبايل\n3) على التيفي اختار \"HMS Phone Keyboard\" كيبورد، وافتح أي خانة كتابة واكتب من الموبايل\n\nلازم IP البوكس يفضل ثابت (من إعدادات الراوتر) علشان الرابط ما يتغيرش.", 14, R.color.h_ink, false);
        steps.setPadding(0, Ui.dp(this, 10), 0, 0);
        box.addView(steps);
        TextView link = Ui.label(this, url, 13, R.color.h_ink2, false);
        link.setPadding(0, Ui.dp(this, 6), 0, 0);
        box.addView(link);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this, R.style.HmsDialog).setTitle("Phone keyboard").setView(sv).setPositiveButton("OK", null).show();
    }

    /** Lets the user pick which server folder (category) feeds the "new" rows on the home. */
    private void pickHomeCat(final String type) {
        if (!Store.hasLogin()) { toast("سجّل دخول IPTV الأول"); return; }
        toast("Loading folders…");
        run(new Callable<List<Xtream.Cat>>() {
            @Override public List<Xtream.Cat> call() throws Exception { return Xtream.categories(type); }
        }, new Net.Ok<List<Xtream.Cat>>() {
            @Override public void ok(final List<Xtream.Cat> cats) {
                String[] names = new String[cats.size() + 1];
                names[0] = "Latest added (automatic)";
                for (int i = 0; i < cats.size(); i++) names[i + 1] = cats.get(i).name;
                new AlertDialog.Builder(SettingsActivity.this, R.style.HmsDialog)
                        .setTitle(type.equals("movie") ? "Home: new movies from" : "Home: new series from")
                        .setItems(names, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                if (w == 0) Store.setHomeCat(type, null, null);
                                else Store.setHomeCat(type, cats.get(w - 1).id, cats.get(w - 1).name);
                                HomeActivity.invalidateMemo();
                                build();
                            }
                        }).show();
            }
        }, new Net.Fail() {
            @Override public void fail(Exception e) { showMsg("Couldn't load folders", Net.msg(e)); }
        });
    }

    private void showMsg(String title, String msg) {
        new AlertDialog.Builder(this, R.style.HmsDialog).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
    }

    private void checkUpdate() {
        toast("Checking for updates…");
        run(new Callable<Updater.Info>() {
            @Override public Updater.Info call() throws Exception { return Updater.check(BuildConfig.VERSION_NAME); }
        }, new Net.Ok<Updater.Info>() {
            @Override public void ok(Updater.Info i) {
                if (i == null) showMsg("Up to date", "You already have the latest version (" + BuildConfig.VERSION_NAME + ").");
                else askInstall(i);
            }
        }, new Net.Fail() {
            @Override public void fail(Exception e) { showMsg("Update check failed", Net.msg(e)); }
        });
    }

    private void askInstall(final Updater.Info info) {
        String notes = info.notes.isEmpty() ? "" : "\n\n" + (info.notes.length() > 400 ? info.notes.substring(0, 400) + "…" : info.notes);
        new AlertDialog.Builder(this, R.style.HmsDialog)
                .setTitle("New version " + info.version)
                .setMessage("You have " + BuildConfig.VERSION_NAME + ". Download and install " + info.version + " now?" + notes)
                .setPositiveButton("Update", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { download(info); }
                })
                .setNegativeButton("Later", null).show();
    }

    private void download(final Updater.Info info) {
        final TextView pt = new TextView(this);
        pt.setPadding(Ui.dp(this, 24), Ui.dp(this, 16), Ui.dp(this, 24), Ui.dp(this, 16));
        pt.setTextSize(18);
        pt.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.h_ink));
        pt.setText("Downloading… 0%");
        final AlertDialog pd = new AlertDialog.Builder(this, R.style.HmsDialog).setTitle("Updating").setView(pt).setCancelable(false).create();
        pd.show();
        run(new Callable<File>() {
            @Override public File call() throws Exception {
                return Updater.download(getApplicationContext(), info, new Updater.Progress() {
                    @Override public void percent(final int p) {
                        runOnUiThread(new Runnable() { @Override public void run() { pt.setText("Downloading… " + p + "%"); } });
                    }
                });
            }
        }, new Net.Ok<File>() {
            @Override public void ok(File f) {
                pd.dismiss();
                if (!Updater.install(SettingsActivity.this, f))
                    showMsg("Allow installing", "Turn on \"Install unknown apps\" for HMS HOME in the screen that just opened, then come back and press Check for updates again.");
            }
        }, new Net.Fail() {
            @Override public void fail(Exception e) { pd.dismiss(); showMsg("Download failed", Net.msg(e)); }
        });
    }
}
