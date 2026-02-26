package com.glassnotify;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class NotifyActivity extends Activity implements GlassServer.Listener {

    private static final int MAX_HISTORY = 50;
    private static final long SCREEN_ON_DURATION_MS = 8000;

    private GlassServer server;
    private MockGPS mockGPS;
    private PowerManager.WakeLock wakeLock;
    private Handler handler;
    private boolean gpsActive;

    private View notificationView;
    private TextView textApp;
    private TextView textTitle;
    private TextView textBody;
    private View navigationView;
    private TextView navInstruction;
    private TextView navDistance;
    private TextView navEta;
    private ScrollView historyView;
    private LinearLayout historyList;
    private TextView textStatus;

    private boolean showingHistory = false;
    private boolean navigationActive = false;
    private final List<NotificationData> history = new ArrayList<>();
    private String clientAddress = null;

    private float touchStartX, touchStartY;
    private long touchStartTime;

    private final Runnable dimScreen = new Runnable() {
        @Override
        public void run() {
            if (wakeLock.isHeld()) wakeLock.release();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notify);

        handler = new Handler();

        notificationView = findViewById(R.id.notification_view);
        textApp = (TextView) findViewById(R.id.text_app);
        textTitle = (TextView) findViewById(R.id.text_title);
        textBody = (TextView) findViewById(R.id.text_body);
        navigationView = findViewById(R.id.navigation_view);
        navInstruction = (TextView) findViewById(R.id.nav_instruction);
        navDistance = (TextView) findViewById(R.id.nav_distance);
        navEta = (TextView) findViewById(R.id.nav_eta);
        historyView = (ScrollView) findViewById(R.id.history_view);
        historyList = (LinearLayout) findViewById(R.id.history_list);
        textStatus = (TextView) findViewById(R.id.text_status);

        // Show waiting state
        textApp.setText("");
        textTitle.setText("Notify");
        textBody.setText("Waiting for phone...");

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "glassnotify:screen");

        mockGPS = new MockGPS(this);
        mockGPS.start();

        server = new GlassServer(this);
        server.start();

        startService(new Intent(this, TiltToWakeService.class));

        updateStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(dimScreen);
        if (wakeLock.isHeld()) wakeLock.release();
        server.stop();
        mockGPS.stop();
        stopService(new Intent(this, TiltToWakeService.class));
    }

    // --- NotificationServer.Listener ---

    @Override
    public void onNotificationReceived(final NotificationData notification) {
        synchronized (history) {
            history.add(0, notification);
            while (history.size() > MAX_HISTORY) {
                history.remove(history.size() - 1);
            }
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showNotification(notification);
                wakeScreen();
            }
        });
    }

    @Override
    public void onLocationReceived(double lat, double lon, double alt, float accuracy, long time) {
        mockGPS.publish(lat, lon, alt, accuracy, time);
        if (!gpsActive) {
            gpsActive = true;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    updateStatus();
                }
            });
        }
    }

    @Override
    public void onNavigationReceived(final String instruction, final String distance, final String eta, long time) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                navigationActive = true;
                navInstruction.setText(instruction);
                navDistance.setText(distance);
                navEta.setText(eta);
                if (showingHistory) {
                    showingHistory = false;
                    historyView.setVisibility(View.GONE);
                }
                notificationView.setVisibility(View.GONE);
                navigationView.setVisibility(View.VISIBLE);
                // Wake screen and keep it on during navigation
                handler.removeCallbacks(dimScreen);
                if (!wakeLock.isHeld()) wakeLock.acquire();
            }
        });
    }

    @Override
    public void onNavigationEnded() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                navigationActive = false;
                navigationView.setVisibility(View.GONE);
                notificationView.setVisibility(View.VISIBLE);
                handler.removeCallbacks(dimScreen);
                handler.postDelayed(dimScreen, SCREEN_ON_DURATION_MS);
            }
        });
    }

    @Override
    public void onClientConnected(String address) {
        clientAddress = address;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updateStatus();
            }
        });
    }

    @Override
    public void onClientDisconnected() {
        clientAddress = null;
        gpsActive = false;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updateStatus();
            }
        });
    }

    // --- UI ---

    private void showNotification(NotificationData n) {
        // During navigation, don't switch away — just update the hidden notification view
        if (navigationActive) {
            textApp.setText(n.app);
            textTitle.setText(n.title);
            textBody.setText(n.text);
            return;
        }
        if (showingHistory) toggleHistory();
        textApp.setText(n.app);
        textTitle.setText(n.title);
        textBody.setText(n.text);
    }

    private void wakeScreen() {
        handler.removeCallbacks(dimScreen);
        if (!wakeLock.isHeld()) wakeLock.acquire();
        // During navigation, keep screen on indefinitely
        if (!navigationActive) {
            handler.postDelayed(dimScreen, SCREEN_ON_DURATION_MS);
        }
    }

    private void toggleHistory() {
        showingHistory = !showingHistory;
        if (showingHistory) {
            rebuildHistoryView();
            notificationView.setVisibility(View.GONE);
            navigationView.setVisibility(View.GONE);
            historyView.setVisibility(View.VISIBLE);
        } else {
            historyView.setVisibility(View.GONE);
            if (navigationActive) {
                navigationView.setVisibility(View.VISIBLE);
            } else {
                notificationView.setVisibility(View.VISIBLE);
            }
        }
    }

    private void rebuildHistoryView() {
        historyList.removeAllViews();
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.US);

        synchronized (history) {
            for (NotificationData n : history) {
                TextView tv = new TextView(this);
                String timeStr = sdf.format(new Date(n.time));
                tv.setText(timeStr + "  " + n.app + " - " + n.title + ": " + n.text);
                tv.setTextColor(0xFFCCCCCC);
                tv.setTextSize(16);
                tv.setPadding(0, 4, 0, 4);
                tv.setMaxLines(2);
                historyList.addView(tv);
            }
        }

        if (history.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("No notifications yet");
            tv.setTextColor(0xFF666666);
            tv.setTextSize(16);
            historyList.addView(tv);
        }
    }

    private void updateStatus() {
        String ip = getWifiIpAddress();
        String gpsTag = gpsActive ? " | GPS" : "";
        if (clientAddress != null) {
            textStatus.setText(ip + ":9876 | Connected: " + clientAddress + gpsTag);
            textStatus.setTextColor(0xFF4CAF50);
        } else {
            textStatus.setText(ip + ":9876 | Waiting for phone...");
            textStatus.setTextColor(0xFFFFAB40);
        }
    }

    private String getWifiIpAddress() {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) return "?.?.?.?";
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        int ip = wifiInfo.getIpAddress();
        if (ip == 0) return "no wifi";
        return String.format(Locale.US, "%d.%d.%d.%d",
                ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
    }

    // --- Input handling ---

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_BACK:
                    if (showingHistory) {
                        toggleHistory();
                        return true;
                    }
                    finish();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    toggleHistory();
                    return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchStartX = event.getX();
                touchStartY = event.getY();
                touchStartTime = System.currentTimeMillis();
                break;
            case MotionEvent.ACTION_UP:
                float dx = event.getX() - touchStartX;
                float dy = event.getY() - touchStartY;
                long dt = System.currentTimeMillis() - touchStartTime;

                if (Math.abs(dy) > 100 && Math.abs(dy) > Math.abs(dx)) {
                    if (showingHistory) {
                        toggleHistory();
                    } else {
                        finish();
                    }
                    return true;
                }
                if (dt > 800) {
                    finish();
                    return true;
                }
                if (Math.abs(dx) < 50 && Math.abs(dy) < 50) {
                    toggleHistory();
                    return true;
                }
                break;
        }
        return super.dispatchTouchEvent(event);
    }
}
