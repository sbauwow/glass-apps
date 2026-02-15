package com.watchbikehud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Foreground service that reads heart rate sensor and GPS,
 * then pushes data to connected Glass devices via BLE GATT.
 */
class BikeSensorService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "BikeSensor"
        private const val CHANNEL_ID = "bike_hud_channel"
        private const val NOTIFICATION_ID = 1
    }

    inner class LocalBinder : Binder() {
        val service: BikeSensorService get() = this@BikeSensorService
    }

    private val binder = LocalBinder()

    private lateinit var sensorManager: SensorManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val handler = Handler(Looper.getMainLooper())

    val bleServer = BleGattServer(this)
    val distanceTracker = DistanceTracker()

    var currentHR: Int = 0
        private set
    var currentSpeedMps: Float = 0f
        private set
    var elapsedSec: Int = 0
        private set
    var hasGpsFix: Boolean = false
        private set
    var isRunning: Boolean = false
        private set

    private var startTimeMs: Long = 0
    var onDataUpdated: (() -> Unit)? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            hasGpsFix = true
            currentSpeedMps = if (loc.hasSpeed()) loc.speed else 0f

            distanceTracker.addPoint(loc)

            bleServer.notifyLocation(
                loc.latitude, loc.longitude,
                currentSpeedMps, if (loc.hasBearing()) loc.bearing else 0f
            )
            bleServer.notifyTrip(distanceTracker.totalMeters, elapsedSec)

            onDataUpdated?.invoke()
        }
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                elapsedSec = ((SystemClock.elapsedRealtime() - startTimeMs) / 1000).toInt()
                bleServer.notifyTrip(distanceTracker.totalMeters, elapsedSec)
                onDataUpdated?.invoke()
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) return START_STICKY

        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Bike HUD")
            .setContentText("Streaming to Glass...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        // Start BLE GATT server
        if (!bleServer.start()) {
            Log.e(TAG, "Failed to start BLE server")
            stopSelf()
            return START_NOT_STICKY
        }

        // Start heart rate sensor
        val hrSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        if (hrSensor != null) {
            sensorManager.registerListener(this, hrSensor, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i(TAG, "Heart rate sensor registered")
        } else {
            Log.w(TAG, "No heart rate sensor available")
        }

        // Start GPS
        try {
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, 1000
            ).setMinUpdateIntervalMillis(500).build()

            fusedLocationClient.requestLocationUpdates(
                locationRequest, locationCallback, Looper.getMainLooper()
            )
            Log.i(TAG, "GPS updates requested")
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission denied", e)
        }

        // Start elapsed timer
        startTimeMs = SystemClock.elapsedRealtime()
        isRunning = true
        handler.postDelayed(timerRunnable, 1000)

        Log.i(TAG, "Bike sensor service started")
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(timerRunnable)
        sensorManager.unregisterListener(this)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        bleServer.stop()
        Log.i(TAG, "Bike sensor service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder = binder

    // ---- SensorEventListener ----

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_HEART_RATE) {
            if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) return
            val bpm = event.values[0].toInt()
            if (bpm > 0) {
                currentHR = bpm
                bleServer.notifyHeartRate(bpm)
                onDataUpdated?.invoke()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Bike HUD Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Bike HUD sensor streaming"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }
}
