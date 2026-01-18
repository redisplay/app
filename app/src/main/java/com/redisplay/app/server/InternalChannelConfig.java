package com.redisplay.app.server;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

public class InternalChannelConfig {
    private static final String TAG = "InternalChannelConfig";
    private static final String PREFS_NAME = "RedisplayChannelConfig";
    private static final String KEY_CHANNEL_CONFIG = "channel_config";
    
    private Context context;
    private Map<String, JSONObject> channelConfigs = new HashMap<>();
    
    public InternalChannelConfig(Context context) {
        this.context = context;
        loadChannelConfig();
        
        // Initialize default channel if none exists
        if (!channelConfigs.containsKey("test")) {
            JSONObject defaultConfig = new JSONObject();
            try {
                defaultConfig.put("views", new JSONArray());
                defaultConfig.put("quadrants", new JSONObject());
                defaultConfig.put("rotation", new JSONObject());
                channelConfigs.put("test", defaultConfig);
                saveChannelConfig();
            } catch (Exception e) {
                Log.e(TAG, "Error initializing default config: " + e.getMessage());
            }
        }
    }
    
    private void loadChannelConfig() {
        if (context == null) return;
        
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String configJson = prefs.getString(KEY_CHANNEL_CONFIG, null);
            
            if (configJson != null) {
                JSONObject allConfigs = new JSONObject(configJson);
                java.util.Iterator<String> keys = allConfigs.keys();
                while (keys.hasNext()) {
                    String channel = keys.next();
                    channelConfigs.put(channel, allConfigs.getJSONObject(channel));
                }
                Log.d(TAG, "Loaded channel config for " + channelConfigs.size() + " channels");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading channel config: " + e.getMessage());
        }
    }
    
    private void saveChannelConfig() {
        if (context == null) return;
        
        try {
            JSONObject allConfigs = new JSONObject();
            for (Map.Entry<String, JSONObject> entry : channelConfigs.entrySet()) {
                allConfigs.put(entry.getKey(), entry.getValue());
            }
            
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                .putString(KEY_CHANNEL_CONFIG, allConfigs.toString())
                .apply();
            Log.d(TAG, "Saved channel config for " + channelConfigs.size() + " channels");
        } catch (Exception e) {
            Log.e(TAG, "Error saving channel config: " + e.getMessage());
        }
    }
    
    public JSONObject getChannelConfig(String channel) {
        JSONObject config = channelConfigs.get(channel);
        if (config == null) {
            // Return default config
            JSONObject defaultConfig = new JSONObject();
            try {
                defaultConfig.put("views", new JSONArray());
                defaultConfig.put("quadrants", new JSONObject());
                defaultConfig.put("rotation", new JSONObject());
            } catch (Exception e) {
                Log.e(TAG, "Error creating default config: " + e.getMessage());
            }
            return defaultConfig;
        }
        return config;
    }
    
    public void setChannelConfig(String channel, JSONObject config) {
        channelConfigs.put(channel, config);
        saveChannelConfig();
        Log.d(TAG, "Set config for channel: " + channel);
    }
    
    public JSONArray getChannelViews(String channel) {
        JSONObject config = getChannelConfig(channel);
        try {
            return config.optJSONArray("views");
        } catch (Exception e) {
            return new JSONArray();
        }
    }
    
    public void setChannelViews(String channel, JSONArray views) {
        JSONObject config = getChannelConfig(channel);
        try {
            config.put("views", views);
            channelConfigs.put(channel, config);
            saveChannelConfig();
            Log.d(TAG, "Set views for channel: " + channel);
        } catch (Exception e) {
            Log.e(TAG, "Error setting channel views: " + e.getMessage());
        }
    }
    
    public void setChannelQuadrants(String channel, JSONObject quadrants) {
        JSONObject config = getChannelConfig(channel);
        try {
            config.put("quadrants", quadrants);
            channelConfigs.put(channel, config);
            saveChannelConfig();
            Log.d(TAG, "Set quadrants for channel: " + channel);
        } catch (Exception e) {
            Log.e(TAG, "Error setting channel quadrants: " + e.getMessage());
        }
    }
    
    public JSONObject getChannelQuadrants(String channel) {
        JSONObject config = getChannelConfig(channel);
        try {
            return config.optJSONObject("quadrants");
        } catch (Exception e) {
            return new JSONObject();
        }
    }
    
    public List<String> getAllChannels() {
        return new ArrayList<>(channelConfigs.keySet());
    }
}

