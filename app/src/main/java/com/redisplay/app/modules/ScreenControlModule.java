package com.redisplay.app.modules;

import android.view.View;
import com.redisplay.app.ContentModule;
import com.redisplay.app.MainActivity;
import org.json.JSONObject;

public class ScreenControlModule implements ContentModule {
    @Override
    public String getType() {
        return "screen_control";
    }
    
    @Override
    public void display(MainActivity activity, JSONObject contentItem, View container) {
        try {
            // Extract action from view data
            JSONObject view = contentItem.getJSONObject("view");
            JSONObject data = view.getJSONObject("data");
            String action = data.getString("action");
            
            if ("turn_off".equals(action)) {
                // Save current view state before turning off
                activity.getContentManager().saveCurrentViewState();
                activity.turnScreenOff();
            } else if ("turn_on".equals(action)) {
                activity.turnScreenOn();
                // Restore saved view state first (at alpha 0), then fade in
                new android.os.Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        // Restore view state without fade (keeps alpha at 0)
                        activity.getContentManager().restoreViewStateWithoutFade();
                        // Then fade in the restored content
                        new android.os.Handler().postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                activity.fadeInContent();
                            }
                        }, 50); // Small delay to ensure view is ready
                    }
                }, 100); // Small delay to ensure screen is on
            } else if ("dim".equals(action)) {
                int brightness = data.optInt("brightness", 0);
                activity.setScreenBrightness(brightness);
            }
        } catch (Exception e) {
            activity.showError("Screen control error: " + e.getMessage());
        }
    }
    
    @Override
    public void hide(MainActivity activity, View container) {
        // Nothing to hide for screen control
    }
}

