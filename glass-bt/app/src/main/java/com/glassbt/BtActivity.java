package com.glassbt;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class BtActivity extends Activity implements RfcommServer.Listener {

    private static final String TAG = "BtActivity";
    private static final int MAX_LOG_ENTRIES = 50;
    private static final int REQUEST_DISCOVERABLE = 1;
    private static final int DISCOVERABLE_DURATION = 300;
    private static final int SCREEN_ON_DURATION_MS = 8000;

    private RfcommServer server;
    private PowerManager.WakeLock wakeLock;
    private Handler handler;

    // UI
    private View messageView;
    private ScrollView logView;
    private LinearLayout logList;
    private TextView textFrom;
    private TextView textMessage;
    private TextView textStatus;
    private boolean showingLog = false;
    private int logCount = 0;

    // Touch tracking
    private float touchStartX, touchStartY;
    private long touchStartTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_bt);

        handler = new Handler();

        messageView = findViewById(R.id.message_view);
        logView = findViewById(R.id.log_view);
        logList = findViewById(R.id.log_list);
        textFrom = findViewById(R.id.text_from);
        textMessage = findViewById(R.id.text_message);
        textStatus = findViewById(R.id.text_status);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "glassbt:wake");

        server = new RfcommServer(this, this);

        if (!server.hasTrustedDevices()) {
            requestDiscoverability();
        }

        server.start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        server.stop();
        if (wakeLock.isHeld()) wakeLock.release();
    }

    // --- Discoverability ---

    private void requestDiscoverability() {
        Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_DURATION);
        startActivityForResult(intent, REQUEST_DISCOVERABLE);
    }

    // --- RfcommServer.Listener ---

    @Override
    public void onClientConnected(String deviceName, String mac) {
        runOnUiThread(() -> {
            textStatus.setText(getString(R.string.status_connected) + " - " + deviceName);
            textStatus.setTextColor(getResources().getColor(R.color.status_connected));
            wakeScreen();
        });
    }

    @Override
    public void onClientDisconnected() {
        runOnUiThread(() -> {
            textStatus.setText(R.string.status_waiting);
            textStatus.setTextColor(getResources().getColor(R.color.status_waiting));
        });
    }

    @Override
    public void onMessageReceived(Message message) {
        runOnUiThread(() -> {
            // Update main view
            String from = message.getDisplayFrom();
            String text = message.getDisplayText();
            textFrom.setText(from);
            textMessage.setText(text);

            // Add to log
            addLogEntry(from, text, message.ts);

            wakeScreen();
        });
    }

    @Override
    public void onError(String error) {
        runOnUiThread(() -> {
            textStatus.setText("ERROR: " + error);
            textStatus.setTextColor(Color.RED);
        });
    }

    @Override
    public void onListening(int channel) {
        runOnUiThread(() -> {
            textStatus.setText(getString(R.string.status_waiting) + " (ch " + channel + ")");
            textStatus.setTextColor(getResources().getColor(R.color.status_waiting));
        });
    }

    // --- UI helpers ---

    private void addLogEntry(String from, String text, long ts) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(ts));
        String entry = time + "  ";
        if (!from.isEmpty()) entry += from + ": ";
        entry += text;

        TextView tv = new TextView(this);
        tv.setText(entry);
        tv.setTextSize(14);
        tv.setTextColor(getResources().getColor(R.color.text_secondary));
        tv.setPadding(0, 2, 0, 2);
        logList.addView(tv);
        logCount++;

        // Trim old entries
        while (logCount > MAX_LOG_ENTRIES) {
            logList.removeViewAt(0);
            logCount--;
        }

        // Auto-scroll
        if (showingLog) {
            logView.post(() -> logView.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void toggleLog() {
        showingLog = !showingLog;
        if (showingLog) {
            messageView.setVisibility(View.GONE);
            logView.setVisibility(View.VISIBLE);
            logView.post(() -> logView.fullScroll(View.FOCUS_DOWN));
        } else {
            logView.setVisibility(View.GONE);
            messageView.setVisibility(View.VISIBLE);
        }
    }

    private void wakeScreen() {
        if (!wakeLock.isHeld()) {
            wakeLock.acquire(SCREEN_ON_DURATION_MS);
        }
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(() -> {
            if (wakeLock.isHeld()) wakeLock.release();
        }, SCREEN_ON_DURATION_MS);
    }

    // --- Touch / gesture handling ---

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchStartX = event.getX();
                touchStartY = event.getY();
                touchStartTime = System.currentTimeMillis();
                return true;

            case MotionEvent.ACTION_UP:
                float dx = event.getX() - touchStartX;
                float dy = event.getY() - touchStartY;
                long duration = System.currentTimeMillis() - touchStartTime;

                if (dy > 100 && Math.abs(dx) < Math.abs(dy)) {
                    // Swipe down
                    if (showingLog) {
                        toggleLog();
                    } else {
                        finish();
                    }
                } else if (duration > 800 && Math.abs(dx) < 50 && Math.abs(dy) < 50) {
                    // Long press — re-enable discoverability
                    requestDiscoverability();
                } else if (Math.abs(dx) < 50 && Math.abs(dy) < 50) {
                    // Tap — toggle log & send tap event to Linux
                    toggleLog();
                    sendTapEvent();
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            toggleLog();
            sendTapEvent();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (showingLog) {
                toggleLog();
            } else {
                finish();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void sendTapEvent() {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "command");
            msg.put("ts", System.currentTimeMillis());
            msg.put("from", "glass");
            msg.put("cmd", "tap");
            server.sendMessage(msg);
        } catch (JSONException ignored) {}
    }
}
