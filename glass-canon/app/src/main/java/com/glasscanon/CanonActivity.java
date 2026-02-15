package com.glasscanon;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Streams Canon T5 live viewfinder to Google Glass via USB OTG.
 *
 * Tap = shutter, Swipe down = exit.
 */
public class CanonActivity extends Activity implements CanonUsb.Listener, SurfaceHolder.Callback {

    private static final int GLASS_W = 640;
    private static final int GLASS_H = 360;

    private CanonUsb usb;
    private PowerManager.WakeLock wakeLock;

    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private boolean surfaceReady = false;
    private TextView statusText;

    // Rendering
    private final Paint paint = new Paint();
    private final Rect dstRect = new Rect();
    private int surfaceWidth;
    private int surfaceHeight;

    // FPS counter
    private int frameCount = 0;
    private long fpsStart = 0;
    private int currentFps = 0;

    // Touchpad gesture tracking
    private float touchDownX;
    private float touchDownY;
    private boolean touchTracking = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_canon);

        surfaceView = (SurfaceView) findViewById(R.id.surface_view);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);

        statusText = (TextView) findViewById(R.id.status_text);

        paint.setFilterBitmap(true);

        // Transparent overlay intercepts all Glass touchpad events
        View interceptor = findViewById(R.id.touch_interceptor);
        interceptor.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                handleTouchEvent(event);
                return true;
            }
        });

        usb = new CanonUsb(this);
        usb.setListener(this);

        handleUsbIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleUsbIntent(intent);
    }

    private void handleUsbIntent(Intent intent) {
        if (intent != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null) {
                usb.stop();
                usb.startWithDevice(device);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "glasscanon:stream");
        wakeLock.acquire();
        usb.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        usb.stop();
    }

    // ---- SurfaceHolder.Callback ----

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        surfaceWidth = width;
        surfaceHeight = height;
        // Default rect until we know camera frame dimensions
        dstRect.set(0, 0, width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
    }

    // ---- Touchpad gesture handling ----

    private void handleTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = event.getX();
                touchDownY = event.getY();
                touchTracking = true;
                break;

            case MotionEvent.ACTION_UP:
                if (!touchTracking) break;
                touchTracking = false;

                float dy = event.getY() - touchDownY;
                float dx = event.getX() - touchDownX;

                // Swipe down → exit
                if (dy > 50 && Math.abs(dy) > Math.abs(dx)) {
                    exitApp();
                    return;
                }

                // Tap (small movement) → shutter
                if (Math.abs(dx) < 20 && Math.abs(dy) < 20) {
                    if (usb != null) {
                        usb.requestShutter();
                    }
                }
                break;
        }
    }

    // ---- Key handling ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_ESCAPE:
                    exitApp();
                    return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        exitApp();
    }

    private void exitApp() {
        usb.stop();
        finish();
    }

    // ---- CanonUsb.Listener ----

    @Override
    public void onStatusChanged(String status) {
        statusText.setText(status);
    }

    @Override
    public void onFrameReceived(byte[] jpegData) {
        Bitmap frame = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
        if (frame == null) return;

        // Calculate aspect-fit destination rect
        computeDstRect(frame.getWidth(), frame.getHeight());

        renderFrame(frame);
        frame.recycle();

        // FPS counter
        frameCount++;
        long now = System.currentTimeMillis();
        if (fpsStart == 0) fpsStart = now;
        if (now - fpsStart >= 1000) {
            currentFps = frameCount;
            frameCount = 0;
            fpsStart = now;
            statusText.setText("Streaming " + currentFps + " fps");
        }
    }

    /**
     * Compute aspect-fit destination rect to center the camera frame on the Glass display.
     */
    private void computeDstRect(int frameW, int frameH) {
        if (surfaceWidth == 0 || surfaceHeight == 0) return;

        float scaleX = (float) surfaceWidth / frameW;
        float scaleY = (float) surfaceHeight / frameH;
        float scale = Math.min(scaleX, scaleY);

        int dw = (int) (frameW * scale);
        int dh = (int) (frameH * scale);
        int left = (surfaceWidth - dw) / 2;
        int top = (surfaceHeight - dh) / 2;

        dstRect.set(left, top, left + dw, top + dh);
    }

    // ---- Rendering ----

    private void renderFrame(Bitmap frame) {
        if (!surfaceReady) return;
        Canvas canvas = null;
        try {
            canvas = surfaceHolder.lockCanvas();
            if (canvas != null) {
                canvas.drawColor(Color.BLACK);
                canvas.drawBitmap(frame, null, dstRect, paint);
            }
        } finally {
            if (canvas != null) {
                surfaceHolder.unlockCanvasAndPost(canvas);
            }
        }
    }
}
