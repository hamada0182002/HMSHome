package com.hms.home.dlna;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import com.hms.home.data.Store;

/** Keeps the DLNA renderer alive while HMS HOME is running (so phones can always find the box). */
public class DlnaService extends Service {
    private static DlnaServer server;

    /** Starts / stops the service according to the setting. */
    public static void sync(Context c) {
        Context a = c.getApplicationContext();
        try {
            if (Store.dlnaOn()) a.startService(new Intent(a, DlnaService.class));
            else a.stopService(new Intent(a, DlnaService.class));
        } catch (Exception ignored) { }
    }

    /** Name or port changed: restart so phones see the new name. */
    public static void restart(Context c) {
        Context a = c.getApplicationContext();
        a.stopService(new Intent(a, DlnaService.class));
        sync(a);
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        if (!Store.dlnaOn()) { stopSelf(); return START_NOT_STICKY; }
        if (server == null) { server = new DlnaServer(this); server.start(); }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (server != null) { server.stop(); server = null; }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
