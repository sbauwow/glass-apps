package com.example.glassstream;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal HTTP server that serves MJPEG stream, single JPEG snapshots, and an HTML viewer page.
 */
public class MjpegHttpServer {

    private static final String TAG = "MjpegHttpServer";
    private static final String BOUNDARY = "frame";

    private final int port;
    private final FrameBuffer frameBuffer;
    private final AtomicInteger clientCount = new AtomicInteger(0);
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread serverThread;

    public MjpegHttpServer(int port, FrameBuffer frameBuffer) {
        this.port = port;
        this.frameBuffer = frameBuffer;
    }

    public void start() {
        running = true;
        serverThread = new Thread(this::serverLoop, "MjpegHttpServer");
        serverThread.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) {
            Log.w(TAG, "Error closing server socket", e);
        }
        if (serverThread != null) {
            serverThread.interrupt();
        }
    }

    public int getClientCount() {
        return clientCount.get();
    }

    private void serverLoop() {
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setReuseAddress(true);
            Log.i(TAG, "MJPEG server listening on port " + port);

            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    new Thread(() -> handleClient(client), "MjpegClient").start();
                } catch (SocketException e) {
                    if (running) Log.e(TAG, "Accept error", e);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Server error", e);
        }
    }

    private void handleClient(Socket client) {
        try {
            client.setTcpNoDelay(true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
            String requestLine = reader.readLine();
            if (requestLine == null) return;

            // Read and discard remaining headers
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // consume headers
            }

            String path = "/";
            String[] parts = requestLine.split(" ");
            if (parts.length >= 2) {
                path = parts[1];
            }

            OutputStream out = client.getOutputStream();

            if ("/stream".equals(path)) {
                handleStream(out);
            } else if ("/snapshot".equals(path)) {
                handleSnapshot(out);
            } else {
                handleIndex(out);
            }
        } catch (IOException e) {
            // Client disconnected, expected
        } finally {
            try {
                client.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    private void handleStream(OutputStream out) throws IOException {
        clientCount.incrementAndGet();
        try {
            String header = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY + "\r\n"
                    + "Cache-Control: no-cache, no-store\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            out.write(header.getBytes());
            out.flush();

            long lastFrameNumber = 0;

            while (running) {
                byte[] jpeg = frameBuffer.waitForFrame(lastFrameNumber, 5000);
                if (jpeg == null) continue;

                lastFrameNumber = frameBuffer.getFrameNumber();

                String partHeader = "--" + BOUNDARY + "\r\n"
                        + "Content-Type: image/jpeg\r\n"
                        + "Content-Length: " + jpeg.length + "\r\n"
                        + "\r\n";
                out.write(partHeader.getBytes());
                out.write(jpeg);
                out.write("\r\n".getBytes());
                out.flush();
            }
        } finally {
            clientCount.decrementAndGet();
        }
    }

    private void handleSnapshot(OutputStream out) throws IOException {
        byte[] jpeg = frameBuffer.getLatestFrame();
        if (jpeg == null) {
            String response = "HTTP/1.1 503 Service Unavailable\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "Connection: close\r\n"
                    + "\r\n"
                    + "No frame available yet";
            out.write(response.getBytes());
        } else {
            String header = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: image/jpeg\r\n"
                    + "Content-Length: " + jpeg.length + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            out.write(header.getBytes());
            out.write(jpeg);
        }
        out.flush();
    }

    private void handleIndex(OutputStream out) throws IOException {
        String html = "<html><head><title>Glass Stream</title>"
                + "<style>body{margin:0;background:#000;display:flex;justify-content:center;align-items:center;height:100vh}"
                + "img{max-width:100%;max-height:100%}</style></head>"
                + "<body><img src=\"/stream\"></body></html>";
        String header = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/html\r\n"
                + "Content-Length: " + html.length() + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(header.getBytes());
        out.write(html.getBytes());
        out.flush();
    }
}
