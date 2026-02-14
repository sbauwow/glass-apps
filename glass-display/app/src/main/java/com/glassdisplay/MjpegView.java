package com.glassdisplay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * SurfaceView that connects to an MJPEG HTTP stream and renders frames fullscreen.
 * Handles auto-reconnect with backoff on disconnection.
 */
public class MjpegView extends SurfaceView implements SurfaceHolder.Callback {

    public interface Listener {
        void onStateChanged(int state);
        void onFps(int fps);
    }

    public static final int STATE_CONNECTING = 0;
    public static final int STATE_CONNECTED = 1;
    public static final int STATE_DISCONNECTED = 2;

    private static final int RECONNECT_DELAY_MS = 2000;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;

    private String streamUrl;
    private Listener listener;
    private volatile boolean surfaceReady;
    private volatile boolean running;
    private Thread workerThread;

    private final Paint paint;
    private final Rect dstRect = new Rect();

    public MjpegView(Context context) {
        this(context, null);
    }

    public MjpegView(Context context, AttributeSet attrs) {
        super(context, attrs);
        getHolder().addCallback(this);
        paint = new Paint();
        paint.setFilterBitmap(true);  // bilinear scaling
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void setStreamUrl(String url) {
        this.streamUrl = url;
    }

    public void startStream() {
        if (running) return;
        running = true;
        workerThread = new Thread(new StreamRunnable(), "MjpegWorker");
        workerThread.start();
    }

    public void stopStream() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
    }

    // ---- SurfaceHolder.Callback ----

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        dstRect.set(0, 0, width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
    }

    // ---- Stream worker ----

    private class StreamRunnable implements Runnable {
        @Override
        public void run() {
            while (running) {
                notifyState(STATE_CONNECTING);
                try {
                    connectAndStream();
                } catch (Exception e) {
                    // Connection failed or dropped
                }
                if (!running) break;
                notifyState(STATE_DISCONNECTED);
                try {
                    Thread.sleep(RECONNECT_DELAY_MS);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        private void connectAndStream() throws IOException {
            HttpURLConnection conn = null;
            InputStream is = null;
            try {
                conn = (HttpURLConnection) new URL(streamUrl).openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("Accept", "multipart/x-mixed-replace");
                conn.connect();

                if (conn.getResponseCode() != 200) return;

                is = conn.getInputStream();

                long fpsStart = System.currentTimeMillis();
                int frameCount = 0;
                boolean notifiedConnected = false;

                // Read MJPEG multipart stream
                // Format: --frame\r\nContent-Type: image/jpeg\r\nContent-Length: N\r\n\r\n<JPEG bytes>
                byte[] lineBuf = new byte[256];

                while (running) {
                    // Read headers until we find Content-Length
                    int contentLength = -1;
                    while (true) {
                        String line = readLine(is, lineBuf);
                        if (line == null) return; // stream ended
                        if (line.isEmpty()) {
                            if (contentLength > 0) break; // end of headers
                            continue;
                        }
                        if (line.startsWith("Content-Length:") || line.startsWith("content-length:")) {
                            contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                        }
                    }

                    if (contentLength <= 0 || contentLength > 1024 * 1024) return;

                    // Read JPEG bytes
                    byte[] jpegData = new byte[contentLength];
                    int offset = 0;
                    while (offset < contentLength) {
                        int read = is.read(jpegData, offset, contentLength - offset);
                        if (read < 0) return;
                        offset += read;
                    }

                    // Decode and render
                    Bitmap bmp = BitmapFactory.decodeByteArray(jpegData, 0, contentLength);
                    if (bmp != null) {
                        if (!notifiedConnected) {
                            notifyState(STATE_CONNECTED);
                            notifiedConnected = true;
                        }
                        renderFrame(bmp);
                        bmp.recycle();
                    }

                    // FPS counter
                    frameCount++;
                    long now = System.currentTimeMillis();
                    if (now - fpsStart >= 1000) {
                        notifyFps(frameCount);
                        frameCount = 0;
                        fpsStart = now;
                    }
                }
            } finally {
                if (is != null) try { is.close(); } catch (IOException e) { /* ignore */ }
                if (conn != null) conn.disconnect();
            }
        }

        /**
         * Read one line (up to \r\n or \n) from the stream.
         * Returns the line without the line ending, or null on EOF.
         */
        private String readLine(InputStream is, byte[] buf) throws IOException {
            int pos = 0;
            while (pos < buf.length) {
                int b = is.read();
                if (b < 0) return null;
                if (b == '\n') {
                    // Strip trailing \r if present
                    int end = (pos > 0 && buf[pos - 1] == '\r') ? pos - 1 : pos;
                    return new String(buf, 0, end, "UTF-8");
                }
                buf[pos++] = (byte) b;
            }
            // Line too long, return what we have
            return new String(buf, 0, pos, "UTF-8");
        }
    }

    private void renderFrame(Bitmap bmp) {
        if (!surfaceReady) return;
        SurfaceHolder holder = getHolder();
        Canvas canvas = null;
        try {
            canvas = holder.lockCanvas();
            if (canvas != null) {
                canvas.drawColor(Color.BLACK);
                canvas.drawBitmap(bmp, null, dstRect, paint);
            }
        } finally {
            if (canvas != null) {
                holder.unlockCanvasAndPost(canvas);
            }
        }
    }

    private void notifyState(final int state) {
        if (listener == null) return;
        post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onStateChanged(state);
            }
        });
    }

    private void notifyFps(final int fps) {
        if (listener == null) return;
        post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onFps(fps);
            }
        });
    }
}
