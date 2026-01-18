package com.redisplay.app.server;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import fi.iki.elonen.NanoHTTPD;
import android.content.Context;
import android.content.res.AssetManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.util.*;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.io.OutputStream;
import java.io.PrintWriter;

public class InternalHttpServer extends NanoHTTPD {
    private static final String TAG = "InternalHttpServer";
    private static final int STATIC_PORT = 8888; // Use static port for testing
    private static final int MIN_PORT = 8000;
    private static final int MAX_PORT = 9000;
    
    private InternalViewManager viewManager;
    private InternalChannelConfig channelConfig;
    private Context context;
    private int actualPort;
    private String serverAddress;
    
    // SSE connection management - channel -> Set of SSE clients
    private final Map<String, Set<SSEClient>> sseClients = new ConcurrentHashMap<>();
    
    // Inner class to represent an SSE client connection
    private static class SSEClient {
        final OutputStream outputStream;
        final String channel;
        final String clientId;
        volatile boolean closed = false;
        
        SSEClient(OutputStream os, String ch, String id) {
            this.outputStream = os;
            this.channel = ch;
            this.clientId = id;
        }
        
        synchronized void send(String data) {
            if (closed) return;
            try {
                outputStream.write(data.getBytes("UTF-8"));
                outputStream.flush();
            } catch (Exception e) {
                closed = true;
            }
        }
        
        void close() {
            closed = true;
            try {
                outputStream.close();
            } catch (Exception e) {
                // Ignore
            }
        }
    }
    
    // Broadcast a message to all SSE clients for a channel
    public void broadcastToChannel(String channel, JSONObject message) {
        Set<SSEClient> clients = sseClients.get(channel);
        if (clients == null || clients.isEmpty()) {
            return;
        }
        
        try {
            String data = "data: " + message.toString() + "\n\n";
            List<SSEClient> toRemove = new ArrayList<>();
            
            for (SSEClient client : clients) {
                if (client.closed) {
                    toRemove.add(client);
                } else {
                    client.send(data);
                }
            }
            
            // Clean up closed clients
            clients.removeAll(toRemove);
            if (clients.isEmpty()) {
                sseClients.remove(channel);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error broadcasting to channel: " + e.getMessage());
        }
    }
    
    public InternalHttpServer(Context context, InternalViewManager viewManager, InternalChannelConfig channelConfig) {
        super(STATIC_PORT); // Use static port from the start
        this.context = context;
        this.viewManager = viewManager;
        this.channelConfig = channelConfig;
        this.actualPort = STATIC_PORT;
    }
    
    @Override
    public void start() throws IOException {
        Log.i(TAG, "=== InternalHttpServer.start() called ===");
        
        // Use static port for testing
        actualPort = STATIC_PORT;
        Log.i(TAG, "Using static port: " + actualPort);
        
        // Verify port is available
        try {
            ServerSocket testSocket = new ServerSocket(actualPort);
            testSocket.close();
            Log.i(TAG, "Static port " + actualPort + " is available");
        } catch (IOException e) {
            Log.e(TAG, "Static port " + actualPort + " is already in use: " + e.getMessage());
            throw new IOException("Port " + actualPort + " is already in use. Please stop the other service or change STATIC_PORT.", e);
        }
        
        // Start server on the found port
        // The second parameter (false) means don't daemonize - keep it running
        // NanoHTTPD handles its own threading and binds to 0.0.0.0 (all interfaces) by default
        try {
            Log.i(TAG, "Calling super.start(" + actualPort + ", false)...");
            super.start(actualPort, false);
            Log.i(TAG, "super.start() returned successfully");
            
            // Wait a moment for server to actually start listening
            Thread.sleep(500);
            Log.i(TAG, "Waited 500ms for server to start");
            
            // Verify server is actually running
            try {
                boolean alive = isAlive();
                int listeningPort = getListeningPort();
                Log.i(TAG, "Server status - isAlive: " + alive + ", listeningPort: " + listeningPort);
                
                if (!alive) {
                    Log.e(TAG, "ERROR: Server reports not alive after start!");
                } else {
                    Log.i(TAG, "Server is confirmed alive and listening");
                }
            } catch (Exception e) {
                Log.e(TAG, "ERROR checking server status: " + e.getMessage(), e);
            }
            
        } catch (IOException e) {
            Log.e(TAG, "FAILED to start server on port " + actualPort + ": " + e.getMessage(), e);
            e.printStackTrace();
            throw e;
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while starting server: " + e.getMessage(), e);
            Thread.currentThread().interrupt();
            throw new IOException("Server startup interrupted", e);
        }
        
        // Get local IP address
        String localIp = getLocalIpAddress();
        if (localIp != null) {
            serverAddress = "http://" + localIp + ":" + actualPort;
            Log.i(TAG, "Server address (network): " + serverAddress);
        } else {
            serverAddress = "http://localhost:" + actualPort;
            Log.w(TAG, "Could not determine local IP, using localhost: " + serverAddress);
        }
        
        Log.i(TAG, "=== Internal server started successfully on " + serverAddress + " ===");
        Log.i(TAG, "Server should be listening on all interfaces (0.0.0.0:" + actualPort + ")");
    }
    
    @Override
    public void start(int port) throws IOException {
        // Use static port, ignore the parameter
        actualPort = STATIC_PORT;
        Log.i(TAG, "start(int) called, using static port: " + actualPort);
        
        // Verify port is available
        try {
            ServerSocket testSocket = new ServerSocket(actualPort);
            testSocket.close();
        } catch (IOException e) {
            Log.e(TAG, "Static port " + actualPort + " is already in use: " + e.getMessage());
            throw new IOException("Port " + actualPort + " is already in use.", e);
        }
        
        super.start(actualPort, false);
        
        // Get local IP address
        String localIp = getLocalIpAddress();
        if (localIp != null) {
            serverAddress = "http://" + localIp + ":" + actualPort;
        } else {
            serverAddress = "http://localhost:" + actualPort;
        }
        
        Log.i(TAG, "Internal server started on " + serverAddress);
    }
    
    private int findFreePort() {
        // Try random ports first (faster than sequential)
        Random random = new Random();
        Set<Integer> tried = new HashSet<>();
        
        // Try up to 50 random ports first
        for (int i = 0; i < 50 && tried.size() < (MAX_PORT - MIN_PORT); i++) {
            int port = MIN_PORT + random.nextInt(MAX_PORT - MIN_PORT + 1);
            if (tried.contains(port)) {
                continue;
            }
            tried.add(port);
            
            try {
                ServerSocket socket = new ServerSocket(port);
                socket.close();
                Log.d(TAG, "Found free port: " + port);
                return port;
            } catch (IOException e) {
                // Port is in use, try next
            }
        }
        
        // If random didn't work, try sequential (but limit to first 100)
        for (int port = MIN_PORT; port <= MIN_PORT + 100 && port <= MAX_PORT; port++) {
            if (tried.contains(port)) {
                continue;
            }
            try {
                ServerSocket socket = new ServerSocket(port);
                socket.close();
                Log.d(TAG, "Found free port: " + port);
                return port;
            } catch (IOException e) {
                // Port is in use, try next
            }
        }
        
        // Fallback to a random port in the range
        int randomPort = MIN_PORT + random.nextInt(MAX_PORT - MIN_PORT + 1);
        Log.w(TAG, "Could not find free port in range, using: " + randomPort);
        return randomPort;
    }
    
    private String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            int checked = 0;
            final int MAX_INTERFACES = 10; // Limit to prevent hanging
            
            while (interfaces.hasMoreElements() && checked < MAX_INTERFACES) {
                checked++;
                NetworkInterface iface = interfaces.nextElement();
                
                // Skip loopback and inactive interfaces
                try {
                    if (iface.isLoopback() || !iface.isUp()) {
                        continue;
                    }
                } catch (Exception e) {
                    // Interface might have been removed, skip it
                    continue;
                }
                
                // Skip virtual interfaces (like docker, vpn, etc.)
                String ifaceName = iface.getName();
                if (ifaceName != null && (ifaceName.contains("docker") || ifaceName.contains("veth") || 
                    ifaceName.contains("br-") || ifaceName.contains("tun") || 
                    ifaceName.contains("ppp") || ifaceName.contains("rmnet"))) {
                    continue;
                }
                
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    // Skip loopback addresses
                    if (addr.isLoopbackAddress()) {
                        continue;
                    }
                    // Prefer IPv4
                    String hostAddress = addr.getHostAddress();
                    if (hostAddress != null && !hostAddress.contains(":")) {
                        // Prefer non-link-local addresses
                        if (!hostAddress.startsWith("169.254.")) {
                            Log.d(TAG, "Found network IP: " + hostAddress + " on interface: " + ifaceName);
                            return hostAddress;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting local IP address: " + e.getMessage());
        }
        return null;
    }
    
    public String getServerAddress() {
        // Always try to get the network IP, not localhost
        if (serverAddress != null && !serverAddress.contains("localhost")) {
            return serverAddress;
        }
        // If we have localhost, try to refresh the IP
        String localIp = getLocalIpAddress();
        if (localIp != null) {
            serverAddress = "http://" + localIp + ":" + actualPort;
            return serverAddress;
        }
        // Last resort: return localhost (but log a warning)
        Log.w(TAG, "Warning: Using localhost address - device may not be on a network");
        return "http://localhost:" + actualPort;
    }
    
    public int getPort() {
        return actualPort;
    }
    
    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        String method = session.getMethod().name();
        
        Log.d(TAG, method + " " + uri);
        
        // CORS headers
        Map<String, String> headers = session.getHeaders();
        Map<String, String> responseHeaders = new HashMap<>();
        responseHeaders.put("Access-Control-Allow-Origin", "*");
        responseHeaders.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        responseHeaders.put("Access-Control-Allow-Headers", "Content-Type");
        
        if ("OPTIONS".equals(method)) {
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "");
        }
        
        try {
            // Serve web interface
            if (uri.equals("/") || uri.equals("/index.html")) {
                return handleWebInterface(responseHeaders);
            }
            
            // Serve view types documentation
            if (uri.equals("/view-types") || uri.equals("/view-types.html")) {
                return handleViewTypesDocumentation(responseHeaders);
            }
            
            // SSE endpoint
            if (uri.startsWith("/sse/") && "GET".equals(method)) {
                return handleSSE(session, uri, responseHeaders);
            }
            
            // Channels endpoint
            if (uri.equals("/api/channels") && "GET".equals(method)) {
                return handleGetChannels(responseHeaders);
            }
            
            // Channel config endpoint
            if (uri.startsWith("/api/channel-config/") && "GET".equals(method)) {
                return handleGetChannelConfig(uri, responseHeaders);
            }
            
            // View types API endpoint (JSON)
            if (uri.equals("/api/view-types") && "GET".equals(method)) {
                return handleGetViewTypes(responseHeaders);
            }
            
            // Views endpoints
            if (uri.equals("/api/views") && "GET".equals(method)) {
                return handleGetViews(responseHeaders);
            }
            if (uri.equals("/api/views") && "POST".equals(method)) {
                return handlePostView(session, responseHeaders);
            }
            // Polling endpoint for current view (for external clients since SSE doesn't work with NanoHTTPD)
            if (uri.startsWith("/api/views/current") && "GET".equals(method)) {
                return handleGetCurrentView(session, uri, responseHeaders);
            }
            if (uri.startsWith("/api/views/") && "PUT".equals(method)) {
                return handlePutView(session, uri, responseHeaders);
            }
            if (uri.startsWith("/api/views/") && "DELETE".equals(method)) {
                return handleDeleteView(uri, responseHeaders);
            }
            if (uri.startsWith("/api/views/") && uri.endsWith("/enable") && "PUT".equals(method)) {
                return handleSetViewEnabled(session, uri, responseHeaders);
            }
            
            // Channel config endpoints
            if (uri.startsWith("/api/channel-config/") && "PUT".equals(method)) {
                return handlePutChannelConfig(session, uri, responseHeaders);
            }
            
            // Channel endpoints
            if (uri.matches("/api/channels/[^/]+/tap") && "POST".equals(method)) {
                return handleChannelTap(session, uri, responseHeaders);
            }
            if (uri.matches("/api/channels/[^/]+/next") && "POST".equals(method)) {
                return handleChannelNext(session, uri, responseHeaders);
            }
            if (uri.matches("/api/channels/[^/]+/previous") && "POST".equals(method)) {
                return handleChannelPrevious(session, uri, responseHeaders);
            }
            
            // Default 404
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
            
        } catch (Exception e) {
            Log.e(TAG, "Error handling request: " + e.getMessage(), e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", 
                "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }
    
    private Response handleWebInterface(Map<String, String> headers) {
        try {
            AssetManager assetManager = context.getAssets();
            InputStream inputStream = assetManager.open("web_interface.html");
            
            // Read the entire file
            byte[] buffer = new byte[inputStream.available()];
            inputStream.read(buffer);
            inputStream.close();
            
            String html = new String(buffer, "UTF-8");
            headers.put("Content-Type", "text/html; charset=utf-8");
            
            return newFixedLengthResponse(Response.Status.OK, "text/html", html);
        } catch (IOException e) {
            Log.e(TAG, "Error loading web interface: " + e.getMessage());
            String errorHtml = "<html><body><h1>Error</h1><p>Could not load web interface: " + e.getMessage() + "</p></body></html>";
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/html", errorHtml);
        }
    }
    
    private Response handleViewTypesDocumentation(Map<String, String> headers) {
        try {
            AssetManager assetManager = context.getAssets();
            StringBuilder html = new StringBuilder();
            
            html.append("<!DOCTYPE html>\n");
            html.append("<html lang=\"en\">\n");
            html.append("<head>\n");
            html.append("    <meta charset=\"UTF-8\">\n");
            html.append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
            html.append("    <title>View Types Documentation</title>\n");
            html.append("    <style>\n");
            html.append("        * { margin: 0; padding: 0; box-sizing: border-box; }\n");
            html.append("        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #1a1a1a; color: #e0e0e0; padding: 20px; line-height: 1.6; }\n");
            html.append("        .container { max-width: 1200px; margin: 0 auto; }\n");
            html.append("        h1 { color: #4CAF50; margin-bottom: 30px; border-bottom: 2px solid #4CAF50; padding-bottom: 10px; }\n");
            html.append("        .view-type { background: #2a2a2a; border-radius: 8px; padding: 20px; margin-bottom: 20px; border: 1px solid #3a3a3a; }\n");
            html.append("        .view-type h2 { color: #4CAF50; margin-bottom: 10px; }\n");
            html.append("        .view-type .description { color: #b0b0b0; margin-bottom: 15px; }\n");
            html.append("        .view-type h3 { color: #888; margin-top: 20px; margin-bottom: 10px; font-size: 1em; }\n");
            html.append("        pre { background: #1a1a1a; border: 1px solid #3a3a3a; border-radius: 4px; padding: 15px; overflow-x: auto; font-family: 'Courier New', monospace; font-size: 12px; }\n");
            html.append("        code { color: #4CAF50; }\n");
            html.append("        .field-desc { color: #aaa; font-size: 0.9em; margin-left: 20px; }\n");
            html.append("        .back-link { display: inline-block; margin-bottom: 20px; color: #4CAF50; text-decoration: none; }\n");
            html.append("        .back-link:hover { text-decoration: underline; }\n");
            html.append("    </style>\n");
            html.append("</head>\n");
            html.append("<body>\n");
            html.append("    <div class=\"container\">\n");
            html.append("        <a href=\"/\" class=\"back-link\">← Back to View Manager</a>\n");
            html.append("        <h1>📚 View Types Documentation</h1>\n");
            
            // List all JSON files in view_types directory
            String[] viewTypeFiles = assetManager.list("view_types");
            if (viewTypeFiles != null && viewTypeFiles.length > 0) {
                java.util.Arrays.sort(viewTypeFiles);
                
                for (String filename : viewTypeFiles) {
                    if (filename.endsWith(".json")) {
                        try {
                            InputStream jsonStream = assetManager.open("view_types/" + filename);
                            byte[] jsonBuffer = new byte[jsonStream.available()];
                            jsonStream.read(jsonBuffer);
                            jsonStream.close();
                            
                            String jsonContent = new String(jsonBuffer, "UTF-8");
                            JSONObject viewTypeDoc = new JSONObject(jsonContent);
                            
                            String type = viewTypeDoc.getString("type");
                            String name = viewTypeDoc.getString("name");
                            String description = viewTypeDoc.optString("description", "");
                            JSONObject sample = viewTypeDoc.getJSONObject("sample");
                            JSONObject fields = viewTypeDoc.optJSONObject("fields");
                            
                            html.append("        <div class=\"view-type\">\n");
                            html.append("            <h2>").append(name).append(" (").append(type).append(")</h2>\n");
                            html.append("            <div class=\"description\">").append(description).append("</div>\n");
                            
                            html.append("            <h3>Sample JSON:</h3>\n");
                            html.append("            <pre><code>").append(sample.toString(2)).append("</code></pre>\n");
                            
                            if (fields != null) {
                                html.append("            <h3>Field Descriptions:</h3>\n");
                                html.append("            <pre><code>").append(fields.toString(2)).append("</code></pre>\n");
                            }
                            
                            html.append("        </div>\n");
                        } catch (Exception e) {
                            Log.e(TAG, "Error loading view type doc " + filename + ": " + e.getMessage());
                        }
                    }
                }
            } else {
                html.append("        <p>No view type documentation found.</p>\n");
            }
            
            html.append("    </div>\n");
            html.append("</body>\n");
            html.append("</html>\n");
            
            headers.put("Content-Type", "text/html; charset=utf-8");
            return newFixedLengthResponse(Response.Status.OK, "text/html", html.toString());
        } catch (IOException e) {
            Log.e(TAG, "Error loading view types documentation: " + e.getMessage());
            String errorHtml = "<html><body><h1>Error</h1><p>Could not load documentation: " + e.getMessage() + "</p></body></html>";
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/html", errorHtml);
        } catch (Exception e) {
            Log.e(TAG, "Error generating view types documentation: " + e.getMessage());
            String errorHtml = "<html><body><h1>Error</h1><p>Could not generate documentation: " + e.getMessage() + "</p></body></html>";
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/html", errorHtml);
        }
    }
    
    private Response handleSSE(IHTTPSession session, String uri, Map<String, String> headers) {
        String[] parts = uri.split("/");
        String channel = parts.length > 2 ? parts[2] : "test";
        
        // Note: NanoHTTPD doesn't support true long-lived SSE connections
        // For external clients, use the polling endpoint: GET /api/views/current?channel=<channel>
        // The internal Android client uses InternalServerConnectionProvider which polls internally
        
        try {
            // Return a helpful message pointing to the polling endpoint
            JSONObject response = new JSONObject();
            response.put("error", "NanoHTTPD doesn't support long-lived SSE connections");
            response.put("suggestion", "Use polling endpoint instead");
            response.put("polling_url", "/api/views/current?channel=" + channel);
            response.put("polling_interval_ms", 1000);
            
            // Also return current view for convenience
            JSONObject currentView = viewManager.getCurrentView(channel);
            if (currentView != null) {
                response.put("current_view", currentView);
            }
            
            Map<String, String> responseHeaders = new HashMap<>();
            responseHeaders.put("Content-Type", "application/json");
            responseHeaders.put("Cache-Control", "no-cache");
            
            return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error in SSE endpoint: " + e.getMessage(), e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", 
                "Error: " + e.getMessage());
        }
    }
    
    private Response handleGetChannels(Map<String, String> headers) {
        try {
            // Return a simple channels response with "test" channel
            JSONObject response = new JSONObject();
            JSONObject channels = new JSONObject();
            JSONObject testChannel = new JSONObject();
            testChannel.put("name", "test");
            channels.put("test", testChannel);
            response.put("channels", channels);
            return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error getting channels: " + e.getMessage());
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", 
                "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }
    
    private Response handleGetChannelConfig(String uri, Map<String, String> headers) {
        String[] parts = uri.split("/");
        String channel = parts.length > 3 ? parts[3] : "test";
        
        JSONObject config = channelConfig.getChannelConfig(channel);
        
        // Ensure MIDDLE_CENTER is always mapped to pause/resume (special handling)
        // Don't override it in the config, but the client will handle it specially
        try {
            if (!config.has("quadrants")) {
                config.put("quadrants", new JSONObject());
            }
            // Note: We don't set MIDDLE_CENTER here because the client handles it specially
            // The client's handleTap method always treats MIDDLE_CENTER as pause/resume
        } catch (Exception e) {
            Log.e(TAG, "Error ensuring quadrants in config: " + e.getMessage());
        }
        
        return newFixedLengthResponse(Response.Status.OK, "application/json", config.toString());
    }
    
    private Response handleGetViews(Map<String, String> headers) {
        JSONArray views = viewManager.getAllViews();
        return newFixedLengthResponse(Response.Status.OK, "application/json", views.toString());
    }
    
    private Response handleGetViewTypes(Map<String, String> headers) {
        try {
            JSONArray viewTypes = new JSONArray();
            AssetManager assetManager = context.getAssets();
            String[] viewTypeFiles = assetManager.list("view_types");
            
            if (viewTypeFiles != null && viewTypeFiles.length > 0) {
                java.util.Arrays.sort(viewTypeFiles);
                
                for (String filename : viewTypeFiles) {
                    if (filename.endsWith(".json")) {
                        try {
                            InputStream jsonStream = assetManager.open("view_types/" + filename);
                            byte[] jsonBuffer = new byte[jsonStream.available()];
                            jsonStream.read(jsonBuffer);
                            jsonStream.close();
                            
                            String jsonContent = new String(jsonBuffer, "UTF-8");
                            JSONObject viewTypeDoc = new JSONObject(jsonContent);
                            
                            // Create a simplified version for the API
                            JSONObject viewType = new JSONObject();
                            viewType.put("type", viewTypeDoc.getString("type"));
                            viewType.put("name", viewTypeDoc.getString("name"));
                            viewType.put("description", viewTypeDoc.optString("description", ""));
                            if (viewTypeDoc.has("sample")) {
                                viewType.put("sample", viewTypeDoc.getJSONObject("sample"));
                            }
                            if (viewTypeDoc.has("fields")) {
                                viewType.put("fields", viewTypeDoc.getJSONObject("fields"));
                            }
                            
                            viewTypes.put(viewType);
                        } catch (Exception e) {
                            Log.e(TAG, "Error loading view type " + filename + ": " + e.getMessage());
                        }
                    }
                }
            }
            
            return newFixedLengthResponse(Response.Status.OK, "application/json", viewTypes.toString());
        } catch (IOException e) {
            Log.e(TAG, "Error listing view types: " + e.getMessage());
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                "{\"error\":\"Could not load view types: " + e.getMessage() + "\"}");
        } catch (Exception e) {
            Log.e(TAG, "Error generating view types JSON: " + e.getMessage());
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                "{\"error\":\"Could not generate view types: " + e.getMessage() + "\"}");
        }
    }
    
    private Response handleGetCurrentView(IHTTPSession session, String uri, Map<String, String> headers) {
        try {
            // Parse channel from query string or default to "test"
            String channel = "test";
            String query = session.getQueryParameterString();
            if (query != null && query.contains("channel=")) {
                String[] params = query.split("&");
                for (String param : params) {
                    if (param.startsWith("channel=")) {
                        channel = param.substring("channel=".length());
                        break;
                    }
                }
            }
            
            JSONObject currentView = viewManager.getCurrentView(channel);
            JSONObject response = new JSONObject();
            response.put("type", "view_change");
            if (currentView != null) {
                response.put("view", currentView);
            } else {
                response.put("view", JSONObject.NULL);
            }
            response.put("channel", channel);
            response.put("timestamp", System.currentTimeMillis());
            
            return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error getting current view: " + e.getMessage(), e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }
    
    private Response handlePostView(IHTTPSession session, Map<String, String> headers) {
        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            String body = files.get("postData");
            
            if (body == null || body.isEmpty()) {
                Log.e(TAG, "Empty request body for POST view");
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", 
                    "{\"error\":\"Request body is required\"}");
            }
            
            JSONObject request = new JSONObject(body);
            String id = request.getString("id");
            JSONObject view = request.optJSONObject("view");
            
            // If no "view" field, check if the request itself is the view (has metadata and data)
            if (view == null && request.has("metadata") && request.has("data")) {
                // The request itself is the view structure
                view = request;
            } else if (view == null) {
                // Try "data" as fallback
                view = request.optJSONObject("data");
            }
            
            if (view == null) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", 
                    "{\"error\":\"View data is required (must include metadata and data fields)\"}");
            }
            
            viewManager.addView(id, view);
            
            // Automatically add view to default channel if not already in any channel
            JSONArray channelViews = channelConfig.getChannelViews("test");
            boolean alreadyInChannel = false;
            for (int i = 0; i < channelViews.length(); i++) {
                if (id.equals(channelViews.optString(i, null))) {
                    alreadyInChannel = true;
                    break;
                }
            }
            if (!alreadyInChannel) {
                channelViews.put(id);
                channelConfig.setChannelViews("test", channelViews);
                Log.d(TAG, "Automatically added view " + id + " to default channel 'test'");
            }
            
            JSONObject response = new JSONObject();
            response.put("success", true);
            return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString());
        } catch (org.json.JSONException e) {
            Log.e(TAG, "Error parsing JSON in POST view: " + e.getMessage());
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", 
                "{\"error\":\"Invalid JSON: " + e.getMessage() + "\"}");
        } catch (Exception e) {
            Log.e(TAG, "Error adding view: " + e.getMessage(), e);
            String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", 
                "{\"error\":\"" + errorMsg + "\"}");
        }
    }
    
    private Response handlePutView(IHTTPSession session, String uri, Map<String, String> headers) {
        try {
            String[] parts = uri.split("/");
            String id = parts.length > 3 ? parts[3] : null;
            
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            String body = files.get("postData");
            
            JSONObject request = new JSONObject(body);
            JSONObject view = request.optJSONObject("view");
            if (view == null) {
                view = request.optJSONObject("data");
            }
            
            if (view == null) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", 
                    "{\"error\":\"View data is required\"}");
            }
            
            viewManager.addView(id, view);
            JSONObject response = new JSONObject();
            response.put("success", true);
            return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error updating view: " + e.getMessage());
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", 
                "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }
    
    private Response handleDeleteView(String uri, Map<String, String> headers) {
        try {
            String[] parts = uri.split("/");
            String id = parts.length > 3 ? parts[3] : null;
            
            viewManager.removeView(id);
            JSONObject response = new JSONObject();
            response.put("success", true);
            return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error deleting view: " + e.getMessage());
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", 
                "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }
    
    private Response handleSetViewEnabled(IHTTPSession session, String uri, Map<String, String> headers) {
        try {
            String[] parts = uri.split("/");
            String id = parts.length > 3 ? parts[3] : null;
            
            if (id == null) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", 
                    "{\"error\":\"View ID is required\"}");
            }
            
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            String body = files.get("postData");
            
            if (body == null || body.trim().isEmpty()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                    "{\"error\":\"Request body is empty\"}");
            }
            
            JSONObject request = new JSONObject(body);
            boolean enabled = request.optBoolean("enabled", true);
            
            viewManager.setViewEnabled(id, enabled);
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("enabled", enabled);
            return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString());
        } catch (org.json.JSONException e) {
            Log.e(TAG, "JSON parsing error in handleSetViewEnabled: " + e.getMessage(), e);
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                "{\"error\":\"Invalid JSON format: " + e.getMessage() + "\"}");
        } catch (Exception e) {
            Log.e(TAG, "Error setting view enabled: " + e.getMessage(), e);
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", 
                "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }
    
    private Response handlePutChannelConfig(IHTTPSession session, String uri, Map<String, String> headers) {
        try {
            String[] parts = uri.split("/");
            String channel = parts.length > 3 ? parts[3] : "test";
            
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            String body = files.get("postData");
            
            if (body == null || body.isEmpty()) {
                Log.e(TAG, "Empty request body for PUT channel-config");
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", 
                    "{\"error\":\"Request body is required\"}");
            }
            
            JSONObject request = new JSONObject(body);
            
            if (request.has("views")) {
                JSONArray views = request.getJSONArray("views");
                channelConfig.setChannelViews(channel, views);
            }
            
            if (request.has("quadrants")) {
                JSONObject quadrants = request.getJSONObject("quadrants");
                // Remove MIDDLE_CENTER from quadrants - it's always handled specially by the client
                quadrants.remove("MIDDLE_CENTER");
                channelConfig.setChannelQuadrants(channel, quadrants);
            }
            
            JSONObject response = new JSONObject();
            response.put("success", true);
            return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString());
        } catch (org.json.JSONException e) {
            Log.e(TAG, "Error parsing JSON in PUT channel-config: " + e.getMessage());
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", 
                "{\"error\":\"Invalid JSON: " + e.getMessage() + "\"}");
        } catch (Exception e) {
            Log.e(TAG, "Error updating channel config: " + e.getMessage(), e);
            String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", 
                "{\"error\":\"" + errorMsg + "\"}");
        }
    }
    
    private Response handleChannelTap(IHTTPSession session, String uri, Map<String, String> headers) {
        try {
            String[] parts = uri.split("/");
            String channel = parts.length > 3 ? parts[3] : "test";
            
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            String body = files.get("postData");
            JSONObject request = new JSONObject(body);
            String quadrant = request.getString("quadrant");
            
            // MIDDLE_CENTER is always handled specially by the client for pause/resume
            // Don't process it on the server side
            if ("MIDDLE_CENTER".equals(quadrant)) {
                JSONObject response = new JSONObject();
                response.put("success", true);
                response.put("message", "MIDDLE_CENTER is handled by client for pause/resume");
                return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString());
            }
            
            JSONObject config = channelConfig.getChannelConfig(channel);
            JSONObject quadrants = config.optJSONObject("quadrants");
            if (quadrants != null && quadrants.has(quadrant)) {
                String targetViewId = quadrants.getString(quadrant);
                if ("NEXT".equals(targetViewId)) {
                    viewManager.nextView(channel);
                } else if ("PREVIOUS".equals(targetViewId)) {
                    viewManager.previousView(channel);
                } else {
                    viewManager.setCurrentView(targetViewId, channel);
                }
            } else {
                // Default to next
                viewManager.nextView(channel);
            }
            
            JSONObject response = new JSONObject();
            response.put("success", true);
            return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error handling tap: " + e.getMessage());
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", 
                "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }
    
    private Response handleChannelNext(IHTTPSession session, String uri, Map<String, String> headers) {
        try {
            String[] parts = uri.split("/");
            String channel = parts.length > 3 ? parts[3] : "test";
            
            viewManager.nextView(channel);
            JSONObject response = new JSONObject();
            response.put("success", true);
            return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error handling next: " + e.getMessage());
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", 
                "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }
    
    private Response handleChannelPrevious(IHTTPSession session, String uri, Map<String, String> headers) {
        try {
            String[] parts = uri.split("/");
            String channel = parts.length > 3 ? parts[3] : "test";
            
            viewManager.previousView(channel);
            JSONObject response = new JSONObject();
            response.put("success", true);
            return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error handling previous: " + e.getMessage());
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", 
                "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }
}

