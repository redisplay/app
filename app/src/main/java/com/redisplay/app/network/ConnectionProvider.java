package com.redisplay.app.network;

public interface ConnectionProvider {
    /**
     * Start listening for data.
     */
    void connect();

    /**
     * Stop listening and close resources.
     */
    void disconnect();

    /**
     * Check if currently connected/running.
     */
    boolean isRunning();

    /**
     * Listener for receiving data from the provider.
     */
    interface ConnectionListener {
        void onMessageReceived(String message);
        void onError(String error);
        void onConnected();
        void onDisconnected();
    }
}

