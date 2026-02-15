package com.glassbikehud;

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

/**
 * BLE manager for bike HUD — connects to the watch GATT server.
 *
 * Adapted from vesc-glass BleManager. Key difference: receive-only
 * (no TX/polling). The watch pushes all data via BLE notifications.
 *
 * Trusted device flow:
 *   1. Scan finds devices advertising the bike HUD service
 *   2. If any are trusted (MAC in SharedPreferences), connect immediately
 *   3. If none trusted, notify listener with found devices for user to pick
 *   4. Once connected, MAC is auto-trusted
 *   5. Trusted list persists across app restarts
 */
@SuppressWarnings("deprecation")
public class BleManager {

    private static final String TAG = "BikeHudBLE";
    private static final String PREFS_NAME = "bike_ble";
    private static final String KEY_TRUSTED = "trusted_macs";

    // Custom Bike HUD GATT service + characteristics
    private static final UUID BIKE_SERVICE  = UUID.fromString("0000ff10-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_HR       = UUID.fromString("0000ff11-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_LOCATION = UUID.fromString("0000ff12-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_TRIP     = UUID.fromString("0000ff13-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD          = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final long SCAN_COLLECT_MS = 5000;

    public interface Listener {
        void onStatusChanged(String status);
        void onDataReceived(BikeData data);
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

    // Current bike data — updated incrementally as characteristics notify
    private final BikeData currentData = new BikeData();

    // Queue for CCCD descriptor writes (BLE only allows one at a time)
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

    // ---- Trusted device management ----

    private Set<String> getTrustedMacs() {
        return new HashSet<>(prefs.getStringSet(KEY_TRUSTED, new HashSet<String>()));
    }

    public void trustDevice(String mac) {
        Set<String> trusted = getTrustedMacs();
        trusted.add(mac);
        prefs.edit().putStringSet(KEY_TRUSTED, trusted).apply();
        Log.i(TAG, "Trusted device: " + mac + " (total: " + trusted.size() + ")");
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

    // ---- Lifecycle ----

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

    // ---- BLE Scanning ----

    private void startScan() {
        if (scanning) return;
        scanning = true;
        foundDevices.clear();

        Set<String> trusted = getTrustedMacs();
        if (trusted.isEmpty()) {
            notifyStatus("SCANNING (NEW)...");
        } else {
            notifyStatus("SCANNING...");
        }
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
            // Accept any device — we'll verify service on connect.
            // Filter by name containing "Bike" or by trusted MAC.
            String name = device.getName();
            String mac = device.getAddress();

            // Accept trusted devices regardless of name
            if (isTrusted(mac)) {
                Log.i(TAG, "Trusted device found, connecting immediately: " + name + " [" + mac + "]");
                stopScan();
                notifyStatus(name != null ? name : mac);
                connectDevice(device);
                return;
            }

            // For new devices, check if the scan record advertises our service UUID
            // or if the name suggests a bike HUD watch
            if (!hasServiceUuid(scanRecord, BIKE_SERVICE)) {
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

    /** Parse 128-bit service UUIDs from BLE scan record (AD type 0x06 or 0x07). */
    private static boolean hasServiceUuid(byte[] scanRecord, UUID target) {
        if (scanRecord == null) return false;
        int i = 0;
        while (i < scanRecord.length - 1) {
            int len = scanRecord[i] & 0xFF;
            if (len == 0) break;
            if (i + len >= scanRecord.length) break;
            int type = scanRecord[i + 1] & 0xFF;
            // 0x06 = Incomplete 128-bit UUIDs, 0x07 = Complete 128-bit UUIDs
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

            // Check for trusted devices
            FoundDevice bestTrusted = null;
            for (FoundDevice fd : foundDevices) {
                if (isTrusted(fd.mac)) {
                    if (bestTrusted == null || fd.rssi > bestTrusted.rssi) {
                        bestTrusted = fd;
                    }
                }
            }

            if (bestTrusted != null) {
                Log.i(TAG, "Connecting to trusted: " + bestTrusted.name);
                notifyStatus(bestTrusted.name);
                connectDevice(bestTrusted.device);
                return;
            }

            // Single device — auto-connect + trust
            if (foundDevices.size() == 1) {
                FoundDevice fd = foundDevices.get(0);
                Log.i(TAG, "Single watch found, auto-trusting: " + fd.name);
                trustDevice(fd.mac);
                notifyStatus(fd.name);
                connectDevice(fd.device);
                return;
            }

            // Multiple — let user pick
            Log.i(TAG, "Multiple watches found (" + foundDevices.size() + "), showing picker");
            notifyStatus("SELECT WATCH");
            if (listener != null) {
                listener.onDevicesFound(new ArrayList<>(foundDevices));
            }
        }
    };

    // ---- BLE Connection ----

    private void connectDevice(BluetoothDevice device) {
        connectedMac = device.getAddress();
        boolean auto = isTrusted(connectedMac);
        gatt = device.connectGatt(context, auto, gattCallback);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT, discovering services...");
                handler.post(new Runnable() {
                    @Override
                    public void run() { notifyStatus("DISCOVERING..."); }
                });
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "Disconnected from GATT (status=" + status + ")");
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

            BluetoothGattService service = g.getService(BIKE_SERVICE);
            if (service == null) {
                Log.e(TAG, "Bike HUD service not found on device");
                handler.post(new Runnable() {
                    @Override
                    public void run() { notifyStatus("NOT A BIKE HUD"); }
                });
                return;
            }

            // Subscribe to all three characteristics via queued descriptor writes
            descriptorWriteQueue.clear();
            writingDescriptor = false;

            subscribeCharacteristic(g, service, CHAR_HR);
            subscribeCharacteristic(g, service, CHAR_LOCATION);
            subscribeCharacteristic(g, service, CHAR_TRIP);

            writeNextDescriptor(g);

            connected = true;
            if (connectedMac != null) {
                trustDevice(connectedMac);
            }
            Log.i(TAG, "Bike HUD BLE ready");
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
            UUID uuid = c.getUuid();
            byte[] value = c.getValue();
            if (value == null) return;

            if (CHAR_HR.equals(uuid) && value.length >= 1) {
                currentData.heartRate = value[0] & 0xFF;
            } else if (CHAR_LOCATION.equals(uuid) && value.length >= 24) {
                ByteBuffer bb = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
                currentData.lat = bb.getDouble();
                currentData.lon = bb.getDouble();
                currentData.speedMps = bb.getFloat();
                currentData.bearing = bb.getFloat();
            } else if (CHAR_TRIP.equals(uuid) && value.length >= 8) {
                ByteBuffer bb = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
                currentData.distanceM = bb.getFloat();
                currentData.elapsedSec = bb.getInt();
            }

            if (listener != null) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onDataReceived(currentData);
                    }
                });
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
