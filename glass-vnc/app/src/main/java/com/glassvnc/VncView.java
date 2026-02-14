package com.glassvnc;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * SurfaceView that connects to a VNC server and renders the remote framebuffer.
 * Supports 4 zoom modes matching glass-monitor:
 *   full    — scale entire remote desktop to 640x360
 *   quarter — 640x360 crop from top-left (1:1 pixels)
 *   half    — 960x540 crop, scaled down to 640x360
 *   zoom    — 1280x720 crop, scaled down to 640x360
 */
public class VncView extends SurfaceView implements SurfaceHolder.Callback {

    public static final int DISPLAY_W = 640;
    public static final int DISPLAY_H = 360;

    public interface Listener {
        void onStateChanged(int state);
        void onFps(int fps);
        void onDesktopSize(int w, int h);
    }

    public static final int STATE_CONNECTING = 0;
    public static final int STATE_CONNECTED = 1;
    public static final int STATE_DISCONNECTED = 2;

    public static final int MODE_FULL = 0;
    public static final int MODE_QUARTER = 1;
    public static final int MODE_HALF = 2;
    public static final int MODE_ZOOM = 3;

    private static final int RECONNECT_DELAY_MS = 2000;

    private String host;
    private int port;
    private String password;
    private Listener listener;
    private volatile boolean surfaceReady;
    private volatile boolean running;
    private volatile int zoomMode = MODE_FULL;
    private Thread workerThread;

    private final Paint paint;
    private final Rect dstRect = new Rect();

    public VncView(Context context) {
        this(context, null);
    }

    public VncView(Context context, AttributeSet attrs) {
        super(context, attrs);
        getHolder().addCallback(this);
        paint = new Paint();
        paint.setFilterBitmap(true);
    }

    public void setListener(Listener l) { this.listener = l; }
    public void setServer(String host, int port, String password) {
        this.host = host;
        this.port = port;
        this.password = password;
    }

    public void setZoomMode(int mode) { this.zoomMode = mode; }
    public int getZoomMode() { return zoomMode; }

    public void startStream() {
        if (running) return;
        running = true;
        workerThread = new Thread(new VncWorker(), "VncWorker");
        workerThread.start();
    }

    public void stopStream() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) { surfaceReady = true; }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        dstRect.set(0, 0, width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) { surfaceReady = false; }

    private class VncWorker implements Runnable {
        @Override
        public void run() {
            while (running) {
                notifyState(STATE_CONNECTING);
                RfbProto rfb = new RfbProto();
                try {
                    connectAndRender(rfb);
                } catch (Exception e) {
                    // connection failed or dropped
                }
                rfb.close();
                if (!running) break;
                notifyState(STATE_DISCONNECTED);
                try { Thread.sleep(RECONNECT_DELAY_MS); } catch (InterruptedException e) { break; }
            }
        }

        private void connectAndRender(RfbProto rfb) throws Exception {
            rfb.connect(host, port);
            rfb.handshake(password);
            rfb.setPixelFormat();
            rfb.setEncodings();

            int fbW = rfb.fbWidth;
            int fbH = rfb.fbHeight;
            int[] framebuffer = new int[fbW * fbH];

            notifyState(STATE_CONNECTED);
            notifyDesktopSize(fbW, fbH);

            // Initial full update
            rfb.requestUpdate(0, 0, fbW, fbH, false);

            long fpsStart = System.currentTimeMillis();
            int frameCount = 0;

            while (running) {
                int msgType = rfb.readServerMessage();

                switch (msgType) {
                    case 0: // FramebufferUpdate
                        int oldW = rfb.fbWidth;
                        int oldH = rfb.fbHeight;
                        rfb.readFramebufferUpdate(framebuffer);

                        // Handle desktop resize
                        if (rfb.fbWidth != oldW || rfb.fbHeight != oldH) {
                            fbW = rfb.fbWidth;
                            fbH = rfb.fbHeight;
                            framebuffer = new int[fbW * fbH];
                            notifyDesktopSize(fbW, fbH);
                            rfb.requestUpdate(0, 0, fbW, fbH, false);
                            continue;
                        }

                        renderFrame(framebuffer, fbW, fbH);
                        frameCount++;

                        long now = System.currentTimeMillis();
                        if (now - fpsStart >= 1000) {
                            notifyFps(frameCount);
                            frameCount = 0;
                            fpsStart = now;
                        }

                        // Request next incremental update
                        rfb.requestUpdate(0, 0, fbW, fbH, true);
                        break;

                    case 1: // SetColourMapEntries
                        rfb.skipSetColourMap();
                        break;

                    case 2: // Bell
                        rfb.skipBell();
                        break;

                    case 3: // ServerCutText
                        rfb.skipServerCutText();
                        break;

                    default:
                        throw new Exception("Unknown server message: " + msgType);
                }
            }
        }
    }

    private void renderFrame(int[] framebuffer, int fbW, int fbH) {
        if (!surfaceReady) return;

        // Determine source crop based on zoom mode
        int srcX, srcY, srcW, srcH;
        int mode = zoomMode;

        switch (mode) {
            case MODE_QUARTER:
                // 1:1 pixel crop from top-left, 640x360
                srcW = Math.min(DISPLAY_W, fbW);
                srcH = Math.min(DISPLAY_H, fbH);
                srcX = 0;
                srcY = 0;
                break;
            case MODE_HALF:
                // 960x540 crop from top-left
                srcW = Math.min(960, fbW);
                srcH = Math.min(540, fbH);
                srcX = 0;
                srcY = 0;
                break;
            case MODE_ZOOM:
                // 1280x720 crop from top-left
                srcW = Math.min(1280, fbW);
                srcH = Math.min(720, fbH);
                srcX = 0;
                srcY = 0;
                break;
            default: // MODE_FULL
                srcW = fbW;
                srcH = fbH;
                srcX = 0;
                srcY = 0;
                break;
        }

        // Create bitmap from the relevant portion
        Bitmap bmp = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888);
        // Copy rows from framebuffer
        for (int row = 0; row < srcH; row++) {
            int fbOffset = (srcY + row) * fbW + srcX;
            bmp.setPixels(framebuffer, fbOffset, fbW, 0, row, srcW, 1);
        }

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
        bmp.recycle();
    }

    private void notifyState(final int state) {
        if (listener == null) return;
        post(new Runnable() {
            @Override
            public void run() { if (listener != null) listener.onStateChanged(state); }
        });
    }

    private void notifyFps(final int fps) {
        if (listener == null) return;
        post(new Runnable() {
            @Override
            public void run() { if (listener != null) listener.onFps(fps); }
        });
    }

    private void notifyDesktopSize(final int w, final int h) {
        if (listener == null) return;
        post(new Runnable() {
            @Override
            public void run() { if (listener != null) listener.onDesktopSize(w, h); }
        });
    }
}
