package com.example.glasslauncher.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;

/**
 * Utility for launching apps and performing navigation actions from any component.
 */
public final class AppLaunchHelper {

    private static final String TAG = "GlassLauncher";

    private AppLaunchHelper() {}

    /** Launch an app by its package name and activity class name. */
    public static void launchApp(Context context, String packageName, String activityName) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setComponent(new ComponentName(packageName, activityName));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch " + packageName + "/" + activityName, e);
        }
    }

    /** Navigate to the home screen (this launcher). */
    public static void goHome(Context context) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /** Open Android system settings. */
    public static void openSettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_SETTINGS);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open settings", e);
        }
    }

    /** Launch the system camera app for still image capture. */
    public static void openCamera(Context context) {
        try {
            Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open camera", e);
        }
    }

    /** Perform the configured camera button action. */
    public static void performCameraAction(Context context) {
        PrefsManager prefs = new PrefsManager(context);
        String action = prefs.getCameraAction();

        switch (action) {
            case PrefsManager.ACTION_OPEN_SETTINGS:
                openSettings(context);
                break;
            case PrefsManager.ACTION_GO_HOME:
            default:
                goHome(context);
                break;
        }
    }
}
