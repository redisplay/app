package com.redisplay.app.modules;

import android.view.View;
import com.redisplay.app.ContentModule;
import com.redisplay.app.MainActivity;
import org.json.JSONObject;

import java.net.URLEncoder;

public class PhotographyModule implements ContentModule {
    @Override
    public String getType() {
        return "photography";
    }
    
    @Override
    public void display(MainActivity activity, JSONObject contentItem, View container) {
        try {
            String serverUrl = activity.getServerUrl();
            if (serverUrl == null || serverUrl.isEmpty()) {
                activity.showError("Photography error: No server URL configured");
                return;
            }
            
            String query = contentItem.optString("query", "");
            // Construct photography URL using configured server
            String imageUrl = serverUrl + "/api/photography/image?query=" + 
                URLEncoder.encode(query, "UTF-8");
            activity.loadImage(imageUrl);
        } catch (Exception e) {
            activity.showError("Photography error: " + e.getMessage());
        }
    }
    
    @Override
    public void hide(MainActivity activity, View container) {
        // Image hiding handled by MainActivity
    }
}

