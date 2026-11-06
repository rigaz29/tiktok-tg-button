package com.rigaz.tgbtn;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The card next to the button that says what is happening to the post. */
final class StatusBubble {
    private static final String TAG_VIEW = "tgbtn.bubble";
    private static final int GAP_DP = 10;
    private static final int MAX_WIDTH_DP = 270;
    private static final int[] DOT = {0xFF3DB7F2, 0xFF3CD070, 0xFFFF6B6B};

    private StatusBubble() {
    }

    static void show(Activity activity, Notice n) {
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        Card card = (Card) decor.findViewWithTag(TAG_VIEW);
        boolean fresh = card == null || card.getVisibility() != View.VISIBLE || card.leaving;
        if (card == null) {
            card = new Card(activity);
            card.setTag(TAG_VIEW);
            decor.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
        }
        card.bind(n);
        card.setVisibility(View.VISIBLE);
        card.bringToFront();
        View btn = FloatButton.find(activity);
        if (btn != null) btn.bringToFront();
        place(activity, card);
        if (fresh) card.enter(onLeft(card, btn));
    }

    static void hide(Activity activity) {
        View v = activity.getWindow().getDecorView().findViewWithTag(TAG_VIEW);
        if (v instanceof Card && v.getVisibility() == View.VISIBLE) ((Card) v).leave();
    }

    /** Keeps the card beside the button while the button moves. */
    static void follow(Activity activity) {
        View v = activity.getWindow().getDecorView().findViewWithTag(TAG_VIEW);
        if (v instanceof Card && v.getVisibility() == View.VISIBLE) place(activity, (Card) v);
    }

    private static boolean onLeft(View card, View btn) {
        if (btn == null) return true;
        return card.getX() < btn.getX();
    }

    private static void place(Activity activity, Card card) {
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        View btn = FloatButton.find(activity);
        if (btn == null || decor.getWidth() == 0) return;

        Rect full = FloatButton.safeArea(decor, 0, 0);
        int gap = FloatButton.dp(activity, GAP_DP);
        boolean buttonOnRight = btn.getX() + btn.getWidth() / 2f > decor.getWidth() / 2f;
        float room = buttonOnRight ? btn.getX() - gap - full.left : full.right - btn.getX() - btn.getWidth() - gap;
        int maxWidth = (int) Math.min(room, FloatButton.dp(activity, MAX_WIDTH_DP));
        card.limitWidth(maxWidth);
        card.measure(View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int w = card.getMeasuredWidth();
        int h = card.getMeasuredHeight();
        ViewGroup.LayoutParams lp = card.getLayoutParams();
        if (lp.width != w) {
            lp.width = w;
            card.setLayoutParams(lp);
        }

        Rect safe = FloatButton.safeArea(decor, w, h);
        float x = buttonOnRight ? btn.getX() - gap - w : btn.getX() + btn.getWidth() + gap;
        float y = btn.getY() + btn.getHeight() / 2f - h / 2f;
        card.setX(FloatButton.clamp(x, safe.left, safe.right));
        card.setY(FloatButton.clamp(y, safe.top, safe.bottom));
    }

    private static final class Card extends LinearLayout {
        private final TextView title;
        private final TextView subtitle;
        private final TextView detail;
        private final ProgressLine progress;
        private final GradientDrawable dot = new GradientDrawable();
        boolean leaving;

        Card(Context ctx) {
            super(ctx);
            setOrientation(VERTICAL);
            int padH = FloatButton.dp(ctx, 14);
            int padV = FloatButton.dp(ctx, 10);
            setPadding(padH, padV, padH, padV);
            setElevation(FloatButton.dp(ctx, 6));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xEB17181C);
            bg.setCornerRadius(FloatButton.dp(ctx, 16));
            bg.setStroke(FloatButton.dp(ctx, 1), 0x1FFFFFFF);
            setBackground(bg);

            dot.setShape(GradientDrawable.OVAL);
            int dotSize = FloatButton.dp(ctx, 8);
            dot.setSize(dotSize, dotSize);

            title = text(ctx, 14, Color.WHITE, 1);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setCompoundDrawablePadding(FloatButton.dp(ctx, 7));
            title.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
            subtitle = text(ctx, 12, 0xD9FFFFFF, 1);
            detail = text(ctx, 12, 0x99FFFFFF, 4);
            progress = new ProgressLine(ctx);

            addView(title);
            addView(subtitle);
            addView(detail);
            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, FloatButton.dp(ctx, 2));
            lp.topMargin = FloatButton.dp(ctx, 8);
            addView(progress, lp);

            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    Jobs.dismiss();
                }
            });
        }

        private static TextView text(Context ctx, int sp, int color, int lines) {
            TextView t = new TextView(ctx);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            t.setTextColor(color);
            t.setMaxLines(lines);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setIncludeFontPadding(false);
            t.setPadding(0, FloatButton.dp(ctx, 2), 0, 0);
            return t;
        }

        void bind(Notice n) {
            dot.setColor(DOT[n.tone]);
            title.setText(n.title);
            setLine(subtitle, n.subtitle);
            setLine(detail, n.detail);
            progress.setVisibility(n.progress ? VISIBLE : GONE);
            progress.setRunning(n.progress);
        }

        private static void setLine(TextView t, String s) {
            t.setText(s);
            t.setVisibility(s == null || s.length() == 0 ? GONE : VISIBLE);
        }

        void limitWidth(int maxWidth) {
            int inner = Math.max(0, maxWidth - getPaddingLeft() - getPaddingRight());
            title.setMaxWidth(inner);
            subtitle.setMaxWidth(inner);
            detail.setMaxWidth(inner);
        }

        void enter(boolean fromRight) {
            leaving = false;
            animate().cancel();
            setAlpha(0f);
            setScaleX(0.94f);
            setScaleY(0.94f);
            setTranslationX(0f);
            setPivotX(fromRight ? getMeasuredWidth() : 0f);
            setPivotY(getMeasuredHeight() / 2f);
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200)
                    .setInterpolator(FloatButton.easeOut()).withEndAction(null).start();
        }

        void leave() {
            if (leaving) return;
            leaving = true;
            animate().cancel();
            animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(160)
                    .setInterpolator(FloatButton.easeOut()).withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            setVisibility(GONE);
                            progress.setRunning(false);
                            leaving = false;
                        }
                    }).start();
        }
    }

    /** A thin line with a highlight sweeping across it. */
    private static final class ProgressLine extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private ValueAnimator anim;
        private float phase;

        ProgressLine(Context ctx) {
            super(ctx);
            track.setColor(0x26FFFFFF);
            bar.setColor(0xFF3DB7F2);
        }

        void setRunning(boolean running) {
            if (running && anim == null) {
                anim = ValueAnimator.ofFloat(0f, 1f);
                anim.setDuration(1100);
                anim.setRepeatCount(ValueAnimator.INFINITE);
                anim.setInterpolator(new AccelerateDecelerateInterpolator());
                anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator a) {
                        phase = (Float) a.getAnimatedValue();
                        invalidate();
                    }
                });
                anim.start();
            } else if (!running && anim != null) {
                anim.cancel();
                anim = null;
            }
        }

        /** Takes the card's width and never asks for any, so the text alone sizes the card. */
        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            boolean exact = MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY;
            setMeasuredDimension(exact ? MeasureSpec.getSize(widthSpec) : 0, MeasureSpec.getSize(heightSpec));
        }

        @Override
        protected void onDetachedFromWindow() {
            setRunning(false);
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            float r = h / 2f;
            rect.set(0, 0, w, h);
            c.drawRoundRect(rect, r, r, track);
            float span = w * 0.35f;
            float x = -span + (w + span) * phase;
            rect.set(Math.max(0, x), 0, Math.min(w, x + span), h);
            c.drawRoundRect(rect, r, r, bar);
        }
    }
}
