package com.hms.home;

import android.app.Application;
import com.hms.home.data.Certs;
import com.hms.home.data.ImageLoader;
import com.hms.home.data.Store;
import com.hms.home.data.Xtream;

public class HmsApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Store.init(this);
        Certs.init(this);
        Xtream.init(this);
        ImageLoader.init(this);
    }
}
