package com.watchinput

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.app.Activity
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView

class InputActivity : Activity() {

    private lateinit var statusText: TextView

    private var service: InputService? = null
    private var bound = false

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val localBinder = binder as InputService.LocalBinder
            service = localBinder.service
            bound = true
            service?.onStatusChanged = { status ->
                runOnUiThread { statusText.text = status }
            }
            if (service?.isRunning == true) {
                val count = service?.bleServer?.connectionCount ?: 0
                statusText.text = if (count > 0) "CONNECTED ($count)" else "ADVERTISING..."
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_input)

        statusText = findViewById(R.id.status_text)

        // D-Pad buttons
        findViewById<Button>(R.id.btn_up).setOnClickListener {
            service?.bleServer?.notifyGesture(BleGattServer.GESTURE_SWIPE_UP)
        }
        findViewById<Button>(R.id.btn_down).setOnClickListener {
            service?.bleServer?.notifyGesture(BleGattServer.GESTURE_SWIPE_DOWN)
        }
        findViewById<Button>(R.id.btn_left).setOnClickListener {
            service?.bleServer?.notifyGesture(BleGattServer.GESTURE_SWIPE_LEFT)
        }
        findViewById<Button>(R.id.btn_right).setOnClickListener {
            service?.bleServer?.notifyGesture(BleGattServer.GESTURE_SWIPE_RIGHT)
        }
        findViewById<Button>(R.id.btn_ok).setOnClickListener {
            service?.bleServer?.notifyGesture(BleGattServer.GESTURE_TAP)
        }

        // System buttons — sent as key down+up pairs
        findViewById<Button>(R.id.btn_back).setOnClickListener {
            sendKey(KeyEvent.KEYCODE_BACK)
        }
        findViewById<Button>(R.id.btn_home).setOnClickListener {
            sendKey(KeyEvent.KEYCODE_HOME)
        }
        findViewById<Button>(R.id.btn_menu).setOnClickListener {
            sendKey(KeyEvent.KEYCODE_MENU)
        }

        // Extra buttons for dialogs and settings navigation
        findViewById<Button>(R.id.btn_tab).setOnClickListener {
            sendKey(KeyEvent.KEYCODE_TAB)
        }
        findViewById<Button>(R.id.btn_enter).setOnClickListener {
            sendKey(KeyEvent.KEYCODE_ENTER)
        }
        findViewById<Button>(R.id.btn_esc).setOnClickListener {
            sendKey(KeyEvent.KEYCODE_ESCAPE)
        }

        requestPermissions()
        startForegroundService(Intent(this, InputService::class.java))
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, InputService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        service?.onStatusChanged = null
        if (bound) {
            unbindService(connection)
            bound = false
            service = null
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_STEM_1 || keyCode == KeyEvent.KEYCODE_STEM_2 ||
            keyCode == KeyEvent.KEYCODE_STEM_3) {
            service?.bleServer?.notifyInput(
                BleGattServer.TYPE_KEY, keyCode.toByte(), BleGattServer.ACTION_DOWN
            )
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_STEM_1 || keyCode == KeyEvent.KEYCODE_STEM_2 ||
            keyCode == KeyEvent.KEYCODE_STEM_3) {
            service?.bleServer?.notifyInput(
                BleGattServer.TYPE_KEY, keyCode.toByte(), BleGattServer.ACTION_UP
            )
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun sendKey(keyCode: Int) {
        service?.bleServer?.notifyInput(
            BleGattServer.TYPE_KEY, keyCode.toByte(), BleGattServer.ACTION_DOWN
        )
        service?.bleServer?.notifyInput(
            BleGattServer.TYPE_KEY, keyCode.toByte(), BleGattServer.ACTION_UP
        )
    }

    private fun requestPermissions() {
        val missing = REQUIRED_PERMISSIONS.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 100)
        }
    }
}
