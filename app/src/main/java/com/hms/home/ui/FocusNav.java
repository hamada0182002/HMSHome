package com.hms.home.ui;

import android.view.KeyEvent;
import android.view.View;
import android.view.ViewParent;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/** Remote-control (D-pad) helpers: stop at the end of a list, jump to a given item. */
public final class FocusNav {
    private FocusNav() { }

    /** The RecyclerView that holds the focused view (nearest one), or null. */
    public static RecyclerView listOf(View focus) {
        if (focus == null) return null;
        ViewParent p = focus.getParent();
        while (p != null) {
            if (p instanceof RecyclerView) return (RecyclerView) p;
            p = p.getParent();
        }
        return null;
    }

    /** Adapter position of the focused item inside its list, or -1. */
    public static int posOf(RecyclerView rv, View focus) {
        if (rv == null || focus == null) return -1;
        View item = rv.findContainingItemView(focus);
        return item == null ? -1 : rv.getChildAdapterPosition(item);
    }

    /**
     * True when the key would walk out of the end of the list the focus is in, so the caller should swallow it
     * (the cursor simply stops at the last item instead of jumping somewhere else on the screen).
     */
    public static boolean atEdge(View focus, int key) {
        RecyclerView rv = listOf(focus);
        if (rv == null || rv.getAdapter() == null) return false;
        int pos = posOf(rv, focus), n = rv.getAdapter().getItemCount();
        if (pos < 0 || n == 0) return false;
        RecyclerView.LayoutManager lm = rv.getLayoutManager();
        if (lm instanceof GridLayoutManager) {
            int span = ((GridLayoutManager) lm).getSpanCount();
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) return pos % span == span - 1 || pos == n - 1;
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) return pos + span >= n;
            return false;
        }
        if (lm instanceof LinearLayoutManager) {
            boolean horizontal = ((LinearLayoutManager) lm).getOrientation() == LinearLayoutManager.HORIZONTAL;
            if (horizontal) {
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) return pos == 0;
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) return pos == n - 1;
            } else {
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) return pos == n - 1;
                if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) return true;   // nothing sideways in a plain list
            }
        }
        return false;
    }

    /** Scrolls to the item and gives it focus (works even if it is not on screen yet). */
    public static void focusPos(final RecyclerView rv, final int pos) {
        if (rv == null || rv.getAdapter() == null || rv.getAdapter().getItemCount() == 0) return;
        final int p = Math.max(0, Math.min(pos, rv.getAdapter().getItemCount() - 1));
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(p);
        if (vh != null && vh.itemView.requestFocus()) return;
        rv.scrollToPosition(p);
        rv.post(new Runnable() {
            @Override public void run() {
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(p);
                if (h != null) h.itemView.requestFocus();
                else rv.post(new Runnable() { @Override public void run() {
                    RecyclerView.ViewHolder h2 = rv.findViewHolderForAdapterPosition(p);
                    if (h2 != null) h2.itemView.requestFocus();
                } });
            }
        });
    }
}
