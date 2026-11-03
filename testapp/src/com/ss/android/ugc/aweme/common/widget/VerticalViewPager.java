package com.ss.android.ugc.aweme.common.widget;

import android.content.Context;
import android.widget.FrameLayout;

/** Mimics the public surface TikTok's pager exposes: getAdapter() and getCurrentItem(). */
public class VerticalViewPager extends FrameLayout {
    private Object adapter;
    private int current;

    public VerticalViewPager(Context c) {
        super(c);
    }

    public void setAdapter(Object a) {
        adapter = a;
    }

    public Object getAdapter() {
        return adapter;
    }

    public int getCurrentItem() {
        return current;
    }

    public void setCurrentItem(int i) {
        current = i;
    }
}
