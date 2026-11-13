package com.hms.home.ui;

import android.app.Activity;
import android.widget.Toast;
import com.hms.home.data.Net;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** Keeps track of background tasks so they stop when the screen goes away. */
public abstract class BaseActivity extends Activity {
    private final List<Net.Task> tasks = new ArrayList<>();

    protected <T> Net.Task run(Callable<T> c, Net.Ok<T> ok, Net.Fail fail) {
        Net.Task t = Net.run(c, ok, fail);
        tasks.add(t);
        return t;
    }

    protected void cancelAll() { for (Net.Task t : tasks) t.cancel(); tasks.clear(); }

    @Override protected void onDestroy() { cancelAll(); super.onDestroy(); }

    protected void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
