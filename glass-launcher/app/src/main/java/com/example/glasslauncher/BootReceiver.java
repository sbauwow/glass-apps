package com.example.glasslauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.example.glasslauncher.service.OverlayService;
import com.example.glasslauncher.util.PrefsManager;

/**
 * Starts the OverlayService on device boot.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "GlassLauncher";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            PrefsManager prefs = new PrefsManager(context);
            if (prefs.isOverlayEnabled()) {
                Log.d(TAG, "Boot completed, starting OverlayService");
                context.startService(new Intent(context, OverlayService.class));
            }
        }
    }
}
