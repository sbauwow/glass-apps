package com.glassmusic;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.UUID;

public class AudioServer {

    private static final String TAG = "AudioServer";
    private static final UUID SERVICE_UUID =
            UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479");
    private static final String SERVICE_NAME = "GlassMusic";
    private static final int HEARTBEAT_INTERVAL_MS = 15000;
    private static final int MAX_FRAME_SIZE = 65536;

    // Frame types
    public static final byte TYPE_CONFIG    = 0x01;
    public static final byte TYPE_AUDIO     = 0x02;
    public static final byte TYPE_COMMAND   = 0x03;
    public static final byte TYPE_HEARTBEAT = 0x04;
    public static final byte TYPE_METADATA  = 0x05;

    public interface Listener {
        void onClientConnected(String deviceName, String mac);
        void onClientDisconnected();
        void onConfigReceived(int sampleRate, int channels);
        void onAudioChunkReceived(byte[] data, int length);
        void onMetadataReceived(String title, String artist);
        void onError(String error);
        void onListening(int channel);
    }

    private final Listener listener;
    private final BluetoothAdapter adapter;
    private BluetoothServerSocket serverSocket;
    private BluetoothSocket clientSocket;
    private OutputStream outStream;
    private volatile boolean running;

    public AudioServer(Listener listener) {
        this.listener = listener;
        this.adapter = BluetoothAdapter.getDefaultAdapter();
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
        new Thread(this::acceptLoop, "AudioAccept").start();
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

    public void sendCommand(String cmd) {
        OutputStream out = this.outStream;
        if (out == null) return;
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("cmd", cmd);
                byte[] body = json.toString().getBytes("UTF-8");
                writeFrame(out, TYPE_COMMAND, body);
            } catch (JSONException | IOException e) {
                Log.w(TAG, "Send command failed: " + e.getMessage());
            }
        }, "AudioSendCmd").start();
    }

    // --- Frame I/O ---

    private static void writeFrame(OutputStream out, byte type, byte[] body) throws IOException {
        int payloadLen = 1 + (body != null ? body.length : 0);
        byte[] header = new byte[5];
        header[0] = (byte) (payloadLen >> 24);
        header[1] = (byte) (payloadLen >> 16);
        header[2] = (byte) (payloadLen >> 8);
        header[3] = (byte) payloadLen;
        header[4] = type;
        synchronized (out) {
            out.write(header);
            if (body != null && body.length > 0) {
                out.write(body);
            }
            out.flush();
        }
    }

    private static void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int offset = 0;
        while (offset < len) {
            int read = in.read(buf, offset, len - offset);
            if (read < 0) throw new IOException("Connection closed");
            offset += read;
        }
    }

    private static int readInt(InputStream in) throws IOException {
        byte[] buf = new byte[4];
        readFully(in, buf, 4);
        return ((buf[0] & 0xFF) << 24)
             | ((buf[1] & 0xFF) << 16)
             | ((buf[2] & 0xFF) << 8)
             |  (buf[3] & 0xFF);
    }

    // --- Server loops ---

    private void acceptLoop() {
        while (running) {
            try {
                serverSocket = adapter.listenUsingInsecureRfcommWithServiceRecord(
                        SERVICE_NAME, SERVICE_UUID);
                int ch = getChannel(serverSocket);
                Log.i(TAG, "Listening on RFCOMM channel " + ch);
                listener.onListening(ch);

                BluetoothSocket socket = serverSocket.accept();
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

        Log.i(TAG, "Client connected: " + name + " (" + mac + ")");
        clientSocket = socket;
        listener.onClientConnected(name, mac);

        try {
            InputStream in = new BufferedInputStream(socket.getInputStream(), MAX_FRAME_SIZE);
            outStream = socket.getOutputStream();

            // Start heartbeat thread
            Thread heartbeat = new Thread(() -> heartbeatLoop(outStream), "AudioHeartbeat");
            heartbeat.setDaemon(true);
            heartbeat.start();

            // Read loop — dispatch on frame type
            byte[] bodyBuf = new byte[MAX_FRAME_SIZE];
            while (running) {
                int payloadLen = readInt(in);
                if (payloadLen <= 0 || payloadLen > MAX_FRAME_SIZE) {
                    throw new IOException("Invalid frame length: " + payloadLen);
                }

                // Read type byte
                byte[] typeBuf = new byte[1];
                readFully(in, typeBuf, 1);
                byte type = typeBuf[0];
                int bodyLen = payloadLen - 1;

                // Read body
                if (bodyLen > 0) {
                    readFully(in, bodyBuf, bodyLen);
                }

                switch (type) {
                    case TYPE_CONFIG:
                        handleConfig(bodyBuf, bodyLen);
                        break;
                    case TYPE_AUDIO:
                        listener.onAudioChunkReceived(bodyBuf, bodyLen);
                        break;
                    case TYPE_COMMAND:
                        break;
                    case TYPE_METADATA:
                        handleMetadata(bodyBuf, bodyLen);
                        break;
                    case TYPE_HEARTBEAT:
                        break;
                    default:
                        Log.w(TAG, "Unknown frame type: " + type);
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

    private void handleConfig(byte[] body, int len) {
        try {
            String json = new String(body, 0, len, "UTF-8");
            JSONObject config = new JSONObject(json);
            int sampleRate = config.optInt("sample_rate", 44100);
            int channels = config.optInt("channels", 1);
            Log.i(TAG, "Config: " + sampleRate + "Hz " + channels + "ch");
            listener.onConfigReceived(sampleRate, channels);
        } catch (Exception e) {
            Log.e(TAG, "Invalid config frame", e);
        }
    }

    private void handleMetadata(byte[] body, int len) {
        try {
            String json = new String(body, 0, len, "UTF-8");
            JSONObject meta = new JSONObject(json);
            String title = meta.optString("title", "");
            String artist = meta.optString("artist", "");
            listener.onMetadataReceived(title, artist);
        } catch (Exception e) {
            Log.e(TAG, "Invalid metadata frame", e);
        }
    }

    private void heartbeatLoop(OutputStream out) {
        while (running && isConnected()) {
            try {
                Thread.sleep(HEARTBEAT_INTERVAL_MS);
                writeFrame(out, TYPE_HEARTBEAT, null);
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
