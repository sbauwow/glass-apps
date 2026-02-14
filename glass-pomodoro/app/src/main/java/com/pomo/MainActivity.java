package com.pomo;

import android.app.Activity;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.PowerManager;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.TextView;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final long WORK_MILLIS  = 15 * 60 * 1000L;
    private static final long BREAK_MILLIS =  5 * 60 * 1000L;

    private TextView phaseLabel;
    private TextView timerText;
    private TextView instructionText;

    private CountDownTimer timer;
    private boolean isWork = true;
    private boolean isPaused = false;
    private long millisRemaining;

    private PowerManager.WakeLock wakeLock;
    private GestureDetector gestureDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pomo);

        phaseLabel = (TextView) findViewById(R.id.phase_label);
        timerText = (TextView) findViewById(R.id.timer_text);
        instructionText = (TextView) findViewById(R.id.instruction_text);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "pomo:timer");

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                togglePause();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
                // Swipe down to exit
                if (e2.getY() - e1.getY() > 100) {
                    finish();
                    return true;
                }
                return false;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                finish();
            }
        });

        startPhase(true);
    }

    private void startPhase(boolean work) {
        isWork = work;
        isPaused = false;
        long duration = work ? WORK_MILLIS : BREAK_MILLIS;
        phaseLabel.setText(work ? "WORK" : "BREAK");
        instructionText.setText("tap to pause");
        startTimer(duration);
    }

    private void startTimer(long millis) {
        if (timer != null) timer.cancel();

        timer = new CountDownTimer(millis, 100) {
            @Override
            public void onTick(long ms) {
                millisRemaining = ms;
                updateDisplay(ms);
            }

            @Override
            public void onFinish() {
                updateDisplay(0);
                startPhase(!isWork);
            }
        }.start();
    }

    private void updateDisplay(long ms) {
        int totalSec = (int) ((ms + 999) / 1000);
        int min = totalSec / 60;
        int sec = totalSec % 60;
        timerText.setText(String.format(Locale.US, "%d:%02d", min, sec));
    }

    private void togglePause() {
        if (isPaused) {
            isPaused = false;
            instructionText.setText("tap to pause");
            startTimer(millisRemaining);
        } else {
            isPaused = true;
            if (timer != null) timer.cancel();
            instructionText.setText("PAUSED");
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        wakeLock.acquire();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (wakeLock.isHeld()) wakeLock.release();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (timer != null) timer.cancel();
        if (wakeLock.isHeld()) wakeLock.release();
    }
}
