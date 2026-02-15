package com.glassflipper;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
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
 * Mirrors Flipper Zero's 128x64 OLED onto Google Glass via USB OTG.
 *
 * Swipe down = exit
 */
public class FlipperActivity extends Activity implements FlipperUsb.Listener, SurfaceHolder.Callback {

    private static final int FLIPPER_W = 128;
    private static final int FLIPPER_H = 64;
    private static final int SCALE = 5; // 128x64 × 5 = 640x320
    private static final int DISPLAY_W = FLIPPER_W * SCALE;
    private static final int DISPLAY_H = FLIPPER_H * SCALE;

    private FlipperUsb usb;
    private PowerManager.WakeLock wakeLock;

    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private boolean surfaceReady = false;
    private TextView statusText;

    // Rendering
    private final Paint paint = new Paint();
    private final int[] pixelBuf = new int[FLIPPER_W * FLIPPER_H];
    private Bitmap frameBitmap;
    private final Rect dstRect = new Rect();

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
        setContentView(R.layout.activity_flipper);

        surfaceView = (SurfaceView) findViewById(R.id.surface_view);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);

        statusText = (TextView) findViewById(R.id.status_text);

        paint.setFilterBitmap(false);
        paint.setAntiAlias(false);

        frameBitmap = Bitmap.createBitmap(FLIPPER_W, FLIPPER_H, Bitmap.Config.ARGB_8888);

        // Transparent overlay intercepts all Glass touchpad events
        View interceptor = findViewById(R.id.touch_interceptor);
        interceptor.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                handleTouchEvent(event);
                return true;
            }
        });

        usb = new FlipperUsb(this);
        usb.setListener(this);

        // Check if launched via USB_DEVICE_ATTACHED intent (auto-grants permission)
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
                "glassflipper:stream");
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
        int left = (width - DISPLAY_W) / 2;
        int top = (height - DISPLAY_H) / 2;
        dstRect.set(left, top, left + DISPLAY_W, top + DISPLAY_H);
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

    // ---- FlipperUsb.Listener ----

    @Override
    public void onStatusChanged(String status) {
        statusText.setText(status);
    }

    @Override
    public void onFrameReceived(byte[] xbmData) {
        // Flipper screen uses vertical byte layout (like SSD1306 OLED):
        // 1024 bytes = 128 columns × 8 pages, each byte = 8 vertical pixels, LSB = top
        // Byte index = page * 128 + column, bit N = row (page*8 + N)
        for (int page = 0; page < 8; page++) {
            for (int col = 0; col < FLIPPER_W; col++) {
                int byteIdx = page * FLIPPER_W + col;
                if (byteIdx >= xbmData.length) break;
                int b = xbmData[byteIdx] & 0xFF;
                for (int bit = 0; bit < 8; bit++) {
                    int row = page * 8 + bit;
                    if (row >= FLIPPER_H) break;
                    int pixelIdx = row * FLIPPER_W + col;
                    pixelBuf[pixelIdx] = ((b >> bit) & 1) == 1 ? 0xFFFFFFFF : 0xFF000000;
                }
            }
        }

        frameBitmap.setPixels(pixelBuf, 0, FLIPPER_W, 0, 0, FLIPPER_W, FLIPPER_H);
        renderFrame();

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

    // ---- Rendering ----

    private void renderFrame() {
        if (!surfaceReady) return;
        Canvas canvas = null;
        try {
            canvas = surfaceHolder.lockCanvas();
            if (canvas != null) {
                canvas.drawColor(Color.BLACK);
                canvas.drawBitmap(frameBitmap, null, dstRect, paint);
            }
        } finally {
            if (canvas != null) {
                surfaceHolder.unlockCanvasAndPost(canvas);
            }
        }
    }
}
