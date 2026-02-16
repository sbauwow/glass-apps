package com.glasswatchinput;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * AccessibilityService that receives BLE input events from the watch
 * and injects them as system-wide key events on Glass.
 *
 * Must be installed as a priv-app (/system/priv-app/).
 *
 * Uses a FIFO at /data/local/tmp/keybridge to communicate with a root
 * daemon that runs "input keyevent" with full permissions.
 * The daemon is started on boot via /system/bin/install-recovery.sh.
 *
 * Enable via adb:
 *   adb shell settings put secure enabled_accessibility_services \
 *     com.glasswatchinput/com.glasswatchinput.InputBridgeService
 *   adb shell settings put secure accessibility_enabled 1
 */
public class InputBridgeService extends AccessibilityService implements BleManager.Listener {

    private static final String TAG = "InputBridge";
    private static final String FIFO_PATH = "/data/local/tmp/keybridge";

    private BleManager bleManager;
    private HandlerThread inputThread;
    private Handler inputHandler;
    private Handler mainHandler;
    private OutputStream fifoOut;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "InputBridgeService connected");

        // Note: do NOT set FLAG_REQUEST_FILTER_KEY_EVENTS here —
        // it would interfere with ButtonRemapService's camera key interception

        mainHandler = new Handler(Looper.getMainLooper());

        inputThread = new HandlerThread("InputInjector");
        inputThread.start();
        inputHandler = new Handler(inputThread.getLooper());

        // Open FIFO for writing (connects to root daemon)
        inputHandler.post(new Runnable() {
            @Override
            public void run() {
                openFifo();
            }
        });

        bleManager = new BleManager(this);
        bleManager.setListener(this);
        bleManager.start();
    }

    private void openFifo() {
        try {
            File fifo = new File(FIFO_PATH);
            if (fifo.exists()) {
                fifoOut = new FileOutputStream(fifo);
                Log.i(TAG, "Connected to key bridge FIFO");
            } else {
                Log.w(TAG, "Key bridge FIFO not found at " + FIFO_PATH);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to open FIFO: " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        if (bleManager != null) {
            bleManager.stop();
        }
        if (fifoOut != null) {
            try { fifoOut.close(); } catch (Exception e) { /* ignore */ }
        }
        if (inputThread != null) {
            inputThread.quitSafely();
        }
        Log.i(TAG, "InputBridgeService destroyed");
        super.onDestroy();
    }

    @Override
    public void onStatusChanged(String status) {
        Log.i(TAG, "BLE status: " + status);
    }

    @Override
    public void onInputReceived(byte type, byte value, byte action) {
        Log.d(TAG, "Input: type=" + type + " value=" + value + " action=" + action);

        if (type == BleManager.TYPE_GESTURE) {
            handleGesture(value);
        } else if (type == BleManager.TYPE_ROTARY) {
            handleRotary(value);
        } else if (type == BleManager.TYPE_KEY) {
            handleKey(value, action);
        }
    }

    @Override
    public void onDevicesFound(List<BleManager.FoundDevice> devices) {
        if (!devices.isEmpty()) {
            bleManager.connectToDevice(devices.get(0));
        }
    }

    private void handleGesture(byte gesture) {
        switch (gesture) {
            case BleManager.GESTURE_TAP:
                injectKey(KeyEvent.KEYCODE_DPAD_CENTER);
                break;
            case BleManager.GESTURE_LONG_PRESS:
                globalAction(GLOBAL_ACTION_BACK);
                break;
            case BleManager.GESTURE_SWIPE_LEFT:
                injectKey(KeyEvent.KEYCODE_DPAD_LEFT);
                break;
            case BleManager.GESTURE_SWIPE_RIGHT:
                injectKey(KeyEvent.KEYCODE_DPAD_RIGHT);
                break;
            case BleManager.GESTURE_SWIPE_UP:
                injectKey(KeyEvent.KEYCODE_DPAD_UP);
                break;
            case BleManager.GESTURE_SWIPE_DOWN:
                injectKey(KeyEvent.KEYCODE_DPAD_DOWN);
                break;
        }
    }

    private void handleRotary(byte direction) {
        if (direction == BleManager.ROTARY_CW) {
            injectKey(KeyEvent.KEYCODE_DPAD_RIGHT);
        } else if (direction == BleManager.ROTARY_CCW) {
            injectKey(KeyEvent.KEYCODE_DPAD_LEFT);
        }
    }

    private void handleKey(byte keyCode, byte action) {
        final int code = keyCode & 0xFF;
        // Only act on key-down to avoid double-firing
        if (action != 0) return;

        switch (code) {
            case KeyEvent.KEYCODE_HOME:
                globalAction(GLOBAL_ACTION_HOME);
                break;
            case KeyEvent.KEYCODE_BACK:
                globalAction(GLOBAL_ACTION_BACK);
                break;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_APP_SWITCH:
                globalAction(GLOBAL_ACTION_RECENTS);
                break;
            default:
                injectKey(code);
                break;
        }
    }

    private void globalAction(final int action) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                performGlobalAction(action);
                Log.d(TAG, "Global action: " + action);
            }
        });
    }

    private void injectKey(final int keyCode) {
        inputHandler.post(new Runnable() {
            @Override
            public void run() {
                if (fifoOut == null) {
                    openFifo();
                }
                if (fifoOut != null) {
                    try {
                        fifoOut.write((keyCode + "\n").getBytes());
                        fifoOut.flush();
                        Log.d(TAG, "FIFO keyevent " + keyCode);
                    } catch (Exception e) {
                        Log.w(TAG, "FIFO write failed: " + e.getMessage());
                        try { fifoOut.close(); } catch (Exception e2) { /* ignore */ }
                        fifoOut = null;
                        // Try to reopen on next call
                    }
                } else {
                    Log.w(TAG, "No FIFO available for keyevent " + keyCode);
                }
            }
        });
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not used - we only inject, don't intercept
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "InputBridgeService interrupted");
    }
}
