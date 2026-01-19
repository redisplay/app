package com.redisplay.app;

import android.app.Activity;
import android.os.Build;
import com.redisplay.app.BuildConfig;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.SslErrorHandler;
import android.net.http.SslError;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import com.redisplay.app.utils.GradientHelper;
import com.redisplay.app.utils.QuadrantDetector;
import com.redisplay.app.utils.RippleView;
import com.redisplay.app.utils.ConfigManager;
import com.redisplay.app.network.ConnectionProvider;
import com.redisplay.app.network.SseConnectionProvider;
import com.redisplay.app.network.InternalServerConnectionProvider;
import com.redisplay.app.server.InternalHttpServer;
import com.redisplay.app.server.InternalViewManager;
import com.redisplay.app.server.InternalChannelConfig;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class MainActivity extends Activity implements ConnectionProvider.ConnectionListener {
    private static final String TAG = "MainActivity";
    
    // Update checking is always enabled - runs on startup and every 24 hours
    
    // Set to true to show debug bar with view information
    private static final boolean SHOW_DEBUG_BAR = false;
    
    // Use BuildConfig.DEBUG to show detailed error messages in debug builds only
    private static final boolean DEBUG_MODE = BuildConfig.DEBUG;
    
    // Fade animation duration in milliseconds
    private static final int FADE_OUT_DURATION_MS = 500;
    private static final int FADE_IN_DURATION_MS = 500; // Same duration as fade-out for consistency
    private TextView contentText;
    private ImageView contentImage;
    private ImageView qrCodeImage;
    private LinearLayout configScreenLayout;
    private TextView configServerAddress;
    private WebView contentWebView;
    private View contentContainer;
    private View errorLayout;
    private Button retryButton;
    private Button screenOffButton;
    private TextView debugBar;
    private RippleView rippleView;
    private View brightnessOverlay;
    private View brightnessBarEmpty;
    private View brightnessBarFill;
    private TextView brightnessText;
    // private GestureDetector gestureDetector; // Removed for custom drag handling
    private Handler handler;
    
    // Drag/Swipe state variables
    private float touchStartX;
    private float touchStartY;
    private float viewStartX;
    private boolean isDragging = false;
    private boolean isBrightnessGesture = false;
    private float initialBrightness = -1f;
    private int touchSlop;
    private int screenWidth;
    private int screenHeight;
    private boolean isScreenOff = false; // Track if screen is off (paused)
    private boolean isClockMode = false; // Track if we are in forced clock mode
    private java.util.Map<String, String> quadrantMap = new java.util.HashMap<String, String>();
    private java.util.Map<String, String> viewNamesMap = new java.util.HashMap<String, String>();
    private String currentChannel = "test";
    private ContentManager contentManager;
    private NetworkChangeReceiver networkChangeReceiver;
    private android.content.IntentFilter networkFilter;
    private PowerManager.WakeLock wakeLock;
    private WindowManager.LayoutParams layoutParams;
    private JSONObject currentContentItem = null; // Track current content for state saving
    // Server URL
    private String serverUrl;
    private ConfigManager configManager;
    private ConnectionProvider connectionProvider;
    private static final int RECONNECT_DELAY = 5000; // 5 seconds
    
    // Internal server components (singleton instances)
    private static InternalViewManager internalViewManager;
    private static InternalChannelConfig internalChannelConfig;
    private static InternalHttpServer internalHttpServer; // Singleton server instance
    private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    
    // Long press detection for config screen
    private boolean isLongPressHandled = false;
    private static final long LONG_PRESS_TIMEOUT = 1000; // 1 second for long press
    private Runnable longPressRunnable;
    private float longPressStartX;
    private float longPressStartY;

    public String getServerUrl() {
        return serverUrl;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Initialize config manager
        configManager = new ConfigManager(this);
        
        // Get configured URL (or empty if not set)
        serverUrl = configManager.getServerUrl();
        
        // Get configured channel name (or default "test")
        currentChannel = configManager.getChannelName();
        
        // Check connection type
        String connectionType = configManager.getConnectionType();
        
        if ("internal".equals(connectionType)) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Using internal server mode");
            }
            // Will show server address after connection is established
        } else if (serverUrl != null && !serverUrl.isEmpty()) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Using server URL: " + serverUrl);
            }
        } else {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "No server URL configured - use long-press middle center to access config");
            }
            // Show a message to user
            Toast.makeText(this, "Long-press middle center to configure server", Toast.LENGTH_LONG).show();
        }
        
        // Disable lock screen / keyguard
        disableKeyguard();
        
        // Wake up screen if locked
        wakeUpScreen();
        
        // Initialize screen control
        layoutParams = getWindow().getAttributes();
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, "RedisplayApp:ScreenControl");
        
        // Keep screen on by default
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        // Show when locked and dismiss keyguard
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        
        // Hide system UI for redisplay mode
        hideSystemUI();
        
        setContentView(R.layout.activity_main);
        
        contentText = (TextView) findViewById(R.id.contentText);
        contentImage = (ImageView) findViewById(R.id.contentImage);
        contentWebView = (WebView) findViewById(R.id.contentWebView);
        contentContainer = findViewById(R.id.contentContainer);
        qrCodeImage = (ImageView) findViewById(R.id.qrCodeImage);
        configScreenLayout = (LinearLayout) findViewById(R.id.configScreenLayout);
        configServerAddress = (TextView) findViewById(R.id.configServerAddress);
        errorLayout = findViewById(R.id.errorLayout);
        retryButton = (Button) findViewById(R.id.retryButton);
        screenOffButton = (Button) findViewById(R.id.screenOffButton);
        debugBar = (TextView) findViewById(R.id.debugBar);
        rippleView = (RippleView) findViewById(R.id.rippleView);
        brightnessOverlay = findViewById(R.id.brightnessOverlay);
        brightnessBarEmpty = findViewById(R.id.brightnessBarEmpty);
        brightnessBarFill = findViewById(R.id.brightnessBarFill);
        brightnessText = (TextView) findViewById(R.id.brightnessText);
        handler = new Handler();

        // Install HTTP response cache
        try {
            File httpCacheDir = new File(getCacheDir(), "http");
            long httpCacheSize = 10 * 1024 * 1024; // 10 MiB
            android.net.http.HttpResponseCache.install(httpCacheDir, httpCacheSize);
            Log.d(TAG, "HTTP response cache installed");
        } catch (Exception e) {
            Log.e(TAG, "HTTP response cache installation failed: " + e);
        }
        
        // Initialize touch parameters
        ViewConfiguration vc = ViewConfiguration.get(this);
        touchSlop = vc.getScaledTouchSlop();
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        
        // Set up retry button
        if (retryButton != null) {
            retryButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    retryConnection();
                }
            });
        }
        
        // Show/hide debug bar based on flag
        if (debugBar != null) {
            debugBar.setVisibility(SHOW_DEBUG_BAR ? View.VISIBLE : View.GONE);
            // Bring debug bar to front to ensure it's always on top
            if (SHOW_DEBUG_BAR) {
                debugBar.bringToFront();
            }
        }
        
        // Set up screen off button click listener
        if (screenOffButton != null) {
            screenOffButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // Hide button and turn screen off using fade trick
                    screenOffButton.setVisibility(View.GONE);
                    turnScreenOff();
                }
            });
        }
        
        // Initialize WebView
        contentWebView.getSettings().setJavaScriptEnabled(true);
        contentWebView.getSettings().setLoadWithOverviewMode(true);
        contentWebView.getSettings().setUseWideViewPort(true);
        
        // Configure WebView for SSL compatibility with older Android
        contentWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // Proceed with SSL error for older Android compatibility
                handler.proceed();
            }
        });
        
        // Initialize content manager
        contentManager = new ContentManager(this);
        
        // Register network change receiver
        networkChangeReceiver = new NetworkChangeReceiver(this);
        networkFilter = new android.content.IntentFilter(android.net.ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(networkChangeReceiver, networkFilter);
        
        // Start internal server if using internal mode (start early so it's always available)
        if ("internal".equals(connectionType)) {
            startInternalServerIfNeeded();
        }
        
        // Initialize Connection Provider (Default to SSE) - only if server URL is set
        if (serverUrl != null && !serverUrl.isEmpty()) {
            initializeConnection();
            connectionProvider.connect();
        } else if ("internal".equals(connectionType)) {
            // For internal server, initialize connection even without server URL
            initializeConnection();
            if (connectionProvider != null) {
                connectionProvider.connect();
            }
        }
        
        // Fetch configuration
        fetchConfiguration();
        
        // Check for updates on startup and schedule periodic checks
        checkForUpdates();
        handler.postDelayed(updateCheckRunnable, UpdateChecker.CHECK_INTERVAL);
    }
    
    private void startInternalServerIfNeeded() {
        // Initialize singleton instances if needed
        if (internalViewManager == null) {
            internalViewManager = new InternalViewManager();
            internalViewManager.setContext(this); // Set context for persistence
        }
        if (internalChannelConfig == null) {
            internalChannelConfig = new InternalChannelConfig(this);
        }
        // Link view manager to channel config for rotation scheduling
        internalViewManager.setChannelConfig(internalChannelConfig);
        
        // Initialize singleton server if needed
        if (internalHttpServer == null) {
            try {
                Log.i(TAG, "Creating singleton internal HTTP server...");
                internalHttpServer = new InternalHttpServer(this, internalViewManager, internalChannelConfig);
                // Set server reference in view manager for SSE broadcasting
                internalViewManager.setServer(internalHttpServer);
                Log.i(TAG, "Starting server in background thread...");
                
                // Start server in background thread to avoid blocking
                final InternalHttpServer serverInstance = internalHttpServer;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            serverInstance.start();
                            String address = serverInstance.getServerAddress();
                            Log.i(TAG, "Singleton server started successfully: " + address);
                            
                            // Verify server is actually running
                            try {
                                if (serverInstance.isAlive()) {
                                    Log.i(TAG, "Server is alive and listening on port: " + serverInstance.getListeningPort());
                                } else {
                                    Log.e(TAG, "Server started but isAlive() returns false!");
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "Could not check if server is alive: " + e.getMessage());
                            }
                            
                            // Don't show temporary "Server running at" message - config screen will show it
                        } catch (Exception e) {
                            Log.e(TAG, "Error starting server in background thread: " + e.getMessage(), e);
                            final String errorMsg = e.getMessage();
                            if (contentText != null) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        contentText.setText("Server failed to start:\n" + errorMsg);
                                        contentText.setVisibility(View.VISIBLE);
                                    }
                                });
                            }
                        }
                    }
                }).start();
            } catch (Exception e) {
                Log.e(TAG, "Error creating singleton server: " + e.getMessage(), e);
                if (contentText != null) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            contentText.setText("Server failed to start:\n" + e.getMessage());
                            contentText.setVisibility(View.VISIBLE);
                        }
                    });
                }
            }
        } else {
            String address = internalHttpServer.getServerAddress();
            Log.d(TAG, "Internal server already running: " + address);
            // Server is already running, no need to restart
        }
    }
    
    public static String getInternalServerAddress() {
        if (internalHttpServer != null) {
            return internalHttpServer.getServerAddress();
        }
        return null;
    }
    
    // Static method to start internal server from ConfigActivity
    public static void startInternalServerFromConfig(android.content.Context context) {
        // This will be called from ConfigActivity to start the server
        // We need to ensure the singletons are initialized
        if (internalViewManager == null) {
            internalViewManager = new InternalViewManager();
            internalViewManager.setContext(context);
        }
        if (internalChannelConfig == null) {
            internalChannelConfig = new InternalChannelConfig(context);
        }
        // Link view manager to channel config
        internalViewManager.setChannelConfig(internalChannelConfig);
        
        // Start server if not already started
        if (internalHttpServer == null) {
            try {
                Log.i(TAG, "Starting internal server from ConfigActivity...");
                internalHttpServer = new InternalHttpServer(context, internalViewManager, internalChannelConfig);
                internalViewManager.setServer(internalHttpServer);
                
                final InternalHttpServer serverInstance = internalHttpServer;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            serverInstance.start();
                            Log.i(TAG, "Internal server started from ConfigActivity on port " + serverInstance.getPort());
                        } catch (Exception e) {
                            Log.e(TAG, "Error starting internal server from ConfigActivity: " + e.getMessage(), e);
                        }
                    }
                }).start();
            } catch (Exception e) {
                Log.e(TAG, "Error creating internal server from ConfigActivity: " + e.getMessage(), e);
            }
        }
    }
    
    private void initializeConnection() {
        // Disconnect existing connection
        if (connectionProvider != null) {
            connectionProvider.disconnect();
            connectionProvider = null;
        }
        
        // Note: We don't stop the singleton server here - it persists across reconnections
        
        // Check connection type
        String connectionType = configManager.getConnectionType();
        
        if ("internal".equals(connectionType)) {
            // Use internal server
            Log.d(TAG, "Using internal server connection");
            
            // Ensure server is started
            startInternalServerIfNeeded();
            
            if (internalHttpServer == null) {
                Log.e(TAG, "Internal server not available");
                showError("Internal server failed to start");
                return;
            }
            
            String channel = currentChannel != null ? currentChannel : "test";
            connectionProvider = new InternalServerConnectionProvider(
                this,
                internalViewManager, 
                internalChannelConfig, 
                channel, 
                this,
                internalHttpServer // Pass the singleton server instance
            );
        } else {
            // Use remote connection (SSE)
            if (serverUrl != null && !serverUrl.isEmpty()) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Using SSE connection: " + serverUrl);
                }
                connectionProvider = new SseConnectionProvider(serverUrl, this);
            } else {
                Log.w(TAG, "No server URL configured");
                showError("No server URL configured. Please configure in settings (long-press middle center).");
            }
        }
    }
    
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // Handle custom drag/swipe logic attached to the finger
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                touchStartX = ev.getX();
                touchStartY = ev.getY();
                viewStartX = contentContainer.getTranslationX();
                isDragging = false;
                isBrightnessGesture = false;
                isLongPressHandled = false;
                
                // Secret gesture for setup: 4-finger tap
                if (ev.getPointerCount() == 4) {
                    Intent intent = new Intent(MainActivity.this, SetupActivity.class);
                    startActivity(intent);
                    finish();
                    return true;
                }
                
                // Start long press detection for MIDDLE_CENTER quadrant
                longPressStartX = ev.getX();
                longPressStartY = ev.getY();
                String downQuadrant = QuadrantDetector.getQuadrant(longPressStartX, longPressStartY, screenWidth, screenHeight);
                
                if (QuadrantDetector.MIDDLE_CENTER.equals(downQuadrant)) {
                    longPressRunnable = new Runnable() {
                        @Override
                        public void run() {
                            if (!isDragging && !isBrightnessGesture && !isLongPressHandled) {
                                isLongPressHandled = true;
                                Log.d(TAG, "Long press detected - opening config screen");
                                
                                // Show ripple feedback
                                if (rippleView != null) {
                                    rippleView.setColor(0x802196F3); // Blue ripple for config
                                    rippleView.triggerRipple(longPressStartX, longPressStartY, RippleView.TYPE_TEXT, "⚙");
                                    rippleView.bringToFront();
                                }
                                
                                // Vibrate feedback if available
                                try {
                                    android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                                    if (vibrator != null && vibrator.hasVibrator()) {
                                        vibrator.vibrate(50); // 50ms vibration
                                    }
                                } catch (SecurityException e) {
                                    // Vibration permission not granted - ignore silently
                                    Log.d(TAG, "Vibration not available: " + e.getMessage());
                                }
                                
                                // Small delay to show ripple
                                handler.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            Intent intent = new Intent(MainActivity.this, ConfigActivity.class);
                                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                            Log.d(TAG, "Starting ConfigActivity...");
                                            startActivity(intent);
                                            Log.d(TAG, "ConfigActivity started successfully");
                                        } catch (Exception e) {
                                            Log.e(TAG, "Error starting ConfigActivity: " + e.getMessage(), e);
                                            Toast.makeText(MainActivity.this, "Error opening config: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                        }
                                    }
                                }, 200); // 200ms delay to show ripple
                            }
                        }
                    };
                    handler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT);
                }
                break;
                
            case MotionEvent.ACTION_MOVE:
                float dx = ev.getX() - touchStartX;
                float dy = ev.getY() - touchStartY;
                
                // Cancel long press if finger moved too much
                if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                    if (longPressRunnable != null) {
                        handler.removeCallbacks(longPressRunnable);
                        longPressRunnable = null;
                    }
                }
                
                if (!isDragging && !isBrightnessGesture) {
                    // Check if we should start dragging (horizontal swipe)
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                        isDragging = true;
                    }
                    // Check for vertical swipe (Brightness)
                    else if (Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx)) {
                        // Check if starting from top edge (avoid conflict with system notification shade)
                        float density = getResources().getDisplayMetrics().density;
                        float topEdgeLimit = 50 * density; // 50dp
                        
                        if (touchStartY > topEdgeLimit) {
                            isBrightnessGesture = true;
                            if (layoutParams.screenBrightness < 0) {
                                // Try to get system brightness if window attr is not set
                                try {
                                    int sysBrightness = android.provider.Settings.System.getInt(
                                        getContentResolver(), 
                                        android.provider.Settings.System.SCREEN_BRIGHTNESS);
                                    initialBrightness = sysBrightness / 255.0f;
                                } catch (Exception e) {
                                    initialBrightness = 0.5f;
                                }
                            } else {
                                initialBrightness = layoutParams.screenBrightness;
                            }
                            
                            // Show Overlay
                            if (brightnessOverlay != null) {
                                brightnessOverlay.setVisibility(View.VISIBLE);
                                brightnessOverlay.bringToFront();
                                
                                // Initialize progress
                                int percent = (int)(initialBrightness * 100);
                                if (brightnessText != null) brightnessText.setText(percent + "%");
                                
                                // Update bar weights
                                if (brightnessBarFill != null && brightnessBarEmpty != null) {
                                    LinearLayout.LayoutParams fillParams = (LinearLayout.LayoutParams) brightnessBarFill.getLayoutParams();
                                    LinearLayout.LayoutParams emptyParams = (LinearLayout.LayoutParams) brightnessBarEmpty.getLayoutParams();
                                    fillParams.weight = initialBrightness;
                                    emptyParams.weight = 1.0f - initialBrightness;
                                    brightnessBarFill.setLayoutParams(fillParams);
                                    brightnessBarEmpty.setLayoutParams(emptyParams);
                                }
                            }
                        }
                    }

                    if (isDragging || isBrightnessGesture) {
                        // Send CANCEL to children to stop them from handling touch
                        MotionEvent cancelEvent = MotionEvent.obtain(ev);
                        cancelEvent.setAction(MotionEvent.ACTION_CANCEL);
                        super.dispatchTouchEvent(cancelEvent);
                        cancelEvent.recycle();
                    }
                }
                
                if (isDragging) {
                    // Move the view with the finger
                    contentContainer.setTranslationX(viewStartX + dx);
                    return true; // Consume event
                }

                if (isBrightnessGesture) {
                    // Up = Brighter (dy is negative), Down = Dimmer
                    // Full screen height = 1.0 change
                    // Multiply by 2.0 to make it more sensitive
                    float delta = (-dy / screenHeight) * 2.0f; 
                    float newBrightness = initialBrightness + delta;
                    
                    if (newBrightness < 0.01f) newBrightness = 0.01f;
                    if (newBrightness > 1.0f) newBrightness = 1.0f;
                    
                    layoutParams.screenBrightness = newBrightness;
                    getWindow().setAttributes(layoutParams);
                    
                    // Update UI
                    int percent = (int)(newBrightness * 100);
                    if (brightnessText != null) brightnessText.setText(percent + "%");
                    
                    if (brightnessBarFill != null && brightnessBarEmpty != null) {
                        LinearLayout.LayoutParams fillParams = (LinearLayout.LayoutParams) brightnessBarFill.getLayoutParams();
                        LinearLayout.LayoutParams emptyParams = (LinearLayout.LayoutParams) brightnessBarEmpty.getLayoutParams();
                        fillParams.weight = newBrightness;
                        emptyParams.weight = 1.0f - newBrightness;
                        brightnessBarFill.setLayoutParams(fillParams);
                        brightnessBarEmpty.setLayoutParams(emptyParams);
                    }
                    
                    return true;
                }
                break;
                
            case MotionEvent.ACTION_UP:
                // Cancel long press if finger lifted
                if (longPressRunnable != null) {
                    handler.removeCallbacks(longPressRunnable);
                    longPressRunnable = null;
                }
                
                // Get quadrant for this tap
                String upQuadrant = QuadrantDetector.getQuadrant(ev.getX(), ev.getY(), screenWidth, screenHeight);
                
                // Hide Brightness Overlay
                if (isBrightnessGesture && brightnessOverlay != null) {
                     // Fade out overlay
                     brightnessOverlay.animate()
                        .alpha(0f)
                        .setDuration(500)
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                brightnessOverlay.setVisibility(View.GONE);
                                brightnessOverlay.setAlpha(1f); // Reset for next time
                            }
                        })
                        .start();
                     isBrightnessGesture = false;
                     return true;
                }

                // Handle Tap (but not if long press was handled for MIDDLE_CENTER)
                float totalDx = ev.getX() - touchStartX;
                float totalDy = ev.getY() - touchStartY;
                
                // Only check isLongPressHandled for MIDDLE_CENTER quadrant
                boolean shouldBlockTap = QuadrantDetector.MIDDLE_CENTER.equals(upQuadrant) && isLongPressHandled;
                
                if (!isDragging && !isBrightnessGesture && !shouldBlockTap && Math.abs(totalDx) < touchSlop && Math.abs(totalDy) < touchSlop) {
                     // This is a tap!
                     handleTap(ev.getX(), ev.getY());
                     // Reset long press flag after handling tap
                     isLongPressHandled = false;
                     return true;
                }
                
                // Reset long press flag if tap wasn't handled (to prevent blocking future taps)
                if (!shouldBlockTap) {
                    isLongPressHandled = false;
                }
                
                if (isDragging) {
                    handleDragEnd(ev.getX() - touchStartX);
                    isDragging = false;
                    return true;
                }
                break;
                
            case MotionEvent.ACTION_CANCEL:
                // Cancel long press
                if (longPressRunnable != null) {
                    handler.removeCallbacks(longPressRunnable);
                    longPressRunnable = null;
                }
                
                if (brightnessOverlay != null && brightnessOverlay.getVisibility() == View.VISIBLE) {
                    brightnessOverlay.setVisibility(View.GONE);
                }
                isBrightnessGesture = false;

                if (isDragging) {
                    handleDragEnd(ev.getX() - touchStartX);
                    isDragging = false;
                    return true;
                }
                break;
        }
        
        return super.dispatchTouchEvent(ev);
    }
    
    private void handleDragEnd(float totalDx) {
        float threshold = screenWidth * 0.25f; // Trigger if dragged > 25% of width
        
        if (Math.abs(totalDx) > threshold) {
            // Trigger swipe action
            final boolean isNext = totalDx < 0; // Drag left (negative dx) -> Next
            
            // Animate off screen
            float targetX = isNext ? -screenWidth : screenWidth;
            
            contentContainer.animate()
                .translationX(targetX)
                .setDuration(250)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        // Reset view state and wait for new content
                        contentContainer.setTranslationX(0f);
                        contentContainer.setAlpha(0f); // Hide until new content fades in
                        
                        // Trigger API call
                        if (isNext) {
                            triggerViewNavigation("next");
                        } else {
                            triggerViewNavigation("previous");
                        }
                    }
                })
                .start();
        } else {
            // Bounce back to center (cancel swipe)
            contentContainer.animate()
                .translationX(0f)
                .setDuration(250)
                .start();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        
        // Ensure internal server is running if using internal mode
        if ("internal".equals(configManager.getConnectionType())) {
            startInternalServerIfNeeded();
        }
        
        // Reset clock mode when returning to activity (e.g., from config screen)
        // This ensures the app doesn't get stuck in paused state
        if (isClockMode) {
            Log.d(TAG, "Resuming from config screen - resetting clock mode");
            isClockMode = false;
            if (contentManager != null) {
                contentManager.restoreViewState();
            }
        }
        
        // Acquire wake lock to keep screen on
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
        }
        
        // Reload config in case it changed
        if (configManager != null) {
            String newConnectionType = configManager.getConnectionType();
            String newServerUrl = configManager.getServerUrl();
            
            // Check if connection type changed (remote <-> internal)
            boolean connectionTypeChanged = false;
            if ("internal".equals(newConnectionType) && !(connectionProvider instanceof InternalServerConnectionProvider)) {
                connectionTypeChanged = true;
            } else if (!"internal".equals(newConnectionType) && connectionProvider instanceof InternalServerConnectionProvider) {
                connectionTypeChanged = true;
            }
            
            // Reinitialize connection if settings changed
            if (connectionTypeChanged ||
                (serverUrl == null || !serverUrl.equals(newServerUrl)) || 
                (connectionProvider == null) ||
                (!"internal".equals(newConnectionType) && !(connectionProvider instanceof SseConnectionProvider))) {
                serverUrl = newServerUrl;
                Log.d(TAG, "Config changed, reinitializing connection (type: " + newConnectionType + ")");
                initializeConnection();
                if (connectionProvider != null) {
                    connectionProvider.connect();
                }
            } else if (connectionProvider != null && !connectionProvider.isRunning()) {
                Log.d(TAG, "Resuming activity, starting connection");
                connectionProvider.connect();
            }
        }
        
        // Hide system UI
        hideSystemUI();
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        
        Log.d(TAG, "Pausing activity");
        
        // Release wake lock
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        
        // Stop connection to "pause" the app
        if (connectionProvider != null) {
            connectionProvider.disconnect();
        }
    }
    
    private void hideSystemUI() {
        View decorView = getWindow().getDecorView();
        
        int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN;
            
        // Use immersive sticky mode on Android 4.4+ (API 19+)
        if (Build.VERSION.SDK_INT >= 19) {
            uiOptions |= 0x00001000; // View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        
        decorView.setSystemUiVisibility(uiOptions);
    }
    
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
            // Screen is back on - reconnect and resume event processing
            if (isScreenOff) {
                isScreenOff = false;
                Log.d(TAG, "Screen turned on - reconnecting to server");
                // Reconnect
                if (connectionProvider != null) {
                    connectionProvider.connect();
                }
            }
        } else {
            // Screen is off - disconnect from server
            if (!isScreenOff) {
                isScreenOff = true;
                Log.d(TAG, "Screen turned off - disconnecting from server");
                // Disconnect
                if (connectionProvider != null) {
                    connectionProvider.disconnect();
                }
            }
        }
    }
    
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Re-hide system UI after orientation change
        hideSystemUI();
    }
    
    // ConnectionListener implementation
    @Override
    public void onConnected() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updateStatus("Connected");
                // Hide error UI on successful connection
                if (errorLayout != null) {
                    errorLayout.setVisibility(View.GONE);
                }
                
                // If using internal server, display the server address
                if ("internal".equals(configManager.getConnectionType()) && connectionProvider instanceof InternalServerConnectionProvider) {
                    InternalServerConnectionProvider internalProvider = (InternalServerConnectionProvider) connectionProvider;
                    String serverAddress = internalProvider.getServerAddress();
                    if (serverAddress != null && contentText != null) {
                        // Check if views are loaded
                        boolean hasViews = false;
                        if (internalViewManager != null) {
                            hasViews = internalViewManager.hasViews();
                        }
                        
                        if (!hasViews) {
                            // No views loaded - keep showing config address with QR code
                            // Hide other content views
                            if (contentImage != null) contentImage.setVisibility(View.GONE);
                            if (contentWebView != null) contentWebView.setVisibility(View.GONE);
                            if (contentText != null) contentText.setVisibility(View.GONE);
                            
                            // Show combined config screen
                            if (configScreenLayout != null) {
                                configScreenLayout.setVisibility(View.VISIBLE);
                                if (configServerAddress != null) {
                                    configServerAddress.setText(serverAddress);
                                }
                                // Generate and show QR code
                                generateAndShowQRCode(serverAddress);
                            }
                            Log.i(TAG, "No views loaded - keeping config screen visible");
                        } else {
                            // Views are loaded - hide config screen
                            if (configScreenLayout != null) {
                                configScreenLayout.setVisibility(View.GONE);
                            }
                            if (qrCodeImage != null) {
                                qrCodeImage.setVisibility(View.GONE);
                            }
                        }
                    }
                }
                
                // Refetch configuration on successful (re)connection
                fetchConfiguration();
            }
        });
    }

    @Override
    public void onMessageReceived(final String message) {
        // Check if views are loaded before processing events
        boolean hasViews = false;
        String connectionType = configManager.getConnectionType();
        if ("internal".equals(connectionType) && internalViewManager != null) {
            hasViews = internalViewManager.hasViews();
        } else {
            // For remote server, assume views are available (server manages them)
            hasViews = true;
        }
        
        if (!hasViews) {
            Log.d(TAG, "Ignoring event - no views configured");
            // Keep showing config screen with QR code
            if ("internal".equals(connectionType) && connectionProvider instanceof InternalServerConnectionProvider) {
                InternalServerConnectionProvider internalProvider = (InternalServerConnectionProvider) connectionProvider;
                String serverAddress = internalProvider.getServerAddress();
                if (serverAddress != null && contentText != null) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Hide other content views
                            if (contentImage != null) contentImage.setVisibility(View.GONE);
                            if (contentWebView != null) contentWebView.setVisibility(View.GONE);
                            
                            // Hide other content views
                            if (contentImage != null) contentImage.setVisibility(View.GONE);
                            if (contentWebView != null) contentWebView.setVisibility(View.GONE);
                            if (contentText != null) contentText.setVisibility(View.GONE);
                            
                            // Show combined config screen
                            if (configScreenLayout != null) {
                                configScreenLayout.setVisibility(View.VISIBLE);
                                if (configServerAddress != null) {
                                    configServerAddress.setText(serverAddress);
                                }
                                // Ensure QR code is visible
                                if (qrCodeImage != null && qrCodeImage.getVisibility() != View.VISIBLE) {
                                    generateAndShowQRCode(serverAddress);
                                }
                            }
                        }
                    });
                }
            }
            return;
        }
        
        // Only process events if screen is on
        if (!isScreenOff) {
            // If in clock mode, exit it when server sends a new view (server takes control)
            if (isClockMode) {
                Log.d(TAG, "Server event received while in clock mode - exiting clock mode to process event");
                isClockMode = false;
                // Restore view state before processing new event
                if (contentManager != null) {
                    contentManager.restoreViewState();
                }
            }
            
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // Hide QR code when processing view events
                    if (qrCodeImage != null) {
                        qrCodeImage.setVisibility(View.GONE);
                    }
                    contentManager.handleEvent(message);
                }
            });
        } else {
            Log.d(TAG, "Ignoring event - screen is off");
        }
    }

                                @Override
    public void onError(final String error) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    updateStatus("Disconnected - Reconnecting...");
                                    showError("Connection error: " + error);
                                }
                            });
                        }

    @Override
    public void onDisconnected() {
        // Handle disconnect if needed
    }
    
    // Getter methods for modules to access views
    public TextView getContentTextView() {
        return contentText;
    }

    public ImageView getContentImageView() {
        return contentImage;
    }

    public WebView getContentWebView() {
        return contentWebView;
    }

    public View getContentContainer() {
        return contentContainer;
    }
    
    public ContentManager getContentManager() {
        return contentManager;
    }
    
    public TextView getDebugBar() {
        return debugBar;
    }
    
    public void updateDebugBar(String viewId, String viewType) {
        if (debugBar != null && SHOW_DEBUG_BAR) {
            String debugText = "View: " + (viewId != null ? viewId : "unknown") + " | Type: " + (viewType != null ? viewType : "unknown");
            debugBar.setText(debugText);
            debugBar.setVisibility(View.VISIBLE);
            // Bring to front to ensure it's always on top
            debugBar.bringToFront();
            // Also bring parent to front if needed
            View parent = (View) debugBar.getParent();
            if (parent != null) {
                parent.bringToFront();
            }
        }
    }
    
    public int getFadeDuration() {
        return FADE_IN_DURATION_MS; // Return fade in duration for screen-on restore timing
    }
    
    public int getFadeOutDuration() {
        return FADE_OUT_DURATION_MS;
    }
    
    // Helper methods for view management
    public void hideAllContentViews() {
        // Don't hide contentText if we're showing config screen (no views configured)
        String connectionType = configManager.getConnectionType();
        boolean shouldPreserveConfigScreen = false;
        if ("internal".equals(connectionType) && internalViewManager != null) {
            shouldPreserveConfigScreen = !internalViewManager.hasViews();
        }
        
        if (contentImage != null) contentImage.setVisibility(View.GONE);
        if (!shouldPreserveConfigScreen && contentText != null) {
            // Only hide if not showing config screen
            String currentText = contentText.getText().toString();
            if (!currentText.contains("No views configured") && !currentText.contains("Internal Server Running")) {
                contentText.setVisibility(View.GONE);
            }
        }
        if (contentWebView != null) contentWebView.setVisibility(View.GONE);
        if (errorLayout != null) {
            errorLayout.setVisibility(View.GONE);
        }
    }
    
    public void clearAllContentViews() {
        // Clear all content from views to prevent flashing and free memory
        // Recycle old bitmap before clearing
        if (contentImage != null) {
            if (contentImage.getDrawable() != null && contentImage.getDrawable() instanceof android.graphics.drawable.BitmapDrawable) {
                android.graphics.drawable.BitmapDrawable bitmapDrawable = (android.graphics.drawable.BitmapDrawable) contentImage.getDrawable();
                android.graphics.Bitmap bitmap = bitmapDrawable.getBitmap();
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
            }
            contentImage.setImageBitmap(null);
            contentImage.clearAnimation();
        }
        
        if (contentText != null) {
            contentText.setText("");
            contentText.clearAnimation();
        }
        
        if (contentWebView != null) {
            // Clear WebView more thoroughly
            contentWebView.loadUrl("about:blank");
            contentWebView.clearCache(true);
            contentWebView.clearHistory();
            contentWebView.clearAnimation();
        }
        
        // Ensure views are hidden
        hideAllContentViews();
    }
    
    public Handler getHandler() {
        return handler;
    }
    
    public void setContentViewsInvisible() {
        // Set views to INVISIBLE (not GONE) so they take space but are transparent
        // This allows the container alpha animation to work properly
        if (contentImage.getVisibility() == View.VISIBLE) {
            contentImage.setVisibility(View.INVISIBLE);
        }
        if (contentText.getVisibility() == View.VISIBLE) {
            contentText.setVisibility(View.INVISIBLE);
        }
        if (contentWebView.getVisibility() == View.VISIBLE) {
            contentWebView.setVisibility(View.INVISIBLE);
        }
        if (errorLayout != null && errorLayout.getVisibility() == View.VISIBLE) {
            errorLayout.setVisibility(View.INVISIBLE);
        }
    }
    
    public void setContentViewsVisibleAtAlphaZero() {
        // Set views to VISIBLE but at alpha 0 for cross-fade effect
        if (contentImage.getVisibility() != View.GONE) {
            contentImage.setVisibility(View.VISIBLE);
            contentImage.setAlpha(0.0f);
        }
        if (contentText.getVisibility() != View.GONE) {
            contentText.setVisibility(View.VISIBLE);
            contentText.setAlpha(0.0f);
        }
        if (contentWebView.getVisibility() != View.GONE) {
            contentWebView.setVisibility(View.VISIBLE);
            contentWebView.setAlpha(0.0f);
        }
    }
    
    public void crossFadeViews(View oldImage, View oldText, View oldWebView,
                                View newImage, View newText, View newWebView,
                                Runnable onComplete) {
        // True cross-fade: fade out old views while fading in new views simultaneously
        
        // Fade out old views
        AlphaAnimation fadeOutImage = null;
        AlphaAnimation fadeOutText = null;
        AlphaAnimation fadeOutWebView = null;
        
        if (oldImage != null && oldImage.getVisibility() == View.VISIBLE) {
            fadeOutImage = new AlphaAnimation(oldImage.getAlpha(), 0.0f);
            fadeOutImage.setDuration(FADE_OUT_DURATION_MS);
            fadeOutImage.setFillAfter(true);
        }
        if (oldText != null && oldText.getVisibility() == View.VISIBLE) {
            fadeOutText = new AlphaAnimation(oldText.getAlpha(), 0.0f);
            fadeOutText.setDuration(FADE_OUT_DURATION_MS);
            fadeOutText.setFillAfter(true);
        }
        if (oldWebView != null && oldWebView.getVisibility() == View.VISIBLE) {
            fadeOutWebView = new AlphaAnimation(oldWebView.getAlpha(), 0.0f);
            fadeOutWebView.setDuration(FADE_OUT_DURATION_MS);
            fadeOutWebView.setFillAfter(true);
        }
        
        // Fade in new views
        AlphaAnimation fadeInImage = null;
        AlphaAnimation fadeInText = null;
        AlphaAnimation fadeInWebView = null;
        
        if (newImage != null && newImage.getVisibility() == View.VISIBLE) {
            fadeInImage = new AlphaAnimation(0.0f, 1.0f);
            fadeInImage.setDuration(FADE_IN_DURATION_MS);
            fadeInImage.setFillAfter(true);
        }
        if (newText != null && newText.getVisibility() == View.VISIBLE) {
            fadeInText = new AlphaAnimation(0.0f, 1.0f);
            fadeInText.setDuration(FADE_IN_DURATION_MS);
            fadeInText.setFillAfter(true);
        }
        if (newWebView != null && newWebView.getVisibility() == View.VISIBLE) {
            fadeInWebView = new AlphaAnimation(0.0f, 1.0f);
            fadeInWebView.setDuration(FADE_IN_DURATION_MS);
            fadeInWebView.setFillAfter(true);
        }
        
        // Count how many animations we're running
        final int[] animationCount = {0};
        final int totalAnimations = 
            (fadeOutImage != null ? 1 : 0) + (fadeOutText != null ? 1 : 0) + (fadeOutWebView != null ? 1 : 0) +
            (fadeInImage != null ? 1 : 0) + (fadeInText != null ? 1 : 0) + (fadeInWebView != null ? 1 : 0);
        
        Animation.AnimationListener completionListener = new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {}
            
            @Override
            public void onAnimationEnd(Animation animation) {
                synchronized (animationCount) {
                    animationCount[0]++;
                    if (animationCount[0] >= totalAnimations && onComplete != null) {
                        onComplete.run();
                    }
                }
            }
            
            @Override
            public void onAnimationRepeat(Animation animation) {}
        };
        
        // Set listeners and start all animations simultaneously
        if (fadeOutImage != null) {
            fadeOutImage.setAnimationListener(completionListener);
            oldImage.startAnimation(fadeOutImage);
        }
        if (fadeOutText != null) {
            fadeOutText.setAnimationListener(completionListener);
            oldText.startAnimation(fadeOutText);
        }
        if (fadeOutWebView != null) {
            fadeOutWebView.setAnimationListener(completionListener);
            oldWebView.startAnimation(fadeOutWebView);
        }
        if (fadeInImage != null) {
            fadeInImage.setAnimationListener(completionListener);
            newImage.startAnimation(fadeInImage);
        }
        if (fadeInText != null) {
            fadeInText.setAnimationListener(completionListener);
            newText.startAnimation(fadeInText);
        }
        if (fadeInWebView != null) {
            fadeInWebView.setAnimationListener(completionListener);
            newWebView.startAnimation(fadeInWebView);
        }
        
        // If no animations were created (e.g. all views GONE), run callback immediately
        if (totalAnimations == 0 && onComplete != null) {
            onComplete.run();
        }
    }
    
    private void retryConnection() {
        // Check if server URL is configured
        if (serverUrl == null || serverUrl.isEmpty()) {
            Toast.makeText(this, "Please configure server URL first (long-press middle center)", Toast.LENGTH_LONG).show();
                return;
            }
        
        // Hide error layout
        if (errorLayout != null) {
            errorLayout.setVisibility(View.GONE);
        }
        
        // Ensure content container is visible for connecting message
        contentContainer.setVisibility(View.VISIBLE);
        
        // Show connecting status
        contentText.setText("Connecting...");
        contentText.setVisibility(View.VISIBLE);
        
        // Restart connection
        initializeConnection();
        if (connectionProvider != null) {
            connectionProvider.connect();
            }
    }
    
    public void updateStatus(String status) {
        // Status bar removed - method kept for compatibility
    }
    
    public void loadImage(final String imageUrl) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "loadImage called with URL: " + imageUrl);
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Opening connection to: " + imageUrl);
                    }
                    URL url = new URL(imageUrl);
                    // All images now come through server proxy (HTTP), no SSL needed
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(10000);
                    connection.connect();
                    
                    int responseCode = connection.getResponseCode();
                    Log.d(TAG, "Image response code: " + responseCode);
                    
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        InputStream input = connection.getInputStream();
                        final Bitmap bitmap = BitmapFactory.decodeStream(input);
                        
                    if (bitmap != null) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                                    if (contentImage != null) {
                                contentImage.setImageBitmap(bitmap);
                                        Log.d(TAG, "Image set to ImageView");
                        }
                            }
                        });
                    } else {
                            Log.e(TAG, "Failed to decode bitmap");
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error loading image: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }).start();
    }
    
    public void showError(final String message) {
        if (!DEBUG_MODE) {
            // User friendly error UI
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                    if (errorLayout != null) {
                        errorLayout.setVisibility(View.VISIBLE);
                        errorLayout.bringToFront();
                        
                        // Hide other content
                        hideAllContentViews();
                        errorLayout.setVisibility(View.VISIBLE); // Re-show error layout as hideAll hides it
                        }
                    }
                });
            return;
    }
    
        // Detailed error for debugging
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (contentText != null) {
                    contentText.setText(message);
                    contentText.setVisibility(View.VISIBLE);
                }
            }
        });
    }
    
    private void fetchConfiguration() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Determine base URL - use internal server if in internal mode
                    String baseUrl = serverUrl;
                    String connectionType = configManager.getConnectionType();
                    if ("internal".equals(connectionType)) {
                        if (internalHttpServer != null) {
                            // For local connections from the app itself, use localhost (more reliable)
                            int port = internalHttpServer.getPort();
                            baseUrl = "http://localhost:" + port;
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "Using internal server (localhost) for config: " + baseUrl);
                            }
                        } else {
                            Log.w(TAG, "Internal server not available yet, skipping config fetch");
                            return;
                        }
                    }
                    
                    if (baseUrl == null || baseUrl.isEmpty()) {
                        Log.d(TAG, "No server URL configured, skipping config fetch");
                        return;
                    }
                    
                    // Fetch Channels
                    String channelsUrl = baseUrl + "/api/channels";
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Fetching channels from: " + channelsUrl);
                    }
                    
                    URL url = new URL(channelsUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    
                    if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line);
                        reader.close();
                        
                        JSONObject channelsConfig = new JSONObject(sb.toString());
                        if (channelsConfig.has("channels")) {
                             JSONObject channels = channelsConfig.getJSONObject("channels");
                             // For now just use "test" channel or first available
                             if (channels.has("test")) {
                                 currentChannel = "test";
                             } else if (channels.keys().hasNext()) {
                                 currentChannel = channels.keys().next();
                             }
                             
                             // 1. Fetch Views metadata to build viewNamesMap
                             try {
                                 String viewsUrl = baseUrl + "/api/views";
                                 URL vUrl = new URL(viewsUrl);
                                 HttpURLConnection vConn = (HttpURLConnection) vUrl.openConnection();
                                 if (vConn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                                     BufferedReader vReader = new BufferedReader(new InputStreamReader(vConn.getInputStream()));
                                     StringBuilder vSb = new StringBuilder();
                                     while ((line = vReader.readLine()) != null) vSb.append(line);
                                     vReader.close();
                                     
                                     // Parse views - could be JSONArray or JSONObject
                                     String viewsResponse = vSb.toString();
                                     int viewCount = 0;
                                     
                                     try {
                                         // Try as JSONArray first (internal server format)
                                         org.json.JSONArray viewsArray = new org.json.JSONArray(viewsResponse);
                                         viewCount = viewsArray.length();
                                         Log.d(TAG, "Loaded " + viewCount + " views from array");
                                         
                                         // If using internal server, check if views are now available
                                         if ("internal".equals(connectionType) && internalViewManager != null) {
                                             boolean hasViews = internalViewManager.hasViews();
                                             if (!hasViews && viewCount > 0) {
                                                 // Views were just loaded - hide config screen
                                                 runOnUiThread(new Runnable() {
                                                     @Override
                                                     public void run() {
                                                         if (contentText != null && contentText.getText().toString().contains("No views configured")) {
                                                             contentText.setText("");
                                                         }
                                                     }
                                                 });
                                             } else if (!hasViews) {
                                                 // Still no views - keep showing config screen
                                                 if (internalHttpServer != null) {
                                                     final String serverAddress = internalHttpServer.getServerAddress();
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            // Hide other content views
                                            if (contentImage != null) contentImage.setVisibility(View.GONE);
                                            if (contentWebView != null) contentWebView.setVisibility(View.GONE);
                                            if (contentText != null) contentText.setVisibility(View.GONE);
                                            
                                            // Show combined config screen
                                            if (configScreenLayout != null) {
                                                configScreenLayout.setVisibility(View.VISIBLE);
                                                if (configServerAddress != null) {
                                                    configServerAddress.setText(serverAddress);
                                                }
                                                // Generate and show QR code
                                                generateAndShowQRCode(serverAddress);
                                            }
                                        }
                                    });
                                                 }
                                             }
                                         }
                                         
                                         // Process array items for view names
                                         for (int i = 0; i < viewsArray.length(); i++) {
                                             JSONObject viewObj = viewsArray.getJSONObject(i);
                                             String viewId = viewObj.optString("id", "");
                                             if (viewId.isEmpty() && viewObj.has("view")) {
                                                 JSONObject view = viewObj.getJSONObject("view");
                                                 viewId = view.optString("id", "");
                                             }
                                             if (!viewId.isEmpty()) {
                                                 String friendlyName = viewId;
                                                 if (viewObj.has("view")) {
                                                     JSONObject view = viewObj.getJSONObject("view");
                                                     if (view.has("data")) {
                                                         JSONObject data = view.getJSONObject("data");
                                                         if (data.has("rippleText")) friendlyName = data.getString("rippleText");
                                                         else if (data.has("label")) friendlyName = data.getString("label");
                                                         else if (data.has("title")) friendlyName = data.getString("title");
                                                         else if (data.has("location") && data.getJSONObject("location").has("name")) friendlyName = data.getJSONObject("location").getString("name");
                                                         else if (data.has("webcamId")) friendlyName = data.getString("webcamId");
                                                     }
                                                 }
                                                 viewNamesMap.put(viewId, friendlyName);
                                             }
                                         }
                                     } catch (org.json.JSONException eArray) {
                                         // Try as JSONObject (remote server format)
                                         try {
                                             JSONObject viewsJson = new JSONObject(viewsResponse);
                                             java.util.Iterator<String> keys = viewsJson.keys();
                                             while (keys.hasNext()) {
                                                 String viewId = keys.next();
                                                 JSONObject view = viewsJson.getJSONObject(viewId);
                                                 String friendlyName = viewId;
                                                 if (view.has("data")) {
                                                     JSONObject data = view.getJSONObject("data");
                                                     if (data.has("rippleText")) friendlyName = data.getString("rippleText");
                                                     else if (data.has("label")) friendlyName = data.getString("label");
                                                     else if (data.has("title")) friendlyName = data.getString("title");
                                                     else if (data.has("location") && data.getJSONObject("location").has("name")) friendlyName = data.getJSONObject("location").getString("name");
                                                     else if (data.has("webcamId")) friendlyName = data.getString("webcamId");
                                                 }
                                                 viewNamesMap.put(viewId, friendlyName);
                                                 viewCount++;
                                             }
                                         } catch (org.json.JSONException eObj) {
                                             Log.e(TAG, "Error parsing views JSON (neither array nor object): " + eObj.getMessage());
                                         }
                                     }
                                 }
                                 vConn.disconnect();
                             } catch (Exception e2) {
                                 Log.e(TAG, "Error parsing views JSON: " + e2.getMessage());
            }
        }
                    }
                    conn.disconnect();
                    
                    // 2. Fetch Channel Config for Quadrants
                    String configUrl = baseUrl + "/api/channel-config/" + currentChannel;
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Fetching config from: " + configUrl);
                    }
                    
                    url = new URL(configUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    
                    if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line);
                        reader.close();
                        
                        JSONObject config = new JSONObject(sb.toString());
                        if (config.has("quadrants")) {
                            JSONObject quadrants = config.getJSONObject("quadrants");
                            quadrantMap.clear();
                            java.util.Iterator<String> keys = quadrants.keys();
                            while (keys.hasNext()) {
                                String key = keys.next();
                                // Skip MIDDLE_CENTER - it's always handled specially for pause/resume
                                if (!"MIDDLE_CENTER".equals(key)) {
                                    quadrantMap.put(key, quadrants.getString(key));
                                }
                            }
                        }
                    }
                    conn.disconnect();
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error fetching configuration: " + e.getMessage(), e);
        }
            }
        }).start();
    }

    private void handleTap(float x, float y) {
        if (isScreenOff) {
            turnScreenOn();
            return;
        }

        String quadrant = QuadrantDetector.getQuadrant(x, y, screenWidth, screenHeight);
        Log.d(TAG, "Tap detected at (" + x + "," + y + ") -> " + quadrant);

        if (QuadrantDetector.MIDDLE_CENTER.equals(quadrant)) {
            // Special behavior for center/middle quadrant (single tap for pause/resume)
            // Note: Long press on this quadrant opens config screen (handled in dispatchTouchEvent)
            isClockMode = !isClockMode;
            
            if (isClockMode) {
                // Entering clock mode - Red Ripple with PAUSE icon
                if (rippleView != null) {
                    rippleView.setColor(0x80FF4444); // Red
                    rippleView.triggerRipple(x, y, RippleView.TYPE_PAUSE);
                    rippleView.bringToFront();
                }
                
                // Save current state so we can restore it
                if (contentManager != null) {
                    contentManager.saveCurrentViewState();
                    
                    // Display clock
                    try {
                        JSONObject clockContent = new JSONObject();
                        clockContent.put("type", "clock");
                        JSONObject view = new JSONObject();
                        JSONObject data = new JSONObject();
                        data.put("timezone", java.util.TimeZone.getDefault().getID());
                        view.put("data", data);
                        clockContent.put("view", view);
                        
                        contentManager.displayContent(clockContent);
                    } catch (Exception e) {
                        Log.e(TAG, "Error switching to clock mode: " + e.getMessage());
                    }
                }
            } else {
                // Exiting clock mode - Green Ripple with PLAY icon
                if (rippleView != null) {
                    rippleView.setColor(0x8044FF44); // Green
                    rippleView.triggerRipple(x, y, RippleView.TYPE_PLAY);
                    rippleView.bringToFront();
                }
                
                // Restore previous content
                if (contentManager != null) {
                    contentManager.restoreViewState();
                }
            }
            
            // Do not send tap to server for this special action
            return;
        }
        
        // If in clock mode (paused), ignore other taps and do not show ripple
        if (isClockMode) {
            return;
        }

        // Default behavior for other quadrants - trigger quadrant selection
        handleQuadrantSelection(quadrant, x, y);
    }
    
    private void handleQuadrantSelection(String quadrantId, float tapX, float tapY) {
        // Show ripple at tap location
        if (rippleView != null) {
            rippleView.setColor(0x80FFFFFF); // White
            
            if (QuadrantDetector.MIDDLE_LEFT.equals(quadrantId)) {
                // Show Previous Arrow
                rippleView.triggerRipple(tapX, tapY, RippleView.TYPE_PREV);
            } else if (QuadrantDetector.MIDDLE_RIGHT.equals(quadrantId)) {
                // Show Next Arrow
                rippleView.triggerRipple(tapX, tapY, RippleView.TYPE_NEXT);
            } else {
                // Determine content for this quadrant
                String viewId = quadrantMap.get(quadrantId);
                String label = null;
                int type = RippleView.TYPE_NUMBER;
                
                if (viewId != null) {
                    if ("PREVIOUS".equals(viewId)) {
                        type = RippleView.TYPE_PREV;
                    } else if ("NEXT".equals(viewId)) {
                        type = RippleView.TYPE_NEXT;
                    } else {
                        // It's a view ID
                        type = RippleView.TYPE_TEXT;
                        label = viewNamesMap.containsKey(viewId) ? viewNamesMap.get(viewId) : viewId;
                        
                        // Fallback logic for known IDs if map is empty
                        if (label.equals(viewId)) {
                            if (viewId.contains("weather")) label = "Meteo";
                            else if (viewId.contains("clock")) label = "Orologio";
                            else if (viewId.contains("webcam")) label = "Webcam";
                        }
                    }
                } else {
                    // Fallback to numbers if no mapping found
                     String num = "0";
                    if (QuadrantDetector.TOP_LEFT.equals(quadrantId)) num = "1";
                    else if (QuadrantDetector.TOP_CENTER.equals(quadrantId)) num = "2";
                    else if (QuadrantDetector.TOP_RIGHT.equals(quadrantId)) num = "3";
                    else if (QuadrantDetector.BOTTOM_LEFT.equals(quadrantId)) num = "7";
                    else if (QuadrantDetector.BOTTOM_CENTER.equals(quadrantId)) num = "8";
                    else if (QuadrantDetector.BOTTOM_RIGHT.equals(quadrantId)) num = "9";
                    label = num;
                    type = RippleView.TYPE_NUMBER;
                }
                
                // Show Ripple
                rippleView.triggerRipple(tapX, tapY, type, label);
            }
            
            rippleView.bringToFront();
        }
        
        // Trigger navigation
        triggerQuadrantTap(quadrantId);
    }

    private String getBaseUrl() {
        String connectionType = configManager.getConnectionType();
        if ("internal".equals(connectionType)) {
            if (internalHttpServer != null) {
                // Use localhost for local connections (more reliable)
                int port = internalHttpServer.getPort();
                return "http://localhost:" + port;
            } else {
                Log.w(TAG, "Internal server not available, falling back to serverUrl");
                return serverUrl != null ? serverUrl : "";
            }
        } else {
            return serverUrl != null ? serverUrl : "";
        }
    }
    
    private void triggerQuadrantTap(final String quadrant) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String baseUrl = getBaseUrl();
                    if (baseUrl == null || baseUrl.isEmpty()) {
                        Log.w(TAG, "No server URL available for tap");
                        return;
                    }
                    
                    String urlString = baseUrl + "/api/channels/" + currentChannel + "/tap";
                    
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Sending tap: " + urlString + " payload: " + quadrant);
                    }
                    URL url = new URL(urlString);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setDoOutput(true);
                    
                    String jsonInputString = "{\"quadrant\": \"" + quadrant + "\"}";
                    
                    java.io.OutputStream os = conn.getOutputStream();
                    byte[] input = jsonInputString.getBytes("utf-8");
                    os.write(input, 0, input.length);
                    os.close();
                    
                    int responseCode = conn.getResponseCode();
                    Log.d(TAG, "Tap response: " + responseCode);
                    
                    conn.disconnect();
                } catch (Exception e) {
                    Log.e(TAG, "Error triggering tap: " + e.getMessage());
                }
            }
        }).start();
    }

    private void triggerViewNavigation(final String action) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String baseUrl = getBaseUrl();
                    if (baseUrl == null || baseUrl.isEmpty()) {
                        Log.w(TAG, "No server URL available for navigation");
                        return;
                    }
                    
                    String urlString = baseUrl + "/api/channels/" + currentChannel + "/" + action;
                    
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Navigating: " + urlString);
                    }
                    URL url = new URL(urlString);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    
                    int responseCode = conn.getResponseCode();
                    Log.d(TAG, "Navigation response: " + responseCode);
                    conn.disconnect();
                } catch (Exception e) {
                    Log.e(TAG, "Error triggering navigation: " + e.getMessage());
                }
            }
        }).start();
    }
    
    // Disable keyguard (lock screen)
    private void disableKeyguard() {
        KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        KeyguardManager.KeyguardLock lock = keyguardManager.newKeyguardLock(TAG);
        lock.disableKeyguard();
    }
    
    // Wake up screen
    private void wakeUpScreen() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, TAG);
        wakeLock.acquire(1000); // Acquire for 1 second to wake up screen
    }
    
    public void turnScreenOff() {
        // We can't actually turn the screen off without root/device admin
        // But we can simulate it by:
        // 1. Showing a black screen (already done via layout)
        // 2. Dimming brightness to minimum
        // 3. Stopping network activity
        
        isScreenOff = true;
        Log.d(TAG, "Turning 'screen off' (simulated)");
        
        // Stop SSE connection
        if (connectionProvider != null) {
            connectionProvider.disconnect();
        }
        
        // Clear views
        clearAllContentViews();
        
        // Dim brightness to minimum
        layoutParams.screenBrightness = 0.01f;
        getWindow().setAttributes(layoutParams);
    }
    
    public void turnScreenOn() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                isScreenOff = false;
                
                // Restore brightness
                layoutParams.screenBrightness = -1.0f; // System default
                getWindow().setAttributes(layoutParams);
                
                // Reconnect
                if (connectionProvider != null) {
                    connectionProvider.connect();
                }
                
                // Wake screen
                wakeUpScreen();
            }
        });
    }
    
    public void setScreenBrightness(int brightness) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // brightness: 0-255 (0 = off, 255 = full)
                if (layoutParams != null) {
                    float brightnessFloat = brightness / 255.0f;
                    if (brightnessFloat < 0.01f) brightnessFloat = 0.01f;
                    if (brightnessFloat > 1.0f) brightnessFloat = 1.0f;
                    layoutParams.screenBrightness = brightnessFloat;
                    getWindow().setAttributes(layoutParams);
                }
            }
        });
    }
    
    public void fadeOutContent(Runnable onComplete) {
        AlphaAnimation fadeOut = new AlphaAnimation(1.0f, 0.0f);
        fadeOut.setDuration(FADE_OUT_DURATION_MS);
        fadeOut.setFillAfter(true);
        fadeOut.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {}
            
            @Override
            public void onAnimationEnd(Animation animation) {
                contentContainer.setAlpha(0.0f);
                if (onComplete != null) {
                    onComplete.run();
                }
            }
            
            @Override
            public void onAnimationRepeat(Animation animation) {}
        });
        contentContainer.startAnimation(fadeOut);
    }
    
    public void fadeInContent() {
        contentContainer.setVisibility(View.VISIBLE);
        contentContainer.clearAnimation();
        
        AlphaAnimation fadeIn = new AlphaAnimation(0.0f, 1.0f);
        fadeIn.setDuration(FADE_IN_DURATION_MS);
        fadeIn.setFillAfter(true);
        fadeIn.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
                contentContainer.setAlpha(1.0f);
            }
            
            @Override
            public void onAnimationEnd(Animation animation) {
                contentContainer.setAlpha(1.0f);
                contentContainer.clearAnimation();
            }
            
            @Override
            public void onAnimationRepeat(Animation animation) {}
        });
        
        contentContainer.setAlpha(1.0f);
        contentContainer.startAnimation(fadeIn);
    }
    
    public void onNetworkAvailable() {
        Log.d(TAG, "Network available - triggering reconnection");
        if (connectionProvider != null && !connectionProvider.isRunning()) {
            retryConnection();
        }
    }
    
    // Helper to start config activity
    private void openConfigScreen() {
        Intent intent = new Intent(this, ConfigActivity.class);
        startActivity(intent);
    }
    
    private void checkForUpdates() {
        UpdateChecker.checkForUpdate(this, new UpdateChecker.UpdateListener() {
            @Override
            public void onUpdateAvailable(String versionName, String downloadUrl) {
                Log.d(TAG, "Update available: " + versionName);
                // Optionally show a notification or dialog to the user
                // For now, just log it - user can manually update if needed
            }
            
            @Override
            public void onUpdateCheckFailed(String error) {
                Log.d(TAG, "Update check failed: " + error);
                // Silently fail - don't bother user with update check failures
            }
            
            @Override
            public void onUpdateDownloaded(File apkFile) {
                Log.d(TAG, "Update downloaded: " + apkFile.getAbsolutePath());
                // APK installation is handled by UpdateChecker
            }
        });
    }
    
    private Runnable updateCheckRunnable = new Runnable() {
        @Override
        public void run() {
            checkForUpdates();
            // Schedule next check (24 hours)
            handler.postDelayed(this, UpdateChecker.CHECK_INTERVAL);
        }
    };
    
    private void generateAndShowQRCode(final String text) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    QRCodeWriter writer = new QRCodeWriter();
                    java.util.Map<EncodeHintType, Object> hints = new java.util.HashMap<>();
                    hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
                    hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
                    hints.put(EncodeHintType.MARGIN, 1);
                    
                    BitMatrix bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, 400, 400, hints);
                    int width = bitMatrix.getWidth();
                    int height = bitMatrix.getHeight();
                    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
                    
                    for (int x = 0; x < width; x++) {
                        for (int y = 0; y < height; y++) {
                            bitmap.setPixel(x, y, bitMatrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                        }
                    }
                    
                    final Bitmap finalBitmap = bitmap;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (qrCodeImage != null) {
                                qrCodeImage.setImageBitmap(finalBitmap);
                                qrCodeImage.setVisibility(View.VISIBLE);
                                qrCodeImage.bringToFront();
                                Log.d(TAG, "QR code displayed for: " + text);
                            } else {
                                Log.w(TAG, "qrCodeImage is null, cannot display QR code");
                            }
                        }
                    });
                } catch (WriterException e) {
                    Log.e(TAG, "Error generating QR code: " + e.getMessage());
                }
            }
        }).start();
    }
}
