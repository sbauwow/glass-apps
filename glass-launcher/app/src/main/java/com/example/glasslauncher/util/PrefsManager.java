package com.example.glasslauncher.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * SharedPreferences wrapper for launcher configuration.
 */
public final class PrefsManager {

    private static final String PREFS_NAME = "glass_launcher_prefs";
    private static final String KEY_CAMERA_ACTION = "camera_button_action";
    private static final String KEY_OVERLAY_ENABLED = "overlay_enabled";
    private static final String KEY_SELECTED_INDEX = "selected_app_index";

    // Camera button action values
    public static final String ACTION_GO_HOME = "go_home";
    public static final String ACTION_OPEN_SETTINGS = "open_settings";
    public static final String ACTION_LAUNCH_APP = "launch_app";
    public static final String ACTION_TAKE_PICTURE = "take_picture";

    private final SharedPreferences prefs;

    public PrefsManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getCameraAction() {
        return prefs.getString(KEY_CAMERA_ACTION, ACTION_GO_HOME);
    }

    public void setCameraAction(String action) {
        prefs.edit().putString(KEY_CAMERA_ACTION, action).apply();
    }

    public boolean isOverlayEnabled() {
        return prefs.getBoolean(KEY_OVERLAY_ENABLED, true);
    }

    public void setOverlayEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_OVERLAY_ENABLED, enabled).apply();
    }

    public int getSelectedIndex() {
        return prefs.getInt(KEY_SELECTED_INDEX, 0);
    }

    public void setSelectedIndex(int index) {
        prefs.edit().putInt(KEY_SELECTED_INDEX, index).apply();
    }
}
