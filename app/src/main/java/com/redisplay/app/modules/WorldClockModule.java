package com.redisplay.app.modules;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.redisplay.app.ContentModule;
import com.redisplay.app.MainActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import com.redisplay.app.utils.GradientHelper;

public class WorldClockModule implements ContentModule {
    private static final String TAG = "WorldClockModule";
    private Handler updateHandler;
    private Runnable updateRunnable;
    private ViewGroup clockContainer;
    private List<ClockItem> clockItems = new ArrayList<ClockItem>();
    
    private static class ClockItem {
        TextView timeView;
        TextView dateView;
        TextView offsetView;
        String timezone;
        SimpleDateFormat timeFormat;
        SimpleDateFormat dateFormat;
    }

    @Override
    public String getType() {
        return "world_clock";
    }

    @Override
    public void display(MainActivity activity, JSONObject contentItem, View container) {
        try {
            // Extract view data
            JSONObject view = contentItem.getJSONObject("view");
            JSONObject data = view.getJSONObject("data");
            JSONArray clocks = data.getJSONArray("clocks");
            JSONObject background = data.optJSONObject("background");
            String baseTimezone = data.optString("baseTimezone", TimeZone.getDefault().getID());

            Log.d(TAG, "Displaying world clock with " + clocks.length() + " clocks, base: " + baseTimezone);

            // Hide other views
            activity.getContentWebView().setVisibility(View.GONE);
            activity.getContentImageView().setVisibility(View.GONE);
            activity.getContentTextView().setVisibility(View.GONE);

            // Clear container background first
            container.setBackground(null);
            
            // Create clock container
            createClockContainer(activity, container, background);
            
            // Create clock views
            createClockViews(activity, clocks, data.optString("format", "24h"), baseTimezone);
            
            // Start updating time
            startClockUpdates(baseTimezone);

        } catch (Exception e) {
            Log.e(TAG, "World clock display error: " + e.getMessage(), e);
            activity.showError("World clock error: " + e.getMessage());
        }
    }
    
    private void createClockContainer(MainActivity activity, View container, JSONObject background) {
        // Remove existing container if any
        if (clockContainer != null) {
            ViewGroup parent = (ViewGroup) clockContainer.getParent();
            if (parent != null) {
                parent.removeView(clockContainer);
            }
            clockContainer = null;
        }

        // Get the root container (activity's content view)
        // We can't use 'container' (contentContainer) directly for background because it might be inside other layouts
        // or have its alpha manipulated during transitions.
        // Instead, we create a new FrameLayout and add it to the container.
        
        clockContainer = new FrameLayout(activity);
        clockContainer.setId(android.view.View.generateViewId());
        
        // Ensure container is cleared of previous backgrounds to be safe
        container.setBackground(null);

        // Apply background to the clockContainer using helper
        // This ensures the background is part of the view being faded in/out
        if (background != null) {
            Log.d(TAG, "Applying background config: " + background.toString());
            GradientHelper.applyBackground(clockContainer, background);
        } else {
            Log.d(TAG, "No background config found, using default");
            clockContainer.setBackgroundColor(0xFF333333); // Default dark background
        }
        
        // Set layout params to fill the screen
        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        );
        clockContainer.setLayoutParams(containerParams);
        clockContainer.setVisibility(View.VISIBLE);
        
        // Add to parent
        if (container instanceof ViewGroup) {
            ((ViewGroup) container).addView(clockContainer);
            // Ensure z-index is correct if needed, though FrameLayout stacks by default
            clockContainer.bringToFront();
        }
    }
    
    private void createClockViews(MainActivity activity, JSONArray clocks, String format, String baseTimezone) {
        clockItems.clear();
        
        // Main layout for clocks
        LinearLayout mainLayout = new LinearLayout(activity);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams mainParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        );
        mainLayout.setLayoutParams(mainParams);
        // Add padding to avoid edge clipping
        mainLayout.setPadding(32, 32, 32, 32);
        
        try {
            for (int i = 0; i < clocks.length(); i++) {
                JSONObject clockConfig = clocks.getJSONObject(i);
                String label = clockConfig.getString("label");
                String timezone = clockConfig.getString("timezone");
                
                // Allow individual clock items to have their own background if needed
                // But for now, we'll use the main background
                
                View clockView = createSingleClockView(activity, label, timezone, format);
                mainLayout.addView(clockView);
                
                // Add spacer if not last
                if (i < clocks.length() - 1) {
                     View spacer = new View(activity);
                     LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(
                         ViewGroup.LayoutParams.MATCH_PARENT,
                         0
                     );
                     spacerParams.weight = 0.2f; // Spacer weight
                     spacer.setLayoutParams(spacerParams);
                     mainLayout.addView(spacer);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error creating clock views: " + e.getMessage());
        }
        
        clockContainer.addView(mainLayout);
    }
    
    private View createSingleClockView(MainActivity activity, String label, String timezone, String format) {
        // Horizontal layout for each clock item: Label (Left) | Time (Right)
        LinearLayout clockLayout = new LinearLayout(activity);
        clockLayout.setOrientation(LinearLayout.HORIZONTAL);
        clockLayout.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.weight = 1.0f;
        clockLayout.setLayoutParams(params);
        clockLayout.setPadding(20, 10, 20, 10);
        
        // Left Side: City Name + Date below it
        LinearLayout leftSide = new LinearLayout(activity);
        leftSide.setOrientation(LinearLayout.VERTICAL);
        leftSide.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        leftParams.weight = 0.5f; // Takes 50% of width
        leftSide.setLayoutParams(leftParams);
        
        // Label (City Name)
        TextView labelView = new TextView(activity);
        labelView.setText(label);
        labelView.setTextSize(56); // Larger city name
        labelView.setTextColor(0xFFFFFFFF); // White
        labelView.setTypeface(null, Typeface.BOLD);
        labelView.setGravity(Gravity.LEFT);
        leftSide.addView(labelView);
        
        // Date (Below City Name)
        TextView dateView = new TextView(activity);
        dateView.setText("--");
        dateView.setTextSize(28);
        dateView.setTextColor(0xFFDDDDDD); // Light gray
        dateView.setGravity(Gravity.LEFT);
        leftSide.addView(dateView);
        
        clockLayout.addView(leftSide);
        
        // Right Side: Time + Offset
        LinearLayout rightSide = new LinearLayout(activity);
        rightSide.setOrientation(LinearLayout.VERTICAL);
        rightSide.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rightParams.weight = 0.5f; // Takes 50% of width
        rightSide.setLayoutParams(rightParams);

        // Time
        TextView timeView = new TextView(activity);
        timeView.setText("--:--");
        timeView.setTextSize(80); // Very large time
        timeView.setTextColor(0xFFFFFFFF); // White
        timeView.setTypeface(null, Typeface.BOLD);
        timeView.setGravity(Gravity.RIGHT);
        // Reduce bottom padding/margin if any implied, but TextView has none by default.
        // We can add negative margin if needed, or just ensure no padding.
        timeView.setIncludeFontPadding(false); // Helps tighten vertical spacing
        rightSide.addView(timeView);

        // Offset
        TextView offsetView = new TextView(activity);
        offsetView.setText("");
        offsetView.setTextSize(32); // Increased from 24
        offsetView.setTextColor(0xFFE0E0E0); // Lighter shade (very light gray)
        offsetView.setGravity(Gravity.RIGHT);
        offsetView.setIncludeFontPadding(false);
        // Add negative top margin to bring closer to time
        LinearLayout.LayoutParams offsetParams = new LinearLayout.LayoutParams(
             ViewGroup.LayoutParams.WRAP_CONTENT,
             ViewGroup.LayoutParams.WRAP_CONTENT
        );
        offsetParams.setMargins(0, -10, 0, 0); // Negative top margin
        offsetParams.gravity = Gravity.RIGHT;
        offsetView.setLayoutParams(offsetParams);
        
        rightSide.addView(offsetView);
        
        clockLayout.addView(rightSide);
        
        // Store for updates
        ClockItem item = new ClockItem();
        item.timeView = timeView;
        item.dateView = dateView;
        item.offsetView = offsetView;
        item.timezone = timezone;
        
        // Create formatters
        try {
            String pattern = "12h".equalsIgnoreCase(format) ? "hh:mm a" : "HH:mm";
            item.timeFormat = new SimpleDateFormat(pattern, Locale.getDefault());
            item.timeFormat.setTimeZone(TimeZone.getTimeZone(timezone));
            
            item.dateFormat = new SimpleDateFormat("EEE, MMM d", Locale.getDefault());
            item.dateFormat.setTimeZone(TimeZone.getTimeZone(timezone));
        } catch (Exception e) {
            Log.e(TAG, "Error creating date format: " + e.getMessage());
        }
        
        clockItems.add(item);
        
        return clockLayout;
    }
    
    private void startClockUpdates(final String baseTimezone) {
        updateHandler = new Handler();
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                updateClocks(baseTimezone);
                // Schedule next update on the next full minute? Or every second?
                // Every second is better for colon blinking if we wanted, but here every 1s is fine.
                if (updateHandler != null) {
                    updateHandler.postDelayed(this, 1000); 
                }
            }
        };
        updateHandler.post(updateRunnable);
    }
    
    private void updateClocks(String baseTimezone) {
        Date now = new Date();
        TimeZone baseTz = TimeZone.getTimeZone(baseTimezone);
        long baseOffset = baseTz.getOffset(now.getTime());

        for (ClockItem item : clockItems) {
            if (item.timeFormat != null && item.timeView != null) {
                item.timeView.setText(item.timeFormat.format(now));
            }
            if (item.dateFormat != null && item.dateView != null) {
                item.dateView.setText(item.dateFormat.format(now));
            }
            // Update offset
            if (item.offsetView != null && item.timezone != null) {
                TimeZone targetTz = TimeZone.getTimeZone(item.timezone);
                long targetOffset = targetTz.getOffset(now.getTime());
                long diffMillis = targetOffset - baseOffset;
                
                String offsetText = "";
                if (Math.abs(diffMillis) < 60000) { // Less than a minute difference
                     // offsetText = "Same time"; // Optional
                     offsetText = "";
                } else {
                    long diffHours = diffMillis / (1000 * 60 * 60);
                    long diffMinutes = Math.abs((diffMillis % (1000 * 60 * 60)) / (1000 * 60));
                    
                    String sign = diffHours >= 0 ? "+" : ""; // Negative is included in diffHours if negative
                    // But if diffHours is 0 and diffMillis is negative (e.g. -30 mins), diffHours is 0.
                    if (diffMillis < 0 && diffHours == 0) sign = "-";
                    
                    offsetText = sign + diffHours + "h";
                    if (diffMinutes > 0) {
                        offsetText += " " + diffMinutes + "m";
                    }
                }
                item.offsetView.setText(offsetText);
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
        
        clockItems.clear();
        
        if (clockContainer != null) {
            ViewGroup parent = (ViewGroup) clockContainer.getParent();
            if (parent != null) {
                parent.removeView(clockContainer);
            }
            clockContainer = null;
        }
    }
}

