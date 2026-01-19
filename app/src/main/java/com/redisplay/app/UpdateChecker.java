package com.redisplay.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateChecker {
    private static final String TAG = "UpdateChecker";
    private static final String UPDATE_CHECK_URL = "https://redisplay.dev/latest-version";
    public static final long CHECK_INTERVAL = 24 * 60 * 60 * 1000; // 24 hours
    
    public interface UpdateListener {
        void onUpdateAvailable(String versionName, String downloadUrl);
        void onUpdateCheckFailed(String error);
        void onUpdateDownloaded(File apkFile);
    }
    
    public static void checkForUpdate(Activity activity, UpdateListener listener) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int currentVersionCode = getCurrentVersionCode(activity);
                    
                    URL url = new URL(UPDATE_CHECK_URL);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(5000);
                    connection.setReadTimeout(5000);
                    
                    int responseCode = connection.getResponseCode();
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        InputStream inputStream = connection.getInputStream();
                        StringBuilder response = new StringBuilder();
                        byte[] buffer = new byte[1024];
                        int bytesRead;
                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            response.append(new String(buffer, 0, bytesRead));
                        }
                        inputStream.close();
                        
                        // Parse JSON response: {"versionCode": 2, "versionName": "1.1", "downloadUrl": "https://..."}
                        String json = response.toString();
                        int newVersionCode = parseVersionCode(json);
                        String versionName = parseVersionName(json);
                        String downloadUrl = parseDownloadUrl(json);
                        
                        if (newVersionCode > currentVersionCode && downloadUrl != null) {
                            activity.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    listener.onUpdateAvailable(versionName, downloadUrl);
                                }
                            });
                        }
                    } else {
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                listener.onUpdateCheckFailed("HTTP " + responseCode);
                            }
                        });
                    }
                    connection.disconnect();
                } catch (Exception e) {
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            listener.onUpdateCheckFailed(e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }
    
    public static void downloadAndInstall(Activity activity, String downloadUrl, UpdateListener listener) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL(downloadUrl);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(30000);
                    
                    int responseCode = connection.getResponseCode();
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        // Save to Downloads directory
                        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                        if (!downloadDir.exists()) {
                            downloadDir.mkdirs();
                        }
                        
                        File apkFile = new File(downloadDir, "kiosk-update.apk");
                        
                        InputStream inputStream = connection.getInputStream();
                        FileOutputStream outputStream = new FileOutputStream(apkFile);
                        
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        long totalBytes = 0;
                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, bytesRead);
                            totalBytes += bytesRead;
                        }
                        
                        outputStream.close();
                        inputStream.close();
                        connection.disconnect();
                        
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                listener.onUpdateDownloaded(apkFile);
                                installApk(activity, apkFile);
                            }
                        });
                    } else {
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                listener.onUpdateCheckFailed("Download failed: HTTP " + responseCode);
                            }
                        });
                    }
                } catch (Exception e) {
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            listener.onUpdateCheckFailed("Download error: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }
    
    private static void installApk(Activity activity, File apkFile) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to install APK", e);
        }
    }
    
    private static int getCurrentVersionCode(Activity activity) {
        try {
            PackageInfo packageInfo = activity.getPackageManager()
                .getPackageInfo(activity.getPackageName(), 0);
            return packageInfo.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }
    
    private static int parseVersionCode(String json) {
        try {
            int start = json.indexOf("\"versionCode\":") + 15;
            int end = json.indexOf(",", start);
            if (end == -1) end = json.indexOf("}", start);
            if (end > start) {
                return Integer.parseInt(json.substring(start, end).trim());
            }
        } catch (Exception e) {
            // Ignore
        }
        return 0;
    }
    
    private static String parseVersionName(String json) {
        try {
            int start = json.indexOf("\"versionName\":\"") + 16;
            int end = json.indexOf("\"", start);
            if (end > start) {
                return json.substring(start, end);
            }
        } catch (Exception e) {
            // Ignore
        }
        return "unknown";
    }
    
    private static String parseDownloadUrl(String json) {
        try {
            int start = json.indexOf("\"downloadUrl\":\"") + 16;
            int end = json.indexOf("\"", start);
            if (end > start) {
                return json.substring(start, end);
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }
}

