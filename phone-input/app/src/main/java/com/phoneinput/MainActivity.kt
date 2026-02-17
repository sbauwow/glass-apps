package com.phoneinput

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.util.Log

class MainActivity : Activity() {

    companion object {
        private const val TAG = "PhoneInput"
    }

    private lateinit var statusText: TextView
    private lateinit var touchpad: TouchpadView
    private lateinit var hiddenEdit: EditText
    private lateinit var btnKeyboard: Button

    private var service: InputService? = null
    private var bound = false
    private var keyboardVisible = false

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
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        touchpad = findViewById(R.id.touchpad)
        hiddenEdit = findViewById(R.id.hidden_edit)
        btnKeyboard = findViewById(R.id.btn_keyboard)

        // Touchpad events
        touchpad.onMove = { dx, dy ->
            service?.bleServer?.sendTouchMove(dx, dy)
        }
        touchpad.onTap = {
            service?.bleServer?.sendTouchTap()
        }
        touchpad.onFingerDown = {
            service?.bleServer?.sendTouchDown()
        }
        touchpad.onFingerUp = {
            service?.bleServer?.sendTouchUp()
        }

        // D-Pad buttons
        findViewById<Button>(R.id.btn_up).setOnClickListener {
            service?.bleServer?.sendGesture(BleGattServer.GESTURE_SWIPE_UP)
        }
        findViewById<Button>(R.id.btn_down).setOnClickListener {
            service?.bleServer?.sendGesture(BleGattServer.GESTURE_SWIPE_DOWN)
        }
        findViewById<Button>(R.id.btn_left).setOnClickListener {
            service?.bleServer?.sendGesture(BleGattServer.GESTURE_SWIPE_LEFT)
        }
        findViewById<Button>(R.id.btn_right).setOnClickListener {
            service?.bleServer?.sendGesture(BleGattServer.GESTURE_SWIPE_RIGHT)
        }
        findViewById<Button>(R.id.btn_ok).setOnClickListener {
            service?.bleServer?.sendGesture(BleGattServer.GESTURE_TAP)
        }

        // System buttons
        findViewById<Button>(R.id.btn_back).setOnClickListener {
            service?.bleServer?.sendKey(KeyEvent.KEYCODE_BACK)
        }
        findViewById<Button>(R.id.btn_home).setOnClickListener {
            service?.bleServer?.sendKey(KeyEvent.KEYCODE_HOME)
        }
        findViewById<Button>(R.id.btn_menu).setOnClickListener {
            service?.bleServer?.sendKey(KeyEvent.KEYCODE_MENU)
        }

        // Keyboard toggle
        btnKeyboard.setOnClickListener {
            toggleKeyboard()
        }

        // Keyboard input capture
        setupKeyboardCapture()

        requestPermissions()
        startForegroundService(Intent(this, InputService::class.java))
    }

    private fun setupKeyboardCapture() {
        // Seed with a space so backspace detection works
        hiddenEdit.setText(" ")
        hiddenEdit.setSelection(1)

        hiddenEdit.addTextChangedListener(object : TextWatcher {
            private var before = ""

            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                before = s.toString()
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable) {
                val now = s.toString()
                if (now.length < before.length) {
                    // Deletion = backspace
                    service?.bleServer?.sendKey(KeyEvent.KEYCODE_DEL)
                    // Re-seed
                    hiddenEdit.removeTextChangedListener(this)
                    hiddenEdit.setText(" ")
                    hiddenEdit.setSelection(1)
                    hiddenEdit.addTextChangedListener(this)
                } else if (now.length > before.length) {
                    val added = now.substring(before.length)
                    for (ch in added) {
                        val keyCode = charToKeyCode(ch)
                        if (keyCode != 0) {
                            service?.bleServer?.sendKey(keyCode)
                        }
                    }
                    // Reset to single space
                    hiddenEdit.removeTextChangedListener(this)
                    hiddenEdit.setText(" ")
                    hiddenEdit.setSelection(1)
                    hiddenEdit.addTextChangedListener(this)
                }
            }
        })

        hiddenEdit.setOnEditorActionListener { _, _, _ ->
            service?.bleServer?.sendKey(KeyEvent.KEYCODE_ENTER)
            true
        }
    }

    private fun charToKeyCode(ch: Char): Int {
        return when (ch) {
            in 'a'..'z' -> KeyEvent.KEYCODE_A + (ch - 'a')
            in 'A'..'Z' -> KeyEvent.KEYCODE_A + (ch - 'A')
            in '0'..'9' -> KeyEvent.KEYCODE_0 + (ch - '0')
            ' ' -> KeyEvent.KEYCODE_SPACE
            '.' -> KeyEvent.KEYCODE_PERIOD
            ',' -> KeyEvent.KEYCODE_COMMA
            '\n' -> KeyEvent.KEYCODE_ENTER
            '-' -> KeyEvent.KEYCODE_MINUS
            '=' -> KeyEvent.KEYCODE_EQUALS
            '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
            ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
            '\\' -> KeyEvent.KEYCODE_BACKSLASH
            ';' -> KeyEvent.KEYCODE_SEMICOLON
            '\'' -> KeyEvent.KEYCODE_APOSTROPHE
            '/' -> KeyEvent.KEYCODE_SLASH
            '@' -> KeyEvent.KEYCODE_AT
            '+' -> KeyEvent.KEYCODE_PLUS
            '\t' -> KeyEvent.KEYCODE_TAB
            else -> {
                Log.d(TAG, "Unmapped char: $ch (${ch.code})")
                0
            }
        }
    }

    private fun toggleKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (keyboardVisible) {
            imm.hideSoftInputFromWindow(hiddenEdit.windowToken, 0)
            keyboardVisible = false
            btnKeyboard.backgroundTintList = android.content.res.ColorStateList.valueOf(
                getColor(R.color.accent)
            )
        } else {
            hiddenEdit.requestFocus()
            imm.showSoftInput(hiddenEdit, InputMethodManager.SHOW_IMPLICIT)
            keyboardVisible = true
            btnKeyboard.backgroundTintList = android.content.res.ColorStateList.valueOf(
                0xFFFF9800.toInt()
            )
        }
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

    private fun requestPermissions() {
        val missing = REQUIRED_PERMISSIONS.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 100)
        }
    }
}
