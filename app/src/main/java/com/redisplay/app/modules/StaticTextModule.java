package com.redisplay.app.modules;

import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.TextView;
import com.redisplay.app.ContentModule;
import com.redisplay.app.MainActivity;
import org.json.JSONObject;

public class StaticTextModule implements ContentModule {
    @Override
    public String getType() {
        return "static_text";
    }
    
    @Override
    public void display(MainActivity activity, JSONObject contentItem, View container) {
        try {
            // Ensure other views are hidden
            activity.hideAllContentViews();
            
            // Extract view data
            JSONObject view = contentItem.getJSONObject("view");
            JSONObject data = view.getJSONObject("data");
            JSONObject metadata = view.getJSONObject("metadata");
            
            String title = data.optString("title", "");
            String text = data.optString("text", "");
            long rotateAfter = metadata.optLong("rotateAfter", 0);
            
            TextView contentText = activity.getContentTextView();
            if (contentText != null) {
                // Build display text
                StringBuilder displayText = new StringBuilder();
                if (!title.isEmpty()) {
                    displayText.append(title).append("\n\n");
                }
                if (!text.isEmpty()) {
                    displayText.append(text);
                }
                contentText.setText(displayText.toString());
                contentText.setVisibility(View.VISIBLE);
                
                // Apply background gradient if present
                if (data.has("background")) {
                    JSONObject background = data.getJSONObject("background");
                    String bgType = background.optString("type", "");
                    if ("gradient".equals(bgType)) {
                        String fromColor = background.optString("from", "#000000");
                        String toColor = background.optString("to", "#000000");
                        String middleColor = background.optString("middle", null);
                        applyGradientBackground(container, fromColor, middleColor, toColor);
                    }
                }
            }
            
            // Countdown will be handled by ContentManager based on rotateAfter
        } catch (Exception e) {
            activity.showError("Static text error: " + e.getMessage());
        }
    }
    
    @Override
    public void hide(MainActivity activity, View container) {
        TextView contentText = activity.getContentTextView();
        if (contentText != null) {
            contentText.setText(""); // Clear text to prevent flashing
            contentText.setVisibility(View.GONE);
            contentText.clearAnimation();
        }
        // Clear background
        if (container != null) {
            container.setBackground(null);
        }
    }
    
    private void applyGradientBackground(View container, String fromColor, String middleColor, String toColor) {
        try {
            int from = parseColor(fromColor);
            int to = parseColor(toColor);
            int[] colors;
            
            if (middleColor != null && !middleColor.isEmpty()) {
                int middle = parseColor(middleColor);
                colors = new int[]{from, middle, to};
            } else {
                colors = new int[]{from, to};
            }
            
            GradientDrawable gradient = new GradientDrawable();
            gradient.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
            gradient.setColors(colors);
            container.setBackground(gradient);
        } catch (Exception e) {
            // Ignore gradient errors
        }
    }
    
    private int parseColor(String colorString) {
        try {
            // Remove # if present
            if (colorString.startsWith("#")) {
                colorString = colorString.substring(1);
            }
            // Handle 6-digit hex colors (add alpha)
            if (colorString.length() == 6) {
                return (int) Long.parseLong(colorString, 16) | 0xFF000000;
            }
            // Handle 8-digit hex colors (already has alpha)
            else if (colorString.length() == 8) {
                return (int) Long.parseLong(colorString, 16);
            }
            return 0xFF000000; // Default to black
        } catch (Exception e) {
            return 0xFF000000; // Default to black
        }
    }
}

