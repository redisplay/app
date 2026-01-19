package com.redisplay.app.network;

import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class SseConnectionProvider implements ConnectionProvider {
    private static final String TAG = "SseConnectionProvider";
    private static final int RECONNECT_DELAY = 5000;
    
    private final String serverUrl;
    private final String channel;
    private final ConnectionListener listener;
    
    private volatile boolean isRunning = false;
    private Thread sseThread;
    private HttpURLConnection currentConnection;

    public SseConnectionProvider(String serverUrl, String channel, ConnectionListener listener) {
        this.serverUrl = serverUrl;
        this.channel = channel != null ? channel : "test"; // Default to "test" if not provided
        this.listener = listener;
    }

    @Override
    public void connect() {
        if (isRunning) {
            disconnect();
        }
        
        // Wait a bit for the old connection to close (mimicking original logic)
        try {
            if (sseThread != null) {
                sseThread.join(500); 
            }
        } catch (InterruptedException e) {
            // Ignore
        }
        
        isRunning = true;
        sseThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (isRunning) {
                    try {
                        String sseUrl = serverUrl + "/sse/" + channel;
                        Log.d(TAG, "Connecting to SSE: " + sseUrl + " (channel: " + channel + ")");
                        
                        URL url = new URL(sseUrl);
                        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                        synchronized (this) {
                            currentConnection = connection;
                        }
                        connection.setRequestMethod("GET");
                        connection.setRequestProperty("Accept", "text/event-stream");
                        connection.setConnectTimeout(10000);
                        connection.setReadTimeout(30000); // 30 second read timeout to prevent getting stuck
                        
                        int responseCode = connection.getResponseCode();
                        if (responseCode == HttpURLConnection.HTTP_OK) {
                            if (listener != null) {
                                listener.onConnected();
                            }
                            
                            BufferedReader reader = null;
                            try {
                                reader = new BufferedReader(
                                new InputStreamReader(connection.getInputStream(), "UTF-8"));
                            
                            StringBuilder eventData = new StringBuilder();
                            String line;
                            
                            long lastKeepAliveTime = System.currentTimeMillis();
                                while (isRunning) {
                                    // Check if connection is still valid before reading
                                    synchronized (this) {
                                        if (currentConnection != connection) {
                                            // Connection was replaced, break out
                                            Log.d(TAG, "Connection replaced, breaking read loop");
                                            break;
                                        }
                                    }
                                    
                                    // Set a read timeout to prevent getting stuck
                                    try {
                                        line = reader.readLine();
                                    } catch (java.net.SocketTimeoutException e) {
                                        // Read timeout - check keep-alive
                                        long timeSinceLastKeepAlive = System.currentTimeMillis() - lastKeepAliveTime;
                                        if (timeSinceLastKeepAlive > 60000) {
                                            Log.w(TAG, "Read timeout and no keep-alive for " + timeSinceLastKeepAlive + "ms, reconnecting...");
                                            break;
                                        }
                                        continue;
                                    }
                                    
                                    if (line == null) {
                                        // End of stream - server closed connection
                                        Log.d(TAG, "End of stream, server closed connection");
                                        break;
                                    }
                                    
                                // Handle keep-alive messages (SSE comments starting with :)
                                if (line.startsWith(":")) {
                                    lastKeepAliveTime = System.currentTimeMillis();
                                    continue;
                                }
                                
                                if (line.startsWith("data: ")) {
                                    String data = line.substring(6); // Remove "data: " prefix
                                    eventData.append(data);
                                    lastKeepAliveTime = System.currentTimeMillis();
                                } else if (line.trim().isEmpty() && eventData.length() > 0) {
                                    // Empty line indicates end of event
                                    final String event = eventData.toString();
                                    lastKeepAliveTime = System.currentTimeMillis();
                                    
                                    if (listener != null) {
                                        listener.onMessageReceived(event);
                                    }
                                    
                                    eventData.setLength(0);
                                } else if (line.startsWith("data:")) {
                                    // Handle data without space after colon
                                    String data = line.substring(5).trim();
                                    if (!data.isEmpty()) {
                                        eventData.append(data);
                                        lastKeepAliveTime = System.currentTimeMillis();
                                    }
                                } else if (!line.trim().isEmpty()) {
                                    // Any other non-empty line resets keep-alive timer
                                    lastKeepAliveTime = System.currentTimeMillis();
                                }
                                
                                // Check if we haven't received keep-alive in 60 seconds
                                long timeSinceLastKeepAlive = System.currentTimeMillis() - lastKeepAliveTime;
                                if (timeSinceLastKeepAlive > 60000) {
                                    Log.w(TAG, "No keep-alive received for " + timeSinceLastKeepAlive + "ms, reconnecting...");
                                    break; // Break to reconnect
                                }
                            }
                            } finally {
                                // Always close reader and connection
                                if (reader != null) {
                                    try {
                            reader.close();
                                    } catch (Exception e) {
                                        Log.e(TAG, "Error closing reader: " + e.getMessage());
                                    }
                                }
                                connection.disconnect();
                                synchronized (this) {
                                    if (currentConnection == connection) {
                                        currentConnection = null;
                                    }
                                }
                            }
                        } else {
                            connection.disconnect();
                            synchronized (this) {
                                if (currentConnection == connection) {
                                    currentConnection = null;
                                }
                            }
                            if (listener != null) {
                                listener.onError("HTTP " + responseCode);
                            }
                        }
                    } catch (Exception e) {
                        if (isRunning) {
                            Log.e(TAG, "Connection error: " + e.getMessage(), e);
                            if (listener != null) {
                                listener.onError(e.getMessage());
                                listener.onDisconnected();
                            }
                            // Ensure connection is properly closed
                            synchronized (this) {
                                if (currentConnection != null) {
                                    try {
                                        currentConnection.disconnect();
                                    } catch (Exception ex) {
                                        // Ignore
                                    }
                                    currentConnection = null;
                                }
                            }
                        }
                    }
                    
                    // Reconnect after delay if still running
                    if (isRunning) {
                        try {
                            Log.d(TAG, "Waiting " + RECONNECT_DELAY + "ms before reconnecting...");
                            Thread.sleep(RECONNECT_DELAY);
                        } catch (InterruptedException e) {
                            Log.d(TAG, "Reconnect interrupted, stopping");
                            break;
                        }
                    } else {
                        Log.d(TAG, "Not running, stopping reconnect loop");
                        break;
                    }
                }
            }
        });
        sseThread.start();
    }

    @Override
    public void disconnect() {
        Log.d(TAG, "Stopping SSE connection");
        isRunning = false;
        
        synchronized (this) {
            if (currentConnection != null) {
                try {
                    currentConnection.disconnect();
                } catch (Exception e) {
                    Log.e(TAG, "Error disconnecting SSE connection: " + e.getMessage());
                }
                currentConnection = null;
            }
        }
        
        if (sseThread != null) {
            sseThread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }
    
    /**
     * Get the current channel being used for this connection.
     */
    public String getChannel() {
        return channel;
    }
}

