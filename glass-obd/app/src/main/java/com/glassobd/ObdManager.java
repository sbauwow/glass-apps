package com.glassobd;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Classic Bluetooth manager for ELM327 OBD2 dongles.
 *
 * Trusted device flow (same pattern as vesc-glass BleManager):
 *   1. Check bonded devices for trusted MACs → connect immediately
 *   2. If not bonded/trusted: run discovery → filter OBD devices → auto-connect or show picker
 *   3. Auto-trust on successful ELM327 init
 *   4. Auto-reconnect on disconnect (2s delay → rescan)
 */
@SuppressWarnings("deprecation")
public class ObdManager {

    private static final String TAG = "ObdMgr";
    private static final String PREFS_NAME = "obd_bt";
    private static final String KEY_TRUSTED = "trusted_macs";

    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

    private static final long DISCOVERY_TIMEOUT_MS = 8000;
    private static final long POLL_INTERVAL_MS = 300;
    private static final long RECONNECT_DELAY_MS = 3000;

    public interface Listener {
        void onStatusChanged(String status);
        void onDataReceived(ObdData data);
        void onDevicesFound(List<FoundDevice> devices);
    }

    public static class FoundDevice {
        public final BluetoothDevice device;
        public final String name;
        public final String mac;

        FoundDevice(BluetoothDevice device, String name) {
            this.device = device;
            this.name = name;
            this.mac = device.getAddress();
        }
    }

    private final Context context;
    private final Handler handler;
    private final SharedPreferences prefs;
    private Listener listener;

    private BluetoothAdapter btAdapter;
    private BluetoothSocket socket;
    private ElmProtocol elm;
    private volatile boolean running = false;
    private volatile boolean connected = false;
    private Thread connectThread;
    private Thread pollThread;

    private final List<FoundDevice> foundDevices = new ArrayList<>();
    private boolean receiverRegistered = false;
    private boolean pairingReceiverRegistered = false;

    // Auto-pair receiver — enters PIN 1234 for ELM327 dongles
    private final BroadcastReceiver pairingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(intent.getAction())) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && isObdDevice(device)) {
                    Log.i(TAG, "Auto-pairing with PIN 1234: " + device.getName());
                    device.setPin(new byte[]{'1', '2', '3', '4'});
                    abortBroadcast();
                }
            }
        }
    };

    public ObdManager(Context context) {
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

    public void clearTrustedDevices() {
        prefs.edit().remove(KEY_TRUSTED).apply();
        Log.i(TAG, "Cleared all trusted devices");
    }

    public boolean isTrusted(String mac) {
        return getTrustedMacs().contains(mac);
    }

    public void connectToDevice(FoundDevice fd) {
        cancelDiscovery();
        trustDevice(fd.mac);
        connectInBackground(fd.device, fd.name);
    }

    // ---- Lifecycle ----

    public void start() {
        if (btAdapter == null) {
            notifyStatus("NO BT HARDWARE");
            return;
        }
        running = true;
        registerPairingReceiver();

        if (!btAdapter.isEnabled()) {
            notifyStatus("ENABLING BT...");
            btAdapter.enable();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (btAdapter.isEnabled()) {
                        scanForDevices();
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

        scanForDevices();
    }

    public void stop() {
        running = false;
        cancelDiscovery();
        unregisterDiscoveryReceiver();
        unregisterPairingReceiver();

        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
        if (connectThread != null) {
            connectThread.interrupt();
            connectThread = null;
        }

        closeSocket();
        connected = false;
    }

    // ---- Scanning / Discovery ----

    private void scanForDevices() {
        if (!running) return;
        foundDevices.clear();

        Set<String> trusted = getTrustedMacs();
        if (trusted.isEmpty()) {
            notifyStatus("SCANNING (NEW)...");
        } else {
            notifyStatus("SCANNING...");
        }

        // First check bonded (paired) devices for trusted MACs
        Set<BluetoothDevice> bonded = btAdapter.getBondedDevices();
        if (bonded != null) {
            for (BluetoothDevice bd : bonded) {
                if (trusted.contains(bd.getAddress()) && isObdDevice(bd)) {
                    Log.i(TAG, "Found trusted bonded device: " + bd.getName());
                    notifyStatus(deviceLabel(bd));
                    connectInBackground(bd, deviceLabel(bd));
                    return;
                }
            }

            // Also collect bonded OBD devices as candidates
            for (BluetoothDevice bd : bonded) {
                if (isObdDevice(bd)) {
                    foundDevices.add(new FoundDevice(bd, deviceLabel(bd)));
                }
            }
        }

        // If we found OBD devices in bonded list but none trusted, evaluate them
        if (!foundDevices.isEmpty() && trusted.isEmpty()) {
            evaluateFoundDevices();
            return;
        }

        // Run classic BT discovery for non-bonded devices
        registerDiscoveryReceiver();
        btAdapter.startDiscovery();

        handler.postDelayed(discoveryTimeout, DISCOVERY_TIMEOUT_MS);
    }

    private boolean isObdDevice(BluetoothDevice device) {
        String name = device.getName();
        if (name == null) return false;
        String upper = name.toUpperCase();
        if (upper.contains("OBD") || upper.contains("ELM")
                || upper.contains("V-LINK") || upper.contains("VLINK")
                || upper.contains("VEEPEAK") || upper.contains("KONNWEI")
                || upper.contains("SCAN")) {
            return true;
        }

        // Check for SPP UUID in advertised services
        ParcelUuid[] uuids = device.getUuids();
        if (uuids != null) {
            for (ParcelUuid pu : uuids) {
                if (SPP_UUID.equals(pu.getUuid())) return true;
            }
        }
        return false;
    }

    private String deviceLabel(BluetoothDevice device) {
        String name = device.getName();
        return name != null ? name : device.getAddress();
    }

    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device == null) return;

                if (!isObdDevice(device)) return;

                String mac = device.getAddress();
                Log.i(TAG, "Discovered: " + device.getName() + " [" + mac + "]");

                // Check for duplicate
                for (FoundDevice fd : foundDevices) {
                    if (fd.mac.equals(mac)) return;
                }

                foundDevices.add(new FoundDevice(device, deviceLabel(device)));

                // If this is a trusted device, connect immediately
                if (isTrusted(mac)) {
                    Log.i(TAG, "Trusted device discovered, connecting: " + device.getName());
                    handler.removeCallbacks(discoveryTimeout);
                    cancelDiscovery();
                    notifyStatus(deviceLabel(device));
                    connectInBackground(device, deviceLabel(device));
                }

            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                handler.removeCallbacks(discoveryTimeout);
                evaluateFoundDevices();
            }
        }
    };

    private final Runnable discoveryTimeout = new Runnable() {
        @Override
        public void run() {
            cancelDiscovery();
            evaluateFoundDevices();
        }
    };

    private void evaluateFoundDevices() {
        if (!running || connected) return;

        if (foundDevices.isEmpty()) {
            notifyStatus("NO OBD FOUND");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() { scanForDevices(); }
            }, 3000);
            return;
        }

        // Check for trusted in found list
        for (FoundDevice fd : foundDevices) {
            if (isTrusted(fd.mac)) {
                notifyStatus(fd.name);
                connectInBackground(fd.device, fd.name);
                return;
            }
        }

        // Single device — auto-connect + trust
        if (foundDevices.size() == 1) {
            FoundDevice fd = foundDevices.get(0);
            Log.i(TAG, "Single OBD device found, auto-connecting: " + fd.name);
            trustDevice(fd.mac);
            notifyStatus(fd.name);
            connectInBackground(fd.device, fd.name);
            return;
        }

        // Multiple — let user pick
        Log.i(TAG, "Multiple OBD devices found (" + foundDevices.size() + ")");
        notifyStatus("SELECT ADAPTER");
        if (listener != null) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (listener != null) {
                        listener.onDevicesFound(new ArrayList<>(foundDevices));
                    }
                }
            });
        }
    }

    private void registerDiscoveryReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        context.registerReceiver(discoveryReceiver, filter);
        receiverRegistered = true;
    }

    private void unregisterDiscoveryReceiver() {
        if (!receiverRegistered) return;
        try {
            context.unregisterReceiver(discoveryReceiver);
        } catch (Exception e) {
            Log.w(TAG, "unregisterReceiver error", e);
        }
        receiverRegistered = false;
    }

    private void registerPairingReceiver() {
        if (pairingReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        context.registerReceiver(pairingReceiver, filter);
        pairingReceiverRegistered = true;
    }

    private void unregisterPairingReceiver() {
        if (!pairingReceiverRegistered) return;
        try {
            context.unregisterReceiver(pairingReceiver);
        } catch (Exception e) {
            Log.w(TAG, "unregisterPairingReceiver error", e);
        }
        pairingReceiverRegistered = false;
    }

    private void cancelDiscovery() {
        if (btAdapter != null && btAdapter.isDiscovering()) {
            btAdapter.cancelDiscovery();
        }
    }

    // ---- RFCOMM Connection ----

    private void connectInBackground(final BluetoothDevice device, final String name) {
        if (connectThread != null) {
            connectThread.interrupt();
        }

        connectThread = new Thread(new Runnable() {
            @Override
            public void run() {
                notifyStatusPost("CONNECTING...");

                try {
                    cancelDiscovery();

                    // Ensure device is bonded before connecting
                    if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                        Log.i(TAG, "Device not bonded, initiating pairing...");
                        notifyStatusPost("PAIRING...");
                        device.createBond();
                        // Wait for bonding to complete (auto-PIN via pairingReceiver)
                        int waited = 0;
                        while (device.getBondState() != BluetoothDevice.BOND_BONDED && waited < 15000) {
                            Thread.sleep(500);
                            waited += 500;
                        }
                        if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                            Log.e(TAG, "Pairing timed out");
                            notifyStatusPost("PAIR FAILED");
                            scheduleReconnect();
                            return;
                        }
                        Log.i(TAG, "Pairing successful");
                        // Small delay after bonding before RFCOMM
                        Thread.sleep(1000);
                    }

                    // Try 3 RFCOMM strategies with proper cleanup between each
                    socket = null;
                    boolean socketConnected = false;

                    // Strategy 1: Standard secure RFCOMM via SPP UUID
                    try {
                        Log.i(TAG, "Trying secure RFCOMM...");
                        socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                        socket.connect();
                        socketConnected = true;
                    } catch (IOException e1) {
                        Log.w(TAG, "Secure RFCOMM failed: " + e1.getMessage());
                        closeSocket();
                    }

                    // Strategy 2: Insecure RFCOMM via SPP UUID
                    if (!socketConnected) {
                        Thread.sleep(1000); // let BT stack release
                        try {
                            Log.i(TAG, "Trying insecure RFCOMM...");
                            socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                            socket.connect();
                            socketConnected = true;
                        } catch (IOException e2) {
                            Log.w(TAG, "Insecure RFCOMM failed: " + e2.getMessage());
                            closeSocket();
                        }
                    }

                    // Strategy 3: Reflection fallback — raw channel 1
                    if (!socketConnected) {
                        Thread.sleep(1000);
                        Log.i(TAG, "Trying reflection RFCOMM channel 1...");
                        Method m = device.getClass().getMethod("createRfcommSocket", int.class);
                        socket = (BluetoothSocket) m.invoke(device, 1);
                        socket.connect();
                        socketConnected = true;
                    }

                    if (!socketConnected) {
                        throw new IOException("All RFCOMM strategies failed");
                    }

                    InputStream is = socket.getInputStream();
                    OutputStream os = socket.getOutputStream();

                    elm = new ElmProtocol(is, os);

                    notifyStatusPost("INITIALIZING...");
                    boolean ok = elm.init();
                    if (!ok || !running) {
                        closeSocket();
                        return;
                    }

                    connected = true;
                    trustDevice(device.getAddress());

                    if (elm.hasEcu()) {
                        notifyStatusPost("CONNECTED");
                    } else {
                        notifyStatusPost("NO ECU — IGNITION OFF?");
                    }
                    Log.i(TAG, "ELM327 init complete, starting poll loop");

                    startPolling();

                } catch (Exception e) {
                    Log.e(TAG, "Connect failed: " + e.getMessage());
                    closeSocket();
                    if (running) {
                        notifyStatusPost("CONNECT FAILED");
                        scheduleReconnect();
                    }
                }
            }
        }, "OBD-Connect");
        connectThread.start();
    }

    // ---- Polling ----

    private void startPolling() {
        pollThread = new Thread(new Runnable() {
            @Override
            public void run() {
                int noEcuRetries = 0;
                while (running && connected && !Thread.interrupted()) {
                    try {
                        // If ECU not found yet, periodically retry PID discovery
                        if (!elm.hasEcu()) {
                            noEcuRetries++;
                            if (noEcuRetries % 10 == 1) { // every ~3s
                                Log.i(TAG, "No ECU, retrying PID discovery...");
                                if (elm.refreshSupportedPids()) {
                                    Log.i(TAG, "ECU found!");
                                    notifyStatusPost("CONNECTED");
                                } else {
                                    notifyStatusPost("NO ECU — IGNITION OFF?");
                                }
                            }
                            Thread.sleep(POLL_INTERVAL_MS);
                            continue;
                        }

                        final ObdData data = elm.poll();
                        if (data != null && listener != null) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (listener != null) listener.onDataReceived(data);
                                }
                            });
                        }
                        Thread.sleep(POLL_INTERVAL_MS);
                    } catch (IOException e) {
                        Log.e(TAG, "Poll error: " + e.getMessage());
                        connected = false;
                        closeSocket();
                        if (running) {
                            notifyStatusPost("DISCONNECTED");
                            scheduleReconnect();
                        }
                        return;
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "OBD-Poll");
        pollThread.start();
    }

    // ---- Reconnection ----

    private void scheduleReconnect() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (running) scanForDevices();
            }
        }, RECONNECT_DELAY_MS);
    }

    // ---- Helpers ----

    private void closeSocket() {
        if (socket != null) {
            try { socket.close(); } catch (IOException e) { /* ignore */ }
            socket = null;
        }
        elm = null;
    }

    private void notifyStatus(final String status) {
        if (listener != null) {
            listener.onStatusChanged(status);
        }
    }

    private void notifyStatusPost(final String status) {
        handler.post(new Runnable() {
            @Override
            public void run() { notifyStatus(status); }
        });
    }
}
