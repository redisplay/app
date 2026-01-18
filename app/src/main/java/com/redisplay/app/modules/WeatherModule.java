package com.redisplay.app.modules;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.redisplay.app.ContentModule;
import com.redisplay.app.MainActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;

import com.redisplay.app.utils.GradientHelper;

public class WeatherModule implements ContentModule {
    private static final String TAG = "WeatherModule";
    private Handler updateHandler;
    private Runnable updateRunnable;
    private MainActivity currentActivity;
    private ViewGroup weatherContainer;
    private TextView locationText;
    private TextView currentTempText;
    private TextView minText;
    private TextView maxText;
    private TextView descriptionText;
    private ImageView summaryIcon;
    private LinearLayout hourlyContainer;

    @Override
    public String getType() {
        return "weather";
    }
    
    @Override
    public void display(MainActivity activity, JSONObject contentItem, View container) {
        try {
            currentActivity = activity;

            // Extract view data
            JSONObject view = contentItem.getJSONObject("view");
            JSONObject data = view.getJSONObject("data");
            JSONObject location = data.getJSONObject("location");
            int hoursCount = data.optInt("hoursToShow", 12); // Default to 12 hours

            String locationName = location.getString("name");
            double lat = location.getDouble("lat");
            double lon = location.getDouble("lon");

            Log.d(TAG, "Displaying weather for: " + locationName + " (" + lat + ", " + lon + ")");

            // Hide other views
            activity.getContentWebView().setVisibility(View.GONE);
            activity.getContentImageView().setVisibility(View.GONE);
            activity.getContentTextView().setVisibility(View.GONE);

            // Clear container background first
            container.setBackground(null);
            Log.d(TAG, "Cleared container background");

            // Create weather container with optional background
            JSONObject background = data.optJSONObject("background");
            createWeatherContainer(activity, container, background);
            
            // Show loading state
            showLoading(activity);

            // Fetch weather data
            // Check if weather data is already available in the content item
            boolean weatherDataInjected = false;
            try {
                if (data.has("weather")) {
                    JSONObject weatherData = data.getJSONObject("weather");
                    Log.d(TAG, "Using injected weather data for: " + locationName);
                    displayWeatherData(activity, weatherData, hoursCount);
                    weatherDataInjected = true;
                }
            } catch (Exception e) {
                Log.d(TAG, "No injected weather data or error parsing: " + e.getMessage());
            }
            
            if (!weatherDataInjected) {
                fetchWeatherData(activity, locationName, lat, lon, hoursCount);
            }

        } catch (Exception e) {
            Log.e(TAG, "Weather display error: " + e.getMessage(), e);
            activity.showError("Weather display error: " + e.getMessage());
        }
    }

    private void createWeatherContainer(MainActivity activity, View container, JSONObject background) {
        // Remove existing container if any - force complete removal
        if (weatherContainer != null) {
            ViewGroup parent = (ViewGroup) weatherContainer.getParent();
            if (parent != null) {
                parent.removeView(weatherContainer);
            }
            weatherContainer = null; // Clear reference to force recreation
        }

        // Get root container
        ViewGroup rootContainer = (ViewGroup) container.getParent();
        if (rootContainer == null) {
            rootContainer = (ViewGroup) activity.findViewById(android.R.id.content);
        }

        // Create main container - this will have the gradient background
        weatherContainer = new FrameLayout(activity);
        weatherContainer.setId(android.view.View.generateViewId());
        
        // Apply background to weatherContainer itself (not container)
        if (background != null) {
            Log.d(TAG, "Applying background to weatherContainer: " + background.toString());
            // We'll apply it after adding to parent to ensure proper rendering
        } else {
            Log.d(TAG, "No background configured, using default");
            weatherContainer.setBackgroundColor(0xFF90EE90); // Default light green background
        }
        
        // Set layout params to fill the screen
        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        );
        weatherContainer.setLayoutParams(containerParams);
        weatherContainer.setVisibility(View.VISIBLE);

        // Get screen dimensions
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getMetrics(metrics);
        int screenHeight = metrics.heightPixels;
        
        // Create content layout directly (no ScrollView to avoid centering issues)
        // MUST be transparent to show parent gradient
        LinearLayout contentLayout = new LinearLayout(activity);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setGravity(Gravity.TOP); // Top-aligned to minimize bottom space
        contentLayout.setBackground(null); // Explicitly null - transparent to show gradient
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            screenHeight // Use full screen height
        );
        contentLayout.setLayoutParams(contentParams);
        contentLayout.setPadding(32, 32, 32, 16); // Further reduced padding

        // Top section: Clean, aligned layout - use remaining space after hourly forecast
        LinearLayout topSection = new LinearLayout(activity);
        topSection.setOrientation(LinearLayout.HORIZONTAL);
        topSection.setBackground(null); // Transparent to show gradient
        Log.d(TAG, "Creating new horizontal topSection layout with 30%/60% split");
        LinearLayout.LayoutParams topParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0 // Will use weight to fill remaining space
        );
        topParams.weight = 1.0f; // Take up remaining vertical space
        topSection.setLayoutParams(topParams);
        topSection.setPadding(0, 0, 0, 24); // Reduced spacing between top and hourly forecast
        
        // Left side: Location (30% of width, centered)
        FrameLayout locationLayout = new FrameLayout(activity);
        locationLayout.setBackground(null); // Transparent to show gradient
        LinearLayout.LayoutParams locationLayoutParams = new LinearLayout.LayoutParams(
            0, // Use weight for width
            LinearLayout.LayoutParams.MATCH_PARENT,
            0.3f // 30% of width
        );
        locationLayout.setLayoutParams(locationLayoutParams);
        
        locationText = new TextView(activity);
        locationText.setTextSize(48); // Increased from 36 to 48
        locationText.setTextColor(0xFFFFFFFF);
        locationText.setTypeface(null, Typeface.BOLD);
        locationText.setGravity(Gravity.CENTER); // Center text within TextView
        FrameLayout.LayoutParams locationTextParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        locationTextParams.gravity = Gravity.CENTER; // Center both horizontally and vertically
        locationText.setLayoutParams(locationTextParams);
        locationLayout.addView(locationText);
        
        topSection.addView(locationLayout);
        
        // Center: Current temperature with icon, summary, and min/max (60% of width)
        LinearLayout centerLayout = new LinearLayout(activity);
        centerLayout.setOrientation(LinearLayout.VERTICAL);
        centerLayout.setGravity(Gravity.CENTER);
        centerLayout.setBackground(null); // Transparent to show gradient
        LinearLayout.LayoutParams centerLayoutParams = new LinearLayout.LayoutParams(
            0, // Use weight for width
            LinearLayout.LayoutParams.MATCH_PARENT,
            0.6f // 60% of width
        );
        centerLayout.setLayoutParams(centerLayoutParams);
        
        // Weather icon and temperature (horizontal)
        LinearLayout tempLayout = new LinearLayout(activity);
        tempLayout.setOrientation(LinearLayout.HORIZONTAL);
        tempLayout.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tempLayoutParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        tempLayout.setLayoutParams(tempLayoutParams);
        
        // Weather icon next to temperature (slightly larger)
        summaryIcon = new ImageView(activity);
        summaryIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        summaryIcon.setAdjustViewBounds(true);
        int iconSize = (int) android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 96, activity.getResources().getDisplayMetrics()); // Increased from 80 to 96
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconParams.setMargins(0, 0, 20, 0); // Increased margin
        summaryIcon.setLayoutParams(iconParams);
        tempLayout.addView(summaryIcon);
        
        // Current temperature (slightly larger)
        currentTempText = new TextView(activity);
        currentTempText.setTextSize(108); // Increased from 96 to 108
        currentTempText.setTextColor(0xFFFFFFFF);
        currentTempText.setTypeface(null, Typeface.BOLD);
        tempLayout.addView(currentTempText);
        centerLayout.addView(tempLayout);
        
        // Description (summary) below icon-temp
        descriptionText = new TextView(activity);
        descriptionText.setTextSize(24); // Slightly larger
        descriptionText.setTextColor(0xFFFFFFFF);
        descriptionText.setGravity(Gravity.CENTER);
        descriptionText.setPadding(0, 16, 0, 12); // Added top padding
        centerLayout.addView(descriptionText);
        
        // Min/Max below summary
        LinearLayout minMaxLayout = new LinearLayout(activity);
        minMaxLayout.setOrientation(LinearLayout.HORIZONTAL);
        minMaxLayout.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams minMaxParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        minMaxLayout.setLayoutParams(minMaxParams);
        
        // Min temperature
        minText = new TextView(activity);
        minText.setTextSize(32);
        minText.setTypeface(null, Typeface.BOLD);
        minText.setGravity(Gravity.CENTER);
        minText.setPadding(0, 0, 8, 0);
        minMaxLayout.addView(minText);
        
        // Separator
        TextView separator = new TextView(activity);
        separator.setText("/");
        separator.setTextSize(32);
        separator.setTextColor(0xFFFFFFFF);
        separator.setPadding(8, 0, 8, 0);
        minMaxLayout.addView(separator);
        
        // Max temperature
        maxText = new TextView(activity);
        maxText.setTextSize(32);
        maxText.setTypeface(null, Typeface.BOLD);
        maxText.setGravity(Gravity.CENTER);
        minMaxLayout.addView(maxText);
        
        centerLayout.addView(minMaxLayout);
        topSection.addView(centerLayout);
        
        contentLayout.addView(topSection);

        // Hourly forecast container (no title, all boxes fit in viewport)
        hourlyContainer = new LinearLayout(activity);
        hourlyContainer.setOrientation(LinearLayout.HORIZONTAL);
        hourlyContainer.setGravity(Gravity.CENTER);
        hourlyContainer.setBackground(null); // Transparent to show gradient
        hourlyContainer.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams hourlyParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        hourlyParams.weight = 0; // Don't expand, just use needed space
        hourlyContainer.setLayoutParams(hourlyParams);
        contentLayout.addView(hourlyContainer);

        weatherContainer.addView(contentLayout);

        // Add weatherContainer to the container (contentContainer)
        if (container instanceof ViewGroup) {
            ((ViewGroup) container).addView(weatherContainer);
            
            // Apply background to weatherContainer AFTER adding to parent
            if (background != null) {
                Log.d(TAG, "Applying background to weatherContainer immediately: " + background.toString());
                // Apply immediately to avoid black screen flash
                applyBackground(weatherContainer, background);
            } else {
                // Default background
                weatherContainer.setBackgroundColor(0xFF90EE90); // Default light green background
            }
            
            // Bring to front to ensure it's visible
            weatherContainer.bringToFront();
            // Force layout
            weatherContainer.invalidate();
            weatherContainer.requestLayout();
        }
    }

    private void showLoading(MainActivity activity) {
        if (locationText != null) {
            locationText.setText("Loading weather...");
            locationText.setVisibility(View.VISIBLE);
        }
        if (currentTempText != null) {
            currentTempText.setText("--°");
            currentTempText.setVisibility(View.VISIBLE);
        }
        if (minText != null) {
            minText.setText("--°");
            minText.setVisibility(View.VISIBLE);
        }
        if (maxText != null) {
            maxText.setText("--°");
            maxText.setVisibility(View.VISIBLE);
        }
        if (summaryIcon != null) {
            summaryIcon.setVisibility(View.VISIBLE);
        }
        if (descriptionText != null) {
            descriptionText.setText("");
            descriptionText.setVisibility(View.VISIBLE);
        }
        if (hourlyContainer != null) {
            hourlyContainer.removeAllViews();
            hourlyContainer.setVisibility(View.VISIBLE);
        }
        if (weatherContainer != null) {
            weatherContainer.setVisibility(View.VISIBLE);
        }
    }

    private void fetchWeatherData(MainActivity activity, String locationName, double lat, double lon, int hoursCount) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Build URL with location parameter
                    String locationJson = "{\"name\":\"" + locationName + "\",\"lat\":" + lat + ",\"lon\":" + lon + "}";
                    String encodedLocation = URLEncoder.encode(locationJson, "UTF-8");
                    String weatherUrl = activity.getServerUrl() + "/api/weather?location=" + encodedLocation;
                    
                    Log.d(TAG, "Fetching weather from: " + weatherUrl);
                    
                    URL url = new URL(weatherUrl);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(10000);

                    int responseCode = connection.getResponseCode();
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        BufferedReader reader = new BufferedReader(
                            new InputStreamReader(connection.getInputStream(), "UTF-8"));
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                        reader.close();

                        JSONObject weatherData = new JSONObject(response.toString());
                        
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                displayWeatherData(activity, weatherData, hoursCount);
                            }
                        });
                    } else {
                        final String errorMsg = "HTTP " + responseCode;
                        Log.e(TAG, "HTTP error: " + errorMsg);
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                showInViewError(activity, errorMsg);
                            }
                        });
                    }
                    connection.disconnect();
                } catch (MalformedURLException e) {
                    Log.e(TAG, "Malformed URL error: " + e.getMessage(), e);
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showInViewError(activity, "Invalid URL");
                        }
                    });
                } catch (UnknownHostException e) {
                    Log.e(TAG, "Unknown host error: " + e.getMessage(), e);
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showInViewError(activity, "Server unreachable");
                        }
                    });
                } catch (ConnectException e) {
                    Log.e(TAG, "Connection error: " + e.getMessage(), e);
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showInViewError(activity, "Connection failed");
                        }
                    });
                } catch (SocketTimeoutException e) {
                    Log.e(TAG, "Timeout error: " + e.getMessage(), e);
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showInViewError(activity, "Timeout");
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Error fetching weather: " + e.getMessage(), e);
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showInViewError(activity, "Error: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void showInViewError(MainActivity activity, String message) {
        if (descriptionText != null) {
            // Keep it short
            if (message.length() > 30) message = message.substring(0, 27) + "...";
            descriptionText.setText("⚠️ " + message);
        }
        if (locationText != null && (locationText.getText().toString().equals("Loading weather...") || locationText.getText().toString().isEmpty())) {
             locationText.setText("Weather Unavailable");
        }
        if (hourlyContainer != null) {
            hourlyContainer.removeAllViews();
        }
        if (currentTempText != null) {
            currentTempText.setText("--");
        }
        // Ensure container is visible so we don't show black screen or global error
        if (weatherContainer != null) {
             weatherContainer.setVisibility(View.VISIBLE);
        }
    }

    private void displayWeatherData(MainActivity activity, JSONObject weatherData, int hoursCount) {
        try {
            JSONObject location = weatherData.getJSONObject("location");
            JSONObject current = weatherData.getJSONObject("current");
            JSONArray hours = weatherData.getJSONArray("hours");

            // Update location
            if (locationText != null) {
                locationText.setText(location.getString("name"));
            }
            // Note: locationSubtext is set from the view config, not from API response

            // Update current temperature
            if (currentTempText != null) {
                int temp = current.getInt("temp");
                currentTempText.setText(temp + "°");
            }

            // Update min/max with temperature-based color coding
            if (minText != null) {
                int tempMin = current.getInt("tempMin");
                minText.setText(tempMin + "°");
                minText.setTextColor(getTemperatureColor(tempMin));
            }
            if (maxText != null) {
                int tempMax = current.getInt("tempMax");
                maxText.setText(tempMax + "°");
                maxText.setTextColor(getTemperatureColor(tempMax));
            }
            
            // Update description and icon (description is now in center section)
            if (descriptionText != null) {
                String description = current.getString("description");
                descriptionText.setText(description);
            }
            if (summaryIcon != null) {
                int weatherCode = current.getInt("weatherCode");
                summaryIcon.setImageDrawable(getWeatherIconDrawable(activity, weatherCode));
            }

            // Update hourly forecast
            if (hourlyContainer != null) {
                hourlyContainer.removeAllViews();
                
                int count = Math.min(hours.length(), hoursCount);
                for (int i = 0; i < count; i++) {
                    JSONObject hour = hours.getJSONObject(i);
                    View hourBox = createHourBox(activity, hour);
                    hourlyContainer.addView(hourBox);
                }
            }

            if (weatherContainer != null) {
                weatherContainer.setVisibility(View.VISIBLE);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error displaying weather data: " + e.getMessage(), e);
            activity.showError("Error displaying weather: " + e.getMessage());
        }
    }

    private View createHourBox(MainActivity activity, JSONObject hour) {
        try {
            LinearLayout box = new LinearLayout(activity);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setPadding(12, 8, 12, 8); // Reduced padding in hour boxes
            // No background - removed as requested
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, // Use 0 width with weight to distribute evenly
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f // Equal weight so all boxes fit
            );
            params.setMargins(8, 0, 8, 0);
            box.setLayoutParams(params);

            // Time format (12h format with AM/PM)
            TextView timeText = new TextView(activity);
            String timeLabel = formatShortTime(hour.getString("time"), hour.getInt("hour"));
            timeText.setText(timeLabel);
            timeText.setTextSize(24); // Slightly smaller to fit AM/PM
            timeText.setTextColor(0xFFFFFFFF);
            timeText.setGravity(Gravity.CENTER);
            timeText.setPadding(0, 0, 0, 20); // Increased vertical padding
            box.addView(timeText);

            // Weather icon (larger for kiosk)
            ImageView iconView = new ImageView(activity);
            int weatherCode = hour.getInt("weatherCode");
            iconView.setImageDrawable(getWeatherIconDrawable(activity, weatherCode));
            iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iconView.setAdjustViewBounds(true);
            int iconSize = (int) android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 56, activity.getResources().getDisplayMetrics());
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                iconSize,
                iconSize
            );
            iconParams.gravity = Gravity.CENTER;
            iconParams.setMargins(0, 0, 0, 20); // Increased vertical padding
            iconView.setLayoutParams(iconParams);
            box.addView(iconView);

            // Temperature (much larger for kiosk display)
            TextView tempText = new TextView(activity);
            int temp = hour.getInt("temp");
            tempText.setText(temp + "°");
            tempText.setTextSize(42); // Much larger for kiosk
            tempText.setTextColor(getTemperatureColor(temp)); // Color based on temperature value
            tempText.setTypeface(null, Typeface.BOLD);
            tempText.setGravity(Gravity.CENTER);
            tempText.setPadding(0, 0, 0, 0);
            box.addView(tempText);

            return box;
        } catch (org.json.JSONException e) {
            Log.e(TAG, "Error creating hour box: " + e.getMessage(), e);
            // Return empty view on error
            return new View(activity);
        }
    }

    private String formatShortTime(String timeStr, int hour) {
        try {
            // Calculate hours from now
            long now = System.currentTimeMillis();
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.getDefault());
            java.util.Date hourDate = sdf.parse(timeStr);
            
            if (hourDate == null) {
                // Fallback to 12h format
                return format12Hour(hour);
            }
            
            long diff = hourDate.getTime() - now;
            long hoursFromNow = diff / (1000 * 60 * 60);
            long minutesFromNow = (diff % (1000 * 60 * 60)) / (1000 * 60);
            
            if (hoursFromNow == 0 && minutesFromNow < 30) {
                return "now";
            } else {
                // Use 12h format
                java.text.SimpleDateFormat outputFormat = new java.text.SimpleDateFormat("h a", java.util.Locale.US);
                return outputFormat.format(hourDate);
            }
        } catch (Exception e) {
            // Fallback to 12h format
            return format12Hour(hour);
        }
    }
    
    private String format12Hour(int hour) {
        if (hour == 0) return "12 AM";
        if (hour == 12) return "12 PM";
        if (hour > 12) return (hour - 12) + " PM";
        return hour + " AM";
    }
    
    private int getTemperatureColor(int temperature) {
        // Color gradient from cold (blue) to hot (red) based on temperature value
        // Range: -10°C (dark blue) to 40°C (red)
        float minTemp = -10.0f;
        float maxTemp = 40.0f;
        float normalized = (temperature - minTemp) / (maxTemp - minTemp);
        normalized = Math.max(0.0f, Math.min(1.0f, normalized)); // Clamp between 0 and 1
        
        // Interpolate between blue and red
        int r, g, b;
        if (normalized < 0.5f) {
            // Blue to cyan (cold to cool)
            float t = normalized * 2.0f;
            r = (int) (0 + t * 0);
            g = (int) (100 + t * 155);
            b = (int) (200 + t * 55);
        } else {
            // Cyan to yellow to red (cool to warm to hot)
            float t = (normalized - 0.5f) * 2.0f;
            if (t < 0.5f) {
                // Cyan to yellow
                float t2 = t * 2.0f;
                r = (int) (0 + t2 * 255);
                g = 255;
                b = (int) (255 - t2 * 255);
            } else {
                // Yellow to red
                float t2 = (t - 0.5f) * 2.0f;
                r = 255;
                g = (int) (255 - t2 * 255);
                b = 0;
            }
        }
        
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private android.graphics.drawable.Drawable getWeatherIconDrawable(MainActivity activity, int weatherCode) {
        // Try to load specific weather icons from drawable resources
        // If you have separate icons for different weather conditions, name them:
        // weather_clear.png, weather_partly_cloudy.png, weather_rain.png, etc.
        // and update the mapping below
        
        try {
            android.content.res.Resources resources = activity.getResources();
            String resourceName = getWeatherIconResourceName(weatherCode);
            int resourceId = resources.getIdentifier(resourceName, "drawable", activity.getPackageName());
            
            if (resourceId != 0) {
                // Use specific icon if available
                return resources.getDrawable(resourceId);
            } else {
                // Fallback to generic weather icon
                int genericId = resources.getIdentifier("weather_icon", "drawable", activity.getPackageName());
                if (genericId != 0) {
                    return resources.getDrawable(genericId);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading weather icon: " + e.getMessage(), e);
        }
        
        // Final fallback: colored circle
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        drawable.setColor(0xFF87CEEB); // Default sky blue
        return drawable;
    }
    
    private void applyBackground(View container, JSONObject background) {
        GradientHelper.applyBackground(container, background);
    }
    
    // Removed duplicate gradient logic in favor of GradientHelper
    // applyGradientBackground and parseColor methods removed

    private String getWeatherIconResourceName(int weatherCode) {
        // Map WMO weather codes to Google weather icon resource names
        // Icons downloaded from: https://developers.google.com/maps/documentation/weather/weather-condition-icons
        // Format: https://maps.gstatic.com/weather/v1/{icon_name}.svg
        // The getWeatherIconDrawable method will handle fallbacks if icons don't exist
        if (weatherCode == 0) return "weather_clear"; // Clear sky -> sunny
        else if (weatherCode == 1) return "weather_mainly_clear"; // Mainly clear -> mostly_sunny
        else if (weatherCode == 2) return "weather_partly_cloudy"; // Partly cloudy -> partly_cloudy
        else if (weatherCode == 3) return "weather_overcast"; // Overcast -> cloudy
        else if (weatherCode >= 45 && weatherCode <= 48) return "weather_overcast"; // Fog (use overcast, fog icon not available)
        else if (weatherCode >= 51 && weatherCode <= 57) return "weather_showers"; // Drizzle (use showers, rain icon not available)
        else if (weatherCode >= 61 && weatherCode <= 67) return "weather_showers"; // Rain (use showers, rain icon not available)
        else if (weatherCode >= 71 && weatherCode <= 77) return "weather_snow"; // Snow
        else if (weatherCode >= 80 && weatherCode <= 86) return "weather_showers"; // Showers -> scattered_showers
        else if (weatherCode >= 95 && weatherCode <= 99) return "weather_thunderstorm"; // Thunderstorm -> strong_tstorms
        else return "weather_clear"; // Default to clear
    }
    
    @Override
    public void hide(MainActivity activity, View container) {
        // Stop any updates
        if (updateHandler != null && updateRunnable != null) {
            updateHandler.removeCallbacks(updateRunnable);
        }
        updateHandler = null;
        updateRunnable = null;

        // Remove weather container
        if (weatherContainer != null) {
            ViewGroup parent = (ViewGroup) weatherContainer.getParent();
            if (parent != null) {
                parent.removeView(weatherContainer);
            }
            weatherContainer = null;
        }

        locationText = null;
        currentTempText = null;
        minText = null;
        maxText = null;
        descriptionText = null;
        summaryIcon = null;
        hourlyContainer = null;
        currentActivity = null;
    }
}
