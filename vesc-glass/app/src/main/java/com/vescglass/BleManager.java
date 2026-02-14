package com.vescglass;

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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * BLE manager for VESC NRF51822 dongle communication.
 *
 * Trusted device flow:
 *   1. Scan finds VESC dongles in range
 *   2. If any are trusted (MAC in SharedPreferences), connect to strongest signal
 *   3. If none trusted, notify listener with list of found devices for user to pick
 *   4. Once connected + confirmed, MAC is auto-trusted
 *   5. Trusted list persists across app restarts (max 10 devices)
 */
@SuppressWarnings("deprecation")
public class BleManager {

    private static final String TAG = "VescBLE";
    private static final String PREFS_NAME = "vesc_ble";
    private static final String KEY_TRUSTED = "trusted_macs";

    // Nordic UART Service UUIDs (used by VESC NRF dongle)
    private static final UUID NUS_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_TX      = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_RX      = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CCCD        = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int BLE_CHUNK_SIZE = 20;
    private static final long POLL_INTERVAL_MS = 200;
    private static final long SCAN_COLLECT_MS = 5000; // collect devices for 5s before deciding

    public interface Listener {
        void onStatusChanged(String status);
        void onDataReceived(VescData data);
        /** Called when untrusted VESC devices found — show picker to user. */
        void onDevicesFound(List<FoundDevice> devices);
    }

    /** A VESC device found during scanning. */
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
    private BluetoothGattCharacteristic txChar;
    private BluetoothGattCharacteristic rxChar;
    private String connectedMac;

    private boolean scanning = false;
    private boolean connected = false;

    // Devices found during current scan window
    private final List<FoundDevice> foundDevices = new ArrayList<>();

    // Receive buffer for packet reassembly
    private byte[] rxBuffer = new byte[1024];
    private int rxLen = 0;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (connected && txChar != null) {
                sendGetValues();
            }
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

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

    public int trustedCount() {
        return getTrustedMacs().size();
    }

    /** Connect to a specific device chosen by the user. Auto-trusts it. */
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
        handler.removeCallbacks(pollRunnable);
        stopScan();
        if (gatt != null) {
            gatt.close();
            gatt = null;
        }
        connected = false;
        connectedMac = null;
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

        // After collection window, evaluate what we found
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
            if (name == null) return;
            if (!(name.contains("VESC") || name.contains("vesc")
                    || name.contains("NRF") || name.contains("nrf"))) return;

            String mac = device.getAddress();
            Log.i(TAG, "Found: " + name + " [" + mac + "] rssi=" + rssi);

            // Update or add to found list (keep strongest RSSI)
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

            // If this is a trusted device, connect immediately (don't wait)
            if (isTrusted(mac)) {
                Log.i(TAG, "Trusted device found, connecting immediately: " + name);
                stopScan();
                notifyStatus(name);
                connectDevice(device);
            }
        }
    };

    /** Called after SCAN_COLLECT_MS — evaluate found devices. */
    private final Runnable scanEvaluator = new Runnable() {
        @Override
        public void run() {
            if (!scanning || connected) return;
            stopScan();

            if (foundDevices.isEmpty()) {
                notifyStatus("NO VESC FOUND");
                // Retry
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() { startScan(); }
                }, 3000);
                return;
            }

            // Check for trusted devices (strongest RSSI first)
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

            // No trusted devices found — if only one VESC, auto-connect + trust
            if (foundDevices.size() == 1) {
                FoundDevice fd = foundDevices.get(0);
                Log.i(TAG, "Single VESC found, auto-trusting: " + fd.name);
                trustDevice(fd.mac);
                notifyStatus(fd.name);
                connectDevice(fd.device);
                return;
            }

            // Multiple untrusted — let user pick
            Log.i(TAG, "Multiple VESCs found (" + foundDevices.size() + "), showing picker");
            notifyStatus("SELECT BOARD");
            if (listener != null) {
                listener.onDevicesFound(new ArrayList<>(foundDevices));
            }
        }
    };

    // ---- BLE Connection ----

    private void connectDevice(BluetoothDevice device) {
        connectedMac = device.getAddress();
        // Use autoConnect for trusted devices — Android BLE will reconnect
        // automatically on brief signal drops without a full rescan
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
                txChar = null;
                rxChar = null;
                // Close stale GATT — required before reconnecting on Android BLE
                g.close();
                gatt = null;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        notifyStatus("DISCONNECTED");
                        handler.removeCallbacks(pollRunnable);
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

            BluetoothGattService nus = g.getService(NUS_SERVICE);
            if (nus == null) {
                Log.e(TAG, "NUS service not found on device");
                handler.post(new Runnable() {
                    @Override
                    public void run() { notifyStatus("NOT A VESC"); }
                });
                return;
            }

            txChar = nus.getCharacteristic(NUS_TX);
            rxChar = nus.getCharacteristic(NUS_RX);

            if (txChar == null || rxChar == null) {
                Log.e(TAG, "NUS characteristics not found");
                return;
            }

            g.setCharacteristicNotification(rxChar, true);
            BluetoothGattDescriptor desc = rxChar.getDescriptor(CCCD);
            if (desc != null) {
                desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(desc);
            }

            connected = true;
            // Auto-trust on successful connection
            if (connectedMac != null) {
                trustDevice(connectedMac);
            }
            Log.i(TAG, "VESC BLE ready — starting telemetry polling");
            handler.post(new Runnable() {
                @Override
                public void run() {
                    notifyStatus("CONNECTED");
                    handler.postDelayed(pollRunnable, 500);
                }
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (NUS_RX.equals(c.getUuid())) {
                byte[] chunk = c.getValue();
                if (chunk == null || chunk.length == 0) return;
                onBleDataReceived(chunk);
            }
        }
    };

    // ---- Send / Receive ----

    private void sendGetValues() {
        if (gatt == null || txChar == null) return;
        byte[] packet = VescProtocol.buildGetValues();
        for (int i = 0; i < packet.length; i += BLE_CHUNK_SIZE) {
            int end = Math.min(i + BLE_CHUNK_SIZE, packet.length);
            byte[] chunk = Arrays.copyOfRange(packet, i, end);
            txChar.setValue(chunk);
            gatt.writeCharacteristic(txChar);
        }
    }

    private void onBleDataReceived(byte[] chunk) {
        if (rxLen + chunk.length > rxBuffer.length) {
            rxLen = 0;
        }
        System.arraycopy(chunk, 0, rxBuffer, rxLen, chunk.length);
        rxLen += chunk.length;

        int[] bounds = VescProtocol.findPacketBounds(rxBuffer, 0, rxLen);
        if (bounds != null) {
            int payloadStart = bounds[0];
            int payloadLen = bounds[1];
            int packetEnd = bounds[2];

            byte[] payload = Arrays.copyOfRange(rxBuffer, payloadStart, payloadStart + payloadLen);

            int remaining = rxLen - packetEnd;
            if (remaining > 0) {
                System.arraycopy(rxBuffer, packetEnd, rxBuffer, 0, remaining);
            }
            rxLen = remaining;

            final VescData data = VescProtocol.parseGetValues(payload);
            if (data != null && listener != null) {
                handler.post(new Runnable() {
                    @Override
                    public void run() { listener.onDataReceived(data); }
                });
            }
        }
    }

    private void notifyStatus(final String status) {
        if (listener != null) {
            listener.onStatusChanged(status);
        }
    }
}
