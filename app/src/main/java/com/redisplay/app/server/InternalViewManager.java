package com.redisplay.app.server;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

public class InternalViewManager {
    private static final String TAG = "InternalViewManager";
    private static final String PREFS_NAME = "InternalViews";
    private static final String KEY_VIEWS = "views_json";
    private static final String KEY_CURRENT_VIEWS = "channel_current_views"; // Persist current view per channel
    
    private Context context;
    private Map<String, JSONObject> views = new HashMap<>();
    private Map<String, Boolean> viewEnabled = new HashMap<>(); // viewId -> enabled
    private Map<String, String> channelCurrentViews = new HashMap<>(); // channel -> viewId
    private Map<String, Long> viewActivationTime = new HashMap<>(); // channel -> timestamp
    private Map<String, Map<String, Long>> manualOverrides = new HashMap<>(); // channel -> { viewId: timestamp }
    
    // Rotation scheduling
    // Use a Handler with a background thread's Looper since we're running in a server context
    private volatile Handler rotationHandler;
    private final Object rotationHandlerLock = new Object();
    private Map<String, Runnable> channelRotationRunnables = new HashMap<>(); // channel -> Runnable
    private InternalChannelConfig channelConfig; // Reference to channel config for getting channel views
    
    // Initialize rotation handler on first use
    private void initRotationHandler() {
        if (rotationHandler == null) {
            synchronized (rotationHandlerLock) {
                if (rotationHandler == null) {
                    // Use MainLooper - it should work from any thread
                    // MainLooper is always available in Android apps
                    rotationHandler = new Handler(Looper.getMainLooper());
                    Log.d(TAG, "Initialized rotation handler with MainLooper");
                }
            }
        }
    }
    
    public InternalViewManager() {
        // Initialize with default channel
        channelCurrentViews.put("test", null);
    }
    
    public void setChannelConfig(InternalChannelConfig channelConfig) {
        this.channelConfig = channelConfig;
    }
    
    public void setContext(Context context) {
        this.context = context;
        loadViews();
    }
    
    private void loadViews() {
        if (context == null) return;
        
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String viewsJson = prefs.getString(KEY_VIEWS, null);
            String enabledJson = prefs.getString("views_enabled", null);
            
            if (viewsJson != null && !viewsJson.isEmpty()) {
                JSONObject viewsObj = new JSONObject(viewsJson);
                java.util.Iterator<String> keys = viewsObj.keys();
                while (keys.hasNext()) {
                    String id = keys.next();
                    JSONObject view = viewsObj.getJSONObject(id);
                    views.put(id, view);
                }
                Log.d(TAG, "Loaded " + views.size() + " views from storage");
            }
            
            if (enabledJson != null && !enabledJson.isEmpty()) {
                JSONObject enabledObj = new JSONObject(enabledJson);
                java.util.Iterator<String> keys = enabledObj.keys();
                while (keys.hasNext()) {
                    String id = keys.next();
                    viewEnabled.put(id, enabledObj.optBoolean(id, true));
                }
            } else {
                // Default all views to enabled if no saved state
                for (String id : views.keySet()) {
                    if (!viewEnabled.containsKey(id)) {
                        viewEnabled.put(id, true);
                    }
                }
            }
            
            // Load current views per channel
            String currentViewsJson = prefs.getString(KEY_CURRENT_VIEWS, null);
            if (currentViewsJson != null && !currentViewsJson.isEmpty()) {
                try {
                    JSONObject currentViewsObj = new JSONObject(currentViewsJson);
                    java.util.Iterator<String> keys = currentViewsObj.keys();
                    while (keys.hasNext()) {
                        String channel = keys.next();
                        String viewId = currentViewsObj.optString(channel, null);
                        if (viewId != null && !viewId.equals("null") && views.containsKey(viewId)) {
                            channelCurrentViews.put(channel, viewId);
                            // Schedule rotation for loaded view
                            scheduleViewRotation(viewId, channel);
                        }
                    }
                    Log.d(TAG, "Loaded current views for " + channelCurrentViews.size() + " channels");
                } catch (Exception e) {
                    Log.e(TAG, "Error loading current views: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading views from storage: " + e.getMessage());
        }
    }
    
    private void saveViews() {
        if (context == null) return;
        
        try {
            JSONObject viewsObj = new JSONObject();
            JSONObject enabledObj = new JSONObject();
            for (Map.Entry<String, JSONObject> entry : views.entrySet()) {
                viewsObj.put(entry.getKey(), entry.getValue());
                Boolean enabled = viewEnabled.get(entry.getKey());
                if (enabled == null) {
                    enabled = true; // Default to enabled
                }
                enabledObj.put(entry.getKey(), enabled);
            }
            
            // Save current views per channel
            JSONObject currentViewsObj = new JSONObject();
            for (Map.Entry<String, String> entry : channelCurrentViews.entrySet()) {
                currentViewsObj.put(entry.getKey(), entry.getValue() != null ? entry.getValue() : "null");
            }
            
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                .putString(KEY_VIEWS, viewsObj.toString())
                .putString("views_enabled", enabledObj.toString())
                .putString(KEY_CURRENT_VIEWS, currentViewsObj.toString())
                .apply();
            Log.d(TAG, "Saved " + views.size() + " views to storage");
        } catch (Exception e) {
            Log.e(TAG, "Error saving views to storage: " + e.getMessage());
        }
    }
    
    public void addView(String id, JSONObject view) {
        try {
            // Normalize view structure - ensure it has metadata and data
            JSONObject normalizedView = new JSONObject();
            
            // Check if view already has metadata and data (new format)
            if (view.has("metadata") && view.has("data")) {
                // Already in correct format
                normalizedView.put("id", id);
                normalizedView.put("metadata", view.getJSONObject("metadata"));
                normalizedView.put("data", view.getJSONObject("data"));
            } else {
                // Legacy format - wrap in default metadata
                normalizedView.put("id", id);
                JSONObject metadata = new JSONObject();
                metadata.put("type", "custom");
                normalizedView.put("metadata", metadata);
                // Use the entire view as data if no data field exists
                if (view.has("data")) {
                    normalizedView.put("data", view.getJSONObject("data"));
                } else {
                    normalizedView.put("data", view);
                }
            }
            
            views.put(id, normalizedView);
            // New views are enabled by default
            if (!viewEnabled.containsKey(id)) {
                viewEnabled.put(id, true);
            }
            Log.d(TAG, "Added view: " + id);
            
            // Persist views
            saveViews();
            
            // If no current view for default channel, set this one
            if (channelCurrentViews.get("test") == null) {
                setCurrentView(id, "test");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error normalizing view " + id + ": " + e.getMessage(), e);
            // Fallback: store as-is if normalization fails
            views.put(id, view);
            Log.d(TAG, "Added view (without normalization): " + id);
        }
    }
    
    public void removeView(String id) {
        views.remove(id);
        Log.d(TAG, "Removed view: " + id);
        
        // Persist views
        saveViews();
        
        // If this was the current view, switch to another
        for (Map.Entry<String, String> entry : channelCurrentViews.entrySet()) {
            if (id.equals(entry.getValue())) {
                String nextView = views.isEmpty() ? null : views.keySet().iterator().next();
                setCurrentView(nextView, entry.getKey());
            }
        }
    }
    
    public JSONObject getView(String id) {
        return views.get(id);
    }
    
    public JSONArray getAllViews() {
        JSONArray result = new JSONArray();
        for (Map.Entry<String, JSONObject> entry : views.entrySet()) {
            try {
                JSONObject view = new JSONObject();
                view.put("id", entry.getKey());
                view.put("view", entry.getValue());
                Boolean enabled = viewEnabled.get(entry.getKey());
                view.put("enabled", enabled != null ? enabled : true);
                result.put(view);
            } catch (Exception e) {
                Log.e(TAG, "Error serializing view: " + e.getMessage());
            }
        }
        return result;
    }
    
    public JSONArray getEnabledViews() {
        JSONArray result = new JSONArray();
        for (Map.Entry<String, JSONObject> entry : views.entrySet()) {
            Boolean enabled = viewEnabled.get(entry.getKey());
            if (enabled == null || enabled) {
                try {
                    JSONObject view = new JSONObject();
                    view.put("id", entry.getKey());
                    view.put("view", entry.getValue());
                    result.put(view);
                } catch (Exception e) {
                    Log.e(TAG, "Error serializing view: " + e.getMessage());
                }
            }
        }
        return result;
    }
    
    public void setViewEnabled(String id, boolean enabled) {
        viewEnabled.put(id, enabled);
        saveViews();
        Log.d(TAG, "View " + id + " " + (enabled ? "enabled" : "disabled"));
        
        // If disabling current view, switch to another
        if (!enabled) {
            for (Map.Entry<String, String> entry : channelCurrentViews.entrySet()) {
                if (id.equals(entry.getValue())) {
                    String nextView = null;
                    for (Map.Entry<String, JSONObject> v : views.entrySet()) {
                        Boolean vEnabled = viewEnabled.get(v.getKey());
                        if ((vEnabled == null || vEnabled) && !v.getKey().equals(id)) {
                            nextView = v.getKey();
                            break;
                        }
                    }
                    setCurrentView(nextView, entry.getKey());
                }
            }
        }
    }
    
    public boolean isViewEnabled(String id) {
        Boolean enabled = viewEnabled.get(id);
        return enabled == null || enabled;
    }
    
    public JSONObject getCurrentView(String channel) {
        if (channel == null) {
            channel = "test";
        }
        
        String viewId = channelCurrentViews.get(channel);
        if (viewId == null || !views.containsKey(viewId)) {
            return null;
        }
        
        JSONObject view = views.get(viewId);
        if (view != null) {
            try {
                // Return the view object directly (it already has id, metadata, data)
                // The view is stored with id, metadata, and data fields
                JSONObject result = new JSONObject();
                result.put("id", viewId);
                // Copy all fields from the stored view (metadata, data, etc.)
                java.util.Iterator<String> keys = view.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    result.put(key, view.get(key));
                }
                return result;
            } catch (Exception e) {
                Log.e(TAG, "Error creating view response: " + e.getMessage());
            }
        }
        return null;
    }
    
    private InternalHttpServer server; // Reference to server for broadcasting
    
    public void setServer(InternalHttpServer server) {
        this.server = server;
    }
    
    public void setCurrentView(String viewId, String channel) {
        setCurrentView(viewId, channel, true); // Default to manual trigger
    }
    
    public void setCurrentView(String viewId, String channel, boolean isManualTrigger) {
        if (channel == null) {
            channel = "test";
        }
        
        if (viewId != null && !views.containsKey(viewId)) {
            Log.w(TAG, "View not found: " + viewId);
            return;
        }
        
        // Clear existing rotation for this channel
        cancelRotation(channel);
        
        channelCurrentViews.put(channel, viewId);
        if (viewId != null) {
            viewActivationTime.put(channel, System.currentTimeMillis());
            // Only mark as manually overridden if it's a manual trigger
            if (isManualTrigger) {
                Map<String, Long> overrides = manualOverrides.get(channel);
                if (overrides == null) {
                    overrides = new HashMap<>();
                    manualOverrides.put(channel, overrides);
                }
                overrides.put(viewId, System.currentTimeMillis());
                Log.d(TAG, "View " + viewId + " set as manual trigger on channel " + channel);
            } else {
                // Clear manual override for automatic rotation
                Map<String, Long> overrides = manualOverrides.get(channel);
                if (overrides != null) {
                    overrides.remove(viewId);
                    if (overrides.isEmpty()) {
                        manualOverrides.remove(channel);
                    }
                }
                Log.d(TAG, "View " + viewId + " set via automatic rotation on channel " + channel);
            }
            
            // Broadcast view change to SSE clients
            if (server != null) {
                try {
                    JSONObject currentView = getCurrentView(channel);
                    if (currentView != null) {
                        JSONObject message = new JSONObject();
                        message.put("type", "view_change");
                        message.put("view", currentView);
                        server.broadcastToChannel(channel, message);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error broadcasting view change: " + e.getMessage());
                }
            }
            
            // Schedule rotation for this view
            scheduleViewRotation(viewId, channel);
        }
        // Persist current view change
        saveViews();
        Log.d(TAG, "Set current view for channel " + channel + ": " + viewId);
    }
    
    private void cancelRotation(String channel) {
        Runnable existingRunnable = channelRotationRunnables.get(channel);
        if (existingRunnable != null) {
            rotationHandler.removeCallbacks(existingRunnable);
            channelRotationRunnables.remove(channel);
        }
    }
    
    private void scheduleViewRotation(String viewId, String channel) {
        if (viewId == null || channel == null) {
            Log.d(TAG, "scheduleViewRotation: viewId or channel is null");
            return;
        }
        
        // Initialize handler if needed
        initRotationHandler();
        
        JSONObject view = views.get(viewId);
        if (view == null) {
            Log.d(TAG, "scheduleViewRotation: view not found: " + viewId);
            return;
        }
        
        try {
            JSONObject metadata = view.optJSONObject("metadata");
            if (metadata == null) {
                Log.d(TAG, "scheduleViewRotation: view has no metadata: " + viewId);
                return;
            }
            
            // Check for rotateAfter (milliseconds)
            long delay = -1;
            if (metadata.has("rotateAfter")) {
                delay = metadata.optLong("rotateAfter", -1);
                Log.d(TAG, "scheduleViewRotation: found rotateAfter=" + delay + "ms for view " + viewId);
            } else {
                Log.d(TAG, "scheduleViewRotation: no rotateAfter in metadata for view " + viewId);
            }
            
            if (delay <= 0) {
                // No rotation scheduled
                Log.d(TAG, "scheduleViewRotation: delay <= 0, not scheduling rotation");
                return;
            }
            
            // Get channel views to determine available views for rotation
            List<String> availableViews = new ArrayList<>();
            if (channelConfig != null) {
                JSONArray channelViews = channelConfig.getChannelViews(channel);
                for (int i = 0; i < channelViews.length(); i++) {
                    String id = channelViews.optString(i, null);
                    if (id != null && views.containsKey(id)) {
                        Boolean enabled = viewEnabled.get(id);
                        if (enabled == null || enabled) {
                            availableViews.add(id);
                        }
                    }
                }
            } else {
                // Fallback: use all enabled views
                for (String id : views.keySet()) {
                    Boolean enabled = viewEnabled.get(id);
                    if (enabled == null || enabled) {
                        availableViews.add(id);
                    }
                }
            }
            
            Log.d(TAG, "scheduleViewRotation: found " + availableViews.size() + " available views: " + availableViews);
            
            if (availableViews.size() <= 1) {
                Log.w(TAG, "Not enough views for rotation (need >1, have " + availableViews.size() + "). Views in channel: " + availableViews);
                return;
            }
            
            // Create rotation runnable
            final String finalViewId = viewId;
            final String finalChannel = channel;
            final List<String> finalAvailableViews = availableViews;
            
            Runnable rotationRunnable = new Runnable() {
                @Override
                public void run() {
                    // Check if this view is still the current view
                    String currentViewId = channelCurrentViews.get(finalChannel);
                    if (!finalViewId.equals(currentViewId)) {
                        Log.d(TAG, "View changed, skipping rotation for " + finalViewId);
                        return;
                    }
                    
                    // Check if view is manually overridden
                    Map<String, Long> overrides = manualOverrides.get(finalChannel);
                    boolean isManuallyOverridden = overrides != null && overrides.containsKey(finalViewId);
                    
                    if (isManuallyOverridden) {
                        // View is manually overridden - reschedule rotation to check again later
                        Log.d(TAG, "View " + finalViewId + " is manually overridden - rescheduling rotation");
                        scheduleViewRotation(finalViewId, finalChannel);
                        return;
                    }
                    
                    // Find next view
                    int currentIndex = finalAvailableViews.indexOf(finalViewId);
                    if (currentIndex < 0) {
                        currentIndex = 0;
                    }
                    int nextIndex = (currentIndex + 1) % finalAvailableViews.size();
                    String nextViewId = finalAvailableViews.get(nextIndex);
                    
                    Log.d(TAG, "Rotating from " + finalViewId + " to " + nextViewId + " on channel " + finalChannel);
                    
                    // Clear manual override for the new view (it's automatic rotation)
                    if (overrides != null) {
                        overrides.remove(finalViewId);
                        if (overrides.isEmpty()) {
                            manualOverrides.remove(finalChannel);
                        }
                    }
                    
                    // Set next view via automatic rotation (this will schedule its own rotation)
                    setCurrentView(nextViewId, finalChannel, false); // false = automatic rotation
                }
            };
            
            // Cancel any existing rotation
            cancelRotation(channel);
            
            // Schedule new rotation
            channelRotationRunnables.put(channel, rotationRunnable);
            rotationHandler.postDelayed(rotationRunnable, delay);
            
            Log.d(TAG, "Scheduled rotation for view " + viewId + " on channel " + channel + " after " + delay + "ms");
        } catch (Exception e) {
            Log.e(TAG, "Error scheduling rotation: " + e.getMessage(), e);
        }
    }
    
    public void nextView(String channel) {
        if (channel == null) {
            channel = "test";
        }
        
        // Only include enabled views
        List<String> viewList = new ArrayList<>();
        for (String id : views.keySet()) {
            Boolean enabled = viewEnabled.get(id);
            if (enabled == null || enabled) {
                viewList.add(id);
            }
        }
        
        if (viewList.isEmpty()) {
            return;
        }
        
        String currentViewId = channelCurrentViews.get(channel);
        int currentIndex = currentViewId != null ? viewList.indexOf(currentViewId) : -1;
        int nextIndex = (currentIndex + 1) % viewList.size();
        setCurrentView(viewList.get(nextIndex), channel);
    }
    
    public void previousView(String channel) {
        if (channel == null) {
            channel = "test";
        }
        
        // Only include enabled views
        List<String> viewList = new ArrayList<>();
        for (String id : views.keySet()) {
            Boolean enabled = viewEnabled.get(id);
            if (enabled == null || enabled) {
                viewList.add(id);
            }
        }
        
        if (viewList.isEmpty()) {
            return;
        }
        
        String currentViewId = channelCurrentViews.get(channel);
        int currentIndex = currentViewId != null ? viewList.indexOf(currentViewId) : -1;
        int nextIndex = currentIndex <= 0 ? viewList.size() - 1 : currentIndex - 1;
        setCurrentView(viewList.get(nextIndex), channel);
    }
    
    public boolean isManuallyOverridden(String viewId, String channel) {
        if (channel == null) {
            channel = "test";
        }
        Map<String, Long> overrides = manualOverrides.get(channel);
        return overrides != null && overrides.containsKey(viewId);
    }
    
    public void clearManualOverride(String channel) {
        if (channel == null) {
            channel = "test";
        }
        manualOverrides.remove(channel);
    }
    
    public boolean hasViews() {
        return !views.isEmpty();
    }
    
    public int getViewCount() {
        return views.size();
    }
}

