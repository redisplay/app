package com.redisplay.app;

import android.os.Handler;
import android.os.Looper;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;

public class RedisplayWebSocketClient extends WebSocketClient {
    private WebSocketListener listener;
    private Handler mainHandler;

    public interface WebSocketListener {
        void onMessage(String message);
        void onError(String error);
        void onConnected();
        void onDisconnected();
    }

    public RedisplayWebSocketClient(URI serverUri, WebSocketListener listener) {
        super(serverUri);
        this.listener = listener;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onConnected();
                }
            }
        });
    }

    @Override
    public void onMessage(String message) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onMessage(message);
                }
            }
        });
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onDisconnected();
                }
            }
        });
    }

    @Override
    public void onError(Exception ex) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onError(ex.getMessage());
                }
            }
        });
    }
}

