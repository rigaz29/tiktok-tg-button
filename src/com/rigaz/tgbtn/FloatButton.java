package com.rigaz.tgbtn;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Draggable round button that lives on top of the activity's decor view. */
final class FloatButton {
    private static final String TAG_VIEW = "tgbtn.float";
    private static final String PREFS = "tgbtn";
    private static final int IDLE_COLOR = 0xCC229ED9;   // Telegram blue
    private static final int BUSY_COLOR = 0xCCFFA000;

    private FloatButton() {
    }

    static void attach(final Activity activity) {
        final ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        if (decor.findViewWithTag(TAG_VIEW) != null) return;

        final Context ctx = activity;
        final int size = dp(ctx, 48);
        final TextView btn = new TextView(ctx);
        btn.setTag(TAG_VIEW);
        btn.setText("TG");
        btn.setTextColor(Color.WHITE);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btn.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(IDLE_COLOR);
        bg.setStroke(dp(ctx, 2), 0x99FFFFFF);
        btn.setBackground(bg);
        btn.setElevation(dp(ctx, 12));
        btn.setContentDescription("Kirim ke Telegram");

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.TOP | Gravity.START;
        decor.addView(btn, lp);

        final SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        btn.setX(prefs.getFloat("x", -1f));
        btn.setY(prefs.getFloat("y", -1f));
        btn.post(new Runnable() {
            @Override
            public void run() {
                // First run (or a position saved on another screen size): park it on the right edge.
                if (btn.getX() < 0 || btn.getY() < 0
                        || btn.getX() > decor.getWidth() - btn.getWidth()
                        || btn.getY() > decor.getHeight() - btn.getHeight()) {
                    btn.setX(decor.getWidth() - btn.getWidth() - dp(ctx, 8));
                    btn.setY(decor.getHeight() * 0.35f);
                }
                btn.bringToFront();
            }
        });

        btn.setOnTouchListener(new DragListener(decor, prefs, dp(ctx, 6)));
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ttg.onButtonClick(activity);
            }
        });
    }

    static void bringToFront(Activity activity) {
        View v = activity.getWindow().getDecorView().findViewWithTag(TAG_VIEW);
        if (v != null) v.bringToFront();
    }

    static void setBusy(Activity activity, boolean busy) {
        View v = activity.getWindow().getDecorView().findViewWithTag(TAG_VIEW);
        if (!(v instanceof TextView)) return;
        TextView tv = (TextView) v;
        tv.setText(busy ? "…" : "TG");
        if (tv.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) tv.getBackground()).setColor(busy ? BUSY_COLOR : IDLE_COLOR);
        }
    }

    private static int dp(Context ctx, int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()));
    }

    private static final class DragListener implements View.OnTouchListener {
        private final ViewGroup parent;
        private final SharedPreferences prefs;
        private final int slop;
        private float downRawX, downRawY, startX, startY;
        private boolean dragging;

        DragListener(ViewGroup parent, SharedPreferences prefs, int slop) {
            this.parent = parent;
            this.prefs = prefs;
            this.slop = slop;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = e.getRawX();
                    downRawY = e.getRawY();
                    startX = v.getX();
                    startY = v.getY();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downRawX;
                    float dy = e.getRawY() - downRawY;
                    if (!dragging && Math.hypot(dx, dy) > slop) dragging = true;
                    if (dragging) {
                        float maxX = parent.getWidth() - v.getWidth();
                        float maxY = parent.getHeight() - v.getHeight();
                        v.setX(Math.max(0, Math.min(maxX, startX + dx)));
                        v.setY(Math.max(0, Math.min(maxY, startY + dy)));
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        prefs.edit().putFloat("x", v.getX()).putFloat("y", v.getY()).apply();
                    } else {
                        v.performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return false;
            }
        }
    }
}
