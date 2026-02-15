package com.watchbikehud

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.app.Activity
import android.widget.Button
import android.widget.TextView

/**
 * Watch UI for the bike HUD system.
 * Shows current sensor data and provides start/stop control.
 */
class WatchBikeActivity : Activity() {

    private lateinit var hrDisplay: TextView
    private lateinit var gpsStatus: TextView
    private lateinit var bleStatus: TextView
    private lateinit var distanceDisplay: TextView
    private lateinit var elapsedDisplay: TextView
    private lateinit var startStopBtn: Button

    private var service: BikeSensorService? = null
    private var bound = false

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.BODY_SENSORS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS,
    )
    private val PERMISSION_REQUEST_CODE = 100

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val localBinder = binder as BikeSensorService.LocalBinder
            service = localBinder.service
            bound = true
            wireServiceCallbacks()
            updateUI()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_watch_bike)

        hrDisplay = findViewById(R.id.hr_display)
        gpsStatus = findViewById(R.id.gps_status)
        bleStatus = findViewById(R.id.ble_status)
        distanceDisplay = findViewById(R.id.distance_display)
        elapsedDisplay = findViewById(R.id.elapsed_display)
        startStopBtn = findViewById(R.id.start_stop_btn)

        startStopBtn.setOnClickListener {
            val svc = service
            if (svc != null && svc.isRunning) {
                stopBikeService()
            } else {
                startBikeService()
            }
        }

        requestPermissions()
    }

    override fun onStart() {
        super.onStart()
        // Bind to running service if it exists
        val intent = Intent(this, BikeSensorService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        service?.onDataUpdated = null
        service?.bleServer?.onConnectionCountChanged = null
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    private fun requestPermissions() {
        val missing = REQUIRED_PERMISSIONS.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Continue regardless — some permissions may be optional
    }

    private fun startBikeService() {
        val intent = Intent(this, BikeSensorService::class.java)
        startForegroundService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        startStopBtn.text = "STOP"
    }

    private fun stopBikeService() {
        service?.onDataUpdated = null
        service?.bleServer?.onConnectionCountChanged = null
        val intent = Intent(this, BikeSensorService::class.java)
        stopService(intent)
        startStopBtn.text = "START"
        hrDisplay.text = "-- bpm"
        gpsStatus.text = "GPS: --"
        bleStatus.text = "BLE: 0"
        distanceDisplay.text = "0.00 mi"
        elapsedDisplay.text = "00:00:00"
    }

    private fun wireServiceCallbacks() {
        val svc = service ?: return

        svc.onDataUpdated = { runOnUiThread { updateUI() } }
        svc.bleServer.onConnectionCountChanged = { count ->
            runOnUiThread { bleStatus.text = "BLE: $count" }
        }
    }

    private fun updateUI() {
        val svc = service ?: return

        startStopBtn.text = if (svc.isRunning) "STOP" else "START"

        if (svc.currentHR > 0) {
            hrDisplay.text = "${svc.currentHR} bpm"
        }

        gpsStatus.text = if (svc.hasGpsFix) "GPS: OK" else "GPS: --"
        bleStatus.text = "BLE: ${svc.bleServer.connectionCount}"

        val distMi = svc.distanceTracker.totalMeters * 0.000621371f
        distanceDisplay.text = String.format("%.2f mi", distMi)

        val h = svc.elapsedSec / 3600
        val m = (svc.elapsedSec % 3600) / 60
        val s = svc.elapsedSec % 60
        elapsedDisplay.text = String.format("%02d:%02d:%02d", h, m, s)
    }
}
