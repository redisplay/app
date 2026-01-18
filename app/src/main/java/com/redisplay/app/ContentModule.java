package com.redisplay.app;

import android.view.View;
import org.json.JSONObject;

public interface ContentModule {
    /**
     * Get the content type this module handles
     */
    String getType();
    
    /**
     * Display the content based on the event data
     * @param activity The main activity
     * @param contentItem The content item JSON
     * @param container The container view to display content in
     */
    void display(MainActivity activity, JSONObject contentItem, View container);
    
    /**
     * Called when this module should be hidden/cleaned up
     */
    void hide(MainActivity activity, View container);
}

