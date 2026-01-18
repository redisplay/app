package com.redisplay.app.modules;

import android.graphics.Typeface;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.redisplay.app.ContentModule;
import com.redisplay.app.MainActivity;
import com.redisplay.app.utils.GradientHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

public class WeatherForecastModule implements ContentModule {
    private static final String TAG = "WeatherForecastModule";
    private MainActivity currentActivity;
    private ViewGroup forecastContainer;
    private TextView locationText;
    private LinearLayout daysContainer;
    private TextView errorText;

    @Override
    public String getType() {
        return "weather_forecast";
    }

    @Override
    public void display(MainActivity activity, JSONObject contentItem, View container) {
        try {
            currentActivity = activity;

            // Extract view data
            JSONObject view = contentItem.getJSONObject("view");
            JSONObject data = view.getJSONObject("data");
            JSONObject location = data.getJSONObject("location");
            
            String locationName = location.getString("name");
            double lat = location.getDouble("lat");
            double lon = location.getDouble("lon");

            Log.d(TAG, "Displaying forecast for: " + locationName);

            // Hide other views
            activity.getContentWebView().setVisibility(View.GONE);
            activity.getContentImageView().setVisibility(View.GONE);
            activity.getContentTextView().setVisibility(View.GONE);

            // Clear container background
            container.setBackground(null);

            // Create container
            JSONObject background = data.optJSONObject("background");
            createForecastContainer(activity, container, background);

            // Show location name
            if (locationText != null) {
                locationText.setText(locationName);
            }

            // Check if data is already injected
            boolean weatherDataInjected = false;
            try {
                if (data.has("weather")) {
                    JSONObject weatherData = data.getJSONObject("weather");
                    Log.d(TAG, "Using injected weather data for forecast: " + locationName);
                    displayForecastData(activity, weatherData);
                    weatherDataInjected = true;
                }
            } catch (Exception e) {
                Log.d(TAG, "No injected weather data or error parsing: " + e.getMessage());
            }

            if (!weatherDataInjected) {
                fetchWeatherData(activity, locationName, lat, lon);
            }

        } catch (Exception e) {
            Log.e(TAG, "Forecast display error: " + e.getMessage(), e);
            activity.showError("Forecast display error: " + e.getMessage());
        }
    }

    private void createForecastContainer(MainActivity activity, View container, JSONObject background) {
        if (forecastContainer != null) {
            ViewGroup parent = (ViewGroup) forecastContainer.getParent();
            if (parent != null) {
                parent.removeView(forecastContainer);
            }
            forecastContainer = null;
        }

        // Main container
        forecastContainer = new FrameLayout(activity);
        forecastContainer.setId(android.view.View.generateViewId());

        if (background != null) {
            GradientHelper.applyBackground(forecastContainer, background);
        } else {
            forecastContainer.setBackgroundColor(0xFF333333);
        }

        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        );
        forecastContainer.setLayoutParams(containerParams);
        forecastContainer.setVisibility(View.VISIBLE);

        // Content Layout
        LinearLayout contentLayout = new LinearLayout(activity);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setGravity(Gravity.CENTER_HORIZONTAL);
        contentLayout.setBackground(null);
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        );
        contentLayout.setLayoutParams(contentParams);
        contentLayout.setPadding(24, 24, 24, 24);

        // Location Text (Top/Center)
        locationText = new TextView(activity);
        locationText.setTextSize(48);
        locationText.setTextColor(0xFFFFFFFF);
        locationText.setTypeface(null, Typeface.BOLD);
        locationText.setGravity(Gravity.CENTER);
        locationText.setPadding(0, 0, 0, 24); // Bottom padding to separate from list
        contentLayout.addView(locationText);

        // Error Text (Hidden by default)
        errorText = new TextView(activity);
        errorText.setTextSize(24);
        errorText.setTextColor(0xFFFF5555);
        errorText.setGravity(Gravity.CENTER);
        errorText.setVisibility(View.GONE);
        contentLayout.addView(errorText);

        // Days Container (Vertical list)
        daysContainer = new LinearLayout(activity);
        daysContainer.setOrientation(LinearLayout.VERTICAL);
        daysContainer.setGravity(Gravity.CENTER_HORIZONTAL);
        daysContainer.setBackground(null);
        
        // Calculate 80% of screen width
        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int containerWidth = (int) (screenWidth * 0.8);
        
        LinearLayout.LayoutParams daysParams = new LinearLayout.LayoutParams(
            containerWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        daysContainer.setLayoutParams(daysParams);
        contentLayout.addView(daysContainer);

        forecastContainer.addView(contentLayout);

        if (container instanceof ViewGroup) {
            ((ViewGroup) container).addView(forecastContainer);
            forecastContainer.bringToFront();
        }
    }

    private void fetchWeatherData(final MainActivity activity, final String locationName, final double lat, final double lon) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String locationJson = "{\"name\":\"" + locationName + "\",\"lat\":" + lat + ",\"lon\":" + lon + "}";
                    String encodedLocation = URLEncoder.encode(locationJson, "UTF-8");
                    String weatherUrl = activity.getServerUrl() + "/api/weather?location=" + encodedLocation;

                    Log.d(TAG, "Fetching forecast from: " + weatherUrl);

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

                        final JSONObject weatherData = new JSONObject(response.toString());
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                displayForecastData(activity, weatherData);
                            }
                        });
                    } else {
                        final String errorMsg = "HTTP " + responseCode;
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                showError(errorMsg);
                            }
                        });
                    }
                    connection.disconnect();
                } catch (Exception e) {
                    Log.e(TAG, "Error fetching forecast: " + e.getMessage());
                    final String errorMsg = e.getMessage();
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showError("Error: " + errorMsg);
                        }
                    });
                }
            }
        }).start();
    }

    private void showError(String message) {
        if (errorText != null) {
            errorText.setText(message);
            errorText.setVisibility(View.VISIBLE);
        }
    }

    private void displayForecastData(MainActivity activity, JSONObject weatherData) {
        try {
            if (daysContainer != null) {
                daysContainer.removeAllViews();
            }
            if (errorText != null) {
                errorText.setVisibility(View.GONE);
            }

            JSONArray days = weatherData.getJSONArray("days");
            
            // Limit to 5 days max to fit screen
            int count = Math.min(days.length(), 5);
            
            for (int i = 0; i < count; i++) {
                JSONObject day = days.getJSONObject(i);
                View dayRow = createDayRow(activity, day);
                daysContainer.addView(dayRow);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error displaying forecast data: " + e.getMessage(), e);
            showError("Data error: " + e.getMessage());
        }
    }

    private View createDayRow(MainActivity activity, JSONObject day) {
        try {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            // Add some background to separate rows slightly if needed, or just padding
            row.setPadding(8, 8, 8, 8);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, 0, 0, 8); // Margin between rows
            row.setLayoutParams(params);

            // Column 1: Day + Date merged (Vertical Layout)
            LinearLayout dateContainer = new LinearLayout(activity);
            dateContainer.setOrientation(LinearLayout.VERTICAL);
            dateContainer.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
            LinearLayout.LayoutParams dateContainerParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f
            );
            dateContainer.setLayoutParams(dateContainerParams);

            // Day Name (e.g., Monday)
            TextView dayText = new TextView(activity);
            String dayLabel = day.optString("day", ""); // Should be full name now
            dayText.setText(dayLabel);
            dayText.setTextSize(26);
            dayText.setTextColor(0xFFFFFFFF);
            dayText.setTypeface(null, Typeface.BOLD);
            dateContainer.addView(dayText);

            // Date (e.g., Oct 25)
            TextView dateText = new TextView(activity);
            String dateLabel = day.getString("date");
            dateText.setText(dateLabel);
            dateText.setTextSize(18);
            dateText.setTextColor(0xFFCCCCCC); // Light gray
            dateContainer.addView(dateText);

            row.addView(dateContainer);

            // Column 2: Icon + Summary (Vertical Layout) - Center
            LinearLayout centerContainer = new LinearLayout(activity);
            centerContainer.setOrientation(LinearLayout.VERTICAL);
            centerContainer.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams centerContainerParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.8f // More weight for summary
            );
            centerContainer.setLayoutParams(centerContainerParams);

            // Weather Icon
            ImageView iconView = new ImageView(activity);
            int weatherCode = day.getInt("weatherCode");
            iconView.setImageDrawable(getWeatherIconDrawable(activity, weatherCode));
            iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iconView.setAdjustViewBounds(true);
            int iconSize = (int) android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 42, activity.getResources().getDisplayMetrics());
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSize, iconSize);
            iconView.setLayoutParams(iconParams);
            centerContainer.addView(iconView);
            
            // Summary Text
            TextView summaryText = new TextView(activity);
            String summary = day.optString("weatherDescription", "");
            summaryText.setText(summary);
            summaryText.setTextSize(16);
            summaryText.setTextColor(0xFFEEEEEE);
            summaryText.setGravity(Gravity.CENTER);
            summaryText.setSingleLine(true);
            summaryText.setEllipsize(android.text.TextUtils.TruncateAt.END);
            centerContainer.addView(summaryText);
            
            row.addView(centerContainer);

            // Column 3: Min/Max Temp + Humidity (Vertical) - Right
            LinearLayout tempContainer = new LinearLayout(activity);
            tempContainer.setOrientation(LinearLayout.VERTICAL);
            tempContainer.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
            LinearLayout.LayoutParams tempContainerParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f
            );
            tempContainer.setLayoutParams(tempContainerParams);

            // Min / Max Temp (Colorized)
            // Use SpannableString for different colors? Or Horizontal layout.
            // Let's use Horizontal layout for Min / Max inside the vertical container
            LinearLayout tempRow = new LinearLayout(activity);
            tempRow.setOrientation(LinearLayout.HORIZONTAL);
            tempRow.setGravity(Gravity.RIGHT);
            
            int min = day.getInt("tempMin");
            int max = day.getInt("tempMax");
            
            TextView minText = new TextView(activity);
            minText.setText(min + "°");
            minText.setTextSize(26);
            minText.setTextColor(getTemperatureColor(min));
            minText.setTypeface(null, Typeface.BOLD);
            tempRow.addView(minText);
            
            TextView sepText = new TextView(activity);
            sepText.setText(" / ");
            sepText.setTextSize(26);
            sepText.setTextColor(0xFFFFFFFF);
            tempRow.addView(sepText);
            
            TextView maxText = new TextView(activity);
            maxText.setText(max + "°");
            maxText.setTextSize(26);
            maxText.setTextColor(getTemperatureColor(max));
            maxText.setTypeface(null, Typeface.BOLD);
            tempRow.addView(maxText);
            
            tempContainer.addView(tempRow);
            
            // Humidity
            TextView humidityText = new TextView(activity);
            String humidity = day.optString("humidity", "");
            if (!humidity.isEmpty()) {
                humidityText.setText("💧 " + humidity);
                humidityText.setTextSize(16);
                humidityText.setTextColor(0xFFAAAAFF); // Light blueish
                humidityText.setGravity(Gravity.RIGHT);
                tempContainer.addView(humidityText);
            }

            row.addView(tempContainer);

            return row;
        } catch (Exception e) {
            return new View(activity);
        }
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
        // Reuse similar logic from WeatherModule, possibly refactor into util if strict duplication check required
        // For now simple duplicate for speed
        try {
            android.content.res.Resources resources = activity.getResources();
            String resourceName = getWeatherIconResourceName(weatherCode);
            int resourceId = resources.getIdentifier(resourceName, "drawable", activity.getPackageName());
            if (resourceId != 0) return resources.getDrawable(resourceId);
            
            int genericId = resources.getIdentifier("weather_icon", "drawable", activity.getPackageName());
            if (genericId != 0) return resources.getDrawable(genericId);
        } catch (Exception e) { }
        
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        drawable.setColor(0xFF87CEEB);
        return drawable;
    }

    private String getWeatherIconResourceName(int weatherCode) {
        if (weatherCode == 0) return "weather_clear";
        else if (weatherCode == 1) return "weather_mainly_clear";
        else if (weatherCode == 2) return "weather_partly_cloudy";
        else if (weatherCode == 3) return "weather_overcast";
        else if (weatherCode >= 45 && weatherCode <= 48) return "weather_overcast";
        else if (weatherCode >= 51 && weatherCode <= 57) return "weather_showers";
        else if (weatherCode >= 61 && weatherCode <= 67) return "weather_showers";
        else if (weatherCode >= 71 && weatherCode <= 77) return "weather_snow";
        else if (weatherCode >= 80 && weatherCode <= 86) return "weather_showers";
        else if (weatherCode >= 95 && weatherCode <= 99) return "weather_thunderstorm";
        else return "weather_clear";
    }

    @Override
    public void hide(MainActivity activity, View container) {
        if (forecastContainer != null) {
            ViewGroup parent = (ViewGroup) forecastContainer.getParent();
            if (parent != null) {
                parent.removeView(forecastContainer);
            }
            forecastContainer = null;
        }
        currentActivity = null;
        locationText = null;
        daysContainer = null;
        errorText = null;
    }
}

