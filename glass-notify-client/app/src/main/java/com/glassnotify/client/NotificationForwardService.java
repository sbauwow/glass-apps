package com.glassnotify.client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class NotificationForwardService extends Service {

    private static final String TAG = "NotifyForward";
    private static final String CHANNEL_ID = "glass_notify_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final int PORT = 9876;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final long HEARTBEAT_INTERVAL_MS = 30000;
    private static final long MAX_BACKOFF_MS = 30000;

    public static final String ACTION_STATUS = "com.glassnotify.client.STATUS";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_HOST = "host";

    private static final long LOCATION_MIN_TIME_MS = 5000;
    private static final float LOCATION_MIN_DISTANCE_M = 10f;

    static final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();

    private volatile boolean running;
    private volatile OutputStream currentOut;
    private final Object writeLock = new Object();
    private Thread connectionThread;
    private String glassHost;
    private LocationManager locationManager;

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            LocationData data = new LocationData(
                    location.getLatitude(),
                    location.getLongitude(),
                    location.getAltitude(),
                    location.getAccuracy(),
                    location.getTime());
            byte[] bytes = data.toBytes();
            if (bytes.length > 0) {
                sendBytes(bytes);
            }
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override
        public void onProviderEnabled(String provider) {}
        @Override
        public void onProviderDisabled(String provider) {}
    };

    public static void enqueue(NotificationData data) {
        queue.offer(data.toBytes());
    }

    public static void enqueueRaw(byte[] bytes) {
        queue.offer(bytes);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            glassHost = intent.getStringExtra(EXTRA_HOST);
        }

        if (glassHost == null || glassHost.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification notification = buildForegroundNotification("Connecting to " + glassHost + "...");
        startForeground(NOTIFICATION_ID, notification);

        running = true;
        queue.clear();
        connectionThread = new Thread(this::connectionLoop, "GlassConnection");
        connectionThread.start();
        startLocationUpdates();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        stopLocationUpdates();
        if (connectionThread != null) {
            connectionThread.interrupt();
        }
        broadcastStatus("Disconnected");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void connectionLoop() {
        long backoff = 1000;

        while (running) {
            Socket socket = null;
            try {
                broadcastStatus("Connecting to " + glassHost + "...");
                socket = new Socket();
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress(glassHost, PORT), CONNECT_TIMEOUT_MS);

                backoff = 1000; // Reset on successful connect
                broadcastStatus("Connected to " + glassHost);
                updateForegroundNotification("Connected to " + glassHost);

                OutputStream out = socket.getOutputStream();
                currentOut = out;
                long lastSend = System.currentTimeMillis();

                while (running && !socket.isClosed()) {
                    byte[] bytes = queue.poll(1, TimeUnit.SECONDS);

                    if (bytes != null) {
                        synchronized (writeLock) {
                            out.write(bytes);
                            out.flush();
                        }
                        lastSend = System.currentTimeMillis();
                    } else if (System.currentTimeMillis() - lastSend > HEARTBEAT_INTERVAL_MS) {
                        synchronized (writeLock) {
                            out.write(NotificationData.heartbeat());
                            out.flush();
                        }
                        lastSend = System.currentTimeMillis();
                    }
                }
            } catch (InterruptedException e) {
                break;
            } catch (IOException e) {
                Log.w(TAG, "Connection error: " + e.getMessage());
            } finally {
                currentOut = null;
                if (socket != null) {
                    try { socket.close(); } catch (IOException ignored) {}
                }
            }

            if (!running) break;

            broadcastStatus("Reconnecting in " + (backoff / 1000) + "s...");
            updateForegroundNotification("Disconnected - reconnecting...");
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                break;
            }
            backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
        }
    }

    /** Write bytes directly to the socket (used by location listener from its callback thread). */
    private void sendBytes(byte[] bytes) {
        OutputStream out = currentOut;
        if (out == null) return;
        try {
            synchronized (writeLock) {
                out.write(bytes);
                out.flush();
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to send location: " + e.getMessage());
        }
    }

    @SuppressWarnings("MissingPermission")
    private void startLocationUpdates() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) return;

        String[] providers = { LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER };
        for (String provider : providers) {
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(provider,
                            LOCATION_MIN_TIME_MS, LOCATION_MIN_DISTANCE_M, locationListener);
                    Log.d(TAG, "Location updates started: " + provider);
                }
            } catch (Exception e) {
                Log.d(TAG, "Cannot subscribe to " + provider);
            }
        }
    }

    private void stopLocationUpdates() {
        if (locationManager != null) {
            locationManager.removeUpdates(locationListener);
        }
    }

    private void broadcastStatus(String status) {
        Intent intent = new Intent(ACTION_STATUS);
        intent.putExtra(EXTRA_STATUS, status);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.channel_description));
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildForegroundNotification(String text) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("Glass Notify")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build();
    }

    private void updateForegroundNotification(String text) {
        Notification notification = buildForegroundNotification(text);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, notification);
    }
}
