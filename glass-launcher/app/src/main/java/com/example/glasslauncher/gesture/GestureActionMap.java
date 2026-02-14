package com.example.glasslauncher.gesture;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Maps Glass touchpad gestures to configurable launcher actions.
 * Gesture mappings are persisted in SharedPreferences.
 */
public class GestureActionMap {

    private static final String PREFS_NAME = "gesture_action_map";

    // Gesture identifiers
    public static final String GESTURE_TAP = "tap";
    public static final String GESTURE_SWIPE_RIGHT = "swipe_right";
    public static final String GESTURE_SWIPE_LEFT = "swipe_left";
    public static final String GESTURE_SWIPE_DOWN = "swipe_down";
    public static final String GESTURE_TWO_FINGER_TAP = "two_finger_tap";
    public static final String GESTURE_LONG_PRESS = "long_press";

    // Action identifiers
    public static final int ACTION_LAUNCH_APP = 0;
    public static final int ACTION_NAVIGATE_NEXT = 1;
    public static final int ACTION_NAVIGATE_PREV = 2;
    public static final int ACTION_OPEN_SETTINGS = 3;
    public static final int ACTION_GO_HOME = 4;
    public static final int ACTION_APP_INFO = 5;
    public static final int ACTION_NONE = -1;

    private final SharedPreferences prefs;

    public GestureActionMap(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        initDefaults();
    }

    private void initDefaults() {
        // Only set defaults if no mappings exist yet
        if (!prefs.contains(GESTURE_TAP)) {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putInt(GESTURE_TAP, ACTION_LAUNCH_APP);
            editor.putInt(GESTURE_SWIPE_RIGHT, ACTION_NAVIGATE_NEXT);
            editor.putInt(GESTURE_SWIPE_LEFT, ACTION_NAVIGATE_PREV);
            editor.putInt(GESTURE_SWIPE_DOWN, ACTION_OPEN_SETTINGS);
            editor.putInt(GESTURE_TWO_FINGER_TAP, ACTION_GO_HOME);
            editor.putInt(GESTURE_LONG_PRESS, ACTION_APP_INFO);
            editor.apply();
        }
    }

    /** Get the action mapped to a gesture. */
    public int getAction(String gesture) {
        return prefs.getInt(gesture, ACTION_NONE);
    }

    /** Set the action for a gesture. */
    public void setAction(String gesture, int action) {
        prefs.edit().putInt(gesture, action).apply();
    }
}
