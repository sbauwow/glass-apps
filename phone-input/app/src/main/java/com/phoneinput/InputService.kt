package com.phoneinput

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log

class InputService : Service() {

    companion object {
        private const val TAG = "InputService"
        private const val CHANNEL_ID = "phone_input_channel"
        private const val NOTIFICATION_ID = 1
    }

    inner class LocalBinder : Binder() {
        val service: InputService get() = this@InputService
    }

    private val binder = LocalBinder()

    val bleServer = BleGattServer(this)
    var isRunning = false
        private set

    var onStatusChanged: ((String) -> Unit)? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) return START_STICKY

        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Phone Input")
            .setContentText("Sending input to Glass...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        if (!bleServer.start()) {
            Log.e(TAG, "Failed to start BLE server")
            onStatusChanged?.invoke("BLE FAILED")
            stopSelf()
            return START_NOT_STICKY
        }

        bleServer.onConnectionCountChanged = { count ->
            val status = if (count > 0) "CONNECTED ($count)" else "WAITING..."
            onStatusChanged?.invoke(status)
        }

        isRunning = true
        onStatusChanged?.invoke("ADVERTISING...")
        Log.i(TAG, "Input service started")
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        bleServer.stop()
        Log.i(TAG, "Input service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Phone Input Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "BLE input bridge to Glass"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
