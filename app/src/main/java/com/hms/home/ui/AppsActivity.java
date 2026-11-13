package com.hms.home.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.hms.home.R;
import com.hms.home.data.AppsManager;
import com.hms.home.data.Net;
import com.hms.home.data.Store;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

public class AppsActivity extends BaseActivity {
    private TileAdapter ad;
    private boolean receiverOn;
    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { AppsManager.invalidate(); load(); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_apps);
        RecyclerView rv = findViewById(R.id.grid);
        rv.setLayoutManager(new GridLayoutManager(this, 6));
        ad = new TileAdapter(110, 110, true, new TileAdapter.Listener() {
            @Override public void click(TileAdapter.Tile t, int pos) {
                if (!AppsManager.launch(AppsActivity.this, (AppsManager.App) t.data)) toast("تعذر فتح التطبيق");
            }
            @Override public void menu(TileAdapter.Tile t, int pos) {
                AppMenu.show(AppsActivity.this, (AppsManager.App) t.data, new Runnable() { @Override public void run() { load(); } });
            }
            @Override public void focus(TileAdapter.Tile t, int pos) { }
        });
        rv.setAdapter(ad);
    }

    @Override protected void onStart() {
        super.onStart();
        if (!receiverOn) { registerReceiver(rx, AppsManager.changeFilter()); receiverOn = true; }
        load();
    }

    @Override protected void onStop() {
        if (receiverOn) { unregisterReceiver(rx); receiverOn = false; }
        super.onStop();
    }

    private void load() {
        run(new Callable<List<AppsManager.App>>() {
            @Override public List<AppsManager.App> call() { return AppsManager.load(getApplicationContext()); }
        }, new Net.Ok<List<AppsManager.App>>() {
            @Override public void ok(List<AppsManager.App> apps) {
                List<TileAdapter.Tile> t = new ArrayList<>();
                for (AppsManager.App a : apps) t.add(new TileAdapter.Tile(a.label, Store.isFavApp(a.pkg) ? "\u2605 Favorite" : "", null, a.icon, a));
                ad.set(t);
            }
        }, null);
    }
}
