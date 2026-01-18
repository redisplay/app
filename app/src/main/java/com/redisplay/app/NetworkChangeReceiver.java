package com.redisplay.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

public class NetworkChangeReceiver extends BroadcastReceiver {
    private static final String TAG = "NetworkChangeReceiver";
    private MainActivity mainActivity;

    public NetworkChangeReceiver(MainActivity activity) {
        this.mainActivity = activity;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction().equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            boolean isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();

            Log.d(TAG, "Network connectivity changed: " + isConnected);

            if (isConnected) {
                // Determine if it's WiFi or Mobile
                boolean isWiFi = activeNetwork.getType() == ConnectivityManager.TYPE_WIFI;
                Log.d(TAG, "Connected via " + (isWiFi ? "WiFi" : "Mobile"));
                
                // Notify activity that network is available
                if (mainActivity != null) {
                    mainActivity.onNetworkAvailable();
                }
            } else {
                Log.d(TAG, "Network disconnected");
            }
        }
    }
}

