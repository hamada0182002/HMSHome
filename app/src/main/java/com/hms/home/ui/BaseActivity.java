package com.hms.home.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.view.KeyEvent;
import android.widget.Toast;
import com.hms.home.data.Net;
import com.hms.home.data.Store;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** Keeps track of background tasks so they stop when the screen goes away. Also: font size + full screen. */
public abstract class BaseActivity extends Activity {
    private final List<Net.Task> tasks = new ArrayList<>();
    private float fontScaleUsed = 1f;

    @Override protected void attachBaseContext(Context base) {
        fontScaleUsed = Store.fontScale();
        Configuration c = new Configuration(base.getResources().getConfiguration());
        c.fontScale = fontScaleUsed;
        super.attachBaseContext(base.createConfigurationContext(c));
    }

    @Override protected void onResume() {
        super.onResume();
        if (Store.fontScale() != fontScaleUsed) { recreate(); return; }
        Ui.immersive(getWindow());
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.immersive(getWindow());
    }

    /** Remote control: the cursor stops at the end of a list instead of jumping to another part of the screen. */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int k = e.getKeyCode();
            if ((k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_DPAD_DOWN)
                    && FocusNav.atEdge(getCurrentFocus(), k)) return true;
        }
        return super.dispatchKeyEvent(e);
    }

    protected <T> Net.Task run(Callable<T> c, Net.Ok<T> ok, Net.Fail fail) {
        Net.Task t = Net.run(c, ok, fail);
        tasks.add(t);
        return t;
    }

    protected void cancelAll() { for (Net.Task t : tasks) t.cancel(); tasks.clear(); }

    @Override protected void onDestroy() { cancelAll(); super.onDestroy(); }

    protected void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
