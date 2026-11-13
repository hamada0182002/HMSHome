package com.hms.home.input;

import android.content.Context;
import android.provider.Settings;

/** Is the phone keyboard turned on / chosen in Android's keyboard settings. */
public final class KbHelper {
    public static boolean enabled(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_INPUT_METHODS);
        return s != null && s.contains(c.getPackageName() + "/");
    }

    public static boolean selected(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return s != null && s.startsWith(c.getPackageName() + "/");
    }

    public static String state(Context c) {
        return selected(c) ? "Selected" : enabled(c) ? "Enabled - choose it as the keyboard" : "Not enabled yet";
    }
}
