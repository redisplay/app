package com.redisplay.app.modules;

import android.view.View;
import android.widget.TextView;
import com.redisplay.app.ContentModule;
import com.redisplay.app.MainActivity;
import org.json.JSONObject;

public class MedicationReminderModule implements ContentModule {
    @Override
    public String getType() {
        return "medication-reminder";
    }
    
    @Override
    public void display(MainActivity activity, JSONObject contentItem, View container) {
        TextView contentText = activity.getContentTextView();
        if (contentText != null) {
            contentText.setText("💊 Medication Reminder\n\nTime to take your medication");
            contentText.setVisibility(View.VISIBLE);
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

