package com.redisplay.app.network;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

/**
 * Bluetooth Classic (SPP) Connection Provider for Android 4.2.2 compatibility
 * Uses RFCOMM/SPP instead of BLE for better compatibility with older devices
 */
public class BluetoothClassicConnectionProvider implements ConnectionProvider {
    private static final String TAG = "BTClassicProvider";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); // Standard SPP UUID
    private static final String SERVER_NAME = "Redisplay Server";
    private static final int CONNECTION_TIMEOUT = 10000;
    
    private final Context context;
    private final ConnectionListener listener;
    private final Handler handler;
    
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket socket;
    private Thread connectionThread;
    private Thread readThread;
    private volatile boolean isRunning = false;
    
    public BluetoothClassicConnectionProvider(Context context, ConnectionListener listener) {
        this.context = context;
        this.listener = listener;
        this.handler = new Handler(Looper.getMainLooper());
        
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }
    
    @Override
    public void connect() {
        if (bluetoothAdapter == null) {
            if (listener != null) {
                listener.onError("Bluetooth not supported on this device");
            }
            return;
        }
        
        if (!bluetoothAdapter.isEnabled()) {
            if (listener != null) {
                listener.onError("Bluetooth is disabled. Please enable Bluetooth in settings.");
            }
            return;
        }
        
        disconnect(); // Clean up any existing connection
        
        isRunning = true;
        connectionThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Find the server device by name
                    BluetoothDevice serverDevice = findServerDevice();
                    
                    if (serverDevice == null) {
                        if (listener != null) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    listener.onError("Redisplay Server not found. Make sure server is paired and Bluetooth is enabled.");
                                }
                            });
                        }
                        isRunning = false;
                        return;
                    }
                    
                    Log.d(TAG, "Found server device: " + serverDevice.getName() + " (" + serverDevice.getAddress() + ")");
                    
                    // Create socket
                    socket = serverDevice.createRfcommSocketToServiceRecord(SPP_UUID);
                    
                    // Connect with timeout
                    socket.connect();
                    
                    Log.d(TAG, "Connected to server");
                    
                    if (listener != null) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                listener.onConnected();
                            }
                        });
                    }
                    
                    // Start reading data
                    startReading();
                    
                } catch (IOException e) {
                    Log.e(TAG, "Connection failed: " + e.getMessage());
                    if (listener != null) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                listener.onError("Connection failed: " + e.getMessage());
                                listener.onDisconnected();
                            }
                        });
                    }
                    isRunning = false;
                    closeSocket();
                }
            }
        });
        connectionThread.start();
    }
    
    private BluetoothDevice findServerDevice() {
        // First, check paired devices
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        for (BluetoothDevice device : pairedDevices) {
            String name = device.getName();
            if (name != null && name.contains(SERVER_NAME)) {
                Log.d(TAG, "Found paired server: " + name);
                return device;
            }
        }
        
        // If not found in paired devices, we could start discovery
        // But for now, we'll require pairing first
        Log.w(TAG, "Server not found in paired devices. Please pair the device first.");
        return null;
    }
    
    private void startReading() {
        readThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    InputStream inputStream = socket.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                    
                    String line;
                    while (isRunning && (line = reader.readLine()) != null) {
                        final String message = line;
                        Log.d(TAG, "Received: " + message);
                        
                        if (listener != null) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    listener.onMessageReceived(message);
                                }
                            });
                        }
                    }
                } catch (IOException e) {
                    if (isRunning) {
                        Log.e(TAG, "Read error: " + e.getMessage());
                        if (listener != null) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    listener.onError("Read error: " + e.getMessage());
                                    listener.onDisconnected();
                                }
                            });
                        }
                    }
                } finally {
                    isRunning = false;
                }
            }
        });
        readThread.start();
    }
    
    @Override
    public void disconnect() {
        isRunning = false;
        closeSocket();
        
        if (connectionThread != null) {
            connectionThread.interrupt();
            try {
                connectionThread.join(1000);
            } catch (InterruptedException e) {
                // Ignore
            }
            connectionThread = null;
        }
        
        if (readThread != null) {
            readThread.interrupt();
            try {
                readThread.join(1000);
            } catch (InterruptedException e) {
                // Ignore
            }
            readThread = null;
        }
    }
    
    private void closeSocket() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing socket: " + e.getMessage());
            }
            socket = null;
        }
    }
    
    @Override
    public boolean isRunning() {
        return isRunning && socket != null && socket.isConnected();
    }
    
    /**
     * Send a command to the server
     */
    public void sendCommand(String command) {
        if (socket != null && socket.isConnected()) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        OutputStream outputStream = socket.getOutputStream();
                        outputStream.write((command + "\n").getBytes());
                        outputStream.flush();
                        Log.d(TAG, "Sent command: " + command);
                    } catch (IOException e) {
                        Log.e(TAG, "Error sending command: " + e.getMessage());
                    }
                }
            }).start();
        }
    }
}

