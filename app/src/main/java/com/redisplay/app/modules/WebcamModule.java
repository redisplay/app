package com.redisplay.app.modules;

import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import com.redisplay.app.ContentModule;
import com.redisplay.app.MainActivity;
import org.json.JSONObject;

public class WebcamModule implements ContentModule {
    private static final String TAG = "WebcamModule";
    private Handler updateHandler;
    private Runnable updateRunnable;
    private long imageTimestamp = 0;
    private TextView timestampOverlay;
    private MainActivity currentActivity;

    @Override
    public String getType() {
        return "webcam";
    }

    @Override
    public void display(MainActivity activity, JSONObject contentItem, View container) {
        try {
            currentActivity = activity;
            
            // Extract view data
            JSONObject view = contentItem.getJSONObject("view");
            JSONObject data = view.getJSONObject("data");
            
            String webcamId = data.getString("webcamId");
            String fallbackUrl = data.optString("url", null);
            
            Log.d(TAG, "Displaying webcam - webcamId: " + webcamId);
            
            // Build image URL - webcam endpoint is on same server, no proxy needed
            String imageUrl;
            if (webcamId != null && !webcamId.isEmpty()) {
                // Use webcam API endpoint directly (same server)
                // Request resized image (w=1024) to reduce bandwidth and load time
                imageUrl = activity.getServerUrl() + "/api/webcams/" + webcamId + "?w=1024&t=" + System.currentTimeMillis();
            } else if (fallbackUrl != null) {
                // Use fallback URL via proxy (external URL)
                imageUrl = activity.getServerUrl() + "/api/proxy/image/" + 
                    java.net.URLEncoder.encode(fallbackUrl, "UTF-8");
            } else {
                activity.showError("Webcam ID or URL is required");
                return;
            }
            
            Log.d(TAG, "Webcam image URL: " + imageUrl);
            
            // Get ImageView
            ImageView contentImage = activity.getContentImageView();
            if (contentImage == null) {
                Log.e(TAG, "ImageView is null");
                return;
            }
            
            // Hide other views
            activity.getContentWebView().setVisibility(View.GONE);
            activity.getContentTextView().setVisibility(View.GONE);
            
            // Set scale type from server data (default to fitCenter)
            String scaleType = data.optString("scaleType", "fitCenter");
            ImageView.ScaleType finalScaleType;
            if ("center".equals(scaleType)) {
                finalScaleType = ImageView.ScaleType.CENTER;
            } else if ("fitCenter".equals(scaleType)) {
                finalScaleType = ImageView.ScaleType.FIT_CENTER;
            } else if ("fitXY".equals(scaleType)) {
                finalScaleType = ImageView.ScaleType.FIT_XY;
            } else if ("centerCrop".equals(scaleType)) {
                finalScaleType = ImageView.ScaleType.CENTER_CROP;
            } else if ("matrix".equals(scaleType)) {
                finalScaleType = ImageView.ScaleType.MATRIX;
            } else {
                // Default to fitCenter (fits entire image without cropping)
                finalScaleType = ImageView.ScaleType.FIT_CENTER;
            }
            
            // Ensure ImageView fills the container for centerCrop and fitXY
            if (finalScaleType == ImageView.ScaleType.CENTER_CROP || finalScaleType == ImageView.ScaleType.FIT_XY) {
                ViewGroup.LayoutParams params = contentImage.getLayoutParams();
                if (params != null) {
                    params.width = ViewGroup.LayoutParams.MATCH_PARENT;
                    params.height = ViewGroup.LayoutParams.MATCH_PARENT;
                    contentImage.setLayoutParams(params);
                }
                contentImage.setAdjustViewBounds(false);
            } else {
                // For fitCenter and center, allow view to adjust bounds
                contentImage.setAdjustViewBounds(true);
            }
            
            contentImage.setScaleType(finalScaleType);
            contentImage.setVisibility(View.VISIBLE);
            
            Log.d(TAG, "Set scale type to: " + finalScaleType + " (requested: " + scaleType + ")");
            Log.d(TAG, "ImageView layout: " + contentImage.getWidth() + "x" + contentImage.getHeight() + ", adjustViewBounds: " + contentImage.getAdjustViewBounds());
            
            // Create timestamp overlay
            createTimestampOverlay(activity);
            
            // Optimization: Check for base64 image injected by server
            if (data.has("image")) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            long startDecode = System.currentTimeMillis();
                            JSONObject imageObj = data.getJSONObject("image");
                            String base64 = imageObj.getString("base64");
                            
                            if (base64 != null && !base64.isEmpty()) {
                                Log.d(TAG, "[Perf] Base64 string length: " + base64.length());
                                
                                final byte[] decodedString = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
                                long afterBase64 = System.currentTimeMillis();
                                Log.d(TAG, "[Perf] Base64 decode took: " + (afterBase64 - startDecode) + "ms");
                                
                                final android.graphics.Bitmap decodedByte = android.graphics.BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);
                                long afterBitmap = System.currentTimeMillis();
                                Log.d(TAG, "[Perf] Bitmap decode took: " + (afterBitmap - afterBase64) + "ms");
                                
                                if (decodedByte != null) {
                                    final long totalTime = System.currentTimeMillis() - startDecode;
                                    activity.runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (contentImage != null) {
                                                contentImage.setImageBitmap(decodedByte);
                                                contentImage.setVisibility(View.VISIBLE);
                                                Log.d(TAG, "[Perf] Total image process took: " + totalTime + "ms");
                                                
                                                // Process metadata if available
                                                try {
                                                    if (data.has("meta")) {
                                                        JSONObject meta = data.getJSONObject("meta");
                                                        if (meta.has("t")) {
                                                            imageTimestamp = meta.getLong("t");
                                                        } else if (meta.has("now")) {
                                                            imageTimestamp = meta.getLong("now");
                                                        }
                                                        startTimestampUpdates(activity);
                                                    }
                                                } catch (Exception e) {
                                                    Log.e(TAG, "Error processing metadata in thread: " + e.getMessage());
                                                }
                                            }
                                        }
                                    });
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error decoding base64 image in thread: " + e.getMessage());
                            // Fallback to URL loading if needed, though usually we just log error here
                            // as URL loading is handled below if this block wasn't entered
                        }
                    }
                }).start();
                return; // Skip loading from URL immediately
            }
            
            // Load image and fetch timestamp
            loadWebcamImage(activity, imageUrl, webcamId);
            
        } catch (Exception e) {
            Log.e(TAG, "Webcam display error: " + e.getMessage(), e);
            activity.showError("Webcam display error: " + e.getMessage());
        }
    }

    private void createTimestampOverlay(MainActivity activity) {
        // Remove existing overlay if any
        if (timestampOverlay != null) {
            ViewGroup parent = (ViewGroup) timestampOverlay.getParent();
            if (parent != null) {
                parent.removeView(timestampOverlay);
            }
        }
        
        // Get the root container
        ViewGroup rootContainer = (ViewGroup) activity.getContentContainer().getParent();
        if (rootContainer == null) {
            rootContainer = (ViewGroup) activity.findViewById(android.R.id.content);
        }
        
        // Create timestamp overlay TextView
        timestampOverlay = new TextView(activity);
        timestampOverlay.setId(android.view.View.generateViewId());
        
        // Style the overlay
        timestampOverlay.setBackgroundColor(0xE6000000); // Black with 90% opacity
        timestampOverlay.setTextColor(0xFFFFFFFF); // White text
        timestampOverlay.setTextSize(24); // 24sp
        timestampOverlay.setPadding(24, 16, 24, 16); // 24dp horizontal, 16dp vertical
        timestampOverlay.setGravity(Gravity.CENTER);
        
        // Position in bottom-right corner
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        params.gravity = Gravity.BOTTOM | Gravity.END;
        params.setMargins(0, 0, 24, 24); // 24dp margin from bottom and right
        
        timestampOverlay.setLayoutParams(params);
        timestampOverlay.setVisibility(View.GONE); // Hide until timestamp is loaded
        
        // Add to root container
        if (rootContainer != null) {
            rootContainer.addView(timestampOverlay);
        }
    }

    private void loadWebcamImage(MainActivity activity, String imageUrl, String webcamId) {
        // Load image
        activity.loadImage(imageUrl);
        
        // Check if metadata is already available in the content item
        // This is now injected by the server to avoid extra requests
        try {
            JSONObject view = activity.getContentManager().getCurrentContentItem().getJSONObject("view");
            JSONObject data = view.getJSONObject("data");
            
            if (data.has("meta")) {
                JSONObject meta = data.getJSONObject("meta");
                Log.d(TAG, "Using injected metadata: " + meta.toString());
                
                if (meta.has("t")) {
                    imageTimestamp = meta.getLong("t");
                } else if (meta.has("now")) {
                    imageTimestamp = meta.getLong("now");
                } else {
                    imageTimestamp = System.currentTimeMillis();
                }
                
                // Start updating relative time immediately
                startTimestampUpdates(activity);
                return;
            }
        } catch (Exception e) {
            Log.d(TAG, "No injected metadata found or error parsing: " + e.getMessage());
            // Continue to fetch metadata manually
        }
        
        // Fetch timestamp from webcam metadata (legacy fallback)
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Fetch metadata to get timestamp
                    String metaUrl = activity.getServerUrl() + "/api/webcams/" + webcamId + "/meta";
                    Log.d(TAG, "Fetching metadata from: " + metaUrl);
                    java.net.URL url = new java.net.URL(metaUrl);
                    java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(10000);
                    
                    int responseCode = connection.getResponseCode();
                    if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                        java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(connection.getInputStream(), "UTF-8"));
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                        reader.close();
                        
                        JSONObject metaData = new JSONObject(response.toString());
                        // Get timestamp (either 't' or 'now' field)
                        if (metaData.has("t")) {
                            imageTimestamp = metaData.getLong("t");
                        } else if (metaData.has("now")) {
                            imageTimestamp = metaData.getLong("now");
                        } else {
                            // Fallback to current time
                            imageTimestamp = System.currentTimeMillis();
                        }
                        
                        Log.d(TAG, "Webcam timestamp: " + imageTimestamp);
                        
                        // Start updating relative time
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                startTimestampUpdates(activity);
                            }
                        });
                    } else if (responseCode == java.net.HttpURLConnection.HTTP_NOT_FOUND) {
                        Log.w(TAG, "Webcam image not found (404) - no image uploaded yet");
                        // Use current time as fallback
                        imageTimestamp = System.currentTimeMillis();
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                startTimestampUpdates(activity);
                            }
                        });
                    } else {
                        Log.w(TAG, "Failed to fetch webcam metadata, response code: " + responseCode);
                        // Use current time as fallback
                        imageTimestamp = System.currentTimeMillis();
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                startTimestampUpdates(activity);
                            }
                        });
                    }
                    connection.disconnect();
                } catch (java.net.MalformedURLException e) {
                    Log.e(TAG, "Malformed URL error: " + e.getMessage(), e);
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            activity.showError("Invalid URL: " + e.getMessage());
                        }
                    });
                    imageTimestamp = System.currentTimeMillis();
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            startTimestampUpdates(activity);
                        }
                    });
                } catch (java.net.UnknownHostException e) {
                    Log.e(TAG, "Unknown host error: " + e.getMessage(), e);
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            activity.showError("Cannot reach server: " + e.getMessage());
                        }
                    });
                    imageTimestamp = System.currentTimeMillis();
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            startTimestampUpdates(activity);
                        }
                    });
                } catch (java.net.ConnectException e) {
                    Log.e(TAG, "Connection error: " + e.getMessage(), e);
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            activity.showError("Connection failed: " + e.getMessage());
                        }
                    });
                    imageTimestamp = System.currentTimeMillis();
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            startTimestampUpdates(activity);
                        }
                    });
                } catch (java.net.SocketException e) {
                    String errorMsg = e.getMessage();
                    if (errorMsg != null && errorMsg.contains("ENETUNREACH")) {
                        Log.e(TAG, "Network unreachable: " + errorMsg, e);
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                activity.showError("Network unreachable. Check server IP: " + activity.getServerUrl());
                            }
                        });
                    } else {
                        Log.e(TAG, "Socket error: " + errorMsg, e);
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                activity.showError("Network error: " + errorMsg);
                            }
                        });
                    }
                    imageTimestamp = System.currentTimeMillis();
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            startTimestampUpdates(activity);
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Error fetching webcam metadata: " + e.getMessage(), e);
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            activity.showError("Error: " + e.getMessage());
                        }
                    });
                    // Use current time as fallback
                    imageTimestamp = System.currentTimeMillis();
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            startTimestampUpdates(activity);
                        }
                    });
                }
            }
        }).start();
    }

    private void startTimestampUpdates(MainActivity activity) {
        // Stop any existing updates
        stopTimestampUpdates();
        
        // Show overlay
        if (timestampOverlay != null) {
            timestampOverlay.setVisibility(View.VISIBLE);
        }
        
        // Create handler for updates on main looper
        updateHandler = new Handler(android.os.Looper.getMainLooper());
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                if (timestampOverlay != null && imageTimestamp > 0 && updateHandler != null) {
                    String relativeTime = getRelativeTime(imageTimestamp);
                    timestampOverlay.setText(relativeTime);
                    
                    // Calculate next update interval based on time elapsed
                    long now = System.currentTimeMillis();
                    long diff = now - imageTimestamp;
                    long seconds = diff / 1000;
                    
                    // Update more frequently for recent times, less frequently for older times
                    long updateInterval;
                    if (seconds < 60) {
                        updateInterval = 1000; // Update every second for "just now"
                    } else if (seconds < 3600) {
                        updateInterval = 60000; // Update every minute for "X minutes ago"
                    } else if (seconds < 86400) {
                        updateInterval = 3600000; // Update every hour for "X hours ago"
                    } else {
                        updateInterval = 86400000; // Update daily for "X days ago"
                    }
                    
                    // Schedule next update
                    updateHandler.postDelayed(this, updateInterval);
                }
            }
        };
        
        // Start updates immediately
        if (updateHandler != null) {
            updateHandler.post(updateRunnable);
        }
    }

    private void stopTimestampUpdates() {
        if (updateHandler != null && updateRunnable != null) {
            updateHandler.removeCallbacks(updateRunnable);
        }
        updateHandler = null;
        updateRunnable = null;
    }

    private String getRelativeTime(long timestamp) {
        long now = System.currentTimeMillis();
        long diff = now - timestamp;
        
        long seconds = diff / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        
        if (seconds < 60) {
            return "just now";
        } else if (minutes < 60) {
            return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
        } else if (hours < 24) {
            return hours + (hours == 1 ? " hour ago" : " hours ago");
        } else {
            return days + (days == 1 ? " day ago" : " days ago");
        }
    }

    @Override
    public void hide(MainActivity activity, View container) {
        stopTimestampUpdates();
        
        // Remove timestamp overlay
        if (timestampOverlay != null) {
            ViewGroup parent = (ViewGroup) timestampOverlay.getParent();
            if (parent != null) {
                parent.removeView(timestampOverlay);
            }
            timestampOverlay = null;
        }
        
        // Hide and clear ImageView, recycle bitmap to free memory
        ImageView contentImage = activity.getContentImageView();
        if (contentImage != null) {
            // Recycle bitmap before clearing to prevent memory leaks
            if (contentImage.getDrawable() != null && contentImage.getDrawable() instanceof android.graphics.drawable.BitmapDrawable) {
                android.graphics.drawable.BitmapDrawable bitmapDrawable = (android.graphics.drawable.BitmapDrawable) contentImage.getDrawable();
                android.graphics.Bitmap bitmap = bitmapDrawable.getBitmap();
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
            }
            contentImage.setImageBitmap(null); // Clear image to prevent flashing
            contentImage.setVisibility(View.GONE);
            contentImage.clearAnimation();
        }
        
        currentActivity = null;
    }
}
