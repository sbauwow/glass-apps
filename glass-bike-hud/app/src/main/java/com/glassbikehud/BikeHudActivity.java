package com.glassbikehud;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Bike HUD for Google Glass Explorer Edition (AOSP 5.1.1).
 *
 * Receives heart rate, GPS, speed, distance, and elapsed time from
 * a Galaxy Watch over BLE and displays them in a heads-up display.
 *
 * Exit: [X] button, right-click, swipe down, long-press, back/escape
 * Reconnect: tap on Glass touchpad
 * Forget trusted watches: long-press [X] button
 */
public class BikeHudActivity extends Activity implements BleManager.Listener {

    private BleManager ble;
    private GestureDetector gestureDetector;
    private PowerManager.WakeLock wakeLock;

    // HUD views
    private TextView hrValue;
    private TextView speedValue;
    private TextView distanceValue;
    private TextView elapsedValue;
    private TextView statusText;

    // Picker overlay (shown when multiple watches found)
    private LinearLayout pickerOverlay;

    private static final int COLOR_GREEN  = Color.parseColor("#00E676");
    private static final int COLOR_YELLOW = Color.parseColor("#FFEB3B");
    private static final int COLOR_RED    = Color.parseColor("#FF1744");
    private static final int COLOR_WHITE  = Color.WHITE;
    private static final int COLOR_DIM    = Color.parseColor("#BDBDBD");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_bike_hud);

        hrValue       = (TextView) findViewById(R.id.hr_value);
        speedValue    = (TextView) findViewById(R.id.speed_value);
        distanceValue = (TextView) findViewById(R.id.distance_value);
        elapsedValue  = (TextView) findViewById(R.id.elapsed_value);
        statusText    = (TextView) findViewById(R.id.status_text);

        // Close button — tap to exit, long-press to forget trusted watches
        View closeBtn = findViewById(R.id.close_btn);
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { exitApp(); }
        });
        closeBtn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                ble.clearTrustedDevices();
                statusText.setText("WATCHES FORGOTTEN");
                ble.stop();
                ble.start();
                return true;
            }
        });

        // Gesture detector
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (pickerOverlay != null && pickerOverlay.getVisibility() == View.VISIBLE) {
                    return false;
                }
                ble.stop();
                ble.start();
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

        ble = new BleManager(this);
        ble.setListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "bikehud:hud");
        wakeLock.acquire();
        ble.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        ble.stop();
    }

    // ---- Touch handling ----

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
                ble.stop();
                ble.start();
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
        ble.stop();
        finish();
    }

    // ---- BLE callbacks ----

    @Override
    public void onStatusChanged(String status) {
        statusText.setText(status);
    }

    @Override
    public void onDataReceived(BikeData d) {
        hidePicker();

        // Heart rate (hero metric)
        if (d.heartRate > 0) {
            hrValue.setText(String.valueOf(d.heartRate));
            hrValue.setTextColor(hrColor(d.heartRate));
        }

        // Speed
        float mph = d.speedMph();
        speedValue.setText(String.format("%.1f", mph));
        speedValue.setTextColor(speedColor(mph));

        // Distance
        distanceValue.setText(String.format("%.2f mi", d.distanceMi()));

        // Elapsed time
        elapsedValue.setText(d.elapsedStr());

        statusText.setText("LIVE");
    }

    @Override
    public void onDevicesFound(List<BleManager.FoundDevice> devices) {
        showPicker(devices);
    }

    // ---- Device picker ----

    private void showPicker(List<BleManager.FoundDevice> devices) {
        hidePicker();

        ViewGroup root = (ViewGroup) findViewById(android.R.id.content);

        pickerOverlay = new LinearLayout(this);
        pickerOverlay.setOrientation(LinearLayout.VERTICAL);
        pickerOverlay.setBackgroundColor(Color.parseColor("#EE000000"));
        pickerOverlay.setGravity(Gravity.CENTER);
        pickerOverlay.setPadding(40, 20, 40, 20);

        TextView title = new TextView(this);
        title.setText("Select your watch (" + devices.size() + " found)");
        title.setTextColor(COLOR_DIM);
        title.setTextSize(16);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 16);
        pickerOverlay.addView(title);

        for (final BleManager.FoundDevice fd : devices) {
            TextView btn = new TextView(this);
            String label = fd.name + "  (" + fd.mac.substring(fd.mac.length() - 5) + ")";
            btn.setText(label);
            btn.setTextColor(Color.WHITE);
            btn.setTextSize(20);
            btn.setGravity(Gravity.CENTER);
            btn.setBackgroundColor(Color.parseColor("#333333"));
            btn.setPadding(24, 16, 24, 16);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 8, 0, 8);
            btn.setLayoutParams(lp);

            btn.setClickable(true);
            btn.setFocusable(true);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hidePicker();
                    ble.connectToDevice(fd);
                }
            });

            pickerOverlay.addView(btn);
        }

        root.addView(pickerOverlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void hidePicker() {
        if (pickerOverlay != null) {
            ViewGroup root = (ViewGroup) findViewById(android.R.id.content);
            root.removeView(pickerOverlay);
            pickerOverlay = null;
        }
    }

    // ---- Color helpers ----

    private int hrColor(int bpm) {
        if (bpm > 160) return COLOR_RED;
        if (bpm > 130) return COLOR_YELLOW;
        return COLOR_GREEN;
    }

    private int speedColor(float mph) {
        if (mph > 30) return COLOR_RED;
        if (mph > 20) return COLOR_YELLOW;
        return COLOR_WHITE;
    }
}
