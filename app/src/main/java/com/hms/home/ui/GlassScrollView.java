package com.hms.home.ui;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.widget.ScrollView;

/** ScrollView whose content scrolls under the glass top bar, but never leaves the focused row hidden behind it. */
public class GlassScrollView extends ScrollView {
    private int inset;
    public GlassScrollView(Context c) { super(c); }
    public GlassScrollView(Context c, AttributeSet a) { super(c, a); }

    /** Height of the bar that covers the top of this view. */
    public void setTopInset(int px) {
        inset = px;
        setClipToPadding(false);
        setPadding(getPaddingLeft(), px, getPaddingRight(), getPaddingBottom());
    }

    @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        Rect r = new Rect(rect);
        r.top -= inset;
        return super.computeScrollDeltaToGetChildRectOnScreen(r);
    }
}
