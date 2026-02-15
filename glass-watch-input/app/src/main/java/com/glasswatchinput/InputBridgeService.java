package com.glasswatchinput;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.List;

/**
 * AccessibilityService that receives BLE input events from the watch
 * and injects them as system-wide key events on Glass.
 *
 * Must be installed as a priv-app (/system/priv-app/) for INJECT_EVENTS.
 *
 * Enable via adb:
 *   adb shell settings put secure enabled_accessibility_services \
 *     com.glasswatchinput/com.glasswatchinput.InputBridgeService
 *   adb shell settings put secure accessibility_enabled 1
 */
public class InputBridgeService extends AccessibilityService implements BleManager.Listener {

    private static final String TAG = "InputBridge";

    private BleManager bleManager;
    private Instrumentation instrumentation;
    private HandlerThread inputThread;
    private Handler inputHandler;
    private Handler mainHandler;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "InputBridgeService connected");

        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }

        mainHandler = new Handler(Looper.getMainLooper());

        inputThread = new HandlerThread("InputInjector");
        inputThread.start();
        inputHandler = new Handler(inputThread.getLooper());

        instrumentation = new Instrumentation();

        bleManager = new BleManager(this);
        bleManager.setListener(this);
        bleManager.start();
    }

    @Override
    public void onDestroy() {
        if (bleManager != null) {
            bleManager.stop();
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
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        performGlobalAction(GLOBAL_ACTION_BACK);
                    }
                });
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
        final int act = (action == 0) ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;
        inputHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    long now = SystemClock.uptimeMillis();
                    instrumentation.sendKeySync(new KeyEvent(now, now, act, code, 0));
                } catch (Exception e) {
                    Log.w(TAG, "Key injection failed: " + e.getMessage());
                }
            }
        });
    }

    private void injectKey(final int keyCode) {
        inputHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    long now = SystemClock.uptimeMillis();
                    instrumentation.sendKeySync(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
                    instrumentation.sendKeySync(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
                    Log.d(TAG, "Injected keyevent " + keyCode);
                } catch (Exception e) {
                    Log.w(TAG, "Key injection failed for " + keyCode + ": " + e.getMessage());
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
