package com.example.glasslauncher.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import com.example.glasslauncher.util.AppLaunchHelper;

/**
 * AccessibilityService that intercepts the camera button (KEYCODE_CAMERA)
 * system-wide and remaps it to a configurable action.
 *
 * Must be enabled via:
 *   Settings > Accessibility > Glass Launcher Button Remap
 * or via adb:
 *   adb shell settings put secure enabled_accessibility_services \
 *     com.example.glasslauncher/com.example.glasslauncher.service.ButtonRemapService
 *   adb shell settings put secure accessibility_enabled 1
 */
public class ButtonRemapService extends AccessibilityService {

    private static final String TAG = "ButtonRemapService";
    private static final long LONG_PRESS_THRESHOLD_MS = 500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean longPressHandled;

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            longPressHandled = true;
            Log.d(TAG, "Camera long press — going home");
            AppLaunchHelper.goHome(ButtonRemapService.this);
        }
    };

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "ButtonRemapService connected");

        // Programmatically ensure key event filtering is enabled
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_CAMERA) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                longPressHandled = false;
                handler.postDelayed(longPressRunnable, LONG_PRESS_THRESHOLD_MS);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(longPressRunnable);
                if (!longPressHandled) {
                    Log.d(TAG, "Camera short press — opening camera");
                    AppLaunchHelper.openCamera(this);
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not used — we only need key event filtering
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "ButtonRemapService interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "ButtonRemapService destroyed");
    }
}
