package com.example.glasslauncher.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

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
    private DialogNavigator dialogNavigator;

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

        dialogNavigator = new DialogNavigator(this);
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
        if (dialogNavigator == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

        CharSequence className = event.getClassName();
        if (className == null) return;

        String cls = className.toString();
        if (isDialogWindow(cls)) {
            Log.d(TAG, "Dialog detected: " + cls);
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    dialogNavigator.show(root);
                    root.recycle();
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to get root window", e);
            }
        } else if (dialogNavigator.isShowing()) {
            Log.d(TAG, "Dialog dismissed, new window: " + cls);
            dialogNavigator.hide();
        }
    }

    /**
     * Detect system dialog windows by class name heuristic.
     */
    private boolean isDialogWindow(String className) {
        return className.contains("Dialog")
                || className.contains("AlertActivity")
                || className.contains("PermissionActivity")
                || className.contains("ChooserActivity")
                || className.contains("ResolverActivity");
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "ButtonRemapService interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (dialogNavigator != null) {
            dialogNavigator.hide();
        }
        Log.d(TAG, "ButtonRemapService destroyed");
    }
}
