package com.hms.home.input;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import com.hms.home.data.KeyServer;
import com.hms.home.data.Store;

/**
 * "HMS Mouse": turns the phone page (see {@link KeyServer}) into a mouse + remote for ANY app on the box.
 * - a pointer is drawn on top of everything (accessibility overlay), moved by the phone's touchpad;
 * - tap / long tap / scroll are injected as gestures at the pointer (Android 7.0+);
 * - Back / Home / Recents are global actions;
 * - arrows + OK move the focus with the accessibility tree (works well in TV apps, approximate in others).
 * The user must turn it on once in Android's Accessibility settings. While it runs it keeps the phone server up.
 */
public class MouseService extends AccessibilityService {
    private static final int OVERLAY_TYPE = 2032;   // TYPE_ACCESSIBILITY_OVERLAY (API 22+)
    private static volatile MouseService inst;

    public static boolean connected() { return inst != null; }
    public static boolean cursorVisible() { MouseService s = inst; return s != null && s.shown; }

    /** Always called on the main thread by KeyServer. */
    public static void act(String t, int x, int y) {
        MouseService s = inst;
        if (s != null) s.handle(t, x, y);
    }

    private WindowManager wm;
    private CursorView cursor;
    private WindowManager.LayoutParams lp;
    private boolean shown;
    private float cx, cy;
    private int sw = 1920, sh = 1080;
    private long gestureBusyUntil;

    // ---------------- service lifecycle ----------------
    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        inst = this;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        measure();
        cx = sw / 2f;
        cy = sh / 2f;
        KeyServer.pinned = true;
        KeyServer.shared().start();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(Intent intent) {
        cleanup();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private void cleanup() {
        hide();
        if (inst == this) inst = null;
        KeyServer.pinned = false;
        KeyServer sv = KeyServer.shared();
        if (Store.kbMode() != 1 && !sv.hasField()) sv.stop();
    }

    private void measure() {
        try {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            sw = Math.max(1, dm.widthPixels);
            sh = Math.max(1, dm.heightPixels);
        } catch (Throwable ignored) { }
    }

    // ---------------- commands from the phone ----------------
    private void handle(String t, int x, int y) {
        switch (t) {
            case "sh": show(); break;
            case "hd": hide(); break;
            case "mv": moveBy(x, y); break;
            case "cl": tap(60); break;
            case "lc": tap(900); break;
            case "sc": swipe(y); break;
            case "bk": performGlobalAction(GLOBAL_ACTION_BACK); break;
            case "hm": performGlobalAction(GLOBAL_ACTION_HOME); break;
            case "rc": performGlobalAction(GLOBAL_ACTION_RECENTS); break;
            case "up": dpad(View.FOCUS_UP); break;
            case "dn": dpad(View.FOCUS_DOWN); break;
            case "lf": dpad(View.FOCUS_LEFT); break;
            case "rt": dpad(View.FOCUS_RIGHT); break;
            case "ok": ok(); break;
            default: break;
        }
    }

    // ---------------- pointer ----------------
    private void show() {
        if (shown || wm == null) return;
        measure();
        if (cursor == null) cursor = new CursorView(this);
        lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                OVERLAY_TYPE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = (int) cx;
        lp.y = (int) cy;
        try { wm.addView(cursor, lp); shown = true; } catch (Throwable ignored) { shown = false; }
    }

    private void hide() {
        if (!shown) return;
        try { wm.removeView(cursor); } catch (Throwable ignored) { }
        shown = false;
    }

    private void moveBy(int dx, int dy) {
        if (!shown) show();
        if (!shown) return;
        cx = Math.max(0, Math.min(sw - 1, cx + dx));
        cy = Math.max(0, Math.min(sh - 1, cy + dy));
        lp.x = (int) cx;
        lp.y = (int) cy;
        try { wm.updateViewLayout(cursor, lp); } catch (Throwable ignored) { }
    }

    // ---------------- gestures at the pointer ----------------
    @TargetApi(24)
    private void tap(long durationMs) {
        if (Build.VERSION.SDK_INT < 24) return;
        Path p = new Path();
        p.moveTo(cx, cy);
        try {
            dispatchGesture(new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, durationMs)).build(), null, null);
        } catch (Throwable ignored) { }
    }

    /** Finger drag from the pointer: dy &gt; 0 drags down (content follows the finger, like on a phone). */
    @TargetApi(24)
    private void swipe(int dy) {
        if (Build.VERSION.SDK_INT < 24) return;
        long now = SystemClock.uptimeMillis();
        if (now < gestureBusyUntil) return;
        float ey = Math.max(0, Math.min(sh - 1, cy + dy));
        if (Math.abs(ey - cy) < 20) return;
        Path p = new Path();
        p.moveTo(cx, cy);
        p.lineTo(cx, ey);
        try {
            dispatchGesture(new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, 280)).build(), null, null);
            gestureBusyUntil = now + 330;
        } catch (Throwable ignored) { }
    }

    // ---------------- remote-style keys (focus navigation) ----------------
    private void dpad(int dir) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            AccessibilityNodeInfo cur = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (cur == null) cur = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            AccessibilityNodeInfo target = cur != null ? cur.focusSearch(dir) : firstFocusable(root, 0);
            if (target == null) return;
            if (!target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
            }
        } catch (Throwable ignored) { }
    }

    /** OK: click the focused item; when there is none, tap at the pointer (if it is visible). */
    private void ok() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                AccessibilityNodeInfo n = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (n == null) n = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
                int depth = 0;
                while (n != null && !n.isClickable() && depth < 6) { n = n.getParent(); depth++; }
                if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
            }
        } catch (Throwable ignored) { }
        if (shown) tap(60);
    }

    private static AccessibilityNodeInfo firstFocusable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 14) return null;
        if (depth > 0 && n.isFocusable() && n.isVisibleToUser()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = firstFocusable(n.getChild(i), depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    // ---------------- the arrow ----------------
    private static final class CursorView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        private final int w, h;

        CursorView(Context c) {
            super(c);
            float s = 1.4f * c.getResources().getDisplayMetrics().density;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0xFFF2E6D0);
            line.setStyle(Paint.Style.STROKE);
            line.setColor(0xFF111111);
            line.setStrokeWidth(Math.max(1.5f, 1.6f * s));
            arrow.moveTo(1 * s, 1 * s);
            arrow.lineTo(1 * s, 26 * s);
            arrow.lineTo(7 * s, 20 * s);
            arrow.lineTo(12 * s, 31 * s);
            arrow.lineTo(16 * s, 29 * s);
            arrow.lineTo(11 * s, 18 * s);
            arrow.lineTo(19 * s, 18 * s);
            arrow.close();
            w = (int) (22 * s);
            h = (int) (34 * s);
        }

        @Override protected void onMeasure(int wSpec, int hSpec) { setMeasuredDimension(w, h); }

        @Override protected void onDraw(Canvas canvas) {
            canvas.drawPath(arrow, fill);
            canvas.drawPath(arrow, line);
        }
    }
}
