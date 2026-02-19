package com.glassmusic;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public class AudioPlayer {

    private static final String TAG = "AudioPlayer";
    private static final int QUEUE_CAPACITY = 200;

    private AudioTrack track;
    private volatile boolean paused;
    private volatile boolean running;
    private ArrayBlockingQueue<byte[]> queue;
    private Thread writerThread;
    private int bufferSize;

    public boolean configure(int sampleRate, int channels) {
        stop();

        int channelConfig = (channels == 2)
                ? AudioFormat.CHANNEL_OUT_STEREO
                : AudioFormat.CHANNEL_OUT_MONO;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;

        int minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding);
        if (minBuf == AudioTrack.ERROR_BAD_VALUE || minBuf == AudioTrack.ERROR) {
            Log.e(TAG, "Invalid buffer size: " + minBuf);
            return false;
        }

        // Large buffer for Bluetooth jitter absorption
        bufferSize = minBuf * 16;

        try {
            track = new AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    channelConfig,
                    encoding,
                    bufferSize,
                    AudioTrack.MODE_STREAM);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to create AudioTrack", e);
            return false;
        }

        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack not initialized");
            track.release();
            track = null;
            return false;
        }

        paused = false;
        running = true;
        queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

        // Must call play() before write() on API 19
        track.play();

        // Pre-fill with silence to create a jitter buffer runway (~1.4s)
        byte[] silence = new byte[bufferSize];
        track.write(silence, 0, bufferSize);
        Log.i(TAG, "Configured: " + sampleRate + "Hz " + channels
                + "ch, buffer=" + bufferSize + ", silence prefill=FULL");

        // Writer thread drains queue into AudioTrack
        writerThread = new Thread(() -> {
            Log.i(TAG, "Writer thread started");
            long totalBytes = 0;
            long lastLog = System.currentTimeMillis();
            int starveCount = 0;
            while (running) {
                try {
                    byte[] chunk = queue.poll(50, TimeUnit.MILLISECONDS);
                    if (chunk != null && !paused) {
                        track.write(chunk, 0, chunk.length);
                        totalBytes += chunk.length;
                    } else if (chunk == null) {
                        starveCount++;
                    }
                    // Log throughput every 5 seconds
                    long now = System.currentTimeMillis();
                    if (now - lastLog >= 5000) {
                        long elapsed = now - lastLog;
                        long bps = totalBytes * 1000 / elapsed;
                        Log.i(TAG, "Writer: " + bps + " bytes/sec, queue=" + queue.size()
                                + ", starves=" + starveCount);
                        totalBytes = 0;
                        starveCount = 0;
                        lastLog = now;
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
            Log.i(TAG, "Writer thread exiting");
        }, "AudioWriter");
        writerThread.start();

        return true;
    }

    /** Called from RFCOMM read thread. Non-blocking enqueue. */
    public void write(byte[] data, int length) {
        if (running && !paused) {
            byte[] copy = new byte[length];
            System.arraycopy(data, 0, copy, 0, length);
            if (!queue.offer(copy)) {
                // Queue full — drop oldest to keep latency bounded
                queue.poll();
                queue.offer(copy);
            }
        }
    }

    public void pause() {
        paused = true;
        AudioTrack t = track;
        if (t != null) {
            try { t.pause(); } catch (IllegalStateException ignored) {}
        }
        if (queue != null) queue.clear();
    }

    public void resume() {
        paused = false;
        AudioTrack t = track;
        if (t != null) {
            try { t.play(); } catch (IllegalStateException ignored) {}
        }
    }

    public boolean isPaused() {
        return paused;
    }

    public void stop() {
        running = false;
        AudioTrack t = track;
        track = null;
        paused = false;
        if (writerThread != null) {
            writerThread.interrupt();
            try { writerThread.join(1000); } catch (InterruptedException ignored) {}
            writerThread = null;
        }
        if (queue != null) {
            queue.clear();
            queue = null;
        }
        if (t != null) {
            try { t.stop(); } catch (IllegalStateException ignored) {}
            t.release();
        }
    }
}
