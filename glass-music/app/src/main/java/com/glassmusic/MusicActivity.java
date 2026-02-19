package com.glassmusic;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.TextView;

public class MusicActivity extends Activity implements AudioServer.Listener {

    private static final String TAG = "MusicActivity";
    private static final int REQUEST_DISCOVERABLE = 1;
    private static final int DISCOVERABLE_DURATION = 300;

    private AudioServer server;
    private AudioPlayer player;
    private PowerManager.WakeLock wakeLock;

    // UI
    private TextView textIcon;
    private TextView textTitle;
    private TextView textArtist;
    private TextView textStatus;

    private volatile boolean playingShown;

    // Touch tracking
    private float touchStartX, touchStartY;
    private long touchStartTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_music);

        textIcon = (TextView) findViewById(R.id.text_icon);
        textTitle = (TextView) findViewById(R.id.text_title);
        textArtist = (TextView) findViewById(R.id.text_artist);
        textStatus = (TextView) findViewById(R.id.text_status);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "glassmusic:audio");

        player = new AudioPlayer();
        server = new AudioServer(this);

        requestDiscoverability();
        server.start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        server.stop();
        player.stop();
        if (wakeLock.isHeld()) wakeLock.release();
    }

    // --- Discoverability ---

    private void requestDiscoverability() {
        Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_DURATION);
        startActivityForResult(intent, REQUEST_DISCOVERABLE);
    }

    // --- AudioServer.Listener ---

    @Override
    public void onClientConnected(String deviceName, String mac) {
        playingShown = false;
        if (!wakeLock.isHeld()) wakeLock.acquire();
        runOnUiThread(() -> {
            textStatus.setText(getString(R.string.status_connected) + " - " + deviceName);
            textStatus.setTextColor(getResources().getColor(R.color.status_connected));
            textIcon.setTextColor(getResources().getColor(R.color.text_primary));
        });
    }

    @Override
    public void onClientDisconnected() {
        player.stop();
        if (wakeLock.isHeld()) wakeLock.release();
        runOnUiThread(() -> {
            textTitle.setText(R.string.app_name);
            textArtist.setText("");
            textStatus.setText(R.string.status_waiting);
            textStatus.setTextColor(getResources().getColor(R.color.status_waiting));
            textIcon.setTextColor(getResources().getColor(R.color.text_dim));
        });
    }

    @Override
    public void onConfigReceived(int sampleRate, int channels) {
        boolean ok = player.configure(sampleRate, channels);
        if (!ok) {
            runOnUiThread(() -> {
                textStatus.setText("AUDIO ERROR");
                textStatus.setTextColor(0xFFFF0000);
            });
        }
    }

    @Override
    public void onAudioChunkReceived(byte[] data, int length) {
        player.write(data, length);
        // Update UI to PLAYING once
        if (!playingShown && !player.isPaused()) {
            playingShown = true;
            runOnUiThread(() -> {
                textStatus.setText(R.string.status_playing);
                textStatus.setTextColor(getResources().getColor(R.color.status_playing));
            });
        }
    }

    @Override
    public void onMetadataReceived(String title, String artist) {
        runOnUiThread(() -> {
            if (title != null && !title.isEmpty()) {
                textTitle.setText(title);
            }
            textArtist.setText(artist != null ? artist : "");
        });
    }

    @Override
    public void onError(String error) {
        runOnUiThread(() -> {
            textStatus.setText("ERROR: " + error);
            textStatus.setTextColor(0xFFFF0000);
        });
    }

    @Override
    public void onListening(int channel) {
        runOnUiThread(() -> {
            textStatus.setText(getString(R.string.status_waiting) + " (ch " + channel + ")");
            textStatus.setTextColor(getResources().getColor(R.color.status_waiting));
        });
    }

    // --- Pause / Resume ---

    private void togglePause() {
        if (player.isPaused()) {
            player.resume();
            server.sendCommand("resume");
            textStatus.setText(R.string.status_playing);
            textStatus.setTextColor(getResources().getColor(R.color.status_playing));
        } else {
            player.pause();
            server.sendCommand("pause");
            textStatus.setText(R.string.status_paused);
            textStatus.setTextColor(getResources().getColor(R.color.status_paused));
        }
    }

    // --- Touch / gesture handling ---

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchStartX = event.getX();
                touchStartY = event.getY();
                touchStartTime = System.currentTimeMillis();
                return true;

            case MotionEvent.ACTION_UP:
                float dx = event.getX() - touchStartX;
                float dy = event.getY() - touchStartY;
                long duration = System.currentTimeMillis() - touchStartTime;

                if (dy > 100 && Math.abs(dx) < Math.abs(dy)) {
                    // Swipe down — exit
                    finish();
                } else if (duration > 800 && Math.abs(dx) < 50 && Math.abs(dy) < 50) {
                    // Long press — re-enable discoverability
                    requestDiscoverability();
                } else if (Math.abs(dx) < 50 && Math.abs(dy) < 50) {
                    // Tap — toggle pause/resume
                    if (server.isConnected()) {
                        togglePause();
                    }
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (server.isConnected()) {
                togglePause();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
