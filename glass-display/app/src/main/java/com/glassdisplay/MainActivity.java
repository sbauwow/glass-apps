package com.glassdisplay;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Fullscreen MJPEG display for Google Glass Explorer Edition.
 *
 * USB:  adb reverse tcp:8080 tcp:8080 → connects to localhost:8080
 * WiFi: adb shell am start -n com.glassdisplay/.MainActivity --es host 192.168.1.X
 *
 * Exit: back key, swipe down, long-press, right-click, escape
 */
public class MainActivity extends Activity implements MjpegView.Listener {

    private static final String PREFS_NAME = "glass_display";
    private static final String PREF_HOST = "host";
    private static final int DEFAULT_PORT = 8080;
    private static final int STATUS_HIDE_DELAY_MS = 3000;

    private MjpegView mjpegView;
    private TextView statusText;
    private TextView fpsText;
    private TextView batteryText;
    private GestureDetector gestureDetector;
    private Handler handler;
    private Runnable hideStatusRunnable;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int pct = (int) (100f * level / scale);
            batteryText.setText(pct + "%");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        mjpegView = (MjpegView) findViewById(R.id.mjpeg_view);
        statusText = (TextView) findViewById(R.id.status_text);
        fpsText = (TextView) findViewById(R.id.fps_text);
        batteryText = (TextView) findViewById(R.id.battery_text);
        handler = new Handler();

        mjpegView.setListener(this);

        // Close button
        findViewById(R.id.close_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exitApp();
            }
        });

        // Determine host: intent extra → saved preference → localhost
        String host = null;
        if (getIntent() != null) {
            host = getIntent().getStringExtra("host");
        }
        if (host != null && !host.isEmpty()) {
            // Save for next launch
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putString(PREF_HOST, host).apply();
        } else {
            host = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString(PREF_HOST, "localhost");
        }

        String url = "http://" + host + ":" + DEFAULT_PORT;
        mjpegView.setStreamUrl(url);
        statusText.setText("CONNECTING");

        // Gesture detector
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent e) {
                exitApp();
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dy = e2.getY() - e1.getY();
                float dx = e2.getX() - e1.getX();
                if (dy > 50 && Math.abs(dy) > Math.abs(dx)) {
                    exitApp();
                    return true;
                }
                return false;
            }
        });

        hideStatusRunnable = new Runnable() {
            @Override
            public void run() {
                statusText.setVisibility(View.GONE);
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        mjpegView.startStream();
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    @Override
    protected void onPause() {
        super.onPause();
        mjpegView.stopStream();
        handler.removeCallbacks(hideStatusRunnable);
        unregisterReceiver(batteryReceiver);
    }

    // ---- MjpegView.Listener ----

    @Override
    public void onStateChanged(int state) {
        handler.removeCallbacks(hideStatusRunnable);
        switch (state) {
            case MjpegView.STATE_CONNECTING:
                statusText.setVisibility(View.VISIBLE);
                statusText.setText("CONNECTING");
                fpsText.setVisibility(View.GONE);
                break;
            case MjpegView.STATE_CONNECTED:
                statusText.setVisibility(View.VISIBLE);
                statusText.setText("CONNECTED");
                fpsText.setVisibility(View.VISIBLE);
                handler.postDelayed(hideStatusRunnable, STATUS_HIDE_DELAY_MS);
                break;
            case MjpegView.STATE_DISCONNECTED:
                statusText.setVisibility(View.VISIBLE);
                statusText.setText("DISCONNECTED");
                fpsText.setVisibility(View.GONE);
                break;
        }
    }

    @Override
    public void onFps(int fps) {
        fpsText.setText(fps + " fps");
    }

    // ---- Input handling ----

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getButtonState() == MotionEvent.BUTTON_SECONDARY) {
            exitApp();
            return true;
        }
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getButtonState() == MotionEvent.BUTTON_SECONDARY) {
            exitApp();
            return true;
        }
        return gestureDetector.onTouchEvent(event) || super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BACK:
                exitApp();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        exitApp();
    }

    private void exitApp() {
        mjpegView.stopStream();
        finish();
    }
}
