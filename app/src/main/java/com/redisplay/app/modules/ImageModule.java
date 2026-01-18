package com.redisplay.app.modules;

import android.util.Log;
import android.view.View;
import android.webkit.WebView;
import android.widget.ImageView;
import com.redisplay.app.ContentModule;
import com.redisplay.app.MainActivity;
import org.json.JSONObject;

public class ImageModule implements ContentModule {
    private static final String TAG = "ImageModule";
    @Override
    public String getType() {
        return "image";
    }
    
    @Override
    public void display(MainActivity activity, JSONObject contentItem, View container) {
        try {
            // Extract view data
            JSONObject view = contentItem.getJSONObject("view");
            JSONObject data = view.getJSONObject("data");
            
            String imageUrl = data.getString("url");
            String scaleType = data.optString("scaleType", "fitCenter");
            
            Log.d(TAG, "Displaying image - URL: " + imageUrl);
            Log.d(TAG, "Scale type: " + scaleType);
            
            // URL is already proxied by the server (transparent to client)
            // Server automatically converts WebP to JPG for Android 4.2.2 compatibility
            // Detect image format: SVG uses WebView, JPG/PNG/WebP use ImageView
            boolean isSvg = isSvgImage(imageUrl);
            Log.d(TAG, "Is SVG: " + isSvg);
            
            if (isSvg) {
                // Use WebView for SVG images
                Log.d(TAG, "Loading SVG image via WebView");
                WebView contentWebView = activity.getContentWebView();
                if (contentWebView != null) {
                    // Hide ImageView
                    ImageView contentImage = activity.getContentImageView();
                    if (contentImage != null) {
                        contentImage.setVisibility(View.GONE);
                    }
                    
                    // Show WebView and load SVG (URL already proxied by server)
                    contentWebView.setVisibility(View.VISIBLE);
                    
                    // Create HTML to display SVG with proper scaling
                    String html = "<!DOCTYPE html><html><head>" +
                        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
                        "<style>" +
                        "body { margin: 0; padding: 0; background: black; display: flex; align-items: center; justify-content: center; height: 100vh; }" +
                        "img { max-width: 100%; max-height: 100%; width: auto; height: auto; }" +
                        "</style></head><body>" +
                        "<img src=\"" + imageUrl + "\" alt=\"SVG Image\" />" +
                        "</body></html>";
                    
                    Log.d(TAG, "Loading SVG HTML into WebView");
                    contentWebView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
                } else {
                    Log.e(TAG, "WebView is null, cannot display SVG");
                }
            } else {
                // Use ImageView for bitmap images (PNG, JPEG, WebP converted to JPG by server)
                Log.d(TAG, "Loading bitmap image via ImageView");
                ImageView contentImage = activity.getContentImageView();
                if (contentImage != null) {
                    // Hide WebView
                    WebView contentWebView = activity.getContentWebView();
                    if (contentWebView != null) {
                        contentWebView.setVisibility(View.GONE);
                    }
                    
                    // Set scale type if specified - ensure it's applied
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
                    
                    contentImage.setScaleType(finalScaleType);
                    contentImage.setAdjustViewBounds(true); // Allow ImageView to adjust its bounds
                    Log.d(TAG, "Set scale type to: " + finalScaleType + " (requested: " + scaleType + ")");
                    Log.d(TAG, "ImageView size: " + contentImage.getWidth() + "x" + contentImage.getHeight());
                    
                    Log.d(TAG, "Calling loadImage with URL: " + imageUrl);
                    // Load the image (URL already proxied by server)
                    activity.loadImage(imageUrl);
                } else {
                    Log.e(TAG, "ImageView is null, cannot display bitmap image");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Image display error: " + e.getMessage(), e);
            activity.showError("Image display error: " + e.getMessage());
        }
    }
    
    @Override
    public void hide(MainActivity activity, View container) {
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
        WebView contentWebView = activity.getContentWebView();
        if (contentWebView != null) {
            contentWebView.loadUrl("about:blank"); // Clear WebView content
            contentWebView.clearCache(true); // Clear WebView cache to free memory
            contentWebView.clearHistory(); // Clear history
            contentWebView.setVisibility(View.GONE);
            contentWebView.clearAnimation();
        }
    }
    
    /**
     * Detects if the image URL is an SVG image
     * Checks for .svg extension in the URL path (handles proxied URLs too)
     */
    private boolean isSvgImage(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        
        String lowerUrl = url.toLowerCase();
        
        // Check if URL ends with .svg or contains .svg in the path
        // Handle both direct URLs and proxied URLs
        // Proxied URL format: http://server:port/api/proxy/image/https://example.com/image.svg
        if (lowerUrl.endsWith(".svg") || 
            lowerUrl.endsWith(".svg?") || 
            lowerUrl.contains(".svg?")) {
            return true;
        }
        
        // Check for .svg in the path (even if there are query parameters)
        // Extract the path part before query parameters
        int queryIndex = lowerUrl.indexOf('?');
        String pathPart = queryIndex > 0 ? lowerUrl.substring(0, queryIndex) : lowerUrl;
        
        // Check if path contains .svg
        if (pathPart.contains(".svg")) {
            // Make sure it's actually a file extension, not part of a domain name
            int svgIndex = pathPart.indexOf(".svg");
            if (svgIndex > 0) {
                // Check if there's a slash before .svg (indicating it's in the path)
                String beforeSvg = pathPart.substring(0, svgIndex);
                if (beforeSvg.contains("/") || beforeSvg.contains("%2f") || beforeSvg.contains("%2F")) {
                    return true;
                }
            }
        }
        
        return false;
    }
}

