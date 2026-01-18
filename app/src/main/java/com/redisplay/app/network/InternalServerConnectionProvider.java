package com.redisplay.app.network;

import android.util.Log;
import com.redisplay.app.server.InternalHttpServer;
import com.redisplay.app.server.InternalViewManager;
import com.redisplay.app.server.InternalChannelConfig;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class InternalServerConnectionProvider implements ConnectionProvider {
    private static final String TAG = "InternalServerConnectionProvider";
    private static final int POLL_INTERVAL = 1000; // Poll every second
    
    private final String channel;
    private final ConnectionListener listener;
    private InternalHttpServer server;
    private InternalViewManager viewManager;
    private InternalChannelConfig channelConfig;
    private android.content.Context context;
    
    private volatile boolean isRunning = false;
    private Thread pollThread;
    private String lastViewId = null;
    private String serverAddress;
    
    public InternalServerConnectionProvider(android.content.Context context,
                                          InternalViewManager viewManager, 
                                          InternalChannelConfig channelConfig,
                                          String channel, 
                                          ConnectionListener listener,
                                          InternalHttpServer existingServer) {
        this.context = context;
        this.viewManager = viewManager;
        this.channelConfig = channelConfig;
        this.channel = channel;
        this.listener = listener;
        this.server = existingServer; // Use the provided server instance
        if (existingServer != null) {
            this.serverAddress = existingServer.getServerAddress();
        }
    }
    
    public String getServerAddress() {
        return serverAddress;
    }
    
    @Override
    public void connect() {
        if (isRunning) {
            disconnect();
        }
        
        isRunning = true;
        
        // Server should already be provided via constructor (singleton)
        // Just start the polling and send connection event
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Server should already be initialized (singleton)
                    if (server == null) {
                        Log.e(TAG, "Server instance is null! This should not happen.");
                        if (listener != null) {
                            listener.onError("Internal server not initialized");
                        }
                        isRunning = false;
                        return;
                    }
                    
                    serverAddress = server.getServerAddress();
                    Log.d(TAG, "Using singleton server: " + serverAddress);
                    
                    // Send initial connection event
                    if (listener != null) {
                        listener.onConnected();
                        
                        // Send initial view
                        JSONObject currentView = viewManager.getCurrentView(channel);
                        if (currentView != null) {
                            try {
                                JSONObject initialMessage = new JSONObject();
                                initialMessage.put("type", "initial_view");
                                initialMessage.put("view", currentView);
                                listener.onMessageReceived(initialMessage.toString());
                                lastViewId = currentView.optString("id");
                            } catch (Exception e) {
                                Log.e(TAG, "Error sending initial view: " + e.getMessage());
                            }
                        }
                    }
                    
                    // Start polling thread to detect view changes
                    pollThread = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            while (isRunning) {
                                try {
                                    JSONObject currentView = viewManager.getCurrentView(channel);
                                    if (currentView != null) {
                                        String currentViewId = currentView.optString("id");
                                        if (!currentViewId.equals(lastViewId)) {
                                            // View changed - send event
                                            try {
                                                JSONObject message = new JSONObject();
                                                message.put("type", "view_change");
                                                message.put("view", currentView);
                                                if (listener != null) {
                                                    listener.onMessageReceived(message.toString());
                                                }
                                                lastViewId = currentViewId;
                                            } catch (Exception e) {
                                                Log.e(TAG, "Error sending view change: " + e.getMessage());
                                            }
                                        }
                                    }
                                    
                                    Thread.sleep(POLL_INTERVAL);
                                } catch (InterruptedException e) {
                                    Log.d(TAG, "Poll thread interrupted");
                                    break;
                                } catch (Exception e) {
                                    Log.e(TAG, "Error in poll thread: " + e.getMessage());
                                }
                            }
                        }
                    });
                    pollThread.start();
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error starting internal server: " + e.getMessage(), e);
                    isRunning = false;
                    if (listener != null) {
                        listener.onError(e.getMessage());
                    }
                }
            }
        }).start();
    }
    
    @Override
    public void disconnect() {
        Log.d(TAG, "Stopping internal server connection");
        isRunning = false;
        
        if (pollThread != null) {
            pollThread.interrupt();
        }
        
        // Don't stop the HTTP server - it might be used by other components
        // The server will be stopped when the app closes
    }
    
    @Override
    public boolean isRunning() {
        return isRunning;
    }
    
    public InternalHttpServer getServer() {
        return server;
    }
}

