package com.example.glasslauncher.service;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;

import com.example.glasslauncher.overlay.HomeButtonView;

/**
 * Floating overlay service that displays a small home button indicator
 * in the corner of the Glass display. Visual-only since Glass has no
 * touchscreen — the home action is triggered by two-finger tap gesture
 * or the remapped camera button.
 *
 * Uses WindowManager.LayoutParams.TYPE_PHONE with SYSTEM_ALERT_WINDOW
 * permission (auto-granted on API 19).
 */
public class OverlayService extends Service {

    private static final String TAG = "OverlayService";
    private static final int OVERLAY_SIZE_PX = 40;

    private WindowManager windowManager;
    private HomeButtonView overlayView;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "OverlayService created");
        showOverlay();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        removeOverlay();
        Log.d(TAG, "OverlayService destroyed");
    }

    @SuppressWarnings("deprecation")
    private void showOverlay() {
        if (overlayView != null) return;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlayView = new HomeButtonView(this);

        // TYPE_PHONE is deprecated in later APIs but is the correct type for API 19
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                OVERLAY_SIZE_PX,
                OVERLAY_SIZE_PX,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );

        // Position in bottom-right corner of Glass display
        params.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        params.x = 8;
        params.y = 8;

        try {
            windowManager.addView(overlayView, params);
            Log.d(TAG, "Overlay view added");
        } catch (Exception e) {
            Log.e(TAG, "Failed to add overlay view", e);
        }
    }

    private void removeOverlay() {
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception e) {
                Log.e(TAG, "Failed to remove overlay view", e);
            }
            overlayView = null;
        }
    }
}
