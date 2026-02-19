package com.glassmusic;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

public class AudioPlayer {

    private static final String TAG = "AudioPlayer";
    private static final int BUFFER_MULTIPLIER = 4;

    private AudioTrack track;
    private int bufferSize;
    private int bytesWritten;
    private volatile boolean paused;
    private volatile boolean started;

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

        bufferSize = minBuf * BUFFER_MULTIPLIER;

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
        started = false;
        bytesWritten = 0;
        Log.i(TAG, "Configured: " + sampleRate + "Hz " + channels + "ch, buffer=" + bufferSize);
        return true;
    }

    public void write(byte[] data, int length) {
        AudioTrack t = track;
        if (t != null && !paused) {
            t.write(data, 0, length);
            if (!started) {
                bytesWritten += length;
                // Start playback once we've pre-filled the buffer
                if (bytesWritten >= bufferSize) {
                    t.play();
                    started = true;
                    Log.i(TAG, "Playback started after " + bytesWritten + " bytes pre-filled");
                }
            }
        }
    }

    public void pause() {
        paused = true;
        AudioTrack t = track;
        if (t != null) {
            try {
                t.pause();
            } catch (IllegalStateException e) {
                Log.w(TAG, "Pause failed", e);
            }
        }
    }

    public void resume() {
        paused = false;
        AudioTrack t = track;
        if (t != null) {
            try {
                t.play();
            } catch (IllegalStateException e) {
                Log.w(TAG, "Resume failed", e);
            }
        }
    }

    public boolean isPaused() {
        return paused;
    }

    public void stop() {
        AudioTrack t = track;
        track = null;
        paused = false;
        started = false;
        if (t != null) {
            try {
                t.stop();
            } catch (IllegalStateException ignored) {}
            t.release();
        }
    }
}
