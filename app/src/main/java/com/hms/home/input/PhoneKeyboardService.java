package com.hms.home.input;

import android.inputmethodservice.InputMethodService;
import android.os.IBinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.hms.home.data.KeyServer;
import com.hms.home.data.Store;
import com.hms.home.ui.Ui;

/**
 * "HMS Phone Keyboard": an input method that has no keys. The text comes from the phone through {@link KeyServer},
 * so it works in any app (YouTube, browser, ...). The server runs according to the mode chosen in Settings.
 */
public class PhoneKeyboardService extends InputMethodService implements KeyServer.Host {
    private KeyServer server;
    private TextView info;
    private volatile boolean field;
    private volatile long epoch = System.currentTimeMillis();

    @Override public void onCreate() {
        super.onCreate();
        server = KeyServer.shared();
        server.setHost(this);
        if (Store.kbMode() == 1) server.start();      // "always on"
    }

    @Override public void onDestroy() {
        server.clearHost(this);
        server.stopUnlessPinned();
        super.onDestroy();
    }

    @Override public boolean onEvaluateFullscreenMode() { return false; }

    @Override public View onCreateInputView() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF1E1E1E);
        bar.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 10));
        info = Ui.label(this, "", 16, com.hms.home.R.color.white, false);
        bar.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView sw = Ui.button(this, "Switch keyboard");
        sw.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { goNext(); } });
        bar.addView(sw);
        updateInfo();
        return bar;
    }

    @Override public void onStartInputView(EditorInfo ei, boolean restarting) {
        super.onStartInputView(ei, restarting);
        field = true;
        if (!restarting) epoch = System.currentTimeMillis();   // new field: the phone page clears its box
        int mode = Store.kbMode();
        server.setHost(this);
        if (mode == 2) server.stopUnlessPinned(); else server.start();
        updateInfo();
    }

    @Override public void onFinishInputView(boolean finishingInput) {
        field = false;
        if (Store.kbMode() != 1) server.stopUnlessPinned();                // only while typing: free the port and the memory
        super.onFinishInputView(finishingInput);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU && isInputViewShown()) { goNext(); return true; }
        return super.onKeyDown(keyCode, event);
    }

    private void updateInfo() {
        if (info == null) return;
        String u = KeyServer.url();
        String s;
        if (Store.kbMode() == 2) s = "HMS Phone Keyboard is OFF (HMS HOME settings). Press MENU to switch keyboard.";
        else if (u == null) s = "No Wi-Fi / Ethernet network - connect the box to your network.   MENU = switch keyboard";
        else if (!server.isRunning()) s = "Port " + KeyServer.PORT + " is busy - restart the box.   MENU = switch keyboard";
        else s = "Type from your phone:  " + u + "\nMENU = switch keyboard";
        info.setText(s);
    }

    /** Goes to the next keyboard (the box's normal one); shows the picker when there is none. */
    private void goNext() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        try {
            IBinder tok = getWindow().getWindow().getAttributes().token;
            if (imm.switchToNextInputMethod(tok, false)) return;
        } catch (Throwable ignored) { }
        imm.showInputMethodPicker();
    }

    // ---------------- KeyServer.Host (main thread) ----------------
    @Override public void type(int del, String add) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.beginBatchEdit();
        if (del > 0) ic.deleteSurroundingText(del, 0);
        if (add != null && !add.isEmpty()) ic.commitText(add, 1);
        ic.endBatchEdit();
    }

    @Override public void key(String k) {
        InputConnection ic = getCurrentInputConnection();
        if (k.equals("switch")) { goNext(); return; }
        if (ic == null) return;
        if (k.equals("clear")) { ic.deleteSurroundingText(100000, 100000); return; }
        if (k.equals("enter")) {
            EditorInfo ei = getCurrentInputEditorInfo();
            int action = ei == null ? EditorInfo.IME_ACTION_NONE : (ei.imeOptions & EditorInfo.IME_MASK_ACTION);
            boolean noAction = ei == null || (ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
                    || action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED;
            if (noAction) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER); else ic.performEditorAction(action);
        }
    }

    @Override public long epoch() { return epoch; }
    @Override public boolean hasField() { return field; }
}
