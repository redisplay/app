package com.redisplay.app.modules;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.FrameLayout;

import com.redisplay.app.ContentModule;
import com.redisplay.app.MainActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public class CalendarModule implements ContentModule {
    private static final String TAG = "CalendarModule";
    private AsyncTask<Void, Void, String> fetchTask;
    private View calendarView;
    private Handler scrollHandler;
    private Runnable scrollUpdateRunnable;

    @Override
    public String getType() {
        return "calendar";
    }

    @Override
    public void hide(MainActivity activity, View container) {
        if (fetchTask != null) {
            fetchTask.cancel(true);
            fetchTask = null;
        }
        
        // Cancel periodic scroll updates
        if (scrollHandler != null && scrollUpdateRunnable != null) {
            scrollHandler.removeCallbacks(scrollUpdateRunnable);
            scrollHandler = null;
            scrollUpdateRunnable = null;
        }
        
        if (calendarView != null && container instanceof ViewGroup) {
            ((ViewGroup) container).removeView(calendarView);
            calendarView = null;
        }
        
        // Ensure that the main content views (which might have been hidden by us) are ready to be shown by next module
        // But we don't want to show them if the next module hides them immediately.
        // ContentManager handles clearing views.
    }

    @Override
    public void display(MainActivity activity, JSONObject contentItem, View container) {
        LinearLayout mainLayout = new LinearLayout(activity);
        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT, 
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
        mainLayout.setLayoutParams(params);
        
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        
        // Extract theme
        JSONObject view = contentItem.optJSONObject("view");
        JSONObject theme = null;
        if (view != null) {
            JSONObject data = view.optJSONObject("data");
            if (data != null) {
                theme = data.optJSONObject("theme");
            }
        }
        
        int bgColor = getColor(theme, "backgroundColor", "#FFFFFF");
        mainLayout.setBackgroundColor(bgColor);

        // Add to container
        ((ViewGroup) container).addView(mainLayout);
        calendarView = mainLayout;

        // Fetch events
        try {
            if (view != null) {
                String viewId = view.optString("id");
                if (viewId != null && !viewId.isEmpty()) {
                    JSONObject data = view.optJSONObject("data");
                    String displayMode = data != null ? data.optString("displayMode", "agenda") : "agenda";
                    
                    // Optimization: Check if events are already injected in data
                    if (data != null && data.has("events")) {
                        JSONArray injectedEvents = data.optJSONArray("events");
                        if (injectedEvents != null) {
                            if ("month".equalsIgnoreCase(displayMode)) {
                                renderMonthView(activity, injectedEvents, mainLayout, theme);
                            } else {
                                renderAgendaView(activity, injectedEvents, mainLayout, theme);
                            }
                            return; // Skip fetching
                        }
                    }
                    
                    fetchEvents(activity, viewId, mainLayout, displayMode, theme);
                } else {
                    showError(activity, mainLayout, "ID vista mancante");
                }
            }
        } catch (Exception e) {
            showError(activity, mainLayout, "Errore config: " + e.getMessage());
        }
    }

    private void fetchEvents(final MainActivity activity, final String viewId, final LinearLayout container, final String displayMode, final JSONObject theme) {
        fetchTask = new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... voids) {
                try {
                    URL url = new URL(activity.getServerUrl() + "/api/calendars/view/" + viewId);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);

                    if (conn.getResponseCode() != 200) {
                        return null;
                    }

                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"));
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }
                    return response.toString();
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            protected void onPostExecute(String result) {
                if (result == null) {
                    // showError(activity, container, "Errore durante il caricamento degli eventi");
                    // Just log, don't show error to avoid flickering if temporary
                    Log.e(TAG, "Error fetching events");
                    return;
                }
                
                try {
                    JSONArray events = new JSONArray(result);
                    if ("month".equalsIgnoreCase(displayMode)) {
                        renderMonthView(activity, events, container, theme);
                    } else {
                        renderAgendaView(activity, events, container, theme);
                    }
                } catch (Exception e) {
                    showError(activity, container, "Errore parsing dati: " + e.getMessage());
                }
            }
        };
        fetchTask.execute();
    }

    private void showError(MainActivity activity, LinearLayout container, String message) {
        TextView errorText = new TextView(activity);
        errorText.setText(message);
        errorText.setTextColor(Color.RED);
        errorText.setPadding(20, 20, 20, 20);
        container.addView(errorText);
    }
    
    // --- AGENDA VIEW ---

    private void renderAgendaView(MainActivity activity, JSONArray events, LinearLayout mainContainer, JSONObject theme) {
        mainContainer.removeAllViews();

        // Theme colors
        int headerColor = getColor(theme, "headerColor", "#000000");
        int textColor = getColor(theme, "textColor", "#000000");
        int bgColor = getColor(theme, "backgroundColor", "#121212");

        // Container for everything
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT));
        mainContainer.addView(container);

        // Header - BIGGER font size
        TextView header = new TextView(activity);
        SimpleDateFormat dateFormat = new SimpleDateFormat("EEEE d MMMM", Locale.ITALIAN);
        header.setText(dateFormat.format(new Date()));
        header.setTextSize(48); // BIGGER - was 20
        header.setTypeface(null, Typeface.BOLD);
        header.setTextColor(headerColor);
        header.setPadding(dpToPx(activity, 24), dpToPx(activity, 24), dpToPx(activity, 24), dpToPx(activity, 16));
        header.setGravity(Gravity.CENTER);
        container.addView(header);

        // Split view container (horizontal)
        LinearLayout splitView = new LinearLayout(activity);
        splitView.setOrientation(LinearLayout.HORIZONTAL);
        splitView.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0, 1.0f)); // Take remaining space
        container.addView(splitView);

        Date now = new Date();
        long nowTime = now.getTime();
        long startOfDay = getStartOfDay(now);
        long endOfDay = getEndOfDay(now);
        long upcomingWindow = endOfDay; // Show all remaining events today (extended from 4 hours)

        // LEFT SIDE: Upcoming events (next 3-4 hours) - 40% width
        ScrollView leftScroll = new ScrollView(activity);
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 0.4f);
        leftParams.setMargins(0, 0, dpToPx(activity, 8), 0);
        leftScroll.setLayoutParams(leftParams);
        LinearLayout leftContainer = new LinearLayout(activity);
        leftContainer.setOrientation(LinearLayout.VERTICAL);
        leftContainer.setPadding(dpToPx(activity, 16), dpToPx(activity, 8), dpToPx(activity, 16), dpToPx(activity, 8));
        leftScroll.addView(leftContainer);
        splitView.addView(leftScroll);

        // LEFT SIDE TITLE
        TextView upcomingTitle = new TextView(activity);
        upcomingTitle.setText("Prossimi eventi");
        upcomingTitle.setTextSize(24);
        upcomingTitle.setTypeface(null, Typeface.BOLD);
        upcomingTitle.setTextColor(headerColor);
        upcomingTitle.setPadding(0, 0, 0, dpToPx(activity, 12));
        leftContainer.addView(upcomingTitle);

        // RIGHT SIDE: Full day timeline - 60% width
        ScrollView rightScroll = new ScrollView(activity);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 0.6f);
        rightScroll.setLayoutParams(rightParams);
        LinearLayout rightContainer = new LinearLayout(activity);
        rightContainer.setOrientation(LinearLayout.VERTICAL);
        rightContainer.setPadding(dpToPx(activity, 16), dpToPx(activity, 8), dpToPx(activity, 16), dpToPx(activity, 8));
        rightScroll.addView(rightContainer);
        splitView.addView(rightScroll);

        // No title for timeline - removed "Giornata completa"

        // Parse and categorize events
        List<JSONObject> upcomingEvents = new ArrayList<>();
        List<JSONObject> todayEvents = new ArrayList<>();

        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null) continue;

            String startStr = event.optString("start");
            Date start = parseDate(startStr);
            if (start == null) continue;

            long startTime = start.getTime();

            // Filter for today
            if (startTime >= startOfDay && startTime < endOfDay) {
                todayEvents.add(event);

                // Add to upcoming if it's today and hasn't started yet, or started recently (within last hour)
                if (startTime >= (nowTime - 60 * 60 * 1000) && startTime <= upcomingWindow) {
                    upcomingEvents.add(event);
                }
            }
        }

        // Render upcoming events (verbose)
        if (upcomingEvents.isEmpty()) {
            TextView noUpcoming = new TextView(activity);
            noUpcoming.setText("Nessun evento nelle prossime ore");
            noUpcoming.setTextColor(textColor);
            noUpcoming.setTextSize(14);
            noUpcoming.setPadding(0, dpToPx(activity, 8), 0, 0);
            leftContainer.addView(noUpcoming);
        } else {
            for (JSONObject event : upcomingEvents) {
                addUpcomingEventCard(activity, leftContainer, event, theme);
            }
        }

        // Render timeline
        renderDayTimeline(activity, rightContainer, todayEvents, now, theme, rightScroll);
    }

    private void addUpcomingEventCard(MainActivity activity, LinearLayout container, JSONObject event, JSONObject theme) {
        try {
            String startStr = event.optString("start");
            String endStr = event.optString("end");
            Date start = parseDate(startStr);
            Date end = parseDate(endStr);
            if (start == null) return;

            int bgColor = getColor(theme, "backgroundColor", "#121212");
            int textColor = getColor(theme, "textColor", "#FFFFFF");
            int titleColor = getColor(theme, "eventTitleColor", "#FFFFFF");
            int eventColor = Color.parseColor(event.optString("color", "#4285F4"));

            // Card container
            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(Color.argb(50, 255, 255, 255)); // Semi-transparent
            card.setPadding(dpToPx(activity, 16), dpToPx(activity, 12), dpToPx(activity, 16), dpToPx(activity, 12));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            cardParams.setMargins(0, 0, 0, dpToPx(activity, 12));
            card.setLayoutParams(cardParams);

            // Time
            TextView timeView = new TextView(activity);
            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
            timeView.setText(timeFormat.format(start) + (end != null ? " - " + timeFormat.format(end) : ""));
            timeView.setTextSize(16);
            timeView.setTextColor(eventColor);
            timeView.setTypeface(null, Typeface.BOLD);
            card.addView(timeView);

            // Title
            TextView titleView = new TextView(activity);
            titleView.setText(event.optString("summary", "Evento"));
            titleView.setTextSize(20);
            titleView.setTextColor(titleColor);
            titleView.setTypeface(null, Typeface.BOLD);
            titleView.setPadding(0, dpToPx(activity, 4), 0, dpToPx(activity, 4));
            card.addView(titleView);

            // Description (if available)
            String description = event.optString("description", "");
            if (!description.isEmpty()) {
                TextView descView = new TextView(activity);
                descView.setText(description);
                descView.setTextSize(14);
                descView.setTextColor(textColor);
                descView.setPadding(0, dpToPx(activity, 4), 0, 0);
                descView.setMaxLines(3);
                card.addView(descView);
            }

            // Location (if available)
            String location = event.optString("location", "");
            if (!location.isEmpty()) {
                TextView locView = new TextView(activity);
                locView.setText("📍 " + location);
                locView.setTextSize(14);
                locView.setTextColor(textColor);
                locView.setPadding(0, dpToPx(activity, 4), 0, 0);
                card.addView(locView);
            }

            container.addView(card);
        } catch (Exception e) {
            Log.e(TAG, "Error adding upcoming event card", e);
        }
    }

    private void renderDayTimeline(MainActivity activity, LinearLayout container, List<JSONObject> todayEvents, Date now, JSONObject theme, ScrollView scrollView) {
        try {
            int textColor = getColor(theme, "textColor", "#FFFFFF");
            int timeColor = getColor(theme, "timeColor", "#BBBBBB");
            int dividerColor = getColor(theme, "dividerColor", "#333333");

            Calendar cal = Calendar.getInstance();
            cal.setTime(now);
            int currentHour = cal.get(Calendar.HOUR_OF_DAY);
            int currentMinute = cal.get(Calendar.MINUTE);
            
            // Store reference to scroll view for auto-scrolling
            final ScrollView timelineScroll = scrollView;

            // Build hour slots (full day: 0-23)
            for (int hour = 0; hour <= 23; hour++) {
                LinearLayout hourSlot = new LinearLayout(activity);
                hourSlot.setOrientation(LinearLayout.HORIZONTAL);
                hourSlot.setPadding(0, dpToPx(activity, 2), 0, dpToPx(activity, 2));
                hourSlot.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(activity, 48)));

                // Hour label
                TextView hourLabel = new TextView(activity);
                hourLabel.setText(String.format("%02d:00", hour));
                hourLabel.setTextSize(14);
                hourLabel.setTextColor(timeColor);
                hourLabel.setWidth(dpToPx(activity, 60));
                hourLabel.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
                hourLabel.setPadding(0, 0, dpToPx(activity, 8), 0);
                hourSlot.addView(hourLabel);

                // Event area
                LinearLayout eventArea = new LinearLayout(activity);
                eventArea.setOrientation(LinearLayout.VERTICAL);
                eventArea.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f));

                // Find events for this hour
                for (JSONObject event : todayEvents) {
                    Date start = parseDate(event.optString("start"));
                    if (start == null) continue;

                    Calendar eventCal = Calendar.getInstance();
                    eventCal.setTime(start);
                    int eventHour = eventCal.get(Calendar.HOUR_OF_DAY);

                    if (eventHour == hour) {
                        int eventColor = Color.parseColor(event.optString("color", "#4285F4"));
                        TextView eventLabel = new TextView(activity);
                        eventLabel.setText(event.optString("summary", ""));
                        eventLabel.setTextSize(12);
                        eventLabel.setTextColor(textColor);
                        eventLabel.setBackgroundColor(Color.argb(100, Color.red(eventColor), Color.green(eventColor), Color.blue(eventColor)));
                        eventLabel.setPadding(dpToPx(activity, 4), dpToPx(activity, 2), dpToPx(activity, 4), dpToPx(activity, 2));
                        eventLabel.setSingleLine(true);
                        eventArea.addView(eventLabel);
                    }
                }

                hourSlot.addView(eventArea);
                container.addView(hourSlot);

                // Current time indicator (red line)
                if (hour == currentHour) {
                    View currentTimeLine = new View(activity);
                    currentTimeLine.setBackgroundColor(Color.RED);
                    currentTimeLine.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 3)));
                    LinearLayout.LayoutParams lineParams = (LinearLayout.LayoutParams) currentTimeLine.getLayoutParams();
                    int minuteOffset = (int)((currentMinute / 60.0) * dpToPx(activity, 48));
                    lineParams.setMargins(dpToPx(activity, 60), minuteOffset - dpToPx(activity, 24), 0, 0);
                    container.addView(currentTimeLine);
                }

                // Divider
                View divider = new View(activity);
                divider.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
                divider.setBackgroundColor(dividerColor);
                container.addView(divider);
            }
            
            // Auto-scroll to current time after layout
            container.post(new Runnable() {
                @Override
                public void run() {
                    if (timelineScroll != null && container.getChildCount() > 0) {
                        // Calculate scroll position: current hour * hour height + minute offset
                        int hourHeight = dpToPx(activity, 48) + dpToPx(activity, 3); // hour slot + divider
                        int scrollY = currentHour * hourHeight;
                        
                        // Add minute offset within current hour
                        int minuteOffset = (int)((currentMinute / 60.0) * dpToPx(activity, 48));
                        scrollY += minuteOffset;
                        
                        // Scroll to show current time, with some padding from top
                        int targetScroll = Math.max(0, scrollY - dpToPx(activity, 200)); // 200dp padding from top
                        timelineScroll.scrollTo(0, targetScroll);
                    }
                }
            });
            
            // Schedule periodic scroll updates to keep current time visible
            scrollHandler = new Handler();
            scrollUpdateRunnable = new Runnable() {
                @Override
                public void run() {
                    if (timelineScroll != null && container.getChildCount() > 0) {
                        Calendar currentCal = Calendar.getInstance();
                        int hour = currentCal.get(Calendar.HOUR_OF_DAY);
                        int minute = currentCal.get(Calendar.MINUTE);
                        
                        int hourHeight = dpToPx(activity, 48) + dpToPx(activity, 3);
                        int scrollY = hour * hourHeight;
                        int minuteOffset = (int)((minute / 60.0) * dpToPx(activity, 48));
                        scrollY += minuteOffset;
                        
                        int currentScroll = timelineScroll.getScrollY();
                        int targetScroll = Math.max(0, scrollY - dpToPx(activity, 200));
                        
                        // Smoothly scroll if we're far from current time
                        if (Math.abs(currentScroll - targetScroll) > dpToPx(activity, 100)) {
                            timelineScroll.smoothScrollTo(0, targetScroll);
                        }
                    }
                    // Schedule next update in 1 minute
                    scrollHandler.postDelayed(this, 60 * 1000);
                }
            };
            // Start periodic updates after initial scroll
            scrollHandler.postDelayed(scrollUpdateRunnable, 1000);
            
        } catch (Exception e) {
            Log.e(TAG, "Error rendering timeline", e);
        }
    }

    private void addEventRow(MainActivity activity, LinearLayout container, JSONObject event, Date start, Date end, boolean allDay, boolean alternate, JSONObject theme) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dpToPx(activity, 16), dpToPx(activity, 4), dpToPx(activity, 16), dpToPx(activity, 4));
        
        // Colors
        int timeColor = getColor(theme, "timeColor", "#555555");
        int titleColor = getColor(theme, "eventTitleColor", "#000000");
        int locColor = getColor(theme, "eventLocationColor", "#777777");
        int dividerColor = getColor(theme, "dividerColor", "#CCCCCC");
        int eventColor = Color.parseColor(event.optString("color", "#4285F4"));

        // Time
        TextView timeView = new TextView(activity);
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        if (allDay) {
            timeView.setText("Tutto\ngiorno");
            timeView.setTextSize(12);
        } else {
            timeView.setText(timeFormat.format(start));
            timeView.setTextSize(14);
        }
        timeView.setTextColor(timeColor);
        timeView.setWidth(dpToPx(activity, 50));
        timeView.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        timeView.setPadding(0, 0, dpToPx(activity, 8), 0);
        row.addView(timeView);

        // Marker line
        View marker = new View(activity);
        marker.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(activity, 4), LinearLayout.LayoutParams.MATCH_PARENT));
        marker.setBackgroundColor(eventColor);
        // Add margin to marker
        LinearLayout.LayoutParams markerParams = (LinearLayout.LayoutParams) marker.getLayoutParams();
        markerParams.setMargins(dpToPx(activity, 8), 0, dpToPx(activity, 8), 0);
        marker.setLayoutParams(markerParams);
        row.addView(marker);

        // Details
        LinearLayout details = new LinearLayout(activity);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(0, dpToPx(activity, 4), 0, dpToPx(activity, 4));
        details.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView titleView = new TextView(activity);
        titleView.setText(event.optString("summary", "Evento"));
        titleView.setTextSize(16);
        titleView.setTextColor(titleColor);
        titleView.setTypeface(null, Typeface.BOLD);
        details.addView(titleView);
        
        String location = event.optString("location");
        if (location != null && !location.isEmpty()) {
            TextView locView = new TextView(activity);
            locView.setText(location);
            locView.setTextSize(12);
            locView.setTextColor(locColor);
            details.addView(locView);
        }

        row.addView(details);
        container.addView(row);
        
        // Divider
        View divider = new View(activity);
        divider.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        divider.setBackgroundColor(dividerColor);
        container.addView(divider);
    }

    // --- MONTH VIEW ---

    private void renderMonthView(MainActivity activity, JSONArray events, LinearLayout container, JSONObject theme) {
        container.removeAllViews();

        Date now = new Date();
        Calendar cal = Calendar.getInstance();
        cal.setTime(now);
        int currentMonth = cal.get(Calendar.MONTH);
        int currentYear = cal.get(Calendar.YEAR);
        int todayDay = cal.get(Calendar.DAY_OF_MONTH);
        
        // Colors
        int headerTextColor = getColor(theme, "headerTextColor", "#444444");
        
        // Pre-process events
        Map<String, List<JSONObject>> eventsMap = new HashMap<String, List<JSONObject>>();
        SimpleDateFormat keyFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null) continue;
            
            Date start = parseDate(event.optString("start"));
            Date end = parseDate(event.optString("end"));
            if (start == null) continue;
            if (end == null) end = start;

            Calendar eventCal = Calendar.getInstance();
            eventCal.setTime(start);
            long endTime = end.getTime();
            
            int safety = 0;
            while (eventCal.getTimeInMillis() <= endTime && safety < 365) {
                String key = keyFormat.format(eventCal.getTime());
                if (!eventsMap.containsKey(key)) {
                    eventsMap.put(key, new ArrayList<JSONObject>());
                }
                eventsMap.get(key).add(event);
                
                eventCal.add(Calendar.DAY_OF_YEAR, 1);
                eventCal.set(Calendar.HOUR_OF_DAY, 0);
                eventCal.set(Calendar.MINUTE, 0);
                eventCal.set(Calendar.SECOND, 0);
                
                if (start.getTime() == end.getTime()) break;
                safety++;
            }
        }

        // Headers
        String[] weekdays = {"Lun", "Mar", "Mer", "Gio", "Ven", "Sab", "Dom"};
        LinearLayout headerRow = new LinearLayout(activity);
        headerRow.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 
            LinearLayout.LayoutParams.WRAP_CONTENT));
        headerRow.setOrientation(LinearLayout.HORIZONTAL);

        for (String day : weekdays) {
            TextView tv = new TextView(activity);
            tv.setText(day);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(dpToPx(activity, 4), dpToPx(activity, 8), dpToPx(activity, 4), dpToPx(activity, 8));
            tv.setTypeface(null, Typeface.BOLD);
            tv.setTextColor(headerTextColor);
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
            headerRow.addView(tv);
        }
        container.addView(headerRow);
        
        // Calendar logic
        cal.set(Calendar.DAY_OF_MONTH, 1);
        int firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK); 
        int offset = (firstDayOfWeek - 2 + 7) % 7; 
        cal.add(Calendar.DAY_OF_YEAR, -offset);

        Calendar todayCal = Calendar.getInstance();
        todayCal.setTime(new Date());
        todayCal.set(Calendar.HOUR_OF_DAY, 0);
        todayCal.set(Calendar.MINUTE, 0);
        todayCal.set(Calendar.SECOND, 0);
        todayCal.set(Calendar.MILLISECOND, 0);
        long todayMillis = todayCal.getTimeInMillis();

        for (int row = 0; row < 6; row++) {
            LinearLayout weekRow = new LinearLayout(activity);
            weekRow.setOrientation(LinearLayout.HORIZONTAL);
            weekRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));
            
            for (int col = 0; col < 7; col++) {
                int dayNum = cal.get(Calendar.DAY_OF_MONTH);
                int monthNum = cal.get(Calendar.MONTH);
                boolean isCurrentMonthBool = (monthNum == currentMonth);
                
                long cellMillis = cal.getTimeInMillis();
                boolean isPast = cellMillis < todayMillis;
                boolean isToday = (isCurrentMonthBool && dayNum == todayDay && cal.get(Calendar.YEAR) == currentYear);
                
                String key = keyFormat.format(cal.getTime());
                List<JSONObject> dayEvents = eventsMap.get(key);
                
                View cell = createMonthCell(activity, dayNum, isToday, isCurrentMonthBool, isPast, dayEvents, theme);
                
                LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f);
                cell.setLayoutParams(cellParams);
                weekRow.addView(cell);
                
                cal.add(Calendar.DAY_OF_YEAR, 1);
            }
            container.addView(weekRow);
        }
    }

    private View createMonthCell(MainActivity activity, int day, boolean isToday, boolean isCurrentMonth, boolean isPast, List<JSONObject> events, JSONObject theme) {
        LinearLayout cell = new LinearLayout(activity);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dpToPx(activity, 2), dpToPx(activity, 2), dpToPx(activity, 2), dpToPx(activity, 2));
        
        // Colors
        int todayBg = getColor(theme, "todayBackgroundColor", "#DBEAFE");
        int pastBg = getColor(theme, "pastDayBackgroundColor", "#FAFAFA");
        int otherMonthBg = getColor(theme, "otherMonthBackgroundColor", "#F1F5F9");
        
        int todayText = getColor(theme, "todayTextColor", "#1E40AF");
        int pastText = getColor(theme, "pastDayTextColor", "#CCCCCC");
        int otherMonthText = getColor(theme, "otherMonthTextColor", "#CBD5E1");
        int normalText = getColor(theme, "dayTextColor", "#000000");

        if (isToday) {
            cell.setBackgroundColor(todayBg);
        } else if (isPast) {
            cell.setBackgroundColor(pastBg);
        } else if (!isCurrentMonth) {
            cell.setBackgroundColor(otherMonthBg);
        } else {
             cell.setBackgroundResource(android.R.drawable.list_selector_background);
        }

        TextView dayText = new TextView(activity);
        dayText.setText(String.valueOf(day));
        dayText.setTextSize(18);
        dayText.setTypeface(null, isToday ? Typeface.BOLD : Typeface.NORMAL);
        
        if (isToday) {
            dayText.setTextColor(todayText);
        } else if (isPast) {
            dayText.setTextColor(pastText);
        } else if (!isCurrentMonth) {
            dayText.setTextColor(otherMonthText);
        } else {
            dayText.setTextColor(normalText);
        }
        
        dayText.setGravity(Gravity.CENTER);
        cell.addView(dayText);

        if (events != null && !events.isEmpty()) {
            LinearLayout eventsLayout = new LinearLayout(activity);
            eventsLayout.setOrientation(LinearLayout.VERTICAL);
            eventsLayout.setGravity(Gravity.TOP);
            eventsLayout.setPadding(0, dpToPx(activity, 2), 0, 0);
            
            int maxEvents = 3;
            for (int i = 0; i < Math.min(events.size(), maxEvents); i++) {
                JSONObject event = events.get(i);
                TextView eventView = new TextView(activity);
                String summary = event.optString("summary", "Evento");
                eventView.setText(summary);
                eventView.setTextSize(12);
                eventView.setSingleLine(true);
                eventView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                eventView.setPadding(dpToPx(activity, 4), dpToPx(activity, 2), dpToPx(activity, 4), dpToPx(activity, 2));
                
                String colorStr = event.optString("color", "#3498db");
                int color = Color.parseColor("#3498db");
                try {
                    color = Color.parseColor(colorStr);
                } catch (Exception e) {}

                if (isPast) {
                    eventView.setAlpha(0.5f);
                    eventView.setBackgroundColor(color); 
                } else if (!isCurrentMonth) {
                    eventView.setAlpha(0.3f);
                } else {
                    eventView.setTextColor(Color.WHITE);
                    eventView.setBackgroundColor(color);
                }
                
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 
                    LinearLayout.LayoutParams.WRAP_CONTENT);
                params.setMargins(0, dpToPx(activity, 1), 0, dpToPx(activity, 1));
                eventView.setLayoutParams(params);
                
                eventsLayout.addView(eventView);
            }
            
            if (events.size() > maxEvents) {
                TextView more = new TextView(activity);
                more.setText("+" + (events.size() - maxEvents));
                more.setTextSize(9);
                more.setTextColor(normalText);
                more.setGravity(Gravity.CENTER);
                eventsLayout.addView(more);
            }
            
            cell.addView(eventsLayout);
        }
        
        return cell;
    }

    private Date parseDate(String dateStr) {
        if (dateStr == null) return null;
        try {
            // ISO 8601
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            return sdf.parse(dateStr);
        } catch (ParseException e) {
             try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                return sdf.parse(dateStr);
            } catch (ParseException e2) {
                return null;
            }
        }
    }

    private long getStartOfDay(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private long getEndOfDay(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 59);
        cal.set(Calendar.SECOND, 59);
        cal.set(Calendar.MILLISECOND, 999);
        return cal.getTimeInMillis();
    }
    
    private int getColor(JSONObject theme, String key, String defaultColor) {
        if (theme != null && theme.has(key)) {
            try {
                return Color.parseColor(theme.getString(key));
            } catch (Exception e) {
                // Ignore
            }
        }
        return Color.parseColor(defaultColor);
    }

    private int dpToPx(MainActivity activity, int dp) {
        float density = activity.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
