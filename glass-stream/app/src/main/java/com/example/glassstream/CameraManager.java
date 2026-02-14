package com.example.glassstream;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.util.Log;
import android.view.SurfaceHolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Manages Camera API v1: opens camera, configures preview, delivers JPEG frames to FrameBuffer.
 */
@SuppressWarnings("deprecation")
public class CameraManager implements Camera.PreviewCallback {

    private static final String TAG = "CameraManager";
    private static final int NUM_BUFFERS = 3;

    private Camera camera;
    private final FrameBuffer frameBuffer;
    private volatile boolean running;
    private int previewWidth;
    private int previewHeight;
    private int jpegQuality = 70;
    private final ByteArrayOutputStream jpegStream = new ByteArrayOutputStream();
    private long frameCount;
    private long fpsStartTime;
    private float currentFps;

    public CameraManager(FrameBuffer frameBuffer) {
        this.frameBuffer = frameBuffer;
    }

    public void start(SurfaceHolder holder) {
        try {
            camera = Camera.open();
            Camera.Parameters params = camera.getParameters();

            // Select best preview size (prefer 1280x720, fall back to largest available)
            Camera.Size bestSize = selectPreviewSize(params.getSupportedPreviewSizes());
            previewWidth = bestSize.width;
            previewHeight = bestSize.height;
            params.setPreviewSize(previewWidth, previewHeight);
            params.setPreviewFormat(ImageFormat.NV21);

            // Set focus mode if available
            List<String> focusModes = params.getSupportedFocusModes();
            if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }

            camera.setParameters(params);
            camera.setPreviewDisplay(holder);

            // Allocate preview callback buffers
            int bufferSize = previewWidth * previewHeight * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
            for (int i = 0; i < NUM_BUFFERS; i++) {
                camera.addCallbackBuffer(new byte[bufferSize]);
            }
            camera.setPreviewCallbackWithBuffer(this);

            running = true;
            fpsStartTime = System.currentTimeMillis();
            frameCount = 0;
            camera.startPreview();

            Log.i(TAG, "Camera started: " + previewWidth + "x" + previewHeight);
        } catch (IOException e) {
            Log.e(TAG, "Failed to start camera", e);
        }
    }

    public void stop() {
        running = false;
        if (camera != null) {
            camera.setPreviewCallbackWithBuffer(null);
            camera.stopPreview();
            camera.release();
            camera = null;
        }
    }

    public void setJpegQuality(int quality) {
        this.jpegQuality = quality;
    }

    public int getJpegQuality() {
        return jpegQuality;
    }

    public float getCurrentFps() {
        return currentFps;
    }

    public String getResolution() {
        return previewWidth + "x" + previewHeight;
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        if (!running || data == null) {
            if (camera != null && data != null) {
                camera.addCallbackBuffer(data);
            }
            return;
        }

        // Convert NV21 to JPEG
        YuvImage yuvImage = new YuvImage(data, ImageFormat.NV21, previewWidth, previewHeight, null);
        jpegStream.reset();
        yuvImage.compressToJpeg(new Rect(0, 0, previewWidth, previewHeight), jpegQuality, jpegStream);
        byte[] jpeg = jpegStream.toByteArray();

        frameBuffer.update(jpeg);

        // Return buffer to camera
        camera.addCallbackBuffer(data);

        // FPS calculation
        frameCount++;
        long elapsed = System.currentTimeMillis() - fpsStartTime;
        if (elapsed >= 1000) {
            currentFps = frameCount * 1000f / elapsed;
            frameCount = 0;
            fpsStartTime = System.currentTimeMillis();
        }
    }

    private Camera.Size selectPreviewSize(List<Camera.Size> sizes) {
        // Prefer 1280x720
        for (Camera.Size size : sizes) {
            if (size.width == 1280 && size.height == 720) {
                return size;
            }
        }
        // Fall back to largest
        Camera.Size best = sizes.get(0);
        for (Camera.Size size : sizes) {
            if (size.width * size.height > best.width * best.height) {
                best = size;
            }
        }
        return best;
    }
}
