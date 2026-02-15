package com.glassnotify;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

/**
 * Tilt-to-wake service for Google Glass.
 * Wakes the screen when the user tilts their head up (looking-up gesture),
 * replicating the Glass Explorer Edition behavior.
 */
public class TiltToWakeService extends Service implements SensorEventListener {

    private static final String TAG = "TiltToWake";

    // Thresholds
    private static final float MOTION_THRESHOLD = 2.0f;
    private static final float SIDEWAYS_THRESHOLD = 0.36f;
    private static final float LOOK_UP_THRESHOLD = 0.26f; // sin(15 degrees)
    private static final long SUSTAIN_MS = 300;
    private static final long COOLDOWN_MS = 2000;

    private SensorManager sensorManager;
    private PowerManager powerManager;
    private long tiltStartTime;
    private long lastWakeTime;

    @Override
    public void onCreate() {
        super.onCreate();
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);

        Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
            Log.d(TAG, "Tilt-to-wake started");
        } else {
            Log.w(TAG, "No accelerometer available");
        }
    }

    @Override
    public void onDestroy() {
        sensorManager.unregisterListener(this);
        Log.d(TAG, "Tilt-to-wake stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;

        // Skip if screen is already on
        @SuppressWarnings("deprecation")
        boolean screenOn = powerManager.isScreenOn();
        if (screenOn) {
            tiltStartTime = 0;
            return;
        }

        // Skip if in cooldown
        long now = System.currentTimeMillis();
        if (now - lastWakeTime < COOLDOWN_MS) return;

        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];

        // Check for motion (gravity magnitude should be ~9.81)
        float magnitude = (float) Math.sqrt(x * x + y * y + z * z);
        if (Math.abs(magnitude - SensorManager.GRAVITY_EARTH) > MOTION_THRESHOLD) {
            tiltStartTime = 0;
            return;
        }

        // Normalize
        float nx = x / magnitude;
        float ny = y / magnitude;
        float nz = z / magnitude;

        // Reject sideways tilt
        if (Math.abs(nx) > SIDEWAYS_THRESHOLD) {
            tiltStartTime = 0;
            return;
        }

        // Check for "looking up" orientation
        // On Glass worn on head, looking up means Y component becomes more negative
        // and Z component shifts — we check if the tilt-up component exceeds threshold
        if (ny > -LOOK_UP_THRESHOLD) {
            tiltStartTime = 0;
            return;
        }

        // Tilt detected — track how long it's sustained
        if (tiltStartTime == 0) {
            tiltStartTime = now;
            return;
        }

        if (now - tiltStartTime >= SUSTAIN_MS) {
            wakeScreen();
            tiltStartTime = 0;
            lastWakeTime = now;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @SuppressWarnings("deprecation")
    private void wakeScreen() {
        Log.d(TAG, "Tilt-to-wake triggered");
        PowerManager.WakeLock wl = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK
                        | PowerManager.ACQUIRE_CAUSES_WAKEUP
                        | PowerManager.ON_AFTER_RELEASE,
                "glassnotify:tiltawake");
        wl.acquire(1000);
        wl.release();
    }
}
