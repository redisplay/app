package com.redisplay.app.utils;

import android.content.Context;
import android.content.SharedPreferences;

public class ConfigManager {
    private static final String PREF_NAME = "RedisplayPrefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_HOME_SCREEN_MODE = "home_screen_mode";
    private static final String KEY_AUTO_UPDATE = "auto_update";
    private static final String KEY_DEBUG_MODE = "debug_mode";
    private static final String KEY_USE_BLUETOOTH = "use_bluetooth";
    private static final String KEY_CONNECTION_TYPE = "connection_type"; // "remote", "internal"
    private static final String DEFAULT_URL = "";
    private static final String DEFAULT_CONNECTION_TYPE = "remote";

    private SharedPreferences prefs;

    public ConfigManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public boolean getUseBluetooth() {
        return prefs.getBoolean(KEY_USE_BLUETOOTH, false);
    }

    public void setUseBluetooth(boolean enabled) {
        prefs.edit().putBoolean(KEY_USE_BLUETOOTH, enabled).apply();
    }

    public String getServerUrl() {
        return prefs.getString(KEY_SERVER_URL, DEFAULT_URL);
    }

    public void setServerUrl(String url) {
        // Ensure URL doesn't end with slash
        if (url != null && url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        // Ensure URL starts with http:// or https://
        if (url != null && !url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        
        prefs.edit().putString(KEY_SERVER_URL, url).apply();
    }
    
    public boolean hasServerUrl() {
        String url = getServerUrl();
        return url != null && !url.isEmpty();
    }
    
    public boolean getHomeScreenMode() {
        return prefs.getBoolean(KEY_HOME_SCREEN_MODE, false);
    }
    
    public void setHomeScreenMode(boolean enabled) {
        prefs.edit().putBoolean(KEY_HOME_SCREEN_MODE, enabled).apply();
    }
    
    public boolean getAutoUpdate() {
        return prefs.getBoolean(KEY_AUTO_UPDATE, false);
    }
    
    public void setAutoUpdate(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_UPDATE, enabled).apply();
    }
    
    public boolean getDebugMode() {
        return prefs.getBoolean(KEY_DEBUG_MODE, false);
    }
    
    public void setDebugMode(boolean enabled) {
        prefs.edit().putBoolean(KEY_DEBUG_MODE, enabled).apply();
    }
    
    public String getConnectionType() {
        return prefs.getString(KEY_CONNECTION_TYPE, DEFAULT_CONNECTION_TYPE);
    }
    
    public void setConnectionType(String type) {
        if (type == null || (!type.equals("remote") && !type.equals("internal"))) {
            type = DEFAULT_CONNECTION_TYPE;
        }
        prefs.edit().putString(KEY_CONNECTION_TYPE, type).apply();
    }
    
    public void clearConfig() {
        prefs.edit().clear().apply();
    }
}

