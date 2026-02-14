package com.example.glasslauncher.gesture;

import android.content.Context;
import android.util.Log;
import android.view.MotionEvent;

/**
 * Handles Glass touchpad gestures by interpreting raw MotionEvents
 * from onGenericMotionEvent(). Uses simple velocity/distance thresholds
 * instead of the GDK GestureDetector (which may not be available at compile time).
 *
 * Glass touchpad delivers MotionEvents via the generic motion event path.
 * The touchpad coordinate space is roughly 0-1000 in X and 0-1000 in Y.
 */
public class GlassGestureHandler {

    private static final String TAG = "GlassLauncher";

    private static final float SWIPE_THRESHOLD = 100f;
    private static final long LONG_PRESS_THRESHOLD_MS = 500;
    private static final long TAP_TIMEOUT_MS = 300;

    public interface GestureListener {
        void onTap();
        void onSwipeRight();
        void onSwipeLeft();
        void onSwipeDown();
        void onTwoFingerTap();
        void onLongPress();
    }

    private final GestureListener listener;

    private float downX;
    private float downY;
    private long downTime;
    private int pointerCount;
    private boolean handled;

    public GlassGestureHandler(GestureListener listener) {
        this.listener = listener;
    }

    /**
     * Call this from Activity.onGenericMotionEvent() or Activity.dispatchTouchEvent().
     * Returns true if the event was consumed.
     */
    public boolean onMotionEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                downTime = event.getEventTime();
                pointerCount = 1;
                handled = false;
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                pointerCount = Math.max(pointerCount, event.getPointerCount());
                return true;

            case MotionEvent.ACTION_MOVE:
                // Check for long press during move
                if (!handled && (event.getEventTime() - downTime) > LONG_PRESS_THRESHOLD_MS) {
                    float moveDist = distance(event.getX(), event.getY(), downX, downY);
                    if (moveDist < SWIPE_THRESHOLD) {
                        handled = true;
                        Log.d(TAG, "Gesture: long press");
                        listener.onLongPress();
                        return true;
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (handled) {
                    return true;
                }

                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                long duration = event.getEventTime() - downTime;

                // Two-finger tap
                if (pointerCount >= 2 && Math.abs(dx) < SWIPE_THRESHOLD
                        && Math.abs(dy) < SWIPE_THRESHOLD) {
                    Log.d(TAG, "Gesture: two-finger tap");
                    listener.onTwoFingerTap();
                    return true;
                }

                // Swipe detection
                if (Math.abs(dx) > SWIPE_THRESHOLD || Math.abs(dy) > SWIPE_THRESHOLD) {
                    if (Math.abs(dx) > Math.abs(dy)) {
                        if (dx > 0) {
                            Log.d(TAG, "Gesture: swipe right");
                            listener.onSwipeRight();
                        } else {
                            Log.d(TAG, "Gesture: swipe left");
                            listener.onSwipeLeft();
                        }
                    } else {
                        if (dy > 0) {
                            Log.d(TAG, "Gesture: swipe down");
                            listener.onSwipeDown();
                        }
                        // Swipe up is not mapped
                    }
                    return true;
                }

                // Single tap
                if (duration < TAP_TIMEOUT_MS) {
                    Log.d(TAG, "Gesture: tap");
                    listener.onTap();
                    return true;
                }

                return false;

            default:
                return false;
        }
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
