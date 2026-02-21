package com.example.glasslauncher;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import com.example.glasslauncher.gesture.GestureActionMap;
import com.example.glasslauncher.gesture.GlassGestureHandler;
import com.example.glasslauncher.util.AppLaunchHelper;
import com.example.glasslauncher.util.PrefsManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Home screen launcher for Google Glass.
 * Displays installed apps as horizontally scrolling cards.
 * Handles Glass touchpad gestures for navigation and app launching.
 */
public class LauncherActivity extends Activity implements GlassGestureHandler.GestureListener {

    private static final String TAG = "GlassLauncher";

    // Pinned apps in display order (first = position 0, second = position 1, etc.)
    private static final List<String> PINNED_PACKAGES = Arrays.asList(
            "com.glassdisplay",
            "com.android.settings",
            "com.vescglass",
            "com.example.glassstream"
    );

    private static final long STATUS_UPDATE_INTERVAL_MS = 30000;

    private static final String ACCESSIBILITY_SERVICE_ID =
            "com.example.glasslauncher/com.example.glasslauncher.service.ButtonRemapService";

    private HorizontalScrollView scrollView;
    private LinearLayout appContainer;
    private TextView emptyText;
    private TextView dateTimeText;
    private TextView wifiText;
    private TextView batteryText;
    private TextView setupBanner;

    private final SimpleDateFormat dateTimeFormat =
            new SimpleDateFormat("EEE, MMM d  h:mm a", Locale.getDefault());

    private static final long LONG_PRESS_THRESHOLD_MS = 500;

    private List<AppInfo> apps;
    private int selectedIndex = 0;
    private GlassGestureHandler gestureHandler;
    private GestureActionMap gestureActionMap;
    private PrefsManager prefsManager;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean cameraLongPressHandled;

    private final Runnable statusUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            handler.postDelayed(this, STATUS_UPDATE_INTERVAL_MS);
        }
    };

    private final Runnable cameraLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            cameraLongPressHandled = true;
            Log.d(TAG, "Camera long press — going home");
            AppLaunchHelper.goHome(LauncherActivity.this);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);

        scrollView = (HorizontalScrollView) findViewById(R.id.scroll_view);
        appContainer = (LinearLayout) findViewById(R.id.app_container);
        emptyText = (TextView) findViewById(R.id.empty_text);
        dateTimeText = (TextView) findViewById(R.id.date_time_text);
        wifiText = (TextView) findViewById(R.id.wifi_text);
        batteryText = (TextView) findViewById(R.id.battery_text);
        setupBanner = (TextView) findViewById(R.id.setup_banner);

        prefsManager = new PrefsManager(this);
        gestureActionMap = new GestureActionMap(this);
        gestureHandler = new GlassGestureHandler(this);

    }

    @Override
    protected void onResume() {
        super.onResume();
        loadApps();
        selectedIndex = prefsManager.getSelectedIndex();
        if (selectedIndex >= apps.size()) {
            selectedIndex = 0;
        }
        populateAppCards();
        highlightSelected();
        handler.removeCallbacks(statusUpdateRunnable);
        statusUpdateRunnable.run();

        // Show setup banner if accessibility service is not enabled
        if (isAccessibilityServiceEnabled()) {
            setupBanner.setVisibility(View.GONE);
        } else {
            setupBanner.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        prefsManager.setSelectedIndex(selectedIndex);
        handler.removeCallbacks(statusUpdateRunnable);
    }

    private void updateStatus() {
        dateTimeText.setText(dateTimeFormat.format(new Date()));

        // WiFi status
        WifiManager wifiManager = (WifiManager) getSystemService(WIFI_SERVICE);
        if (wifiManager != null && wifiManager.isWifiEnabled()) {
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            if (wifiInfo != null && wifiInfo.getNetworkId() != -1) {
                String ssid = wifiInfo.getSSID();
                if (ssid != null) {
                    ssid = ssid.replace("\"", "");
                }
                int rssi = wifiInfo.getRssi();
                int bars = WifiManager.calculateSignalLevel(rssi, 4);
                String signal;
                switch (bars) {
                    case 0: signal = "\u2581"; break;
                    case 1: signal = "\u2582"; break;
                    case 2: signal = "\u2584"; break;
                    default: signal = "\u2586"; break;
                }
                wifiText.setText(ssid + " " + signal);
            } else {
                wifiText.setText("No WiFi");
            }
        } else {
            wifiText.setText("WiFi off");
        }

        // Battery status
        Intent batteryStatus = registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) {
                batteryText.setText((level * 100 / scale) + "%");
            }
        }
    }

    private void loadApps() {
        apps = new ArrayList<AppInfo>();
        PackageManager pm = getPackageManager();

        Intent mainIntent = new Intent(Intent.ACTION_MAIN);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolveInfos = pm.queryIntentActivities(mainIntent, 0);

        for (ResolveInfo ri : resolveInfos) {
            // Skip our own launcher from the list
            if (ri.activityInfo.packageName.equals(getPackageName())) {
                continue;
            }

            String label = ri.loadLabel(pm).toString();
            String packageName = ri.activityInfo.packageName;
            String activityName = ri.activityInfo.name;
            Drawable icon = ri.loadIcon(pm);

            apps.add(new AppInfo(label, packageName, activityName, icon));
        }

        // Sort: pinned apps first (in defined order), then alphabetically
        Collections.sort(apps, new Comparator<AppInfo>() {
            @Override
            public int compare(AppInfo a, AppInfo b) {
                int posA = PINNED_PACKAGES.indexOf(a.getPackageName());
                int posB = PINNED_PACKAGES.indexOf(b.getPackageName());
                if (posA >= 0 && posB >= 0) return posA - posB;
                if (posA >= 0) return -1;
                if (posB >= 0) return 1;
                return a.getLabel().compareToIgnoreCase(b.getLabel());
            }
        });

        Log.d(TAG, "Loaded " + apps.size() + " launchable apps");
    }

    private void populateAppCards() {
        appContainer.removeAllViews();

        if (apps.isEmpty()) {
            emptyText.setVisibility(View.VISIBLE);
            return;
        }
        emptyText.setVisibility(View.GONE);

        LayoutInflater inflater = getLayoutInflater();
        for (int i = 0; i < apps.size(); i++) {
            AppInfo app = apps.get(i);
            View card = inflater.inflate(R.layout.item_app, appContainer, false);

            ImageView icon = (ImageView) card.findViewById(R.id.app_icon);
            TextView label = (TextView) card.findViewById(R.id.app_label);

            icon.setImageDrawable(app.getIcon());
            label.setText(app.getLabel());

            card.setTag(i);
            appContainer.addView(card);
        }
    }

    private void highlightSelected() {
        if (apps.isEmpty()) return;

        for (int i = 0; i < appContainer.getChildCount(); i++) {
            View card = appContainer.getChildAt(i);
            if (i == selectedIndex) {
                card.setBackgroundColor(Color.parseColor("#4488CCFF"));
            } else {
                card.setBackgroundResource(R.drawable.bg_app_card);
            }
        }

        // Scroll to make the selected card visible
        final View selected = appContainer.getChildAt(selectedIndex);
        if (selected != null) {
            scrollView.post(new Runnable() {
                @Override
                public void run() {
                    int scrollX = selected.getLeft()
                            - (scrollView.getWidth() / 2)
                            + (selected.getWidth() / 2);
                    scrollView.smoothScrollTo(Math.max(0, scrollX), 0);
                }
            });
        }
    }

    // --- Gesture handling ---

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (gestureHandler.onMotionEvent(event)) {
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (gestureHandler.onMotionEvent(event)) {
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // D-pad / keyboard fallback for testing on emulator
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                navigateNext();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                navigatePrev();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                launchSelected();
                return true;
            case KeyEvent.KEYCODE_CAMERA:
                if (event.getRepeatCount() == 0) {
                    cameraLongPressHandled = false;
                    handler.postDelayed(cameraLongPressRunnable, LONG_PRESS_THRESHOLD_MS);
                }
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_CAMERA) {
            handler.removeCallbacks(cameraLongPressRunnable);
            if (!cameraLongPressHandled) {
                Log.d(TAG, "Camera short press — opening camera");
                AppLaunchHelper.openCamera(this);
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // --- GestureListener implementation ---

    @Override
    public void onTap() {
        if (setupBanner.getVisibility() == View.VISIBLE) {
            openAccessibilitySettings();
            return;
        }
        int action = gestureActionMap.getAction(GestureActionMap.GESTURE_TAP);
        performAction(action);
    }

    @Override
    public void onSwipeRight() {
        int action = gestureActionMap.getAction(GestureActionMap.GESTURE_SWIPE_RIGHT);
        performAction(action);
    }

    @Override
    public void onSwipeLeft() {
        int action = gestureActionMap.getAction(GestureActionMap.GESTURE_SWIPE_LEFT);
        performAction(action);
    }

    @Override
    public void onSwipeDown() {
        int action = gestureActionMap.getAction(GestureActionMap.GESTURE_SWIPE_DOWN);
        performAction(action);
    }

    @Override
    public void onTwoFingerTap() {
        int action = gestureActionMap.getAction(GestureActionMap.GESTURE_TWO_FINGER_TAP);
        performAction(action);
    }

    @Override
    public void onLongPress() {
        int action = gestureActionMap.getAction(GestureActionMap.GESTURE_LONG_PRESS);
        performAction(action);
    }

    private void performAction(int action) {
        switch (action) {
            case GestureActionMap.ACTION_LAUNCH_APP:
                launchSelected();
                break;
            case GestureActionMap.ACTION_NAVIGATE_NEXT:
                navigateNext();
                break;
            case GestureActionMap.ACTION_NAVIGATE_PREV:
                navigatePrev();
                break;
            case GestureActionMap.ACTION_OPEN_SETTINGS:
                AppLaunchHelper.openSettings(this);
                break;
            case GestureActionMap.ACTION_GO_HOME:
                // Already home, no-op
                break;
            case GestureActionMap.ACTION_APP_INFO:
                showAppInfo();
                break;
        }
    }

    private void navigateNext() {
        if (apps.isEmpty()) return;
        selectedIndex = (selectedIndex + 1) % apps.size();
        highlightSelected();
    }

    private void navigatePrev() {
        if (apps.isEmpty()) return;
        selectedIndex = (selectedIndex - 1 + apps.size()) % apps.size();
        highlightSelected();
    }

    private void launchSelected() {
        if (apps.isEmpty() || selectedIndex >= apps.size()) return;
        AppInfo app = apps.get(selectedIndex);
        Log.d(TAG, "Launching: " + app.getLabel());
        AppLaunchHelper.launchApp(this, app.getPackageName(), app.getActivityName());
    }

    private boolean isAccessibilityServiceEnabled() {
        String enabledServices = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabledServices)) return false;
        return enabledServices.contains(ACCESSIBILITY_SERVICE_ID);
    }

    private void openAccessibilitySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open accessibility settings", e);
        }
    }

    private void showAppInfo() {
        if (apps.isEmpty() || selectedIndex >= apps.size()) return;
        AppInfo app = apps.get(selectedIndex);
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(android.net.Uri.parse("package:" + app.getPackageName()));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to show app info for " + app.getPackageName(), e);
        }
    }
}
