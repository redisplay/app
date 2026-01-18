package com.redisplay.app.modules;

import android.graphics.Color;
import android.graphics.Typeface;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.redisplay.app.ContentModule;
import com.redisplay.app.MainActivity;
import org.json.JSONObject;

public class GalleryModule implements ContentModule {
    private static final String TAG = "GalleryModule";
    private TextView captionView;
    
    @Override
    public String getType() {
        return "gallery";
    }
    
    @Override
    public void display(MainActivity activity, JSONObject contentItem, View container) {
        try {
            // Extract view data
            JSONObject view = contentItem.getJSONObject("view");
            JSONObject data = view.getJSONObject("data");
            
            String imageUrl = data.optString("imageUrl", "");
            String caption = data.optString("caption", "");
            String scaleType = data.optString("scaleType", "fitCenter");
            
            Log.d(TAG, "Displaying gallery image - URL: " + imageUrl);
            Log.d(TAG, "Caption: " + caption);
            Log.d(TAG, "Scale type: " + scaleType);
            
            // Get ImageView
            ImageView contentImage = activity.getContentImageView();
            if (contentImage == null) {
                Log.e(TAG, "ImageView is null");
                return;
            }
            
            // Hide WebView if visible
            if (activity.getContentWebView() != null) {
                activity.getContentWebView().setVisibility(View.GONE);
            }
            
            // Set scale type
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
                finalScaleType = ImageView.ScaleType.FIT_CENTER;
            }
            
            contentImage.setScaleType(finalScaleType);
            contentImage.setAdjustViewBounds(true);
            Log.d(TAG, "Set scale type to: " + finalScaleType);
            
            // Display caption if present
            if (caption != null && !caption.isEmpty()) {
                displayCaption(activity, container, caption);
            } else {
                // Hide caption if no caption text
                hideCaption();
            }
            
            // Optimization: Check for base64 image injected by server
            if (data.has("image")) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // First, recycle old bitmap synchronously on UI thread
                            final Object lock = new Object();
                            activity.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    synchronized (lock) {
                                        ImageView img = activity.getContentImageView();
                                        if (img != null && img.getDrawable() != null && img.getDrawable() instanceof android.graphics.drawable.BitmapDrawable) {
                                            android.graphics.drawable.BitmapDrawable bitmapDrawable = (android.graphics.drawable.BitmapDrawable) img.getDrawable();
                                            android.graphics.Bitmap oldBitmap = bitmapDrawable.getBitmap();
                                            if (oldBitmap != null && !oldBitmap.isRecycled()) {
                                                Log.d(TAG, "Recycling old bitmap before decoding new one");
                                                oldBitmap.recycle();
                                            }
                                            img.setImageBitmap(null);
                                        }
                                        lock.notify();
                                    }
                                }
                            });
                            
                            // Wait for recycling to complete
                            synchronized (lock) {
                                lock.wait(500); // Max 500ms wait
                            }
                            
                            // Force garbage collection to free memory
                            System.gc();
                            Thread.sleep(100);
                            
                            long startDecode = System.currentTimeMillis();
                            JSONObject imageObj = data.getJSONObject("image");
                            String base64 = imageObj.getString("base64");
                            
                            if (base64 != null && !base64.isEmpty()) {
                                Log.d(TAG, "[Perf] Base64 string length: " + base64.length());
                                
                                final byte[] decodedString = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
                                long afterDecode = System.currentTimeMillis();
                                Log.d(TAG, "[Perf] Base64 decode took: " + (afterDecode - startDecode) + "ms");
                                
                                // Use inSampleSize to reduce memory usage
                                android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
                                options.inJustDecodeBounds = true;
                                android.graphics.BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length, options);
                                
                                // Calculate inSampleSize - decode at lower resolution if very large
                                int maxDimension = 1024;
                                int inSampleSize = 1;
                                if (options.outHeight > maxDimension || options.outWidth > maxDimension) {
                                    final int heightRatio = Math.round((float) options.outHeight / (float) maxDimension);
                                    final int widthRatio = Math.round((float) options.outWidth / (float) maxDimension);
                                    inSampleSize = heightRatio < widthRatio ? heightRatio : widthRatio;
                                }
                                
                                options.inSampleSize = inSampleSize;
                                options.inJustDecodeBounds = false;
                                options.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565; // Use less memory
                                
                                final android.graphics.Bitmap decodedByte = android.graphics.BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length, options);
                                long afterBitmap = System.currentTimeMillis();
                                Log.d(TAG, "[Perf] Bitmap decode took: " + (afterBitmap - afterDecode) + "ms (sampleSize: " + inSampleSize + ")");
                                
                                if (decodedByte != null) {
                                    final long totalTime = System.currentTimeMillis() - startDecode;
                                    activity.runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            ImageView img = activity.getContentImageView();
                                            if (img != null) {
                                                img.setImageBitmap(decodedByte);
                                                img.setVisibility(View.VISIBLE);
                                                Log.d(TAG, "[Perf] Gallery image displayed from base64. Total time: " + totalTime + "ms");
                                            }
                                        }
                                    });
                                } else {
                                    Log.e(TAG, "Failed to decode bitmap from base64");
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error decoding base64 image in thread: " + e.getMessage(), e);
                            // Fallback to URL loading
                            activity.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Log.d(TAG, "Falling back to URL loading: " + imageUrl);
                                    if (imageUrl != null && !imageUrl.isEmpty()) {
                                        activity.loadImage(imageUrl);
                                    }
                                }
                            });
                        }
                    }
                }).start();
                return; // Skip loading from URL immediately
            }
            
            // Load the image from URL (fallback if no base64)
            if (imageUrl != null && !imageUrl.isEmpty()) {
                Log.d(TAG, "Loading image from URL: " + imageUrl);
                activity.loadImage(imageUrl);
            } else {
                Log.w(TAG, "No image URL provided and no base64 image data");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Gallery display error: " + e.getMessage(), e);
            activity.showError("Gallery display error: " + e.getMessage());
        }
    }
    
    private void displayCaption(MainActivity activity, View container, String caption) {
        try {
            // Get or create caption view
            if (captionView == null) {
                captionView = new TextView(activity);
                captionView.setId(View.generateViewId());
                captionView.setTextColor(Color.WHITE);
                captionView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
                captionView.setTypeface(null, Typeface.BOLD);
                captionView.setGravity(Gravity.CENTER);
                captionView.setPadding(32, 24, 32, 24);
                captionView.setBackgroundColor(Color.argb(180, 0, 0, 0)); // Semi-transparent black
                captionView.setShadowLayer(8.0f, 0f, 0f, Color.BLACK);
                
                // Add to container (FrameLayout or LinearLayout)
                if (container instanceof android.widget.FrameLayout) {
                    android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                    );
                    params.gravity = Gravity.BOTTOM;
                    ((android.widget.FrameLayout) container).addView(captionView, params);
                    Log.d(TAG, "Caption added to FrameLayout");
                } else if (container instanceof LinearLayout) {
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    );
                    params.gravity = Gravity.BOTTOM;
                    ((LinearLayout) container).addView(captionView, params);
                    Log.d(TAG, "Caption added to LinearLayout");
                }
            }
            
            captionView.setText(caption);
            captionView.setVisibility(View.VISIBLE);
            captionView.bringToFront(); // Ensure caption is on top
            
            Log.d(TAG, "Caption displayed: " + caption);
        } catch (Exception e) {
            Log.e(TAG, "Error displaying caption: " + e.getMessage(), e);
        }
    }
    
    private void hideCaption() {
        if (captionView != null) {
            captionView.setVisibility(View.GONE);
        }
    }
    
    @Override
    public void hide(MainActivity activity, View container) {
        // Hide caption
        hideCaption();
        
        // Clean up image
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
            contentImage.setImageBitmap(null);
            contentImage.setVisibility(View.GONE);
            contentImage.clearAnimation();
        }
        
        // Remove caption view from container
        if (captionView != null) {
            if (container instanceof android.widget.FrameLayout) {
                ((android.widget.FrameLayout) container).removeView(captionView);
            } else if (container instanceof LinearLayout) {
                ((LinearLayout) container).removeView(captionView);
            }
            captionView = null;
        }
    }
}

