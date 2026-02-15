package com.glassstocks;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.InputStream;
import java.net.URL;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class MainActivity extends Activity {

    private static final String TAG = "Stocks";
    private static final int IMAGE_COUNT = 10;
    private static final long CYCLE_INTERVAL_MS = 10000;
    private static final long REFRESH_INTERVAL_MS = 60000;
    private static final String BASE_URL = "https://stockcharts.com/voyeur/voyeur";

    private ImageView chartImage;
    private TextView counterText;
    private Handler handler;
    private PowerManager.WakeLock wakeLock;

    private final Bitmap[] bitmaps = new Bitmap[IMAGE_COUNT];
    private int currentIndex = 0;
    private boolean running = false;

    private static final int ZOOM_FIT = 0;
    private static final int ZOOM_FILL = 1;
    private static final int ZOOM_CLOSE = 2;
    private int zoomLevel = ZOOM_FIT;
    private float touchDownX, touchDownY;
    private long touchDownTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stocks);

        chartImage = (ImageView) findViewById(R.id.chart_image);
        counterText = (TextView) findViewById(R.id.counter_text);
        handler = new Handler(Looper.getMainLooper());

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, TAG + ":wl");

        fetchAllImages();
    }

    @Override
    protected void onResume() {
        super.onResume();
        running = true;
        wakeLock.acquire();
        startSlideshow();
        scheduleRefresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void fetchAllImages() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    TrustManager[] trustAll = new TrustManager[] {
                        new X509TrustManager() {
                            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                            public void checkClientTrusted(X509Certificate[] certs, String type) {}
                            public void checkServerTrusted(X509Certificate[] certs, String type) {}
                        }
                    };
                    SSLContext sc = SSLContext.getInstance("TLS");
                    sc.init(null, trustAll, new java.security.SecureRandom());

                    long timestamp = System.currentTimeMillis();
                    for (int i = 0; i < IMAGE_COUNT; i++) {
                        String urlStr = BASE_URL + (i + 1) + ".png?rnd=" + timestamp;
                        URL url = new URL(urlStr);
                        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
                        conn.setSSLSocketFactory(sc.getSocketFactory());
                        conn.setConnectTimeout(10000);
                        conn.setReadTimeout(10000);

                        try {
                            InputStream in = conn.getInputStream();
                            Bitmap bmp = BitmapFactory.decodeStream(in);
                            in.close();
                            if (bmp != null) {
                                bitmaps[i] = bmp;
                            }
                            Log.d(TAG, "Fetched image " + (i + 1));
                        } finally {
                            conn.disconnect();
                        }
                    }

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            showCurrentImage();
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Image fetch failed", e);
                }
            }
        }).start();
    }

    private void showCurrentImage() {
        Bitmap bmp = bitmaps[currentIndex];
        if (bmp != null) {
            chartImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
            chartImage.setImageBitmap(bmp);
            zoomLevel = ZOOM_FIT;
        }
        counterText.setText((currentIndex + 1) + " / " + IMAGE_COUNT);
    }

    private void advanceSlideshow() {
        currentIndex = (currentIndex + 1) % IMAGE_COUNT;
        showCurrentImage();
    }

    private void previousImage() {
        currentIndex = (currentIndex - 1 + IMAGE_COUNT) % IMAGE_COUNT;
        showCurrentImage();
    }

    private void nextImage() {
        currentIndex = (currentIndex + 1) % IMAGE_COUNT;
        showCurrentImage();
    }

    private final Runnable slideshowRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            advanceSlideshow();
            handler.postDelayed(this, CYCLE_INTERVAL_MS);
        }
    };

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            fetchAllImages();
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    private void startSlideshow() {
        handler.removeCallbacks(slideshowRunnable);
        handler.postDelayed(slideshowRunnable, CYCLE_INTERVAL_MS);
    }

    private void scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable);
        handler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = event.getX();
                touchDownY = event.getY();
                touchDownTime = System.currentTimeMillis();
                return true;
            case MotionEvent.ACTION_UP:
                float dx = event.getX() - touchDownX;
                float dy = event.getY() - touchDownY;
                long dt = System.currentTimeMillis() - touchDownTime;

                if (Math.abs(dy) > 100 && Math.abs(dy) > Math.abs(dx)) {
                    finish();
                    return true;
                }
                if (dt > 800) {
                    finish();
                    return true;
                }
                if (Math.abs(dx) > 50) {
                    // Swipe left/right
                    if (dx > 0) {
                        previousImage();
                    } else {
                        nextImage();
                    }
                    // Reset slideshow timer after manual navigation
                    startSlideshow();
                    return true;
                }
                if (Math.abs(dx) < 50 && Math.abs(dy) < 50) {
                    // Tap = toggle zoom
                    toggleZoom();
                    return true;
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void toggleZoom() {
        zoomLevel = (zoomLevel + 1) % 3;
        applyZoom();
    }

    private void applyZoom() {
        switch (zoomLevel) {
            case ZOOM_FIT:
                chartImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
                startSlideshow();
                break;
            case ZOOM_FILL:
                chartImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
                handler.removeCallbacks(slideshowRunnable);
                break;
            case ZOOM_CLOSE:
                // 2.5x zoom anchored top-left to read the symbol
                Bitmap bmp = bitmaps[currentIndex];
                if (bmp != null) {
                    float scale = 2.5f;
                    Matrix m = new Matrix();
                    m.setScale(scale, scale);
                    chartImage.setScaleType(ImageView.ScaleType.MATRIX);
                    chartImage.setImageMatrix(m);
                }
                handler.removeCallbacks(slideshowRunnable);
                break;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                finish();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
}
