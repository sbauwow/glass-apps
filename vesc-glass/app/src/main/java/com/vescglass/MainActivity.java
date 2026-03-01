package com.vescglass;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
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
 * VESC HUD for Google Glass Explorer Edition (AOSP 5.1.1).
 *
 * Exit: [X] button, right-click, swipe down, long-press, back/escape
 * Reconnect: tap on Glass touchpad, left-click on HUD
 * Forget trusted boards: long-press [X] button
 */
public class MainActivity extends Activity implements BleManager.Listener, SensorEventListener {

    private BleManager ble;
    private GestureDetector gestureDetector;
    private PowerManager.WakeLock wakeLock;
    private SensorManager sensorManager;
    private Sensor orientationSensor;

    // HUD views
    private DutyCircleView dutyCircle;
    private TextView speedValue;
    private TextView battValue;
    private TextView voltageValue;
    private TextView tempMosValue;
    private TextView tempMotorValue;
    private TextView tempBattValue;
    private TextView tripValue;
    private TextView statusText;
    private TextView headingValue;
    private TextView glassBatt;

    // Picker overlay (shown when multiple untrusted VESCs found)
    private LinearLayout pickerOverlay;

    // Glass battery receiver
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int pct = (level * 100) / scale;
            glassBatt.setText("G:" + pct + "%");
            if (pct < 15) glassBatt.setTextColor(COLOR_RED);
            else if (pct < 30) glassBatt.setTextColor(COLOR_YELLOW);
            else glassBatt.setTextColor(COLOR_DIM);
        }
    };

    private static final int COLOR_OK     = Color.WHITE;
    private static final int COLOR_YELLOW = Color.parseColor("#FFEB3B");
    private static final int COLOR_RED    = Color.parseColor("#FF1744");
    private static final int COLOR_DIM    = Color.parseColor("#BDBDBD");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_hud);

        dutyCircle     = (DutyCircleView) findViewById(R.id.duty_circle);
        speedValue     = (TextView)    findViewById(R.id.speed_value);
        battValue      = (TextView)    findViewById(R.id.batt_value);
        voltageValue   = (TextView)    findViewById(R.id.voltage_value);
        tempMosValue   = (TextView)    findViewById(R.id.temp_mos_value);
        tempMotorValue = (TextView)    findViewById(R.id.temp_motor_value);
        tempBattValue  = (TextView)    findViewById(R.id.temp_batt_value);
        tripValue      = (TextView)    findViewById(R.id.trip_value);
        statusText     = (TextView)    findViewById(R.id.status_text);
        headingValue   = (TextView)    findViewById(R.id.heading_value);
        glassBatt      = (TextView)    findViewById(R.id.glass_batt);

        // Compass sensor
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        orientationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION);

        // Close button — tap to exit, long-press to forget trusted devices
        View closeBtn = findViewById(R.id.close_btn);
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { exitApp(); }
        });
        closeBtn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                ble.clearTrustedDevices();
                statusText.setText("BOARDS FORGOTTEN");
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
                    return false; // let picker handle taps
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

        // Board config
        VescProtocol.motorPoles     = 30;
        VescProtocol.wheelDiameterM = 0.280;
        VescProtocol.gearRatio      = 1.0;
        VescProtocol.cellCountS     = 0;   // auto-detect
        VescProtocol.cellFull       = 4.2;
        VescProtocol.cellEmpty      = 3.0;

        ble = new BleManager(this);
        ble.setListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "vescglass:hud");
        wakeLock.acquire();
        if (orientationSensor != null) {
            sensorManager.registerListener(this, orientationSensor, SensorManager.SENSOR_DELAY_UI);
        }
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        ble.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(batteryReceiver);
        sensorManager.unregisterListener(this);
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
    public void onDataReceived(VescData d) {
        hidePicker();

        double duty = d.dutyPct();
        dutyCircle.setDuty((float) duty, dutyColor(duty));

        speedValue.setText(String.format("%.0f", d.speedMph));

        battValue.setText(String.format("%.0f", d.batteryPct));
        battValue.setTextColor(battColor(d.batteryPct));

        voltageValue.setText(String.format("%.1fV", d.voltage));

        tempMosValue.setText(String.format("C:%.0f°", cToF(d.tempMos)));
        tempMosValue.setTextColor(tempColor(d.tempMos));

        tempMotorValue.setText(String.format("M:%.0f°", cToF(d.tempMotor)));
        tempMotorValue.setTextColor(tempColor(d.tempMotor));

        if (d.tempBatt >= 0) {
            tempBattValue.setText(String.format("B:%.0f°", cToF(d.tempBatt)));
            tempBattValue.setTextColor(tempColor(d.tempBatt));
        } else {
            tempBattValue.setText("B:--°");
        }

        tripValue.setText(String.format("%.1f mi", d.tripMiles));
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

        // Title
        TextView title = new TextView(this);
        title.setText("Select your board (" + devices.size() + " found)");
        title.setTextColor(COLOR_DIM);
        title.setTextSize(16);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 16);
        pickerOverlay.addView(title);

        // One button per device
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

    // ---- Compass ----

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ORIENTATION) {
            float azimuth = event.values[0]; // 0-360 degrees
            headingValue.setText(azimuthToDirection(azimuth));
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private static String azimuthToDirection(float deg) {
        if (deg >= 337.5 || deg < 22.5)  return "N";
        if (deg < 67.5)  return "NE";
        if (deg < 112.5) return "E";
        if (deg < 157.5) return "SE";
        if (deg < 202.5) return "S";
        if (deg < 247.5) return "SW";
        if (deg < 292.5) return "W";
        return "NW";
    }

    // ---- Color helpers ----

    private static final int COLOR_CYAN = Color.parseColor("#00E5FF");

    private int dutyColor(double pct) {
        if (pct > 85) return COLOR_RED;
        if (pct > 70) return COLOR_YELLOW;
        return COLOR_CYAN;
    }

    private int battColor(double pct) {
        if (pct < 15) return COLOR_RED;
        if (pct < 30) return COLOR_YELLOW;
        return COLOR_OK;
    }

    private double cToF(double c) { return c * 9.0 / 5.0 + 32; }

    private int tempColor(double celsius) {
        if (celsius > 80) return COLOR_RED;
        if (celsius > 60) return COLOR_YELLOW;
        return COLOR_DIM;
    }
}
