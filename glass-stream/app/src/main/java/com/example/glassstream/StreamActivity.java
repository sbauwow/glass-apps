package com.example.glassstream;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.Locale;

public class StreamActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "StreamActivity";
    private static final int SERVER_PORT = 8080;
    private static final int[] QUALITY_LEVELS = {50, 70, 85};

    private SurfaceView surfaceView;
    private TextView statusText;

    private FrameBuffer frameBuffer;
    private CameraManager cameraManager;
    private MjpegHttpServer httpServer;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private Handler statusHandler;
    private Runnable statusUpdater;
    private int qualityIndex = 1; // Start at 70

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_stream);

        surfaceView = (SurfaceView) findViewById(R.id.surface_view);
        statusText = (TextView) findViewById(R.id.status_text);

        frameBuffer = new FrameBuffer();
        cameraManager = new CameraManager(frameBuffer);
        httpServer = new MjpegHttpServer(SERVER_PORT, frameBuffer);

        surfaceView.getHolder().addCallback(this);

        acquireLocks();

        statusHandler = new Handler();
        statusUpdater = new Runnable() {
            @Override
            public void run() {
                updateStatus();
                statusHandler.postDelayed(this, 1000);
            }
        };
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        cameraManager.start(holder);
        httpServer.start();
        statusHandler.post(statusUpdater);
        Log.i(TAG, "Camera and server started");
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // No action needed
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        statusHandler.removeCallbacks(statusUpdater);
        cameraManager.stop();
        httpServer.stop();
        Log.i(TAG, "Camera and server stopped");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseLocks();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_CAMERA) {
            // Cycle JPEG quality
            qualityIndex = (qualityIndex + 1) % QUALITY_LEVELS.length;
            cameraManager.setJpegQuality(QUALITY_LEVELS[qualityIndex]);
            updateStatus();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        // Swipe down to exit (Glass touchpad)
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    private void updateStatus() {
        String ip = getWifiIpAddress();
        String status = String.format(Locale.US,
                "http://%s:%d/stream  |  %s  Q:%d  %.1f fps  %d clients",
                ip, SERVER_PORT,
                cameraManager.getResolution(),
                cameraManager.getJpegQuality(),
                cameraManager.getCurrentFps(),
                httpServer.getClientCount());
        statusText.setText(status);
    }

    private String getWifiIpAddress() {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) return "?.?.?.?";
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        int ip = wifiInfo.getIpAddress();
        if (ip == 0) return "no wifi";
        return String.format(Locale.US, "%d.%d.%d.%d",
                ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "glassstream:wakelock");
        wakeLock.acquire();

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "glassstream:wifilock");
        wifiLock.acquire();
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
    }
}
