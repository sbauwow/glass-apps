package com.glasswatchinput;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Setup-only activity for initial watch pairing.
 *
 * WARNING: This activity creates its own BLE connection which conflicts
 * with the InputBridgeService's connection. Close this activity for
 * normal operation — the service handles everything in the background.
 *
 * Only open this activity when you need to pair a new watch or debug
 * the BLE connection.
 */
public class SetupActivity extends Activity implements BleManager.Listener {

    private TextView statusText;
    private TextView lastInputText;
    private TextView hintText;
    private LinearLayout deviceList;
    private BleManager bleManager;
    private boolean bleStarted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

        statusText = findViewById(R.id.status_text);
        lastInputText = findViewById(R.id.last_input_text);
        hintText = findViewById(R.id.hint_text);
        deviceList = findViewById(R.id.device_list);
        Button scanBtn = findViewById(R.id.scan_btn);

        bleManager = new BleManager(this);
        bleManager.setListener(this);

        scanBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                bleStarted = true;
                hintText.setVisibility(View.GONE);
                bleManager.clearTrustedDevices();
                deviceList.removeAllViews();
                bleManager.stop();
                bleManager.start();
            }
        });

        // Don't auto-start BLE — let the InputBridgeService handle the connection.
        // Only scan when the user presses the SCAN button for initial pairing.
        statusText.setText("IDLE");
        if (hintText != null) {
            hintText.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onDestroy() {
        if (bleStarted) {
            bleManager.stop();
        }
        super.onDestroy();
    }

    @Override
    public void onStatusChanged(String status) {
        statusText.setText(status);
    }

    @Override
    public void onInputReceived(byte type, byte value, byte action) {
        String label;
        if (type == BleManager.TYPE_GESTURE) {
            switch (value) {
                case BleManager.GESTURE_TAP:        label = "TAP"; break;
                case BleManager.GESTURE_LONG_PRESS: label = "LONG PRESS"; break;
                case BleManager.GESTURE_SWIPE_LEFT:  label = "SWIPE LEFT"; break;
                case BleManager.GESTURE_SWIPE_RIGHT: label = "SWIPE RIGHT"; break;
                case BleManager.GESTURE_SWIPE_UP:    label = "SWIPE UP"; break;
                case BleManager.GESTURE_SWIPE_DOWN:  label = "SWIPE DOWN"; break;
                default: label = "GESTURE " + value; break;
            }
        } else if (type == BleManager.TYPE_ROTARY) {
            label = value == BleManager.ROTARY_CW ? "ROTARY CW" : "ROTARY CCW";
        } else {
            label = "KEY " + (value & 0xFF) + (action == 0 ? " DOWN" : " UP");
        }
        lastInputText.setText("Last: " + label);
    }

    @Override
    public void onDevicesFound(List<BleManager.FoundDevice> devices) {
        deviceList.removeAllViews();
        for (final BleManager.FoundDevice fd : devices) {
            Button btn = new Button(this);
            btn.setText(fd.name + " [" + fd.mac + "] " + fd.rssi + "dBm");
            btn.setTextSize(12);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    bleManager.connectToDevice(fd);
                }
            });
            deviceList.addView(btn);
        }
    }
}
