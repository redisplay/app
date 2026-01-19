package com.redisplay.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Bitmap;
import java.io.InputStream;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import com.redisplay.app.utils.ConfigManager;

public class ConfigActivity extends Activity {
    private static final String TAG = "ConfigActivity";

    private EditText serverUrlInput;
    private EditText channelNameInput;
    private RadioGroup connectionTypeGroup;
    private RadioButton connectionTypeRemote;
    private RadioButton connectionTypeInternal;
    private CheckBox homeScreenModeCheckbox;
    private CheckBox autoUpdateCheckbox;
    private CheckBox debugModeCheckbox;
    private Button saveButton;
    private Button cancelButton;
    private Button clearButton;
    private Button viewTypesButton;
    private ConfigManager configManager;
    private ScrollView configInfoScrollView;
    private TextView configInfoMessage;
    private TextView configChannelHeader;
    private TextView configViewsHeader;
    private TableLayout configViewsTable;
    private TextView configTapMappingsHeader;
    private TableLayout configTapMappingsTable;
    private TextView configSchedulesHeader;
    private TableLayout configSchedulesTable;
    private TextView configInternalServerAddress;
    private LinearLayout configInternalServerLayout;
    private ImageView configInternalServerQRCode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        android.util.Log.d(TAG, "onCreate called");
        
        try {
            setContentView(R.layout.activity_config);
            android.util.Log.d(TAG, "Layout set successfully");
        } catch (Exception e) {
            android.util.Log.e(TAG, "Error setting layout: " + e.getMessage(), e);
            throw e;
        }

        configManager = new ConfigManager(this);
        android.util.Log.d(TAG, "ConfigManager initialized");
        
        // Initialize views
        android.util.Log.d(TAG, "Finding views...");
        serverUrlInput = (EditText) findViewById(R.id.configServerUrl);
        android.util.Log.d(TAG, "serverUrlInput: " + (serverUrlInput != null ? "found" : "NULL"));
        channelNameInput = (EditText) findViewById(R.id.configChannelName);
        connectionTypeGroup = (RadioGroup) findViewById(R.id.configConnectionType);
        connectionTypeRemote = (RadioButton) findViewById(R.id.configConnectionTypeRemote);
        connectionTypeInternal = (RadioButton) findViewById(R.id.configConnectionTypeInternal);
        homeScreenModeCheckbox = (CheckBox) findViewById(R.id.configHomeScreenMode);
        autoUpdateCheckbox = (CheckBox) findViewById(R.id.configAutoUpdate);
        debugModeCheckbox = (CheckBox) findViewById(R.id.configDebugMode);
        saveButton = (Button) findViewById(R.id.configSaveButton);
        cancelButton = (Button) findViewById(R.id.configCancelButton);
        clearButton = (Button) findViewById(R.id.configClearButton);
        viewTypesButton = (Button) findViewById(R.id.configViewTypesButton);
        configInfoScrollView = (ScrollView) findViewById(R.id.configInfoScrollView);
        configInfoMessage = (TextView) findViewById(R.id.configInfoMessage);
        configChannelHeader = (TextView) findViewById(R.id.configChannelHeader);
        configViewsHeader = (TextView) findViewById(R.id.configViewsHeader);
        configViewsTable = (TableLayout) findViewById(R.id.configViewsTable);
        configTapMappingsHeader = (TextView) findViewById(R.id.configTapMappingsHeader);
        configTapMappingsTable = (TableLayout) findViewById(R.id.configTapMappingsTable);
        configSchedulesHeader = (TextView) findViewById(R.id.configSchedulesHeader);
        configSchedulesTable = (TableLayout) findViewById(R.id.configSchedulesTable);
        configInternalServerAddress = (TextView) findViewById(R.id.configInternalServerAddress);
        configInternalServerLayout = (LinearLayout) findViewById(R.id.configInternalServerLayout);
        configInternalServerQRCode = (ImageView) findViewById(R.id.configInternalServerQRCode);
        
        // Load current configuration
        loadCurrentConfig();
        
        // Update internal server address if using internal server
        updateInternalServerAddress();
        
        // Start a thread to periodically update the server address (in case server starts later)
        startServerAddressUpdater();
        
        // Load server configuration info
        loadServerConfigInfo();
        
        // Set up button listeners
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveConfiguration();
            }
        });
        
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish(); // Just close without saving
            }
        });
        
        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearConfiguration();
            }
        });
        
        viewTypesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showViewTypes();
            }
        });
        
        // Home screen mode checkbox listener
        homeScreenModeCheckbox.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    // Show info about home screen mode
                    Toast.makeText(ConfigActivity.this, 
                        "You may need to manually set this app as your home launcher in Android settings", 
                        Toast.LENGTH_LONG).show();
                }
            }
        });
    }
    
    private void loadCurrentConfig() {
        // Load and display current configuration
        String serverUrl = configManager.getServerUrl();
        if (serverUrl != null && !serverUrl.isEmpty()) {
            serverUrlInput.setText(serverUrl);
        }
        
        // Load channel name
        String channelName = configManager.getChannelName();
        if (channelNameInput != null) {
            channelNameInput.setText(channelName);
        }
        
        // Load connection type
        String connectionType = configManager.getConnectionType();
        View channelNameLayout = findViewById(R.id.configChannelNameLayout);
        if ("internal".equals(connectionType)) {
            connectionTypeInternal.setChecked(true);
            serverUrlInput.setVisibility(View.GONE);
            if (channelNameLayout != null) {
                channelNameLayout.setVisibility(View.GONE);
            }
            if (configInternalServerLayout != null) {
                configInternalServerLayout.setVisibility(View.VISIBLE);
            }
        } else {
            connectionTypeRemote.setChecked(true);
            serverUrlInput.setVisibility(View.VISIBLE);
            if (channelNameLayout != null) {
                channelNameLayout.setVisibility(View.VISIBLE);
            }
            if (configInternalServerLayout != null) {
                configInternalServerLayout.setVisibility(View.GONE);
            }
        }
        
        homeScreenModeCheckbox.setChecked(configManager.getHomeScreenMode());
        autoUpdateCheckbox.setChecked(configManager.getAutoUpdate());
        debugModeCheckbox.setChecked(configManager.getDebugMode());
        // useBluetoothCheckbox.setChecked(configManager.getUseBluetooth()); // Commented out
        
        // Update server URL field based on connection type
        connectionTypeGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                View channelNameLayout = findViewById(R.id.configChannelNameLayout);
                if (checkedId == R.id.configConnectionTypeInternal) {
                    // Hide server URL input and channel name, show internal server info
                    serverUrlInput.setVisibility(View.GONE);
                    if (channelNameLayout != null) {
                        channelNameLayout.setVisibility(View.GONE);
                    }
                    if (configInternalServerLayout != null) {
                        configInternalServerLayout.setVisibility(View.VISIBLE);
                    }
                    // Start the internal server immediately when selected
                    // This allows the server address to be displayed right away
                    startInternalServerIfNeeded();
                    updateInternalServerAddress();
                } else {
                    // Show server URL input and channel name, hide internal server info
                    serverUrlInput.setVisibility(View.VISIBLE);
                    if (channelNameLayout != null) {
                        channelNameLayout.setVisibility(View.VISIBLE);
                    }
                    if (configInternalServerLayout != null) {
                        configInternalServerLayout.setVisibility(View.GONE);
                    }
                }
            }
        });
    }
    
    private void startInternalServerIfNeeded() {
        // Start the internal server by calling MainActivity's static method
        // This ensures the server is running even before config is saved
        try {
            com.redisplay.app.MainActivity.startInternalServerFromConfig(this);
            // Give it a moment to start
            android.os.Handler handler = new android.os.Handler();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    updateInternalServerAddress();
                }
            }, 500);
        } catch (Exception e) {
            android.util.Log.e(TAG, "Error starting internal server: " + e.getMessage());
        }
    }
    
    private void updateInternalServerAddress() {
        // Check if internal server is selected
        if (connectionTypeInternal != null && connectionTypeInternal.isChecked()) {
            if (configInternalServerAddress != null) {
                // Try to get server address from MainActivity
                String serverAddress = com.redisplay.app.MainActivity.getInternalServerAddress();
                if (serverAddress != null && !serverAddress.isEmpty()) {
                    configInternalServerAddress.setText(serverAddress);
                    // Generate and show QR code
                    generateAndShowQRCode(serverAddress);
                } else {
                    configInternalServerAddress.setText("Please save changes to start the internal server.\nThe address will appear here once the server is running.");
                    if (configInternalServerQRCode != null) {
                        configInternalServerQRCode.setVisibility(View.GONE);
                    }
                }
            }
        }
    }
    
    private void generateAndShowQRCode(String text) {
        if (text == null || text.isEmpty() || configInternalServerQRCode == null) {
            return;
        }
        
        try {
            QRCodeWriter writer = new QRCodeWriter();
            java.util.Map<EncodeHintType, Object> hints = new java.util.HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1);
            
            BitMatrix bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, 512, 512, hints);
            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bitmap.setPixel(x, y, bitMatrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            
            configInternalServerQRCode.setImageBitmap(bitmap);
            configInternalServerQRCode.setVisibility(View.VISIBLE);
        } catch (WriterException e) {
            android.util.Log.e(TAG, "Error generating QR code: " + e.getMessage(), e);
            if (configInternalServerQRCode != null) {
                configInternalServerQRCode.setVisibility(View.GONE);
            }
        }
    }
    
    private void startServerAddressUpdater() {
        // Update server address every 2 seconds if internal server is selected
        final android.os.Handler handler = new android.os.Handler();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (connectionTypeInternal != null && connectionTypeInternal.isChecked()) {
                    updateInternalServerAddress();
                    handler.postDelayed(this, 2000); // Update every 2 seconds
                }
            }
        }, 2000);
    }
    
    private void saveConfiguration() {
        // Get values from UI
        String connectionType = connectionTypeInternal.isChecked() ? "internal" : "remote";
        String serverUrl = serverUrlInput.getText().toString().trim();
        String channelName = channelNameInput != null ? channelNameInput.getText().toString().trim() : "test";
        boolean homeScreenMode = homeScreenModeCheckbox.isChecked();
        boolean autoUpdate = autoUpdateCheckbox.isChecked();
        boolean debugMode = debugModeCheckbox.isChecked();
        
        // Validate server URL (only if using remote connection)
        if ("remote".equals(connectionType) && serverUrl.isEmpty()) {
            Toast.makeText(this, "Server URL cannot be empty for remote connection", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Save configuration
        configManager.setConnectionType(connectionType);
        configManager.setServerUrl(serverUrl);
        configManager.setChannelName(channelName);
        configManager.setHomeScreenMode(homeScreenMode);
        configManager.setAutoUpdate(autoUpdate);
        configManager.setDebugMode(debugMode);
        
        // Update home screen mode in manifest if needed
        updateHomeScreenMode(homeScreenMode);
        
        Toast.makeText(this, "Configuration saved!", Toast.LENGTH_SHORT).show();
        
        // Return to MainActivity - it will reinitialize connection with new settings
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }
    
    private void showViewTypes() {
        // Open view types in a WebView or show in a dialog
        // For now, open the web interface view-types page if internal server is running
        String connectionType = configManager.getConnectionType();
        if ("internal".equals(connectionType)) {
            String serverAddress = com.redisplay.app.MainActivity.getInternalServerAddress();
            if (serverAddress != null && !serverAddress.isEmpty()) {
                // Open in browser or WebView
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(android.net.Uri.parse(serverAddress + "/view-types"));
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    // If browser not available, show in a dialog with the JSON
                    loadAndShowViewTypes();
                }
            } else {
                loadAndShowViewTypes();
            }
        } else {
            // For remote server, try to fetch from API or show local list
            loadAndShowViewTypes();
        }
    }
    
    private void loadAndShowViewTypes() {
        // Load view types from assets and show in a dialog
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    android.content.res.AssetManager assetManager = getAssets();
                    String[] viewTypeFiles = assetManager.list("view_types");
                    final java.util.List<String> viewTypeNames = new java.util.ArrayList<>();
                    
                    if (viewTypeFiles != null) {
                        java.util.Arrays.sort(viewTypeFiles);
                        for (String filename : viewTypeFiles) {
                            if (filename.endsWith(".json")) {
                                try {
                                    InputStream jsonStream = assetManager.open("view_types/" + filename);
                                    byte[] jsonBuffer = new byte[jsonStream.available()];
                                    jsonStream.read(jsonBuffer);
                                    jsonStream.close();
                                    
                                    String jsonContent = new String(jsonBuffer, "UTF-8");
                                    org.json.JSONObject viewTypeDoc = new org.json.JSONObject(jsonContent);
                                    String name = viewTypeDoc.getString("name");
                                    String type = viewTypeDoc.getString("type");
                                    viewTypeNames.add(name + " (" + type + ")");
                                } catch (Exception e) {
                                    android.util.Log.e(TAG, "Error loading view type: " + e.getMessage());
                                }
                            }
                        }
                    }
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            StringBuilder message = new StringBuilder();
                            message.append("Available View Types:\n\n");
                            for (String name : viewTypeNames) {
                                message.append("• ").append(name).append("\n");
                            }
                            message.append("\nFor detailed documentation and sample JSON, visit:\n");
                            String connectionType = configManager.getConnectionType();
                            if ("internal".equals(connectionType)) {
                                String serverAddress = com.redisplay.app.MainActivity.getInternalServerAddress();
                                if (serverAddress != null) {
                                    message.append(serverAddress).append("/view-types");
                                } else {
                                    message.append("(Internal server address not available)");
                                }
                            } else {
                                message.append("The server's /view-types endpoint");
                            }
                            
                            new android.app.AlertDialog.Builder(ConfigActivity.this)
                                .setTitle("Available View Types")
                                .setMessage(message.toString())
                                .setPositiveButton("Open in Browser", new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface dialog, int which) {
                                        String connectionType = configManager.getConnectionType();
                                        if ("internal".equals(connectionType)) {
                                            String serverAddress = com.redisplay.app.MainActivity.getInternalServerAddress();
                                            if (serverAddress != null) {
                                                Intent intent = new Intent(Intent.ACTION_VIEW);
                                                intent.setData(android.net.Uri.parse(serverAddress + "/view-types"));
                                                try {
                                                    startActivity(intent);
                                                } catch (Exception e) {
                                                    Toast.makeText(ConfigActivity.this, "Could not open browser: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                                }
                                            }
                                        }
                                    }
                                })
                                .setNegativeButton("Close", null)
                                .show();
                        }
                    });
                } catch (Exception e) {
                    android.util.Log.e(TAG, "Error loading view types: " + e.getMessage());
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(ConfigActivity.this, "Error loading view types: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }).start();
    }
    
    private void clearConfiguration() {
        // Show confirmation
        new android.app.AlertDialog.Builder(this)
            .setTitle("Clear All Settings")
            .setMessage("Are you sure you want to clear all settings? This will return the app to its initial state.")
            .setPositiveButton("Clear", new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface dialog, int which) {
                    configManager.clearConfig();
                    updateHomeScreenMode(false);
                    Toast.makeText(ConfigActivity.this, "All settings cleared", Toast.LENGTH_SHORT).show();
                    
                    // Redirect to setup
                    Intent intent = new Intent(ConfigActivity.this, SetupActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    finish();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
    
    private void updateHomeScreenMode(boolean enabled) {
        // Enable or disable HOME category in MainActivity's intent filter
        // This is a simplified approach - the app will be available as a home launcher
        // but the user needs to manually select it in Android settings
        try {
            PackageManager pm = getPackageManager();
            ComponentName componentName = new ComponentName(this, MainActivity.class);
            
            if (enabled) {
                // The HOME category is already in the manifest, so nothing to do programmatically
                // Just inform the user
                Toast.makeText(this, 
                    "Home screen mode enabled. Go to Android Settings > Home to set this app as your launcher.", 
                    Toast.LENGTH_LONG).show();
            } else {
                // We can't really disable the HOME category at runtime, but we can inform the user
                Toast.makeText(this, 
                    "Home screen mode disabled. You can change your launcher in Android Settings.", 
                    Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error updating home screen mode: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void loadServerConfigInfo() {
        String serverUrl = configManager.getServerUrl();
        if (serverUrl == null || serverUrl.isEmpty()) {
            if (configInfoMessage != null) {
                configInfoMessage.setText("No server URL configured. Please configure a server URL above to view channel and view information.");
                configInfoMessage.setVisibility(android.view.View.VISIBLE);
            }
            hideAllTables();
            return;
        }
        
        // Fetch server configuration in background
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Fetch channel config
                    String channelConfigUrl = serverUrl + "/api/channel-config/test";
                    java.net.URL url = new java.net.URL(channelConfigUrl);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    
                    java.util.Map<String, String> viewSchedules = new java.util.HashMap<String, String>();
                    org.json.JSONObject channelConfig = null;
                    
                    if (conn.getResponseCode() == java.net.HttpURLConnection.HTTP_OK) {
                        java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(conn.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line);
                        reader.close();
                        
                        channelConfig = new org.json.JSONObject(sb.toString());
                    }
                    conn.disconnect();
                    
                    // Fetch all views to get schedule info
                    String viewsUrl = serverUrl + "/api/views";
                    url = new java.net.URL(viewsUrl);
                    conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    
                    if (conn.getResponseCode() == java.net.HttpURLConnection.HTTP_OK) {
                        java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(conn.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line);
                        reader.close();
                        
                        // /api/views returns an array of view objects
                        org.json.JSONArray viewsArray = new org.json.JSONArray(sb.toString());
                        
                        // Build schedule map
                        for (int i = 0; i < viewsArray.length(); i++) {
                            org.json.JSONObject view = viewsArray.getJSONObject(i);
                            String viewId = view.getString("id");
                            
                            StringBuilder scheduleText = new StringBuilder();
                            
                            if (view.has("metadata") && view.getJSONObject("metadata").has("schedule")) {
                                org.json.JSONObject schedule = view.getJSONObject("metadata").getJSONObject("schedule");
                                
                                if (schedule.has("days")) {
                                    org.json.JSONArray days = schedule.getJSONArray("days");
                                    scheduleText.append("Days: ");
                                    for (int j = 0; j < days.length(); j++) {
                                        if (j > 0) scheduleText.append(", ");
                                        scheduleText.append(formatDayName(days.getString(j)));
                                    }
                                } else {
                                    scheduleText.append("Days: All");
                                }
                                
                                if (schedule.has("hours")) {
                                    scheduleText.append(" | ");
                                    Object hoursObj = schedule.get("hours");
                                    if (hoursObj instanceof org.json.JSONArray) {
                                        org.json.JSONArray hoursArray = (org.json.JSONArray) hoursObj;
                                        scheduleText.append("Hours: ");
                                        for (int j = 0; j < hoursArray.length(); j++) {
                                            if (j > 0) scheduleText.append(", ");
                                            org.json.JSONObject range = hoursArray.getJSONObject(j);
                                            scheduleText.append(range.getString("from"))
                                                .append("-")
                                                .append(range.getString("to"));
                                        }
                                    } else if (hoursObj instanceof org.json.JSONObject) {
                                        org.json.JSONObject hours = (org.json.JSONObject) hoursObj;
                                        scheduleText.append("Hours: ")
                                            .append(hours.getString("from"))
                                            .append("-")
                                            .append(hours.getString("to"));
                                    }
                                } else {
                                    scheduleText.append(" | Hours: All day");
                                }
                            } else {
                                scheduleText.append("Always active");
                            }
                            
                            viewSchedules.put(viewId, scheduleText.toString());
                        }
                    }
                    conn.disconnect();
                    
                    // Store data for UI thread
                    final org.json.JSONObject finalChannelConfig = channelConfig;
                    final java.util.Map<String, String> finalViewSchedules = viewSchedules;
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (finalChannelConfig == null) {
                                if (configInfoMessage != null) {
                                    configInfoMessage.setText("Unable to connect to server. Please check that the server is running and the URL is correct.");
                                    configInfoMessage.setVisibility(android.view.View.VISIBLE);
                                }
                                hideAllTables();
                                return;
                            }
                            
                            // Hide message, show tables
                            if (configInfoMessage != null) {
                                configInfoMessage.setVisibility(android.view.View.GONE);
                            }
                            
                            // Show channel header
                            if (configChannelHeader != null) {
                                configChannelHeader.setText("Channel: test");
                                configChannelHeader.setVisibility(android.view.View.VISIBLE);
                            }
                            
                            // Populate Views table
                            try {
                                if (finalChannelConfig.has("views")) {
                                    org.json.JSONArray views = finalChannelConfig.getJSONArray("views");
                                    if (configViewsHeader != null) {
                                        configViewsHeader.setText("Views (" + views.length() + ")");
                                        configViewsHeader.setVisibility(android.view.View.VISIBLE);
                                    }
                                    if (configViewsTable != null) {
                                        configViewsTable.removeAllViews();
                                        // Add header row
                                        addTableHeaderRow(configViewsTable, "#", "View ID");
                                        // Add data rows
                                        for (int i = 0; i < views.length(); i++) {
                                            String viewId = views.getString(i);
                                            addTableDataRow(configViewsTable, String.valueOf(i + 1), viewId);
                                        }
                                        configViewsTable.setVisibility(android.view.View.VISIBLE);
                                    }
                                }
                            } catch (org.json.JSONException e) {
                                android.util.Log.e(TAG, "Error parsing views: " + e.getMessage());
                            }
                            
                            // Populate Tap Mappings table
                            try {
                                if (finalChannelConfig.has("quadrants")) {
                                    org.json.JSONObject quadrants = finalChannelConfig.getJSONObject("quadrants");
                                    if (configTapMappingsHeader != null) {
                                        configTapMappingsHeader.setVisibility(android.view.View.VISIBLE);
                                    }
                                    if (configTapMappingsTable != null) {
                                        configTapMappingsTable.removeAllViews();
                                        // Add header row
                                        addTableHeaderRow(configTapMappingsTable, "Quadrant", "Action");
                                        
                                        // Order quadrants logically
                                        String[] quadrantOrder = {
                                            "TOP_LEFT", "TOP_CENTER", "TOP_RIGHT",
                                            "MIDDLE_LEFT", "MIDDLE_CENTER", "MIDDLE_RIGHT",
                                            "BOTTOM_LEFT", "BOTTOM_CENTER", "BOTTOM_RIGHT"
                                        };
                                        
                                        for (String quadrant : quadrantOrder) {
                                            if (quadrants.has(quadrant)) {
                                                String viewId = quadrants.getString(quadrant);
                                                String displayQuadrant = formatQuadrantName(quadrant);
                                                addTableDataRow(configTapMappingsTable, displayQuadrant, viewId);
                                            }
                                        }
                                        configTapMappingsTable.setVisibility(android.view.View.VISIBLE);
                                    }
                                }
                            } catch (org.json.JSONException e) {
                                android.util.Log.e(TAG, "Error parsing quadrants: " + e.getMessage());
                            }
                            
                            // Populate Schedules table
                            try {
                                if (!finalViewSchedules.isEmpty() && finalChannelConfig.has("views")) {
                                    org.json.JSONArray views = finalChannelConfig.getJSONArray("views");
                                    if (configSchedulesHeader != null) {
                                        configSchedulesHeader.setVisibility(android.view.View.VISIBLE);
                                    }
                                    if (configSchedulesTable != null) {
                                        configSchedulesTable.removeAllViews();
                                        // Add header row
                                        addTableHeaderRow(configSchedulesTable, "View ID", "Schedule");
                                        // Add data rows
                                        for (int i = 0; i < views.length(); i++) {
                                            String viewId = views.getString(i);
                                            String schedule = finalViewSchedules.containsKey(viewId) 
                                                ? finalViewSchedules.get(viewId) 
                                                : "No schedule data";
                                            addTableDataRow(configSchedulesTable, viewId, schedule);
                                        }
                                        configSchedulesTable.setVisibility(android.view.View.VISIBLE);
                                    }
                                }
                            } catch (org.json.JSONException e) {
                                android.util.Log.e(TAG, "Error parsing schedules: " + e.getMessage());
                            }
                        }
                    });
                    
                } catch (Exception e) {
                    android.util.Log.e(TAG, "Error loading server config: " + e.getMessage(), e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (configInfoMessage != null) {
                                configInfoMessage.setText("Error loading server configuration:\n" + e.getMessage() + 
                                    "\n\nMake sure server is running and accessible.");
                                configInfoMessage.setVisibility(android.view.View.VISIBLE);
                            }
                            hideAllTables();
                        }
                    });
                }
            }
        }).start();
    }
    
    private void hideAllTables() {
        if (configChannelHeader != null) configChannelHeader.setVisibility(android.view.View.GONE);
        if (configViewsHeader != null) configViewsHeader.setVisibility(android.view.View.GONE);
        if (configViewsTable != null) configViewsTable.setVisibility(android.view.View.GONE);
        if (configTapMappingsHeader != null) configTapMappingsHeader.setVisibility(android.view.View.GONE);
        if (configTapMappingsTable != null) configTapMappingsTable.setVisibility(android.view.View.GONE);
        if (configSchedulesHeader != null) configSchedulesHeader.setVisibility(android.view.View.GONE);
        if (configSchedulesTable != null) configSchedulesTable.setVisibility(android.view.View.GONE);
    }
    
    private void addTableHeaderRow(TableLayout table, String col1, String col2) {
        TableRow row = new TableRow(this);
        row.setBackgroundColor(0xFF444444);
        
        TextView tv1 = new TextView(this);
        tv1.setText(col1);
        tv1.setTextColor(0xFFFFFFFF);
        tv1.setTextSize(12);
        tv1.setPadding(8, 8, 8, 8);
        tv1.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(tv1);
        
        TextView tv2 = new TextView(this);
        tv2.setText(col2);
        tv2.setTextColor(0xFFFFFFFF);
        tv2.setTextSize(12);
        tv2.setPadding(8, 8, 8, 8);
        tv2.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(tv2);
        
        table.addView(row);
    }
    
    private void addTableDataRow(TableLayout table, String col1, String col2) {
        TableRow row = new TableRow(this);
        
        TextView tv1 = new TextView(this);
        tv1.setText(col1);
        tv1.setTextColor(0xFFCCCCCC);
        tv1.setTextSize(11);
        tv1.setPadding(8, 6, 8, 6);
        row.addView(tv1);
        
        TextView tv2 = new TextView(this);
        tv2.setText(col2);
        tv2.setTextColor(0xFFCCCCCC);
        tv2.setTextSize(11);
        tv2.setPadding(8, 6, 8, 6);
        row.addView(tv2);
        
        table.addView(row);
    }
    
    private String formatQuadrantName(String quadrant) {
        // Convert TOP_LEFT -> "Top Left", etc.
        String[] parts = quadrant.split("_");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) result.append(" ");
            String part = parts[i].toLowerCase();
            result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return result.toString();
    }
    
    private String formatDayName(String day) {
        // Convert "mon" -> "Mon", "tue" -> "Tue", etc.
        if (day == null || day.length() == 0) return day;
        return Character.toUpperCase(day.charAt(0)) + day.substring(1).toLowerCase();
    }
    
    @Override
    public void onBackPressed() {
        // Allow back button to close config screen
        finish();
    }
}
