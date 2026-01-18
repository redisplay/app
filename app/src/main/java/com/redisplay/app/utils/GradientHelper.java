package com.redisplay.app.utils;

import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.View;
import org.json.JSONObject;

public class GradientHelper {
    private static final String TAG = "GradientHelper";

    public static void applyBackground(View container, JSONObject background) {
        if (container == null) return;
        
        try {
            if (background == null) {
                // No background config, do nothing or let caller handle default
                return;
            }

            Log.d(TAG, "Applying background: " + background.toString());
            String bgType = background.optString("type", "");
            
            if ("gradient".equals(bgType)) {
                String fromColor = background.optString("from", "#333333");
                String toColor = background.optString("to", "#000000");
                String middleColor = background.optString("middle", null);
                String orientation = background.optString("orientation", "TOP_BOTTOM");
                
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
                gradient.setOrientation(getOrientation(orientation));
                gradient.setColors(colors);
                
                // Use setBackgroundDrawable for compatibility if needed, though setBackground is standard
                // For Android 4.2.2 (API 17), setBackground is available (added in API 16)
                container.setBackground(gradient);
                
                Log.d(TAG, "Applied gradient: " + fromColor + " -> " + toColor + " (Orientation: " + orientation + ")");
            } else if ("solid".equals(bgType) || background.has("color")) {
                String color = background.optString("color", "#333333");
                int colorInt = parseColor(color);
                container.setBackgroundColor(colorInt);
                Log.d(TAG, "Applied solid color: " + color);
            } else {
                // Try to use "color" field directly if type is missing
                String color = background.optString("color", null);
                if (color != null) {
                    int colorInt = parseColor(color);
                    container.setBackgroundColor(colorInt);
                    Log.d(TAG, "Applied implicit solid color: " + color);
                }
            }
            
            // Force redraw
            container.invalidate();
        } catch (Exception e) {
            Log.e(TAG, "Error applying background: " + e.getMessage());
            // Fallback not strictly needed here, caller can set default if this fails
        }
    }
    
    private static GradientDrawable.Orientation getOrientation(String orientationName) {
        if ("LEFT_RIGHT".equalsIgnoreCase(orientationName)) return GradientDrawable.Orientation.LEFT_RIGHT;
        if ("RIGHT_LEFT".equalsIgnoreCase(orientationName)) return GradientDrawable.Orientation.RIGHT_LEFT;
        if ("BOTTOM_TOP".equalsIgnoreCase(orientationName)) return GradientDrawable.Orientation.BOTTOM_TOP;
        if ("TL_BR".equalsIgnoreCase(orientationName)) return GradientDrawable.Orientation.TL_BR;
        if ("TR_BL".equalsIgnoreCase(orientationName)) return GradientDrawable.Orientation.TR_BL;
        if ("BL_TR".equalsIgnoreCase(orientationName)) return GradientDrawable.Orientation.BL_TR;
        if ("BR_TL".equalsIgnoreCase(orientationName)) return GradientDrawable.Orientation.BR_TL;
        return GradientDrawable.Orientation.TOP_BOTTOM;
    }
    
    public static int parseColor(String colorString) {
        try {
            if (colorString == null || colorString.isEmpty()) return 0xFF333333;
            
            if (colorString.startsWith("#")) {
                colorString = colorString.substring(1);
            }
            if (colorString.length() == 6) {
                return (int) Long.parseLong(colorString, 16) | 0xFF000000;
            } else if (colorString.length() == 8) {
                return (int) Long.parseLong(colorString, 16);
            }
            return 0xFF333333;
        } catch (Exception e) {
            return 0xFF333333;
        }
    }
}

