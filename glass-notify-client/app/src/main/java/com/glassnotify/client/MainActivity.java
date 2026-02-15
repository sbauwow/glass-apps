package com.glassnotify.client;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String PREFS = "glass_notify_prefs";
    private static final String KEY_HOST = "glass_host";
    private static final int MAX_LOG_LINES = 50;

    private EditText editHost;
    private Button btnToggle;
    private TextView textStatus;
    private LinearLayout logList;
    private ScrollView logScroll;

    private boolean serviceRunning = false;
    private int logCount = 0;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra(NotificationForwardService.EXTRA_STATUS);
            if (status != null) {
                textStatus.setText(status);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        editHost = (EditText) findViewById(R.id.edit_host);
        btnToggle = (Button) findViewById(R.id.btn_toggle);
        textStatus = (TextView) findViewById(R.id.text_status);
        logList = (LinearLayout) findViewById(R.id.log_list);
        logScroll = (ScrollView) findViewById(R.id.log_scroll);

        // Restore saved host
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String savedHost = prefs.getString(KEY_HOST, "");
        editHost.setText(savedHost);

        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (serviceRunning) {
                    stopForwarding();
                } else {
                    startForwarding();
                }
            }
        });

        // Request permissions
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, 2);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(NotificationForwardService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
        checkNotificationAccess();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(statusReceiver);
    }

    private void startForwarding() {
        if (!isNotificationAccessGranted()) {
            textStatus.setText("Grant notification access first!");
            openNotificationAccessSettings();
            return;
        }

        String host = editHost.getText().toString().trim();
        if (host.isEmpty()) {
            textStatus.setText("Enter Glass IP address");
            return;
        }

        // Save host
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_HOST, host).apply();

        Intent intent = new Intent(this, NotificationForwardService.class);
        intent.putExtra(NotificationForwardService.EXTRA_HOST, host);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        serviceRunning = true;
        btnToggle.setText("Stop");
        editHost.setEnabled(false);
        textStatus.setText("Starting...");
        addLog("Service started, connecting to " + host);
    }

    private void stopForwarding() {
        stopService(new Intent(this, NotificationForwardService.class));
        serviceRunning = false;
        btnToggle.setText("Start");
        editHost.setEnabled(true);
        textStatus.setText("Stopped");
        addLog("Service stopped");
    }

    private void checkNotificationAccess() {
        if (!isNotificationAccessGranted()) {
            textStatus.setText("Notification access required - tap Start to grant");
        }
    }

    private boolean isNotificationAccessGranted() {
        String listeners = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        if (TextUtils.isEmpty(listeners)) return false;
        ComponentName cn = new ComponentName(this, NotificationCaptureService.class);
        return listeners.contains(cn.flattenToString());
    }

    private void openNotificationAccessSettings() {
        startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
    }

    private void addLog(String message) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.US);
        String time = sdf.format(new Date());

        TextView tv = new TextView(this);
        tv.setText(time + "  " + message);
        tv.setTextSize(12);
        tv.setPadding(0, 2, 0, 2);
        logList.addView(tv);
        logCount++;

        // Trim old entries
        while (logCount > MAX_LOG_LINES) {
            logList.removeViewAt(0);
            logCount--;
        }

        logScroll.post(new Runnable() {
            @Override
            public void run() {
                logScroll.fullScroll(ScrollView.FOCUS_DOWN);
            }
        });
    }
}
