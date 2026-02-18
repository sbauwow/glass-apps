package com.glassbt;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class RfcommServer {

    private static final String TAG = "RfcommServer";
    private static final UUID SERVICE_UUID = UUID.fromString("5e3d4f8a-1b2c-3d4e-5f6a-7b8c9d0e1f2a");
    private static final String SERVICE_NAME = "GlassBT";
    private static final int HEARTBEAT_INTERVAL_MS = 15000;
    private static final String PREFS_NAME = "glass_bt";
    private static final String KEY_TRUSTED = "trusted_macs";

    public interface Listener {
        void onClientConnected(String deviceName, String mac);
        void onClientDisconnected();
        void onMessageReceived(Message message);
        void onError(String error);
        void onListening(int channel);
    }

    private final Listener listener;
    private final BluetoothAdapter adapter;
    private final SharedPreferences prefs;
    private BluetoothServerSocket serverSocket;
    private BluetoothSocket clientSocket;
    private DataOutputStream outStream;
    private volatile boolean running;

    public RfcommServer(Context context, Listener listener) {
        this.listener = listener;
        this.adapter = BluetoothAdapter.getDefaultAdapter();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void start() {
        if (adapter == null) {
            listener.onError("No Bluetooth adapter");
            return;
        }
        if (!adapter.isEnabled()) {
            listener.onError("Bluetooth is disabled");
            return;
        }
        running = true;
        new Thread(this::acceptLoop, "RfcommAccept").start();
    }

    public void stop() {
        running = false;
        closeClient();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
    }

    public boolean isConnected() {
        return clientSocket != null && clientSocket.isConnected();
    }

    public void sendMessage(JSONObject msg) {
        DataOutputStream out = this.outStream;
        if (out == null) return;
        new Thread(() -> {
            try {
                MessageProtocol.writeMessage(out, msg);
            } catch (IOException e) {
                Log.w(TAG, "Send failed: " + e.getMessage());
            }
        }, "RfcommSend").start();
    }

    public boolean hasTrustedDevices() {
        return !getTrustedMacs().isEmpty();
    }

    // --- Trusted device management ---

    private Set<String> getTrustedMacs() {
        return new HashSet<>(prefs.getStringSet(KEY_TRUSTED, new HashSet<String>()));
    }

    public void trustDevice(String mac) {
        Set<String> trusted = getTrustedMacs();
        trusted.add(mac);
        prefs.edit().putStringSet(KEY_TRUSTED, trusted).apply();
        Log.i(TAG, "Trusted device: " + mac);
    }

    private boolean isTrusted(String mac) {
        return getTrustedMacs().contains(mac);
    }

    // --- Server loops ---

    private void acceptLoop() {
        while (running) {
            try {
                serverSocket = adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID);
                int ch = getChannel(serverSocket);
                Log.i(TAG, "Listening on RFCOMM channel " + ch);
                listener.onListening(ch);

                BluetoothSocket socket = serverSocket.accept();
                // Close server socket — one client at a time
                try { serverSocket.close(); } catch (IOException ignored) {}
                handleClient(socket);
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "Accept error: " + e.getMessage());
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                }
            } finally {
                try {
                    if (serverSocket != null) serverSocket.close();
                } catch (IOException ignored) {}
            }
        }
    }

    private static int getChannel(BluetoothServerSocket serverSocket) {
        try {
            Field socketField = BluetoothServerSocket.class.getDeclaredField("mSocket");
            socketField.setAccessible(true);
            Object socket = socketField.get(serverSocket);
            Field portField = socket.getClass().getDeclaredField("mPort");
            portField.setAccessible(true);
            return portField.getInt(socket);
        } catch (Exception e) {
            Log.w(TAG, "Could not get channel: " + e);
            return -1;
        }
    }

    private void handleClient(BluetoothSocket socket) {
        BluetoothDevice device = socket.getRemoteDevice();
        String mac = device.getAddress();
        String name = device.getName();
        if (name == null) name = mac;

        trustDevice(mac);
        Log.i(TAG, "Client connected: " + name + " (" + mac + ")");

        clientSocket = socket;
        listener.onClientConnected(name, mac);

        try {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            outStream = new DataOutputStream(socket.getOutputStream());

            // Start heartbeat thread
            Thread heartbeat = new Thread(() -> heartbeatLoop(outStream), "RfcommHeartbeat");
            heartbeat.setDaemon(true);
            heartbeat.start();

            // Read loop
            while (running) {
                Message msg = MessageProtocol.readMessage(in);
                Log.i(TAG, "Received: type=" + msg.type + " text=" + msg.getDisplayText());
                if (!msg.isHeartbeat()) {
                    listener.onMessageReceived(msg);
                }
            }
        } catch (IOException e) {
            if (running) {
                Log.i(TAG, "Client disconnected: " + e.getMessage());
            }
        } finally {
            closeClient();
            listener.onClientDisconnected();
        }
    }

    private void heartbeatLoop(DataOutputStream out) {
        while (running && isConnected()) {
            try {
                Thread.sleep(HEARTBEAT_INTERVAL_MS);
                MessageProtocol.sendHeartbeat(out);
            } catch (InterruptedException ignored) {
                break;
            } catch (IOException e) {
                Log.w(TAG, "Heartbeat failed: " + e.getMessage());
                break;
            }
        }
    }

    private void closeClient() {
        outStream = null;
        try {
            if (clientSocket != null) clientSocket.close();
        } catch (IOException ignored) {}
        clientSocket = null;
    }
}
