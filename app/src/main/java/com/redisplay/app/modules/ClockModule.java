package com.redisplay.app.modules;

import android.graphics.Color;
import android.graphics.Typeface;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.redisplay.app.ContentModule;
import com.redisplay.app.MainActivity;
import com.redisplay.app.utils.GradientHelper;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class ClockModule implements ContentModule {
    private static final String TAG = "ClockModule";
    private Handler updateHandler;
    private Runnable updateRunnable;
    private ViewGroup clockContainer;
    
    private TextView timeView;
    private TextView dateView;
    private TextView weekView;
    private TextView dayOfYearView;
    private TextView daysLeftView;
    private ProgressBar yearProgressBar;
    private TextView progressText;

    private SimpleDateFormat timeFormat;
    private SimpleDateFormat dateFormat;
    private String timezone;

    @Override
    public String getType() {
        return "clock";
    }

    @Override
    public void display(MainActivity activity, JSONObject contentItem, View container) {
        try {
            JSONObject view = contentItem.getJSONObject("view");
            JSONObject data = view.getJSONObject("data");
            JSONObject background = data.optJSONObject("background");
            timezone = data.optString("timezone", TimeZone.getDefault().getID());

            Log.d(TAG, "Displaying detailed clock for timezone: " + timezone);

            // Hide other views
            activity.getContentWebView().setVisibility(View.GONE);
            activity.getContentImageView().setVisibility(View.GONE);
            activity.getContentTextView().setVisibility(View.GONE);

            // Clear container background
            container.setBackground(null);

            // Create container
            createClockContainer(activity, container, background);

            // Start updates
            startClockUpdates();

        } catch (Exception e) {
            Log.e(TAG, "Clock display error: " + e.getMessage(), e);
            activity.showError("Clock error: " + e.getMessage());
        }
    }

    private void createClockContainer(MainActivity activity, View container, JSONObject background) {
        if (clockContainer != null) {
            ViewGroup parent = (ViewGroup) clockContainer.getParent();
            if (parent != null) {
                parent.removeView(clockContainer);
            }
            clockContainer = null;
        }

        clockContainer = new FrameLayout(activity);
        clockContainer.setId(android.view.View.generateViewId());
        
        if (background != null) {
            GradientHelper.applyBackground(clockContainer, background);
        } else {
            applyDynamicGradient(clockContainer);
        }

        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        );
        clockContainer.setLayoutParams(containerParams);
        clockContainer.setVisibility(View.VISIBLE);

        // Content Layout
        LinearLayout contentLayout = new LinearLayout(activity);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        );
        contentLayout.setLayoutParams(contentParams);
        contentLayout.setPadding(48, 48, 48, 48);

        // Time (Big, with seconds)
        timeView = new TextView(activity);
        timeView.setText("--:--:--");
        timeView.setTextSize(120);
        timeView.setTextColor(0xFFFFFFFF);
        timeView.setTypeface(null, Typeface.BOLD);
        timeView.setGravity(Gravity.CENTER);
        contentLayout.addView(timeView);

        // Date (Full)
        dateView = new TextView(activity);
        dateView.setText("---");
        dateView.setTextSize(48);
        dateView.setTextColor(0xFFDDDDDD);
        dateView.setGravity(Gravity.CENTER);
        dateView.setPadding(0, 0, 0, 48);
        contentLayout.addView(dateView);

        // Info Grid (Week, Day of Year)
        LinearLayout infoLayout = new LinearLayout(activity);
        infoLayout.setOrientation(LinearLayout.HORIZONTAL);
        infoLayout.setGravity(Gravity.CENTER);
        infoLayout.setPadding(0, 24, 0, 48);
        
        // Week Number
        View weekBox = createInfoBoxView(activity, "Week");
        infoLayout.addView(weekBox);
        
        // Spacer
        View spacer1 = new View(activity);
        spacer1.setLayoutParams(new LinearLayout.LayoutParams(64, 1));
        infoLayout.addView(spacer1);

        // Day of Year
        View dayOfYearBox = createInfoBoxView(activity, "Day of Year");
        infoLayout.addView(dayOfYearBox);

        // Spacer
        View spacer2 = new View(activity);
        spacer2.setLayoutParams(new LinearLayout.LayoutParams(64, 1));
        infoLayout.addView(spacer2);

        // Days Left
        View daysLeftBox = createInfoBoxView(activity, "Days Left");
        infoLayout.addView(daysLeftBox);

        contentLayout.addView(infoLayout);

        // Year Progress Bar
        // Removed label "Year Progress"
        // TextView progressLabel = new TextView(activity);
        
        // Progress Bar Container (to hold bar and text)
        FrameLayout progressContainer = new FrameLayout(activity);
        LinearLayout.LayoutParams progressContainerParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            64
        );
        progressContainerParams.setMargins(48, 8, 48, 0); // Reduced top margin from 16 to 8
        progressContainer.setLayoutParams(progressContainerParams);

        // Actual Progress Bar
        yearProgressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        yearProgressBar.setMax(100);
        yearProgressBar.setProgress(0);
        
        // Use custom drawable for flat color
        android.graphics.drawable.GradientDrawable progressBarBackground = new android.graphics.drawable.GradientDrawable();
        progressBarBackground.setColor(0xFF444444); // Dark gray background
        progressBarBackground.setCornerRadius(16f); // Reduced rounded corners from 32f to 16f
        
        android.graphics.drawable.GradientDrawable progress = new android.graphics.drawable.GradientDrawable();
        progress.setColor(0xFF00AA00); // Flat Green
        progress.setCornerRadius(16f); // Reduced rounded corners
        
        android.graphics.drawable.ClipDrawable clipProgress = new android.graphics.drawable.ClipDrawable(
            progress, Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL
        );
        
        android.graphics.drawable.LayerDrawable progressDrawable = new android.graphics.drawable.LayerDrawable(
            new android.graphics.drawable.Drawable[]{ progressBarBackground, clipProgress }
        );
        progressDrawable.setId(0, android.R.id.background);
        progressDrawable.setId(1, android.R.id.progress);
        
        yearProgressBar.setProgressDrawable(progressDrawable);
        
        FrameLayout.LayoutParams pbParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT // Fill container
        );
        yearProgressBar.setLayoutParams(pbParams);
        progressContainer.addView(yearProgressBar);

        // Percentage Text Overlay
        progressText = new TextView(activity);
        progressText.setText("2025: 0%");
        progressText.setTextColor(0xFFFFFFFF);
        progressText.setTextSize(20);
        progressText.setTypeface(null, Typeface.BOLD);
        progressText.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams ptParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        ptParams.gravity = Gravity.CENTER;
        progressText.setLayoutParams(ptParams);
        progressContainer.addView(progressText);

        contentLayout.addView(progressContainer);

        clockContainer.addView(contentLayout);

        if (container instanceof ViewGroup) {
            ((ViewGroup) container).addView(clockContainer);
            clockContainer.bringToFront();
        }

        // Initialize Formatters
        timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        timeFormat.setTimeZone(TimeZone.getTimeZone(timezone));
        dateFormat = new SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault());
        dateFormat.setTimeZone(TimeZone.getTimeZone(timezone));
    }

    // Method removed

    
    // Overriding the helper method structure slightly
    private View createInfoBoxView(MainActivity activity, String label) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        
        TextView valueText = new TextView(activity);
        valueText.setText("--");
        valueText.setTextSize(36);
        valueText.setTextColor(0xFFFFFFFF);
        valueText.setTypeface(null, Typeface.BOLD);
        valueText.setGravity(Gravity.CENTER);
        box.addView(valueText);
        
        TextView labelText = new TextView(activity);
        labelText.setText(label);
        labelText.setTextSize(18);
        labelText.setTextColor(0xFFAAAAAA);
        labelText.setGravity(Gravity.CENTER);
        box.addView(labelText);

        if (label.equals("Week")) weekView = valueText;
        else if (label.equals("Day of Year")) dayOfYearView = valueText;
        else if (label.equals("Days Left")) daysLeftView = valueText;

        return box;
    }

    private void applyDynamicGradient(final View container) {
        // Define color states for the gradient animation
        final int[][] colorStates = new int[][] {
            {0xFF0F2027, 0xFF2C5364}, // Deep Space
            {0xFF141E30, 0xFF243B55}, // Royal
            {0xFF232526, 0xFF414345}, // Midnight City
            {0xFF0F2027, 0xFF2C5364}  // Back to Start (Deep Space)
        };

        final GradientDrawable gradientDrawable = new GradientDrawable();
        gradientDrawable.setOrientation(GradientDrawable.Orientation.BR_TL);
        container.setBackground(gradientDrawable);

        // We animate through the color indices
        ValueAnimator animator = ValueAnimator.ofFloat(0, colorStates.length - 1);
        animator.setDuration(30000); // 30 seconds for full cycle
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART); // Restart from 0 when done (circular)
        
        final ArgbEvaluator evaluator = new ArgbEvaluator();

        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float val = (float) animation.getAnimatedValue();
                int startIndex = (int) val;
                int endIndex = (startIndex + 1) % colorStates.length;
                float fraction = val - startIndex;

                int[] startColors = colorStates[startIndex];
                int[] endColors = colorStates[endIndex];

                // Interpolate each color in the gradient
                int[] currentColors = new int[startColors.length];
                for (int i = 0; i < startColors.length; i++) {
                    currentColors[i] = (int) evaluator.evaluate(fraction, startColors[i], endColors[i]);
                }

                gradientDrawable.setColors(currentColors);
            }
        });
        
        animator.start();
        
        // Save animator to tag to cancel it later if needed (optional but good practice)
        container.setTag(animator);
    }

    private void startClockUpdates() {
        updateHandler = new Handler();
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                updateClock();
                if (updateHandler != null) {
                    updateHandler.postDelayed(this, 1000);
                }
            }
        };
        updateHandler.post(updateRunnable);
    }

    private void updateClock() {
        if (timeView == null) return;

        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone(timezone));
        Date now = calendar.getTime();

        timeView.setText(timeFormat.format(now));
        dateView.setText(dateFormat.format(now));

        int week = calendar.get(Calendar.WEEK_OF_YEAR);
        int dayOfYear = calendar.get(Calendar.DAY_OF_YEAR);
        int daysInYear = calendar.getActualMaximum(Calendar.DAY_OF_YEAR);
        int daysLeft = daysInYear - dayOfYear;
        
        if (weekView != null) weekView.setText(week + getOrdinalSuffix(week));
        if (dayOfYearView != null) dayOfYearView.setText(dayOfYear + getOrdinalSuffix(dayOfYear));
        if (daysLeftView != null) daysLeftView.setText(String.valueOf(daysLeft));

        if (yearProgressBar != null) {
            float percentage = ((float) dayOfYear / daysInYear) * 100;
            yearProgressBar.setProgress((int) percentage);
            if (progressText != null) {
                int currentYear = calendar.get(Calendar.YEAR);
                progressText.setText(String.format(Locale.US, "%d: %.1f%%", currentYear, percentage));
            }
        }
    }

    @Override
    public void hide(MainActivity activity, View container) {
        if (updateHandler != null && updateRunnable != null) {
            updateHandler.removeCallbacks(updateRunnable);
        }
        updateHandler = null;
        updateRunnable = null;

        if (clockContainer != null) {
            // Cancel any running animation
            Object tag = clockContainer.getTag();
            if (tag instanceof ValueAnimator) {
                ((ValueAnimator) tag).cancel();
            }
            
            ViewGroup parent = (ViewGroup) clockContainer.getParent();
            if (parent != null) {
                parent.removeView(clockContainer);
            }
            clockContainer = null;
        }
        
        timeView = null;
        dateView = null;
        weekView = null;
        dayOfYearView = null;
        daysLeftView = null;
        yearProgressBar = null;
        progressText = null;
    }
    
    // Fix call in display() - Removing dummy method as it is not used anymore

    private String getOrdinalSuffix(int value) {
        int hundredRemainder = value % 100;
        if (hundredRemainder >= 11 && hundredRemainder <= 13) {
            return "th";
        }
        int tenRemainder = value % 10;
        switch (tenRemainder) {
            case 1:
                return "st";
            case 2:
                return "nd";
            case 3:
                return "rd";
            default:
                return "th";
        }
    }
}

