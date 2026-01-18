package com.redisplay.app.network;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class BleConnectionProvider implements ConnectionProvider {
    private static final String TAG = "BleConnectionProvider";
    private static final long SCAN_PERIOD = 10000;
    
    private final Context context;
    private final ConnectionListener listener;
    private final String targetChannel; // We might use this to "filter" or "announce" our channel later
    
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private Handler handler;
    private boolean isScanning = false;
    private boolean isConnected = false;
    
    // Data reassembly buffer
    private StringBuilder messageBuffer = new StringBuilder();

    // Protocol Flags
    private static final byte FLAG_START = 0x01;
    private static final byte FLAG_CONTINUE = 0x02;
    private static final byte FLAG_END = 0x03;
    private static final byte FLAG_SINGLE = 0x04;

    public BleConnectionProvider(Context context, String targetChannel, ConnectionListener listener) {
        this.context = context;
        this.targetChannel = targetChannel;
        this.listener = listener;
        this.handler = new Handler(Looper.getMainLooper());
        
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            this.bluetoothAdapter = bluetoothManager.getAdapter();
        }
    }

    @Override
    public void connect() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            if (listener != null) listener.onError("Bluetooth is disabled or not supported");
            return;
        }

        scanLeDevice(true);
    }

    @Override
    public void disconnect() {
        scanLeDevice(false);
        close();
        if (listener != null) listener.onDisconnected();
    }

    @Override
    public boolean isRunning() {
        return isConnected;
    }

    private void scanLeDevice(final boolean enable) {
        if (enable) {
            // Stops scanning after a pre-defined scan period.
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (isScanning) {
                        isScanning = false;
                        bluetoothAdapter.stopLeScan(leScanCallback);
                        Log.d(TAG, "Scan timeout");
                        if (!isConnected && listener != null) {
                            listener.onError("No server found via Bluetooth");
                        }
                    }
                }
            }, SCAN_PERIOD);

            isScanning = true;
            // Scan specifically for our Service UUID would be better, but some older Androids have issues with filtered startLeScan
            // We'll scan all and filter in callback for maximum compatibility
            bluetoothAdapter.startLeScan(leScanCallback);
            Log.d(TAG, "Started BLE scan");
            if (listener != null) listener.onMessageReceived("Scanning for Redisplay Server...");
        } else {
            isScanning = false;
            bluetoothAdapter.stopLeScan(leScanCallback);
        }
    }

    // Device scan callback.
    private BluetoothAdapter.LeScanCallback leScanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
            // In a real app, we should parse scanRecord to find our Service UUID
            // For now, we'll check device name or just try to connect if it looks promising
            // A robust way is to connect and check services, or parse the advertisement data manually
            
            // Optimization: Only connect if name matches or we parse the UUID
            String name = device.getName();
            if (name != null && name.contains("Redisplay")) {
                Log.d(TAG, "Found Redisplay device: " + name);
                if (isScanning) {
                    bluetoothAdapter.stopLeScan(this);
                    isScanning = false;
                    connectToDevice(device);
                }
            }
        }
    };

    private void connectToDevice(BluetoothDevice device) {
        Log.d(TAG, "Connecting to " + device.getAddress());
        // AutoConnect = false for faster initial connection
        bluetoothGatt = device.connectGatt(context, false, gattCallback);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true;
                Log.d(TAG, "Connected to GATT server.");
                if (listener != null) listener.onConnected();
                
                // Attempts to discover services after successful connection.
                Log.d(TAG, "Attempting to start service discovery: " + bluetoothGatt.discoverServices());
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false;
                Log.d(TAG, "Disconnected from GATT server.");
                if (listener != null) listener.onDisconnected();
                // Consider auto-reconnect logic here
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered");
                
                // 1. Request higher MTU for faster transfer (Android 5.0+ only)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    gatt.requestMtu(512); 
                } else {
                    // Fallback for older devices: proceed to setup characteristics immediately
                    setupCharacteristics(gatt);
                }
            } else {
                Log.w(TAG, "onServicesDiscovered received: " + status);
            }
        }
        
        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.d(TAG, "MTU changed to: " + mtu);
            // After MTU is negotiated, setup characteristics
            setupCharacteristics(gatt);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (BleConstants.CHARACTERISTIC_VIEW_DATA.equals(characteristic.getUuid())) {
                processIncomingPacket(characteristic.getValue());
            }
        }
    };
    
    private void setupCharacteristics(BluetoothGatt gatt) {
        BluetoothGattService service = gatt.getService(BleConstants.SERVICE_UUID);
        if (service != null) {
            BluetoothGattCharacteristic viewChar = service.getCharacteristic(BleConstants.CHARACTERISTIC_VIEW_DATA);
            if (viewChar != null) {
                // Enable local notifications
                gatt.setCharacteristicNotification(viewChar, true);
                
                // Enable remote notifications (CCCD)
                BluetoothGattDescriptor descriptor = viewChar.getDescriptor(BleConstants.CLIENT_CHARACTERISTIC_CONFIG);
                if (descriptor != null) {
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    gatt.writeDescriptor(descriptor);
                    Log.d(TAG, "Subscribed to View Data notifications");
                }
            }
        } else {
            Log.e(TAG, "Redisplay Service not found on device");
            if (listener != null) listener.onError("Service not found on device");
        }
    }
    
    private void processIncomingPacket(byte[] data) {
        if (data == null || data.length < 1) return;
        
        byte flag = data[0];
        String payload = new String(data, 1, data.length - 1, StandardCharsets.UTF_8);
        
        switch (flag) {
            case FLAG_START:
                messageBuffer.setLength(0); // Clear buffer
                messageBuffer.append(payload);
                break;
                
            case FLAG_CONTINUE:
                messageBuffer.append(payload);
                break;
                
            case FLAG_END:
                messageBuffer.append(payload);
                final String fullMessage = messageBuffer.toString();
                // Process complete message
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (listener != null) listener.onMessageReceived(fullMessage);
                    }
                });
                break;
                
            case FLAG_SINGLE:
                final String singleMsg = payload;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (listener != null) listener.onMessageReceived(singleMsg);
                    }
                });
                break;
        }
    }

    private void close() {
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
    }
}


