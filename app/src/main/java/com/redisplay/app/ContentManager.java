package com.redisplay.app;

import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class ContentManager {
    private Map<String, ContentModule> modules = new HashMap<String, ContentModule>();
    private ContentModule currentModule = null;
    private MainActivity activity;
    private JSONObject savedViewState = null; // Store view state when screen turns off
    private JSONObject currentContentItem = null; // Track current content item
    private Runnable pendingDisplayRunnable = null; // Track pending display runnable to prevent accumulation
    
    public ContentManager(MainActivity activity) {
        this.activity = activity;
        registerDefaultModules();
    }
    
    private void registerDefaultModules() {
        // Register all content modules
        registerModule(new com.redisplay.app.modules.StaticTextModule());
        registerModule(new com.redisplay.app.modules.ScreenControlModule());
        registerModule(new com.redisplay.app.modules.ImageModule());
        registerModule(new com.redisplay.app.modules.MedicationReminderModule());
        registerModule(new com.redisplay.app.modules.WebcamModule());
        registerModule(new com.redisplay.app.modules.SaintModule());
        registerModule(new com.redisplay.app.modules.PhotographyModule());
        registerModule(new com.redisplay.app.modules.WeatherModule());
        registerModule(new com.redisplay.app.modules.WeatherForecastModule());
        registerModule(new com.redisplay.app.modules.WorldClockModule());
        registerModule(new com.redisplay.app.modules.ClockModule());
        registerModule(new com.redisplay.app.modules.CalendarModule());
        registerModule(new com.redisplay.app.modules.GalleryModule());
    }
    
    public void registerModule(ContentModule module) {
        String type = module.getType();
        modules.put(type, module);
        android.util.Log.d("ContentManager", "Registered module: " + type);
    }
    
    public void handleEvent(String jsonData) {
        long eventStart = System.currentTimeMillis();
        try {
            if (jsonData == null || jsonData.trim().isEmpty()) {
                android.util.Log.w("ContentManager", "Received empty or null event data");
                return;
            }
            
            JSONObject data = new JSONObject(jsonData);
            
            // Check for new SSE format: initial_view or view_change
            String eventType = data.optString("type", "");
            if ("initial_view".equals(eventType) || "view_change".equals(eventType)) {
                handleViewEvent(data);
                return;
            }
            // Check if this is a playlist update event (has both playback and playlist)
            else if (data.has("playback") && data.has("playlist")) {
                handlePlaylistEvent(data);
                return;
            } 
            // Check if this is a direct content event (has type field)
            else if (data.has("type")) {
                handleDirectContentEvent(data);
                return;
            }
            // Check if it's just a playback update (no playlist change)
            else if (data.has("playback")) {
                handlePlaybackUpdate(data);
                return;
            }
            // Check if it has activeContent array (alternative format)
            else if (data.has("activeContent")) {
                handleActiveContentEvent(data);
                return;
            }
            // Unknown format - ignore silently
            else {
                // Unknown event format - ignore
            }
        } catch (org.json.JSONException e) {
            android.util.Log.e("ContentManager", "JSON parse error: " + e.getMessage() + " Data: " + jsonData);
            activity.showError("JSON parse error: " + e.getMessage());
        } catch (Exception e) {
            android.util.Log.e("ContentManager", "Event error: " + e.getMessage(), e);
            activity.showError("Event error: " + e.getMessage());
        }
    }
    
    private void handleScreenControlEvent(JSONObject data) {
        try {
            String action = data.optString("action", "");
            if ("turn_off".equals(action) || "off".equals(action)) {
                activity.turnScreenOff();
            } else if ("turn_on".equals(action) || "on".equals(action)) {
                activity.turnScreenOn();
            } else if ("dim".equals(action)) {
                int brightness = data.optInt("brightness", 0);
                activity.setScreenBrightness(brightness);
            }
        } catch (Exception e) {
            activity.showError("Screen control error: " + e.getMessage());
        }
    }
    
    private void handlePlaybackUpdate(JSONObject data) {
        // Playback updates are handled by view change events
        // This method is kept for backward compatibility
    }
    
    private void handleActiveContentEvent(JSONObject data) {
        try {
            JSONArray activeContent = data.getJSONArray("activeContent");
            if (activeContent.length() > 0) {
                JSONObject contentItem = activeContent.getJSONObject(0);
                displayContent(contentItem);
            }
        } catch (Exception e) {
            activity.showError("Active content error: " + e.getMessage());
        }
    }
    
    private void handlePlaylistEvent(JSONObject data) {
        try {
            JSONObject playback = data.optJSONObject("playback");
            if (playback == null) {
                return;
            }
            
            int currentIndex = playback.optInt("currentIndex", -1);
            boolean paused = playback.optBoolean("paused", false);
            long timeRemaining = playback.optLong("timeRemainingMs", 0);
            int totalItems = playback.optInt("totalItems", 0);
            
            // Countdown removed - views will change based on rotateAfter timing from server
            
            // Get current content item from playlist
            JSONArray playlist = data.optJSONArray("playlist");
            if (playlist != null && currentIndex >= 0 && currentIndex < playlist.length()) {
                JSONObject contentItem = playlist.getJSONObject(currentIndex);
                // Update debug bar with playlist item info
                try {
                    String viewType = contentItem.optString("type", null);
                    String viewId = contentItem.optString("id", null);
                    activity.updateDebugBar(viewId, viewType);
                } catch (Exception e) {
                    // Ignore errors in debug bar update
                }
                displayContent(contentItem);
            }
            
            if (totalItems > 0) {
                activity.updateStatus("Playing item " + (currentIndex + 1) + " of " + totalItems);
            }
        } catch (Exception e) {
            activity.showError("Playlist error: " + e.getMessage());
        }
    }
    
    private void handleViewEvent(JSONObject data) {
        try {
            // Extract view object
            if (!data.has("view")) {
                activity.showError("View event missing 'view' field");
                return;
            }
            
            JSONObject view = data.getJSONObject("view");
            
            // Check if metadata exists
            if (!view.has("metadata")) {
                activity.showError("View missing 'metadata' field");
                return;
            }
            
            JSONObject metadata = view.getJSONObject("metadata");
            
            if (!metadata.has("type")) {
                activity.showError("Metadata missing 'type' field");
                return;
            }
            
            String viewType = metadata.getString("type");
            String viewId = view.optString("id", null);
            
            // Create a content item structure that modules can understand
            JSONObject contentItem = new JSONObject();
            contentItem.put("type", viewType);
            contentItem.put("view", view);
            
            // Update debug bar
            activity.updateDebugBar(viewId, viewType);
            
            displayContent(contentItem);
        } catch (org.json.JSONException e) {
            android.util.Log.e("ContentManager", "JSON error in handleViewEvent: " + e.getMessage(), e);
            activity.showError("View event JSON error: " + e.getMessage());
        } catch (Exception e) {
            android.util.Log.e("ContentManager", "Error in handleViewEvent: " + e.getMessage(), e);
            activity.showError("View event error: " + e.getMessage());
        }
    }
    
    private void handleDirectContentEvent(JSONObject contentItem) {
        // Try to extract view info for debug bar
        try {
            String viewType = contentItem.optString("type", null);
            String viewId = null;
            if (contentItem.has("view")) {
                JSONObject view = contentItem.getJSONObject("view");
                viewId = view.optString("id", null);
                if (viewType == null && view.has("metadata")) {
                    JSONObject metadata = view.getJSONObject("metadata");
                    viewType = metadata.optString("type", null);
                }
            }
            activity.updateDebugBar(viewId, viewType);
        } catch (Exception e) {
            // Ignore errors in debug bar update
        }
        displayContent(contentItem);
    }
    
    void displayContent(JSONObject contentItem) {
        try {
            String type = contentItem.getString("type");
            boolean showCountdown = contentItem.optBoolean("showCountdown", false);
            
            // Screen control doesn't need fade animations
            boolean isScreenControl = "screen_control".equals(type);
            
            // Save current content item (unless it's screen_control)
            if (!isScreenControl) {
                currentContentItem = contentItem;
            }
            
            if (isScreenControl) {
                // Screen control - execute immediately without fade
                ContentModule module = modules.get(type);
                if (module != null) {
                    if (currentModule != null && !"screen_control".equals(currentModule.getType())) {
                        currentModule.hide(activity, activity.getContentContainer());
                    }
                    currentModule = module;
                    module.display(activity, contentItem, activity.getContentContainer());
                } else {
                    String availableTypes = modules.keySet().toString();
                    activity.showError("Unknown content type: " + type + " (available: " + availableTypes + ")");
                }
            } else {
                // Regular content - use container cross-fade
                if (currentModule != null) {
                    // Fade out container with old content
                    final ContentModule oldModule = currentModule; // Capture old module reference
                    activity.fadeOutContent(new Runnable() {
                        @Override
                        public void run() {
                            // After fade out completes, destroy old module components
                            if (oldModule != null) {
                                oldModule.hide(activity, activity.getContentContainer());
                            }
                            // Force hide and clear all content views
                            activity.hideAllContentViews();
                            activity.clearAllContentViews();
                            
                            // Cancel any pending display runnable to prevent accumulation
                            if (pendingDisplayRunnable != null) {
                                activity.getHandler().removeCallbacks(pendingDisplayRunnable);
                            }
                            
                            // Small delay to ensure cleanup is complete before showing new content
                            pendingDisplayRunnable = new Runnable() {
                                @Override
                                public void run() {
                                    pendingDisplayRunnable = null; // Clear reference
                                    ContentModule module = modules.get(type);
                                    if (module != null) {
                                        currentModule = module;
                                        // Display new content
                                        module.display(activity, contentItem, activity.getContentContainer());
                                        // Ensure container and views are at alpha 0
                                        activity.getContentContainer().setAlpha(0.0f);
                                        // Bring debug bar to front before fade in
                                        if (activity.getDebugBar() != null) {
                                            activity.getDebugBar().bringToFront();
                                        }
                                        // Start fade in immediately
                                        activity.fadeInContent();
                                    } else {
                                        activity.showError("Unknown content type: " + type);
                                    }
                                }
                            };
                            activity.getHandler().postDelayed(pendingDisplayRunnable, 50); // Small delay to ensure cleanup
                        }
                    });
                } else {
                    // No current module, just fade in the new one
                    activity.hideAllContentViews();
                    ContentModule module = modules.get(type);
                    if (module != null) {
                        currentModule = module;
                        module.display(activity, contentItem, activity.getContentContainer());
                        activity.fadeInContent();
                    } else {
                        activity.showError("Unknown content type: " + type);
                    }
                }
            }
        } catch (Exception e) {
            activity.showError("Display error: " + e.getMessage());
        }
    }
    
    public void saveCurrentViewState() {
        // Save the current view state before screen turns off
        if (currentContentItem != null && !"screen_control".equals(currentContentItem.optString("type", ""))) {
            savedViewState = currentContentItem;
        }
    }
    
    public void restoreViewState() {
        if (savedViewState != null) {
            displayContent(savedViewState);
        }
    }
    
    public void restoreViewStateWithoutFade() {
        // Restore view state without fade animations (for screen-on)
        if (savedViewState != null) {
            try {
                String type = savedViewState.getString("type");
                boolean isScreenControl = "screen_control".equals(type);
                
                if (!isScreenControl) {
                    currentContentItem = savedViewState;
                    
                    // Hide current module if any
                    if (currentModule != null) {
                        currentModule.hide(activity, activity.getContentContainer());
                    }
                    
                    // Display new module without fade (will be faded in separately)
                    ContentModule module = modules.get(type);
                    if (module != null) {
                        currentModule = module;
                        activity.hideAllContentViews();
                        module.display(activity, savedViewState, activity.getContentContainer());
                        // Keep alpha at 0 - fade in will be called separately
                        activity.getContentContainer().setAlpha(0.0f);
                    }
                }
            } catch (Exception e) {
                activity.showError("Restore error: " + e.getMessage());
            }
        }
    }
    
    public JSONObject getSavedViewState() {
        return savedViewState;
    }
    
    public JSONObject getCurrentContentItem() {
        return currentContentItem;
    }

    public void cleanup() {
        // Cancel any pending display runnable
        if (pendingDisplayRunnable != null && activity != null && activity.getHandler() != null) {
            activity.getHandler().removeCallbacks(pendingDisplayRunnable);
            pendingDisplayRunnable = null;
        }
        
        // Hide current module if any
        if (currentModule != null && activity != null) {
            currentModule.hide(activity, activity.getContentContainer());
            currentModule = null;
        }
        
        // Clear references
        savedViewState = null;
        currentContentItem = null;
        activity = null;
    }
}

