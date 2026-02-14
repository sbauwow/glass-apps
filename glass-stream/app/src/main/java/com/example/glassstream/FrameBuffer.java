package com.example.glassstream;

/**
 * Thread-safe holder for the latest JPEG frame.
 * One writer (camera thread) calls update(), N readers (HTTP client threads) call waitForFrame().
 */
public class FrameBuffer {

    private final Object lock = new Object();
    private byte[] frame;
    private long frameNumber;

    public void update(byte[] jpeg) {
        synchronized (lock) {
            this.frame = jpeg;
            this.frameNumber++;
            lock.notifyAll();
        }
    }

    /**
     * Blocks until a new frame is available (different from lastFrameNumber).
     * Returns the JPEG bytes, or null if interrupted or timed out.
     */
    public byte[] waitForFrame(long lastFrameNumber, long timeoutMs) {
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (frameNumber <= lastFrameNumber) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) return null;
                try {
                    lock.wait(remaining);
                } catch (InterruptedException e) {
                    return null;
                }
            }
            return frame;
        }
    }

    public long getFrameNumber() {
        synchronized (lock) {
            return frameNumber;
        }
    }

    public byte[] getLatestFrame() {
        synchronized (lock) {
            return frame;
        }
    }
}
