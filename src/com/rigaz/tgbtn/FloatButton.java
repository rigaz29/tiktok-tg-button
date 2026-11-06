package com.rigaz.tgbtn;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

/**
 * Draggable round button on top of the activity's decor view. It stays clear of the status and
 * navigation bars, and comes back to its default spot when the app is opened again.
 */
final class FloatButton {
    static final int IDLE = 0;
    static final int BUSY = 1;
    static final int OK = 2;
    static final int FAIL = 3;

    private static final String TAG_VIEW = "tgbtn.float";
    private static final int SIZE_DP = 52;
    private static final int EDGE_DP = 10;
    private static final float DEFAULT_Y = 0.35f;

    /** Where the user dragged the button in this session; NaN means the default spot. */
    private static float savedX = Float.NaN;
    private static float savedY = Float.NaN;

    private FloatButton() {
    }

    static void attach(final Activity activity) {
        final ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        if (decor.findViewWithTag(TAG_VIEW) != null) return;

        final int size = dp(activity, SIZE_DP);
        final ButtonView btn = new ButtonView(activity);
        btn.setTag(TAG_VIEW);
        btn.setContentDescription("Kirim ke Telegram");
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.TOP | Gravity.START;
        decor.addView(btn, lp);

        btn.setOnTouchListener(new DragListener(activity, decor));
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                Ttg.onButtonClick(activity);
            }
        });
        // Rotation and split screen change the safe area.
        decor.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (r - l != or - ol || b - t != ob - ot) moveToSpot(btn, decor);
            }
        });
    }

    /** Puts the button where it belongs on this screen and above everything else. */
    static void place(Activity activity) {
        final ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        final View btn = decor.findViewWithTag(TAG_VIEW);
        if (btn == null) return;
        btn.post(new Runnable() {
            @Override
            public void run() {
                moveToSpot(btn, decor);
                btn.bringToFront();
            }
        });
    }

    static void resetPosition() {
        savedX = Float.NaN;
        savedY = Float.NaN;
    }

    static void setState(Activity activity, int state) {
        View v = find(activity);
        if (v instanceof ButtonView) ((ButtonView) v).setState(state);
    }

    static View find(Activity activity) {
        return activity.getWindow().getDecorView().findViewWithTag(TAG_VIEW);
    }

    private static void moveToSpot(View btn, ViewGroup decor) {
        if (decor.getWidth() == 0 || btn.getWidth() == 0) return;
        Rect safe = safeArea(decor, btn.getWidth(), btn.getHeight());
        float x = Float.isNaN(savedX) ? safe.right : savedX;
        float y = Float.isNaN(savedY) ? decor.getHeight() * DEFAULT_Y : savedY;
        btn.setX(clamp(x, safe.left, safe.right));
        btn.setY(clamp(y, safe.top, safe.bottom));
    }

    /**
     * The range for the top-left corner of a view of the given size. Hidden bars count too:
     * touches at the top of the screen go to the system even when the status bar is not drawn.
     */
    static Rect safeArea(View decor, int width, int height) {
        Rect bars = systemBars(decor);
        int edge = dp(decor.getContext(), EDGE_DP);
        return new Rect(bars.left + edge, bars.top + edge,
                Math.max(bars.left + edge, decor.getWidth() - bars.right - width - edge),
                Math.max(bars.top + edge, decor.getHeight() - bars.bottom - height - edge));
    }

    private static Rect systemBars(View decor) {
        WindowInsets insets = decor.getRootWindowInsets();
        if (insets == null) return barsFromResources(decor.getResources());
        if (Build.VERSION.SDK_INT >= 30) {
            Insets i = insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            return new Rect(i.left, i.top, i.right, i.bottom);
        }
        return new Rect(insets.getStableInsetLeft(), insets.getStableInsetTop(),
                insets.getStableInsetRight(), insets.getStableInsetBottom());
    }

    /** Before the window is attached there are no insets yet. */
    private static Rect barsFromResources(Resources res) {
        return new Rect(0, dimen(res, "status_bar_height"), 0, dimen(res, "navigation_bar_height"));
    }

    private static int dimen(Resources res, String name) {
        int id = res.getIdentifier(name, "dimen", "android");
        return id == 0 ? 0 : res.getDimensionPixelSize(id);
    }

    static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    static int dp(Context ctx, int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()));
    }

    static Interpolator easeOut() {
        return new DecelerateInterpolator(2f);
    }

    private static final class DragListener implements View.OnTouchListener {
        private final Activity activity;
        private final ViewGroup parent;
        private final int slop;
        private float downRawX, downRawY, startX, startY;
        private boolean dragging;

        DragListener(Activity activity, ViewGroup parent) {
            this.activity = activity;
            this.parent = parent;
            this.slop = dp(activity, 6);
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
                    v.animate().cancel();
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downRawX;
                    float dy = e.getRawY() - downRawY;
                    if (!dragging && Math.hypot(dx, dy) > slop) {
                        dragging = true;
                        v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).start();
                    }
                    if (dragging) {
                        Rect safe = safeArea(parent, v.getWidth(), v.getHeight());
                        v.setX(clamp(startX + dx, safe.left, safe.right));
                        v.setY(clamp(startY + dy, safe.top, safe.bottom));
                        StatusBubble.follow(activity);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        settle(v);
                    } else {
                        release(v);
                        v.performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) settle(v);
                    else release(v);
                    return true;
                default:
                    return false;
            }
        }

        private void release(View v) {
            v.animate().scaleX(1f).scaleY(1f).setDuration(220)
                    .setInterpolator(new OvershootInterpolator(2.5f)).start();
        }

        /** Glides to the nearest side and remembers the spot for the rest of this session. */
        private void settle(final View v) {
            Rect safe = safeArea(parent, v.getWidth(), v.getHeight());
            boolean toLeft = v.getX() + v.getWidth() / 2f < parent.getWidth() / 2f;
            float targetX = toLeft ? safe.left : safe.right;
            savedX = targetX;
            savedY = v.getY();
            v.animate().x(targetX).scaleX(1f).scaleY(1f).setDuration(260)
                    .setInterpolator(new OvershootInterpolator(0.9f))
                    .setUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                        @Override
                        public void onAnimationUpdate(ValueAnimator a) {
                            StatusBubble.follow(activity);
                        }
                    }).withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            v.animate().setUpdateListener(null);
                        }
                    }).start();
        }
    }

    /** Everything is drawn here: the module ships as bare dex, so it has no drawables to load. */
    private static final class ButtonView extends View {
        private static final int[][] COLORS = {
                {0xFF3DB7F2, 0xFF1C8BD0}, // idle
                {0xFF3DB7F2, 0xFF1C8BD0}, // busy
                {0xFF3CD070, 0xFF1FA85A}, // ok
                {0xFFFF6B6B, 0xFFD93A4A}, // fail
        };

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path plane = new Path();
        private final Path check = new Path();
        private final Path cross = new Path();
        private final RectF arc = new RectF();
        private final ArgbEvaluator evaluator = new ArgbEvaluator();

        private int state = IDLE;
        private int colorTop = COLORS[IDLE][0];
        private int colorBottom = COLORS[IDLE][1];
        private float glyphScale = 1f;
        private float spin;
        private ValueAnimator colorAnim;
        private ValueAnimator glyphAnim;
        private ValueAnimator spinAnim;

        ButtonView(Context ctx) {
            super(ctx);
            setClickable(true);
            setElevation(dp(ctx, 8));
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setOval(0, 0, v.getWidth(), v.getHeight());
                }
            });
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(ctx, 1));
            ring.setColor(0x4DFFFFFF);
            glyph.setColor(Color.WHITE);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setStrokeWidth(dp(ctx, 3));
            stroke.setColor(Color.WHITE);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(dp(ctx, 2));
            spinner.setColor(Color.WHITE);
        }

        void setState(int next) {
            if (next == state) return;
            final int top = colorTop;
            final int bottom = colorBottom;
            final int[] target = COLORS[next];
            boolean newGlyph = glyphOf(next) != glyphOf(state);
            state = next;

            if (colorAnim != null) colorAnim.cancel();
            colorAnim = ValueAnimator.ofFloat(0f, 1f);
            colorAnim.setDuration(220);
            colorAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    float f = a.getAnimatedFraction();
                    colorTop = (Integer) evaluator.evaluate(f, top, target[0]);
                    colorBottom = (Integer) evaluator.evaluate(f, bottom, target[1]);
                    applyGradient();
                    invalidate();
                }
            });
            colorAnim.start();

            if (newGlyph) {
                if (glyphAnim != null) glyphAnim.cancel();
                glyphAnim = ValueAnimator.ofFloat(0.4f, 1f);
                glyphAnim.setDuration(260);
                glyphAnim.setInterpolator(new OvershootInterpolator(2f));
                glyphAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator a) {
                        glyphScale = (Float) a.getAnimatedValue();
                        invalidate();
                    }
                });
                glyphAnim.start();
            }
            updateSpinner();
        }

        private static int glyphOf(int state) {
            return state == OK || state == FAIL ? state : IDLE;
        }

        private void updateSpinner() {
            boolean want = state == BUSY && isAttachedToWindow();
            if (want && spinAnim == null) {
                spinAnim = ValueAnimator.ofFloat(0f, 360f);
                spinAnim.setDuration(900);
                spinAnim.setRepeatCount(ValueAnimator.INFINITE);
                spinAnim.setInterpolator(new LinearInterpolator());
                spinAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator a) {
                        spin = (Float) a.getAnimatedValue();
                        invalidate();
                    }
                });
                spinAnim.start();
            } else if (!want && spinAnim != null) {
                spinAnim.cancel();
                spinAnim = null;
                invalidate();
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            updateSpinner();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (spinAnim != null) {
                spinAnim.cancel();
                spinAnim = null;
            }
            super.onDetachedFromWindow();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float inset = dp(getContext(), 3);
            arc.set(inset, inset, w - inset, h - inset);
            applyGradient();

            // Laid out on a 24-unit grid centred on the button.
            float unit = w * 0.56f / 24f;
            Matrix m = new Matrix();
            m.setScale(unit, unit);
            m.postRotate(-24f, 12f * unit, 12f * unit);
            m.postTranslate(w / 2f - 12f * unit + unit, h / 2f - 12f * unit);
            plane.reset();
            plane.moveTo(2.01f, 21f);
            plane.lineTo(23f, 12f);
            plane.lineTo(2.01f, 3f);
            plane.lineTo(2f, 10f);
            plane.lineTo(17f, 12f);
            plane.lineTo(2f, 14f);
            plane.close();
            plane.transform(m);

            float cx = w / 2f;
            float cy = h / 2f;
            float s = w * 0.17f;
            check.reset();
            check.moveTo(cx - s, cy + s * 0.05f);
            check.lineTo(cx - s * 0.25f, cy + s * 0.75f);
            check.lineTo(cx + s, cy - s * 0.65f);
            cross.reset();
            cross.moveTo(cx - s * 0.8f, cy - s * 0.8f);
            cross.lineTo(cx + s * 0.8f, cy + s * 0.8f);
            cross.moveTo(cx + s * 0.8f, cy - s * 0.8f);
            cross.lineTo(cx - s * 0.8f, cy + s * 0.8f);
        }

        private void applyGradient() {
            if (getHeight() == 0) return;
            fill.setShader(new LinearGradient(0, 0, getWidth(), getHeight(), colorTop, colorBottom, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(cx, cy);
            c.drawCircle(cx, cy, r, fill);
            c.drawCircle(cx, cy, r - ring.getStrokeWidth() / 2f, ring);

            c.save();
            c.scale(glyphScale, glyphScale, cx, cy);
            if (state == OK) c.drawPath(check, stroke);
            else if (state == FAIL) c.drawPath(cross, stroke);
            else c.drawPath(plane, glyph);
            c.restore();

            if (state == BUSY) c.drawArc(arc, spin - 90f, 100f, false, spinner);
        }
    }
}
