package com.glassnotify;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

public class GlassServer {

    public interface Listener {
        void onNotificationReceived(NotificationData notification);
        void onLocationReceived(double lat, double lon, double alt, float accuracy, long time);
        void onNavigationReceived(String instruction, String distance, String eta, long time);
        void onNavigationEnded();
        void onClientConnected(String clientAddress);
        void onClientDisconnected();
    }

    private static final int PORT = 9876;
    private static final int SOCKET_TIMEOUT_MS = 60000;

    private final Listener listener;
    private ServerSocket serverSocket;
    private volatile boolean running;

    public GlassServer(Listener listener) {
        this.listener = listener;
    }

    public void start() {
        running = true;
        new Thread(this::serverLoop, "GlassServer").start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
    }

    private void serverLoop() {
        while (running) {
            try {
                serverSocket = new ServerSocket(PORT);
                serverSocket.setReuseAddress(true);

                while (running) {
                    Socket client = serverSocket.accept();
                    handleClient(client);
                }
            } catch (IOException e) {
                if (running) {
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                }
            } finally {
                try {
                    if (serverSocket != null) serverSocket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void handleClient(Socket client) {
        String addr = client.getInetAddress().getHostAddress();
        listener.onClientConnected(addr);

        try {
            client.setSoTimeout(SOCKET_TIMEOUT_MS);
            client.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(client.getInputStream());

            while (running) {
                int length = in.readInt();
                if (length <= 0 || length > 65536) break;

                byte[] data = new byte[length];
                in.readFully(data);
                String json = new String(data, "UTF-8");

                try {
                    JSONObject obj = new JSONObject(json);
                    String type = obj.optString("type", "");

                    if ("nav".equals(type)) {
                        listener.onNavigationReceived(
                                obj.optString("instruction", ""),
                                obj.optString("distance", ""),
                                obj.optString("eta", ""),
                                obj.optLong("time", System.currentTimeMillis()));
                    } else if ("nav_end".equals(type)) {
                        listener.onNavigationEnded();
                    } else if ("location".equals(type)) {
                        listener.onLocationReceived(
                                obj.getDouble("lat"),
                                obj.getDouble("lon"),
                                obj.optDouble("alt", 0),
                                (float) obj.optDouble("acc", 0),
                                obj.optLong("time", System.currentTimeMillis()));
                    } else {
                        // "notification" type or legacy messages without type
                        NotificationData notification = new NotificationData(
                                obj.optString("app", ""),
                                obj.optString("title", ""),
                                obj.optString("text", ""),
                                obj.optLong("time", System.currentTimeMillis()));
                        if (!notification.isHeartbeat()) {
                            listener.onNotificationReceived(notification);
                        }
                    }
                } catch (Exception e) {
                    // Skip malformed JSON
                }
            }
        } catch (SocketTimeoutException e) {
            // Client timed out (no heartbeat received)
        } catch (IOException e) {
            // Client disconnected
        } finally {
            try { client.close(); } catch (IOException ignored) {}
            listener.onClientDisconnected();
        }
    }
}
