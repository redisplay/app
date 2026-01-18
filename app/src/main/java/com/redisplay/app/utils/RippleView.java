package com.redisplay.app.utils;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.ScaleAnimation;

import android.graphics.Path;

public class RippleView extends View {
    public static final int TYPE_NONE = 0;
    public static final int TYPE_PREV = 1;
    public static final int TYPE_NEXT = 2;
    public static final int TYPE_PAUSE = 3;
    public static final int TYPE_PLAY = 4;
    public static final int TYPE_NUMBER = 5;
    public static final int TYPE_TEXT = 6;

    private float mX, mY;
    private float mRadius;
    private Paint mPaint;
    private Paint mIconPaint;
    private Paint mTextPaint;
    private Paint mSmallTextPaint; // For view names
    private AnimationSet mAnimationSet;
    private int mIconType = TYPE_NONE;
    private Path mIconPath;
    private String mText;

    public RippleView(Context context) {
        super(context);
        init();
    }

    public RippleView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        mPaint = new Paint();
        mPaint.setAntiAlias(true);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(0x80FFFFFF); // Semi-transparent white

        mIconPaint = new Paint();
        mIconPaint.setAntiAlias(true);
        mIconPaint.setStyle(Paint.Style.STROKE);
        mIconPaint.setStrokeWidth(15); // Thicker stroke for icon
        mIconPaint.setStrokeCap(Paint.Cap.ROUND);
        mIconPaint.setStrokeJoin(Paint.Join.ROUND);
        mIconPaint.setColor(0xFFFFFFFF); // Solid white for icon
        
        mTextPaint = new Paint();
        mTextPaint.setAntiAlias(true);
        mTextPaint.setColor(0xFFFFFFFF);
        mTextPaint.setTextSize(100);
        mTextPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        
        mSmallTextPaint = new Paint();
        mSmallTextPaint.setAntiAlias(true);
        mSmallTextPaint.setColor(0xFFFFFFFF);
        mSmallTextPaint.setTextSize(60); // Smaller text for view names
        mSmallTextPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        mSmallTextPaint.setTextAlign(Paint.Align.CENTER);
        
        setVisibility(View.GONE);
    }

    public void setColor(int color) {
        mPaint.setColor(color);
    }

    public void triggerRipple(float x, float y) {
        triggerRipple(x, y, TYPE_NONE, null);
    }

    public void triggerRipple(float x, float y, int type) {
        triggerRipple(x, y, type, null);
    }

    public void triggerRipple(float x, float y, int type, String text) {
        mX = x;
        mY = y;
        mRadius = 100; // Initial radius
        mIconType = type;
        mText = text;
        
        // Prepare icon path if needed
        if (mIconType != TYPE_NONE && mIconType != TYPE_NUMBER && mIconType != TYPE_TEXT) {
            mIconPath = new Path();
            float size = 60; // Base size for icon
            
            if (mIconType == TYPE_PREV) {
                // < Arrow
                mIconPath.moveTo(x + size/2, y - size);
                mIconPath.lineTo(x - size/2, y);
                mIconPath.lineTo(x + size/2, y + size);
            } else if (mIconType == TYPE_NEXT) {
                // > Arrow
                mIconPath.moveTo(x - size/2, y - size);
                mIconPath.lineTo(x + size/2, y);
                mIconPath.lineTo(x - size/2, y + size);
            } else if (mIconType == TYPE_PAUSE) {
                // || Pause symbol
                float barWidth = size * 0.4f;
                float gap = size * 0.4f;
                // Left bar
                mIconPath.moveTo(x - gap/2 - barWidth/2, y - size);
                mIconPath.lineTo(x - gap/2 - barWidth/2, y + size);
                // Right bar
                mIconPath.moveTo(x + gap/2 + barWidth/2, y - size);
                mIconPath.lineTo(x + gap/2 + barWidth/2, y + size);
                
                // For filled look we would use Style.FILL but here we use thick stroke
            } else if (mIconType == TYPE_PLAY) {
                // |> Play triangle
                // Using stroke to draw outline, or close path
                mIconPath.moveTo(x - size/2, y - size);
                mIconPath.lineTo(x + size, y);
                mIconPath.lineTo(x - size/2, y + size);
                mIconPath.close();
            }
        }

        // Reset any existing animation
        if (mAnimationSet != null) {
            mAnimationSet.cancel();
        }
        
        setVisibility(View.VISIBLE);

        // Create scale animation (expand)
        ScaleAnimation scaleAnimation = new ScaleAnimation(
            0.5f, 3.0f, // Start scale, end scale x
            0.5f, 3.0f, // Start scale, end scale y
            Animation.ABSOLUTE, x,
            Animation.ABSOLUTE, y
        );
        scaleAnimation.setDuration(400);

        // Create alpha animation (fade out)
        AlphaAnimation alphaAnimation = new AlphaAnimation(1.0f, 0.0f);
        alphaAnimation.setDuration(400);

        mAnimationSet = new AnimationSet(true);
        mAnimationSet.addAnimation(scaleAnimation);
        mAnimationSet.addAnimation(alphaAnimation);
        mAnimationSet.setFillAfter(false);
        
        mAnimationSet.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {}

            @Override
            public void onAnimationEnd(Animation animation) {
                setVisibility(View.GONE);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {}
        });

        startAnimation(mAnimationSet);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getVisibility() == View.VISIBLE) {
            canvas.drawCircle(mX, mY, mRadius, mPaint);
            
            if (mIconType == TYPE_NUMBER && mText != null) {
                // Adjust text position for vertical center
                Paint.FontMetrics metrics = mTextPaint.getFontMetrics();
                float dy = -(metrics.descent + metrics.ascent) / 2;
                canvas.drawText(mText, mX, mY + dy, mTextPaint);
            } else if (mIconType == TYPE_TEXT && mText != null) {
                // Draw view name
                Paint.FontMetrics metrics = mSmallTextPaint.getFontMetrics();
                float dy = -(metrics.descent + metrics.ascent) / 2;
                
                // If text is too long, we could break it or scale it down
                // For now, let's just draw it
                canvas.drawText(mText, mX, mY + dy, mSmallTextPaint);
            } else if (mIconType != TYPE_NONE && mIconPath != null) {
                canvas.drawPath(mIconPath, mIconPaint);
            }
        }
    }
}

