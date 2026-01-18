package com.redisplay.app.modules;

import android.view.View;
import android.widget.TextView;
import com.redisplay.app.ContentModule;
import com.redisplay.app.MainActivity;
import org.json.JSONObject;

public class SaintModule implements ContentModule {
    @Override
    public String getType() {
        return "saint";
    }
    
    @Override
    public void display(MainActivity activity, JSONObject contentItem, View container) {
        try {
            String url = contentItem.optString("url", "");
            if (!url.isEmpty()) {
                activity.loadImage(url);
            } else {
                TextView contentText = activity.getContentTextView();
                if (contentText != null) {
                    contentText.setText("⛪ Saint of the Day");
                    contentText.setVisibility(View.VISIBLE);
                }
            }
        } catch (Exception e) {
            activity.showError("Saint display error: " + e.getMessage());
        }
    }
    
    @Override
    public void hide(MainActivity activity, View container) {
        TextView contentText = activity.getContentTextView();
        if (contentText != null) {
            contentText.setVisibility(View.GONE);
        }
    }
}

