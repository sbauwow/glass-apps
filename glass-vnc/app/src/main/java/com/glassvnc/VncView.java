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
import android.util.Log;
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
                    Log.e("GlassVNC", "Connection error: " + e.getMessage(), e);
                }
                rfb.close();
                if (!running) break;
                notifyState(STATE_DISCONNECTED);
                try { Thread.sleep(RECONNECT_DELAY_MS); } catch (InterruptedException e) { break; }
            }
        }

        private int[] getViewport(int fbW, int fbH) {
            // Returns {x, y, w, h} for the region to request from the server
            int mode = zoomMode;
            int vw, vh;
            switch (mode) {
                case MODE_QUARTER: vw = DISPLAY_W; vh = DISPLAY_H; break;
                case MODE_HALF:    vw = 960;       vh = 540;       break;
                case MODE_ZOOM:    vw = 1280;      vh = 720;       break;
                default:           vw = fbW;       vh = fbH;       break; // MODE_FULL
            }
            vw = Math.min(vw, fbW);
            vh = Math.min(vh, fbH);
            return new int[]{ 0, 0, vw, vh };
        }

        private void connectAndRender(RfbProto rfb) throws Exception {
            Log.d("GlassVNC", "Connecting to " + host + ":" + port);
            rfb.connect(host, port);
            Log.d("GlassVNC", "TCP connected, starting handshake");
            rfb.handshake(password);
            Log.d("GlassVNC", "Handshake done: " + rfb.desktopWidth + "x" + rfb.desktopHeight + " " + rfb.serverName);
            rfb.setPixelFormat();
            rfb.setEncodings();

            int fbW = rfb.desktopWidth;
            int fbH = rfb.desktopHeight;

            notifyState(STATE_CONNECTED);
            notifyDesktopSize(fbW, fbH);

            // Request only the viewport region, not the full desktop
            int[] vp = getViewport(fbW, fbH);
            int vpW = vp[2], vpH = vp[3];
            int[] framebuffer = new int[vpW * vpH];
            // Override fbWidth/fbHeight so readRawRect writes into our viewport buffer
            rfb.fbWidth = vpW;
            rfb.fbHeight = vpH;
            rfb.viewportX = vp[0];
            rfb.viewportY = vp[1];
            Log.d("GlassVNC", "Requesting viewport: " + vpW + "x" + vpH);
            rfb.requestUpdate(vp[0], vp[1], vpW, vpH, false);

            long fpsStart = System.currentTimeMillis();
            int frameCount = 0;

            while (running) {
                int msgType = rfb.readServerMessage();

                switch (msgType) {
                    case 0: // FramebufferUpdate
                        rfb.readFramebufferUpdate(framebuffer);

                        renderFrame(framebuffer, vpW, vpH);
                        frameCount++;

                        long now = System.currentTimeMillis();
                        if (now - fpsStart >= 1000) {
                            notifyFps(frameCount);
                            frameCount = 0;
                            fpsStart = now;
                        }

                        // Check if zoom mode changed — adjust viewport
                        int[] newVp = getViewport(fbW, fbH);
                        if (newVp[2] != vpW || newVp[3] != vpH) {
                            vpW = newVp[2]; vpH = newVp[3];
                            framebuffer = new int[vpW * vpH];
                            rfb.fbWidth = vpW;
                            rfb.fbHeight = vpH;
                            Log.d("GlassVNC", "Viewport changed: " + vpW + "x" + vpH);
                            rfb.requestUpdate(newVp[0], newVp[1], vpW, vpH, false);
                        } else {
                            rfb.requestUpdate(vp[0], vp[1], vpW, vpH, true);
                        }
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

        // Framebuffer is already viewport-sized, just blit it
        Bitmap bmp = Bitmap.createBitmap(fbW, fbH, Bitmap.Config.ARGB_8888);
        bmp.setPixels(framebuffer, 0, fbW, 0, 0, fbW, fbH);

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
