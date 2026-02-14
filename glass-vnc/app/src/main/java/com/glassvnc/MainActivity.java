package com.glassvnc;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * VNC viewer for Google Glass Explorer Edition.
 *
 * Launch:
 *   adb shell am start -n com.glassvnc/.MainActivity --es host 192.168.1.X
 *   adb shell am start -n com.glassvnc/.MainActivity --es host 192.168.1.X --ei port 5900 --es password secret
 *   adb shell am start -n com.glassvnc/.MainActivity --es mode zoom
 *
 * Controls:
 *   Tap:        cycle zoom mode (full → quarter → half → zoom)
 *   Swipe down / back / long-press: exit
 *
 * Zoom modes (same as glass-monitor):
 *   full    — scale entire remote desktop to 640x360
 *   quarter — 640x360 crop (1:1 pixels)
 *   half    — 960x540 crop scaled to 640x360
 *   zoom    — 1280x720 crop scaled to 640x360
 */
public class MainActivity extends Activity implements VncView.Listener {

    private static final String PREFS_NAME = "glass_vnc";
    private static final String PREF_HOST = "host";
    private static final String PREF_PORT = "port";
    private static final String PREF_PASSWORD = "password";
    private static final String PREF_MODE = "mode";
    private static final int DEFAULT_PORT = 5900;
    private static final int STATUS_HIDE_DELAY_MS = 3000;

    private static final String[] MODE_NAMES = { "FULL", "1:1", "HALF", "ZOOM" };

    private VncView vncView;
    private TextView statusText;
    private TextView fpsText;
    private TextView modeText;
    private GestureDetector gestureDetector;
    private Handler handler;
    private Runnable hideStatusRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        vncView = (VncView) findViewById(R.id.vnc_view);
        statusText = (TextView) findViewById(R.id.status_text);
        fpsText = (TextView) findViewById(R.id.fps_text);
        modeText = (TextView) findViewById(R.id.mode_text);
        handler = new Handler();

        vncView.setListener(this);

        // Close button
        findViewById(R.id.close_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { exitApp(); }
        });

        // Resolve connection params: intent extras → saved prefs → defaults
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        String host = getIntentString("host", prefs.getString(PREF_HOST, "localhost"));
        int port = getIntentInt("port", prefs.getInt(PREF_PORT, DEFAULT_PORT));
        String password = getIntentString("password", prefs.getString(PREF_PASSWORD, ""));
        String modeName = getIntentString("mode", prefs.getString(PREF_MODE, "full"));
        int mode = parseModeArg(modeName);

        // Save for next launch
        prefs.edit()
                .putString(PREF_HOST, host)
                .putInt(PREF_PORT, port)
                .putString(PREF_PASSWORD, password)
                .putString(PREF_MODE, modeName)
                .apply();

        vncView.setServer(host, port, password);
        vncView.setZoomMode(mode);
        modeText.setText(MODE_NAMES[mode]);
        statusText.setText("CONNECTING");

        // Gesture detector
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                cycleZoomMode();
                return true;
            }

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

    private String getIntentString(String key, String fallback) {
        if (getIntent() != null) {
            String v = getIntent().getStringExtra(key);
            if (v != null && !v.isEmpty()) return v;
        }
        return fallback;
    }

    private int getIntentInt(String key, int fallback) {
        if (getIntent() != null && getIntent().hasExtra(key)) {
            return getIntent().getIntExtra(key, fallback);
        }
        return fallback;
    }

    private int parseModeArg(String name) {
        if ("quarter".equalsIgnoreCase(name)) return VncView.MODE_QUARTER;
        if ("half".equalsIgnoreCase(name))    return VncView.MODE_HALF;
        if ("zoom".equalsIgnoreCase(name))    return VncView.MODE_ZOOM;
        return VncView.MODE_FULL;
    }

    private void cycleZoomMode() {
        int next = (vncView.getZoomMode() + 1) % 4;
        vncView.setZoomMode(next);
        modeText.setText(MODE_NAMES[next]);
        modeText.setVisibility(View.VISIBLE);

        // Save preference
        String[] names = { "full", "quarter", "half", "zoom" };
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putString(PREF_MODE, names[next]).apply();

        // Flash the status text with mode name
        statusText.setVisibility(View.VISIBLE);
        statusText.setText(MODE_NAMES[next]);
        handler.removeCallbacks(hideStatusRunnable);
        handler.postDelayed(hideStatusRunnable, STATUS_HIDE_DELAY_MS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        vncView.startStream();
    }

    @Override
    protected void onPause() {
        super.onPause();
        vncView.stopStream();
        handler.removeCallbacks(hideStatusRunnable);
    }

    // ---- VncView.Listener ----

    @Override
    public void onStateChanged(int state) {
        handler.removeCallbacks(hideStatusRunnable);
        switch (state) {
            case VncView.STATE_CONNECTING:
                statusText.setVisibility(View.VISIBLE);
                statusText.setText("CONNECTING");
                fpsText.setVisibility(View.GONE);
                break;
            case VncView.STATE_CONNECTED:
                statusText.setVisibility(View.VISIBLE);
                statusText.setText("CONNECTED");
                fpsText.setVisibility(View.VISIBLE);
                handler.postDelayed(hideStatusRunnable, STATUS_HIDE_DELAY_MS);
                break;
            case VncView.STATE_DISCONNECTED:
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

    @Override
    public void onDesktopSize(int w, int h) {
        // Could update a display, for now just log
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
            case KeyEvent.KEYCODE_DPAD_CENTER:
                cycleZoomMode();
                return true;
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BACK:
                exitApp();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() { exitApp(); }

    private void exitApp() {
        vncView.stopStream();
        finish();
    }
}
