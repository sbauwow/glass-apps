package com.glasswatchinput;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

@SuppressWarnings("deprecation")
public class BleManager {

    private static final String TAG = "WatchInputBLE";
    private static final String PREFS_NAME = "watch_input_ble";
    private static final String KEY_TRUSTED = "trusted_macs";

    private static final UUID INPUT_SERVICE = UUID.fromString("0000ff20-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_INPUT    = UUID.fromString("0000ff21-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD          = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final long SCAN_COLLECT_MS = 5000;

    public static final byte TYPE_KEY     = 0x01;
    public static final byte TYPE_GESTURE = 0x02;
    public static final byte TYPE_ROTARY  = 0x03;

    public static final byte GESTURE_TAP        = 1;
    public static final byte GESTURE_LONG_PRESS = 2;
    public static final byte GESTURE_SWIPE_LEFT  = 3;
    public static final byte GESTURE_SWIPE_RIGHT = 4;
    public static final byte GESTURE_SWIPE_UP    = 5;
    public static final byte GESTURE_SWIPE_DOWN  = 6;

    public static final byte ROTARY_CW  = 1;
    public static final byte ROTARY_CCW = 2;

    public interface Listener {
        void onStatusChanged(String status);
        void onInputReceived(byte type, byte value, byte action);
        void onDevicesFound(List<FoundDevice> devices);
    }

    public static class FoundDevice {
        public final BluetoothDevice device;
        public final String name;
        public final String mac;
        public final int rssi;

        FoundDevice(BluetoothDevice device, String name, int rssi) {
            this.device = device;
            this.name = name;
            this.mac = device.getAddress();
            this.rssi = rssi;
        }
    }

    private final Context context;
    private final Handler handler;
    private final SharedPreferences prefs;
    private Listener listener;

    private BluetoothAdapter btAdapter;
    private BluetoothGatt gatt;
    private String connectedMac;

    private boolean scanning = false;
    private boolean connected = false;

    private final List<FoundDevice> foundDevices = new ArrayList<>();
    private final Queue<BluetoothGattDescriptor> descriptorWriteQueue = new LinkedList<>();
    private boolean writingDescriptor = false;

    public BleManager(Context context) {
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());
        this.btAdapter = BluetoothAdapter.getDefaultAdapter();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    private Set<String> getTrustedMacs() {
        return new HashSet<>(prefs.getStringSet(KEY_TRUSTED, new HashSet<String>()));
    }

    public void trustDevice(String mac) {
        Set<String> trusted = getTrustedMacs();
        trusted.add(mac);
        prefs.edit().putStringSet(KEY_TRUSTED, trusted).apply();
        Log.i(TAG, "Trusted device: " + mac);
    }

    public void clearTrustedDevices() {
        prefs.edit().remove(KEY_TRUSTED).apply();
        Log.i(TAG, "Cleared all trusted devices");
    }

    public boolean isTrusted(String mac) {
        return getTrustedMacs().contains(mac);
    }

    public void connectToDevice(FoundDevice fd) {
        stopScan();
        trustDevice(fd.mac);
        notifyStatus(fd.name);
        connectDevice(fd.device);
    }

    public boolean isConnected() {
        return connected;
    }

    public void start() {
        if (btAdapter == null) {
            notifyStatus("NO BT HARDWARE");
            return;
        }
        if (!btAdapter.isEnabled()) {
            Log.i(TAG, "Bluetooth disabled, enabling...");
            notifyStatus("ENABLING BT...");
            btAdapter.enable();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (btAdapter.isEnabled()) {
                        startScan();
                    } else {
                        notifyStatus("BT ENABLE FAILED");
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() { start(); }
                        }, 3000);
                    }
                }
            }, 2000);
            return;
        }
        startScan();
    }

    public void stop() {
        stopScan();
        if (gatt != null) {
            gatt.close();
            gatt = null;
        }
        connected = false;
        connectedMac = null;
        descriptorWriteQueue.clear();
        writingDescriptor = false;
    }

    private void startScan() {
        if (scanning) return;
        scanning = true;
        foundDevices.clear();

        Set<String> trusted = getTrustedMacs();
        notifyStatus(trusted.isEmpty() ? "SCANNING (NEW)..." : "SCANNING...");
        Log.i(TAG, "Starting BLE scan, trusted=" + trusted.size());

        btAdapter.startLeScan(scanCallback);
        handler.postDelayed(scanEvaluator, SCAN_COLLECT_MS);
    }

    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        handler.removeCallbacks(scanEvaluator);
        try {
            btAdapter.stopLeScan(scanCallback);
        } catch (Exception e) {
            Log.w(TAG, "stopLeScan error", e);
        }
    }

    private final BluetoothAdapter.LeScanCallback scanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
            String name = device.getName();
            String mac = device.getAddress();

            if (isTrusted(mac)) {
                Log.i(TAG, "Trusted device found, connecting: " + name + " [" + mac + "]");
                stopScan();
                notifyStatus(name != null ? name : mac);
                connectDevice(device);
                return;
            }

            if (!hasServiceUuid(scanRecord, INPUT_SERVICE)) {
                return;
            }

            if (name == null) name = "Watch";
            Log.i(TAG, "Found: " + name + " [" + mac + "] rssi=" + rssi);

            boolean exists = false;
            for (int i = 0; i < foundDevices.size(); i++) {
                if (foundDevices.get(i).mac.equals(mac)) {
                    if (rssi > foundDevices.get(i).rssi) {
                        foundDevices.set(i, new FoundDevice(device, name, rssi));
                    }
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                foundDevices.add(new FoundDevice(device, name, rssi));
            }
        }
    };

    private static final UUID BT_BASE_UUID = UUID.fromString("00000000-0000-1000-8000-00805f9b34fb");

    private static boolean hasServiceUuid(byte[] scanRecord, UUID target) {
        if (scanRecord == null) return false;
        int i = 0;
        while (i < scanRecord.length - 1) {
            int len = scanRecord[i] & 0xFF;
            if (len == 0) break;
            if (i + len >= scanRecord.length) break;
            int type = scanRecord[i + 1] & 0xFF;

            // 16-bit UUIDs (Incomplete=0x02, Complete=0x03)
            if ((type == 0x02 || type == 0x03) && len >= 3) {
                for (int j = i + 2; j + 2 <= i + 1 + len; j += 2) {
                    int uuid16 = (scanRecord[j] & 0xFF) | ((scanRecord[j + 1] & 0xFF) << 8);
                    long msb = BT_BASE_UUID.getMostSignificantBits() | ((long) uuid16 << 32);
                    UUID found = new UUID(msb, BT_BASE_UUID.getLeastSignificantBits());
                    if (found.equals(target)) return true;
                }
            }

            // 128-bit UUIDs (Incomplete=0x06, Complete=0x07)
            if ((type == 0x06 || type == 0x07) && len >= 17) {
                for (int j = i + 2; j + 16 <= i + 1 + len; j += 16) {
                    ByteBuffer bb = ByteBuffer.wrap(scanRecord, j, 16).order(ByteOrder.LITTLE_ENDIAN);
                    long lsb = bb.getLong();
                    long msb = bb.getLong();
                    UUID found = new UUID(msb, lsb);
                    if (found.equals(target)) return true;
                }
            }
            i += len + 1;
        }
        return false;
    }

    private final Runnable scanEvaluator = new Runnable() {
        @Override
        public void run() {
            if (!scanning || connected) return;
            stopScan();

            if (foundDevices.isEmpty()) {
                notifyStatus("NO WATCH FOUND");
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() { startScan(); }
                }, 3000);
                return;
            }

            FoundDevice bestTrusted = null;
            for (FoundDevice fd : foundDevices) {
                if (isTrusted(fd.mac)) {
                    if (bestTrusted == null || fd.rssi > bestTrusted.rssi) {
                        bestTrusted = fd;
                    }
                }
            }

            if (bestTrusted != null) {
                notifyStatus(bestTrusted.name);
                connectDevice(bestTrusted.device);
                return;
            }

            if (foundDevices.size() == 1) {
                FoundDevice fd = foundDevices.get(0);
                trustDevice(fd.mac);
                notifyStatus(fd.name);
                connectDevice(fd.device);
                return;
            }

            notifyStatus("SELECT WATCH");
            if (listener != null) {
                listener.onDevicesFound(new ArrayList<>(foundDevices));
            }
        }
    };

    private void connectDevice(BluetoothDevice device) {
        connectedMac = device.getAddress();
        // Always use autoConnect=false for direct connection — autoConnect=true
        // uses passive scanning which stalls on API 19.
        gatt = device.connectGatt(context, false, gattCallback);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected, discovering services...");
                handler.post(new Runnable() {
                    @Override
                    public void run() { notifyStatus("DISCOVERING..."); }
                });
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "Disconnected (status=" + status + ")");
                connected = false;
                descriptorWriteQueue.clear();
                writingDescriptor = false;
                g.close();
                gatt = null;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        notifyStatus("DISCONNECTED");
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() { startScan(); }
                        }, 2000);
                    }
                });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: " + status);
                return;
            }

            BluetoothGattService service = g.getService(INPUT_SERVICE);
            if (service == null) {
                Log.e(TAG, "Input service not found");
                handler.post(new Runnable() {
                    @Override
                    public void run() { notifyStatus("NOT AN INPUT WATCH"); }
                });
                return;
            }

            descriptorWriteQueue.clear();
            writingDescriptor = false;

            subscribeCharacteristic(g, service, CHAR_INPUT);
            writeNextDescriptor(g);

            connected = true;
            if (connectedMac != null) {
                trustDevice(connectedMac);
            }
            Log.i(TAG, "Watch Input BLE ready");
            handler.post(new Runnable() {
                @Override
                public void run() { notifyStatus("CONNECTED"); }
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            writingDescriptor = false;
            writeNextDescriptor(g);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] value = c.getValue();
            if (value == null || value.length < 3) return;

            if (CHAR_INPUT.equals(c.getUuid())) {
                final byte type = value[0];
                final byte val = value[1];
                final byte action = value[2];

                if (listener != null) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onInputReceived(type, val, action);
                        }
                    });
                }
            }
        }
    };

    private void subscribeCharacteristic(BluetoothGatt g, BluetoothGattService service, UUID charUuid) {
        BluetoothGattCharacteristic c = service.getCharacteristic(charUuid);
        if (c == null) {
            Log.w(TAG, "Characteristic not found: " + charUuid);
            return;
        }
        g.setCharacteristicNotification(c, true);
        BluetoothGattDescriptor desc = c.getDescriptor(CCCD);
        if (desc != null) {
            desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            descriptorWriteQueue.add(desc);
        }
    }

    private void writeNextDescriptor(BluetoothGatt g) {
        if (writingDescriptor) return;
        BluetoothGattDescriptor desc = descriptorWriteQueue.poll();
        if (desc != null) {
            writingDescriptor = true;
            g.writeDescriptor(desc);
        }
    }

    private void notifyStatus(final String status) {
        if (listener != null) {
            listener.onStatusChanged(status);
        }
    }
}
