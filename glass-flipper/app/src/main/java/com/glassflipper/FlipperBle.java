package com.glassflipper;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
 * BLE manager for Flipper Zero screen streaming.
 *
 * Trusted device flow (same as vesc-glass BleManager):
 *   1. Scan finds Flipper devices in range
 *   2. If any are trusted (MAC in SharedPreferences), connect to strongest signal
 *   3. If none trusted and only one found, auto-trust and connect
 *   4. If multiple untrusted found, notify listener to show picker
 *
 * RPC state machine: IDLE → STARTING_RPC → RPC_READY → STREAMING
 */
@SuppressWarnings("deprecation")
public class FlipperBle {

    private static final String TAG = "FlipperBLE";
    private static final String PREFS_NAME = "flipper_ble";
    private static final String KEY_TRUSTED = "trusted_macs";

    // Flipper Zero BLE Serial Service UUIDs
    private static final UUID SERIAL_SERVICE = UUID.fromString("8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000");
    private static final UUID CHAR_RX        = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e61fe0000");
    private static final UUID CHAR_TX        = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e62fe0000");
    private static final UUID CCCD           = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int BLE_CHUNK_SIZE = 20;
    private static final long SCAN_COLLECT_MS = 5000;

    private static final int STATE_IDLE = 0;
    private static final int STATE_STARTING_RPC = 1;
    private static final int STATE_RPC_READY = 2;
    private static final int STATE_STREAMING = 3;

    public interface Listener {
        void onStatusChanged(String status);
        void onFrameReceived(byte[] xbmData);
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
    private BluetoothGattCharacteristic rxChar; // Glass writes to this (Flipper RX)
    private BluetoothGattCharacteristic txChar; // Flipper notifies on this (Flipper TX)
    private String connectedMac;

    private boolean scanning = false;
    private boolean connected = false;
    private int rpcState = STATE_IDLE;
    private int scanAttempts = 0;

    private final List<FoundDevice> foundDevices = new ArrayList<>();
    private final List<FoundDevice> allDevices = new ArrayList<>(); // fallback: all named BLE devices

    // Receive buffer for protobuf message reassembly
    // Screen frames are ~1030 bytes, add margin for multiple buffered messages
    private byte[] rxBuffer = new byte[4096];
    private int rxLen = 0;

    // RPC session init: buffer for detecting newline response
    private boolean waitingForRpcReady = false;

    // Fallback: start RPC if onDescriptorWrite never fires
    private final Runnable cccdTimeout = new Runnable() {
        @Override
        public void run() {
            Log.w(TAG, "CCCD write timeout — starting RPC session anyway");
            if (connected && rpcState == STATE_IDLE) {
                startRpcSession();
            }
        }
    };

    public FlipperBle(Context context) {
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
        Log.i(TAG, "Trusted device: " + mac);
    }

    public void untrustDevice(String mac) {
        Set<String> trusted = getTrustedMacs();
        trusted.remove(mac);
        prefs.edit().putStringSet(KEY_TRUSTED, trusted).apply();
        Log.i(TAG, "Untrusted device: " + mac);
    }

    public boolean isTrusted(String mac) {
        return getTrustedMacs().contains(mac);
    }

    public void connectToDevice(FoundDevice fd) {
        stopScan();
        trustDevice(fd.mac);
        notifyStatus("Connecting to " + fd.name + "...");
        connectDevice(fd.device);
    }

    // ---- Lifecycle ----

    public void start() {
        if (btAdapter == null) {
            notifyStatus("No BT hardware");
            return;
        }
        scanAttempts = 0;
        allDevices.clear();
        if (!btAdapter.isEnabled()) {
            Log.i(TAG, "Bluetooth disabled, enabling...");
            notifyStatus("Enabling BT...");
            btAdapter.enable();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (btAdapter.isEnabled()) {
                        startScan();
                    } else {
                        notifyStatus("BT enable failed");
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
        handler.removeCallbacks(cccdTimeout);
        unregisterBondReceiver();
        stopScan();
        if (connected && rpcState == STATE_STREAMING) {
            sendStopStream();
        }
        if (gatt != null) {
            gatt.close();
            gatt = null;
        }
        connected = false;
        connectedMac = null;
        rpcState = STATE_IDLE;
        rxLen = 0;
        waitingForRpcReady = false;
    }

    // ---- BLE Scanning ----

    private void startScan() {
        if (scanning) return;
        scanning = true;
        foundDevices.clear();

        Set<String> trusted = getTrustedMacs();
        if (trusted.isEmpty()) {
            notifyStatus("Scanning (new)...");
        } else {
            notifyStatus("Scanning...");
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
            String name = device.getName();
            String mac = device.getAddress();

            // Always collect named devices for fallback picker
            if (name != null && name.length() > 0) {
                addToList(allDevices, device, name, rssi);
            }

            // Accept if: trusted MAC, or Flipper service UUID in scan record
            boolean isTrustedMac = isTrusted(mac);
            boolean hasService = hasFlipperService(scanRecord);
            if (!isTrustedMac && !hasService) return;

            if (name == null) name = "Flipper";
            Log.i(TAG, "Found Flipper: " + name + " [" + mac + "] rssi=" + rssi);

            addToList(foundDevices, device, name, rssi);

            // If trusted, connect immediately
            if (isTrustedMac) {
                Log.i(TAG, "Trusted device found, connecting: " + name);
                stopScan();
                notifyStatus("Connecting to " + name + "...");
                connectDevice(device);
            }
        }
    };

    private static void addToList(List<FoundDevice> list, BluetoothDevice device, String name, int rssi) {
        String mac = device.getAddress();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).mac.equals(mac)) {
                if (rssi > list.get(i).rssi) {
                    list.set(i, new FoundDevice(device, name, rssi));
                }
                return;
            }
        }
        list.add(new FoundDevice(device, name, rssi));
    }

    private final Runnable scanEvaluator = new Runnable() {
        @Override
        public void run() {
            if (!scanning || connected) return;
            stopScan();
            scanAttempts++;

            if (foundDevices.isEmpty()) {
                // After 2 failed scan attempts, show all named devices as fallback picker
                if (scanAttempts >= 2 && !allDevices.isEmpty()) {
                    Log.i(TAG, "No Flipper identified, showing all " + allDevices.size() + " devices");
                    notifyStatus("Select your Flipper");
                    if (listener != null) {
                        listener.onDevicesFound(new ArrayList<>(allDevices));
                    }
                    return;
                }
                notifyStatus("Scanning... (" + scanAttempts + ")");
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() { startScan(); }
                }, 3000);
                return;
            }

            // Check for trusted device with strongest signal
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
                notifyStatus("Connecting to " + bestTrusted.name + "...");
                connectDevice(bestTrusted.device);
                return;
            }

            // Single device → auto-trust and connect
            if (foundDevices.size() == 1) {
                FoundDevice fd = foundDevices.get(0);
                Log.i(TAG, "Single Flipper found, auto-trusting: " + fd.name);
                trustDevice(fd.mac);
                notifyStatus("Connecting to " + fd.name + "...");
                connectDevice(fd.device);
                return;
            }

            // Multiple untrusted → show picker
            Log.i(TAG, "Multiple Flippers found (" + foundDevices.size() + "), showing picker");
            notifyStatus("Select Flipper");
            if (listener != null) {
                listener.onDevicesFound(new ArrayList<>(foundDevices));
            }
        }
    };

    // ---- BLE Connection ----

    private void connectDevice(BluetoothDevice device) {
        // Guard against double-connect (scan callback can fire multiple times)
        if (gatt != null) {
            Log.w(TAG, "Already connecting, ignoring duplicate");
            return;
        }
        connectedMac = device.getAddress();
        boolean auto = isTrusted(connectedMac);
        gatt = device.connectGatt(context, auto, gattCallback);
    }

    // BroadcastReceiver for bonding state changes and pairing requests
    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device == null || !device.getAddress().equals(connectedMac)) return;

            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                Log.i(TAG, "Bond state changed: " + device.getAddress() + " state=" + state);

                if (state == BluetoothDevice.BOND_BONDED) {
                    Log.i(TAG, "Bonding complete — discovering services");
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            notifyStatus("Discovering services...");
                            if (gatt != null) gatt.discoverServices();
                        }
                    });
                }
            } else if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(action)) {
                int variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
                Log.i(TAG, "Pairing request variant=" + variant);

                // Auto-confirm pairing for our selected Flipper device
                // Variant 0 = PIN, 2 = PASSKEY_CONFIRMATION, 3 = CONSENT
                try {
                    if (variant == 0) {
                        // PIN — Flipper may use default or no PIN
                        device.setPin(new byte[]{0, 0, 0, 0, 0, 0});
                    }
                    device.setPairingConfirmation(true);
                    Log.i(TAG, "Auto-confirmed pairing");
                    abortBroadcast();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to auto-confirm pairing", e);
                }

                handler.post(new Runnable() {
                    @Override
                    public void run() { notifyStatus("Pairing (confirm on Flipper)..."); }
                });
            }
        }
    };
    private boolean bondReceiverRegistered = false;

    private void registerBondReceiver() {
        if (!bondReceiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
            filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
            context.registerReceiver(bondReceiver, filter);
            bondReceiverRegistered = true;
        }
    }

    private void unregisterBondReceiver() {
        if (bondReceiverRegistered) {
            try { context.unregisterReceiver(bondReceiver); } catch (Exception e) { /* ignore */ }
            bondReceiverRegistered = false;
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                BluetoothDevice device = g.getDevice();
                int bondState = device.getBondState();
                Log.i(TAG, "Connected to GATT, bondState=" + bondState);

                if (bondState == BluetoothDevice.BOND_BONDED) {
                    // Already bonded — discover services immediately
                    Log.i(TAG, "Already bonded, discovering services...");
                    handler.post(new Runnable() {
                        @Override
                        public void run() { notifyStatus("Discovering services..."); }
                    });
                    g.discoverServices();
                } else {
                    // Need to bond first
                    Log.i(TAG, "Not bonded — initiating bonding...");
                    handler.post(new Runnable() {
                        @Override
                        public void run() { notifyStatus("Pairing..."); }
                    });
                    registerBondReceiver();
                    device.createBond();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "Disconnected from GATT (status=" + status + ")");
                connected = false;
                rpcState = STATE_IDLE;
                rxLen = 0;
                waitingForRpcReady = false;
                txChar = null;
                rxChar = null;
                g.close();
                gatt = null;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        notifyStatus("Disconnected");
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

            BluetoothGattService serial = g.getService(SERIAL_SERVICE);
            if (serial == null) {
                Log.e(TAG, "Flipper serial service not found — untrusting " + connectedMac);
                // Not a Flipper — remove from trusted and rescan
                if (connectedMac != null) untrustDevice(connectedMac);
                g.close();
                gatt = null;
                connected = false;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        notifyStatus("Not a Flipper, rescanning...");
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() { startScan(); }
                        }, 1000);
                    }
                });
                return;
            }

            rxChar = serial.getCharacteristic(CHAR_RX);
            txChar = serial.getCharacteristic(CHAR_TX);

            if (rxChar == null || txChar == null) {
                Log.e(TAG, "Serial characteristics not found");
                g.close();
                gatt = null;
                connected = false;
                handler.post(new Runnable() {
                    @Override
                    public void run() { startScan(); }
                });
                return;
            }

            // Enable notifications on TX (Flipper → Glass)
            g.setCharacteristicNotification(txChar, true);
            BluetoothGattDescriptor desc = txChar.getDescriptor(CCCD);
            if (desc != null) {
                desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(desc);
            }

            connected = true;
            if (connectedMac != null) {
                trustDevice(connectedMac);
            }

            Log.i(TAG, "Flipper BLE ready — waiting for CCCD write to complete");
            handler.post(new Runnable() {
                @Override
                public void run() { notifyStatus("Starting RPC..."); }
            });
            // RPC session starts in onDescriptorWrite, with timeout fallback
            handler.postDelayed(cccdTimeout, 2000);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            Log.i(TAG, "Descriptor write status=" + status + " — starting RPC session");
            handler.removeCallbacks(cccdTimeout);
            if (connected && rpcState == STATE_IDLE) {
                handler.post(new Runnable() {
                    @Override
                    public void run() { startRpcSession(); }
                });
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            // Chunk write complete — send next chunk if queued
            synchronized (writeQueue) {
                writePending = false;
                if (!writeQueue.isEmpty()) {
                    byte[] next = writeQueue.remove(0);
                    doWriteChunk(next);
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (CHAR_TX.equals(c.getUuid())) {
                byte[] chunk = c.getValue();
                if (chunk == null || chunk.length == 0) return;
                onBleDataReceived(chunk);
            }
        }
    };

    // ---- Write queue (BLE writes must be serialized) ----

    private final List<byte[]> writeQueue = new ArrayList<>();
    private boolean writePending = false;

    private void bleWrite(byte[] data) {
        // Split into BLE_CHUNK_SIZE chunks and queue
        for (int i = 0; i < data.length; i += BLE_CHUNK_SIZE) {
            int end = Math.min(i + BLE_CHUNK_SIZE, data.length);
            byte[] chunk = Arrays.copyOfRange(data, i, end);
            synchronized (writeQueue) {
                if (!writePending) {
                    doWriteChunk(chunk);
                } else {
                    writeQueue.add(chunk);
                }
            }
        }
    }

    private void doWriteChunk(byte[] chunk) {
        if (gatt == null || rxChar == null) return;
        writePending = true;
        rxChar.setValue(chunk);
        gatt.writeCharacteristic(rxChar);
    }

    // ---- RPC Protocol ----

    private void startRpcSession() {
        rpcState = STATE_STARTING_RPC;
        waitingForRpcReady = true;
        rxLen = 0;
        // Send "start_rpc_session\r" to initiate Flipper RPC mode
        byte[] cmd = "start_rpc_session\r".getBytes();
        bleWrite(cmd);
    }

    private void sendStartStream() {
        rpcState = STATE_STREAMING;
        rxLen = 0; // clear buffer for protobuf messages
        byte[] msg = FlipperProto.encodeStartScreenStream();
        bleWrite(msg);
        Log.i(TAG, "Sent start_screen_stream request");
        handler.post(new Runnable() {
            @Override
            public void run() { notifyStatus("Streaming"); }
        });
    }

    private void sendStopStream() {
        byte[] msg = FlipperProto.encodeStopScreenStream();
        bleWrite(msg);
        Log.i(TAG, "Sent stop_screen_stream request");
    }

    // ---- Receive handling ----

    private void onBleDataReceived(byte[] chunk) {
        // During RPC init, look for newline response
        if (waitingForRpcReady) {
            // Buffer the chunk
            if (rxLen + chunk.length > rxBuffer.length) {
                rxLen = 0;
            }
            System.arraycopy(chunk, 0, rxBuffer, rxLen, chunk.length);
            rxLen += chunk.length;

            // Check for newline in buffer — indicates RPC mode is active
            for (int i = 0; i < rxLen; i++) {
                if (rxBuffer[i] == '\n') {
                    Log.i(TAG, "RPC session started");
                    waitingForRpcReady = false;
                    rpcState = STATE_RPC_READY;
                    rxLen = 0;
                    // Now start the screen stream
                    handler.post(new Runnable() {
                        @Override
                        public void run() { sendStartStream(); }
                    });
                    return;
                }
            }
            return;
        }

        // Streaming mode: reassemble protobuf messages
        if (rpcState != STATE_STREAMING) return;

        if (rxLen + chunk.length > rxBuffer.length) {
            // Buffer overflow — reset
            Log.w(TAG, "RX buffer overflow, resetting");
            rxLen = 0;
        }
        System.arraycopy(chunk, 0, rxBuffer, rxLen, chunk.length);
        rxLen += chunk.length;

        // Try to decode complete messages
        processRxBuffer();
    }

    private void processRxBuffer() {
        while (rxLen > 0) {
            // Decode varint length prefix
            long[] varint = FlipperProto.decodeVarint(rxBuffer, 0, rxLen);
            if (varint == null) return; // need more data

            int prefixLen = (int) varint[1];
            int msgLen = (int) varint[0];
            int totalLen = prefixLen + msgLen;

            if (totalLen > rxLen) return; // need more data

            // Extract complete message (without length prefix)
            byte[] xbm = FlipperProto.decodeMainResponse(rxBuffer, prefixLen, msgLen);

            // Consume this message from the buffer
            int remaining = rxLen - totalLen;
            if (remaining > 0) {
                System.arraycopy(rxBuffer, totalLen, rxBuffer, 0, remaining);
            }
            rxLen = remaining;

            // Deliver frame if decoded
            if (xbm != null && xbm.length == FlipperProto.XBM_FRAME_SIZE) {
                final byte[] frame = xbm;
                if (listener != null) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() { listener.onFrameReceived(frame); }
                    });
                }
            }
        }
    }

    private void notifyStatus(final String status) {
        if (listener != null) {
            listener.onStatusChanged(status);
        }
    }

    // ---- Scan record parsing ----

    /**
     * Check if a BLE scan record contains the Flipper serial service UUID.
     * Parses AD structures looking for 128-bit service UUIDs (types 0x06/0x07).
     * UUID bytes are little-endian in the scan record.
     */
    private static boolean hasFlipperService(byte[] scanRecord) {
        if (scanRecord == null) return false;
        // Flipper serial service UUID: 8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000
        // As little-endian bytes:
        // 00 00 fe 60 cc 7a 48 2a  98 4a 7f 2e d5 b3 e5 8f
        final byte[] target = {
            0x00, 0x00, (byte)0xfe, 0x60, (byte)0xcc, 0x7a, 0x48, 0x2a,
            (byte)0x98, 0x4a, 0x7f, 0x2e, (byte)0xd5, (byte)0xb3, (byte)0xe5, (byte)0x8f
        };

        int i = 0;
        while (i < scanRecord.length - 1) {
            int len = scanRecord[i] & 0xFF;
            if (len == 0) break;
            if (i + len >= scanRecord.length) break;

            int type = scanRecord[i + 1] & 0xFF;
            // 0x06 = Incomplete 128-bit UUIDs, 0x07 = Complete 128-bit UUIDs
            if (type == 0x06 || type == 0x07) {
                // Data starts at i+2, each UUID is 16 bytes
                for (int j = i + 2; j + 16 <= i + 1 + len; j += 16) {
                    boolean match = true;
                    for (int k = 0; k < 16; k++) {
                        if (scanRecord[j + k] != target[k]) {
                            match = false;
                            break;
                        }
                    }
                    if (match) return true;
                }
            }
            i += len + 1;
        }
        return false;
    }
}
