package com.redisplay.app.utils;

public class QuadrantDetector {
    public static final String TOP_LEFT = "TOP_LEFT";
    public static final String TOP_CENTER = "TOP_CENTER";
    public static final String TOP_RIGHT = "TOP_RIGHT";
    
    public static final String MIDDLE_LEFT = "MIDDLE_LEFT";
    public static final String MIDDLE_CENTER = "MIDDLE_CENTER";
    public static final String MIDDLE_RIGHT = "MIDDLE_RIGHT";
    
    public static final String BOTTOM_LEFT = "BOTTOM_LEFT";
    public static final String BOTTOM_CENTER = "BOTTOM_CENTER";
    public static final String BOTTOM_RIGHT = "BOTTOM_RIGHT";

    public static String getQuadrant(float x, float y, int width, int height) {
        int col = (int) (x / (width / 3.0f));
        int row = (int) (y / (height / 3.0f));

        // Clamp values to be safe
        if (col < 0) col = 0; if (col > 2) col = 2;
        if (row < 0) row = 0; if (row > 2) row = 2;

        if (row == 0) {
            if (col == 0) return TOP_LEFT;
            if (col == 1) return TOP_CENTER;
            return TOP_RIGHT;
        } else if (row == 1) {
            if (col == 0) return MIDDLE_LEFT;
            if (col == 1) return MIDDLE_CENTER;
            return MIDDLE_RIGHT;
        } else {
            if (col == 0) return BOTTOM_LEFT;
            if (col == 1) return BOTTOM_CENTER;
            return BOTTOM_RIGHT;
        }
    }
}

