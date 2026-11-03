package com.rigaz.tgbtn;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the Aweme that is on screen.
 *
 * TikTok 46.4.3 shows the feed (MainActivity) and opened videos (DetailActivity) through
 * BaseListFragmentPanel, which drives a com.ss.android.ugc.aweme.common.widget.VerticalViewPager.
 * Its base class X.12PR declares getAdapter()/getCurrentItem(), and the feed adapter X.0RYP has
 * public Aweme getItem(int). Those names survive obfuscation, so the lookup is
 * adapter.getItem(pager.getCurrentItem()) on the pager that covers the middle of the screen.
 * If no pager matches, the views under the middle of the screen are searched for an Aweme field.
 */
final class AwemeLocator {
    private static final String AWEME = "com.ss.android.ugc.aweme.feed.model.Aweme";
    private static final String PAGER = "com.ss.android.ugc.aweme.common.widget.VerticalViewPager";

    private AwemeLocator() {
    }

    static Object find(Activity activity) throws ClassNotFoundException {
        ClassLoader cl = activity.getClassLoader();
        Class<?> awemeCls = Class.forName(AWEME, false, cl);
        Class<?> pagerCls = Class.forName(PAGER, false, cl);
        View decor = activity.getWindow().getDecorView();
        int cx = decor.getWidth() / 2;
        int cy = decor.getHeight() / 2;

        List<View> pagers = new ArrayList<View>();
        collect(decor, pagerCls, pagers);
        // Later in traversal order means drawn on top.
        for (int i = pagers.size() - 1; i >= 0; i--) {
            View pager = pagers.get(i);
            if (!covers(pager, cx, cy)) continue;
            Object aweme = currentItem(pager, awemeCls);
            if (aweme != null) return aweme;
        }
        return scanUnder(decor, cx, cy, awemeCls);
    }

    private static void collect(View v, Class<?> cls, List<View> out) {
        if (cls.isInstance(v)) out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), cls, out);
        }
    }

    private static boolean covers(View v, int x, int y) {
        if (!v.isShown()) return false;
        Rect r = new Rect();
        return v.getGlobalVisibleRect(r) && r.contains(x, y);
    }

    private static Object currentItem(View pager, Class<?> awemeCls) {
        try {
            Object adapter = pager.getClass().getMethod("getAdapter").invoke(pager);
            if (adapter == null) return null;
            int index = (Integer) pager.getClass().getMethod("getCurrentItem").invoke(pager);
            Method getItem = itemGetter(adapter.getClass(), awemeCls);
            if (getItem == null) return null;
            Object item = getItem.invoke(adapter, index);
            return awemeCls.isInstance(item) ? item : null;
        } catch (Throwable t) {
            android.util.Log.w(Ttg.LOG, "pager lookup failed", t);
            return null;
        }
    }

    /** getItem(int) by name first, then any public (int) -> Aweme method. */
    private static Method itemGetter(Class<?> adapterCls, Class<?> awemeCls) {
        try {
            Method m = adapterCls.getMethod("getItem", int.class);
            if (awemeCls.isAssignableFrom(m.getReturnType())) return m;
        } catch (NoSuchMethodException ignored) {
        }
        for (Method m : adapterCls.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && p[0] == int.class && awemeCls.isAssignableFrom(m.getReturnType())) return m;
        }
        return null;
    }

    // ── Fallback: look for an Aweme held by the views under (x, y) ──

    private static Object scanUnder(View root, int x, int y, Class<?> awemeCls) {
        List<View> chain = new ArrayList<View>();
        View v = deepestAt(root, x, y);
        while (v != null) {
            chain.add(v);
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        for (View view : chain) {
            Object found = awemeIn(view, awemeCls);
            if (found == null) found = awemeIn(view.getTag(), awemeCls);
            if (found != null) return found;
        }
        return null;
    }

    private static View deepestAt(View v, int x, int y) {
        if (!covers(v, x, y)) return null;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                View hit = deepestAt(g.getChildAt(i), x, y);
                if (hit != null) return hit;
            }
        }
        return v;
    }

    /** Aweme fields of obj, then one level down into non-framework fields. */
    private static Object awemeIn(Object obj, Class<?> awemeCls) {
        if (obj == null) return null;
        if (awemeCls.isInstance(obj)) return obj;
        List<Object> next = new ArrayList<Object>();
        for (Object value : fieldValues(obj)) {
            if (awemeCls.isInstance(value)) return value;
            if (value != null && !isFramework(value.getClass())) next.add(value);
        }
        for (Object o : next) {
            for (Object value : fieldValues(o)) {
                if (awemeCls.isInstance(value)) return value;
            }
        }
        return null;
    }

    private static List<Object> fieldValues(Object obj) {
        List<Object> out = new ArrayList<Object>();
        for (Class<?> c = obj.getClass(); c != null && !isFramework(c); c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    out.add(f.get(obj));
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    private static boolean isFramework(Class<?> c) {
        String n = c.getName();
        return n.startsWith("android.") || n.startsWith("java.") || n.startsWith("javax.")
                || n.startsWith("kotlin.") || n.startsWith("androidx.") || c.isArray();
    }
}
