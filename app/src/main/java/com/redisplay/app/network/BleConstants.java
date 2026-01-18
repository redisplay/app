package com.redisplay.app.network;

import java.util.UUID;

public class BleConstants {
    // Custom UUIDs for our Redisplay Service
    // Generated randomly to avoid collisions
    public static final UUID SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    
    // Characteristic for receiving View Data (Server -> Client)
    // Supports: NOTIFY
    public static final UUID CHARACTERISTIC_VIEW_DATA = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    
    // Characteristic for sending commands (Client -> Server) like "Identify Channel"
    // Supports: WRITE
    public static final UUID CHARACTERISTIC_COMMAND = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    
    // Client Characteristic Configuration Descriptor (CCCD) - standard UUID
    // Needed to enable notifications
    public static final UUID CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
}


