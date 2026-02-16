package com.example.glassreader;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.File;

public class ReaderActivity extends Activity {

    private ReaderView readerView;
    private ReadingState readingState;
    private String filename;

    // Gesture tracking
    private float downX, downY;
    private long downTime;
    private boolean longPressHandled = false;
    private static final float SWIPE_THRESHOLD = 100f;
    private static final long LONG_PRESS_MS = 600;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        readingState = new ReadingState(this);
        readerView = new ReaderView(this);
        setContentView(readerView);

        String path = getIntent().getStringExtra("pdf_path");
        if (path == null) {
            finish();
            return;
        }

        File bookFile = new File(path);
        filename = bookFile.getName();

        readerView.setLoading("Loading " + filename + "...");

        ExtractionCallback cb = new ExtractionCallback();

        if (filename.toLowerCase().endsWith(".epub")) {
            EpubTextExtractor.extract(bookFile, cb);
        } else {
            PdfTextExtractor.extract(this, bookFile, cb);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (filename != null && readerView != null) {
            readerView.stopTeleprompter();
            readingState.savePosition(filename,
                    readerView.getCurrentPage(),
                    readerView.getScrollOffsetY(),
                    readerView.getMode());
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                downTime = event.getEventTime();
                longPressHandled = false;
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!longPressHandled && (event.getEventTime() - downTime) > LONG_PRESS_MS) {
                    float moveDist = Math.abs(event.getX() - downX) + Math.abs(event.getY() - downY);
                    if (moveDist < SWIPE_THRESHOLD) {
                        longPressHandled = true;
                        onLongPress();
                        return true;
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (longPressHandled) return true;

                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                long duration = event.getEventTime() - downTime;

                if (Math.abs(dx) > SWIPE_THRESHOLD || Math.abs(dy) > SWIPE_THRESHOLD) {
                    if (Math.abs(dx) > Math.abs(dy)) {
                        if (dx < 0) onSwipeForward();
                        else onSwipeBackward();
                    } else {
                        if (dy > 0) onSwipeDown();
                        else onSwipeUp();
                    }
                } else if (duration < 300) {
                    onTap();
                }
                return true;

            default:
                return true;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    onSwipeBackward();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    onSwipeForward();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (readerView.getMode() == ReaderView.MODE_TELEPROMPTER) {
                        readerView.adjustSpeed(0.05f);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (readerView.getMode() == ReaderView.MODE_TELEPROMPTER) {
                        readerView.adjustSpeed(-0.05f);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    onTap();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    finish();
                    return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void onSwipeForward() {
        if (readerView.getMode() == ReaderView.MODE_BOOK) {
            readerView.nextPage();
        } else {
            readerView.adjustSpeed(0.05f);
        }
    }

    private void onSwipeBackward() {
        if (readerView.getMode() == ReaderView.MODE_BOOK) {
            readerView.prevPage();
        } else {
            readerView.adjustSpeed(-0.05f);
        }
    }

    private void onSwipeDown() {
        finish();
    }

    private void onSwipeUp() {
        if (readerView.getMode() == ReaderView.MODE_TELEPROMPTER) {
            readerView.adjustSpeed(0.05f);
        }
    }

    private void onTap() {
        readerView.toggleMode();
    }

    private void onLongPress() {
        if (readerView.getMode() == ReaderView.MODE_BOOK) {
            readerView.toggleStatusBar();
        } else {
            readerView.toggleTeleprompter();
        }
    }

    private class ExtractionCallback implements PdfTextExtractor.Callback, EpubTextExtractor.Callback {
        @Override
        public void onSuccess(String text) {
            if (text.trim().isEmpty()) {
                readerView.setLoading("No extractable text");
                return;
            }
            readerView.setText(text, filename);
            int savedPage = readingState.getPage(filename);
            float savedScroll = readingState.getScrollOffset(filename);
            int savedMode = readingState.getMode(filename);
            readerView.restorePosition(savedPage, savedScroll, savedMode);
        }

        @Override
        public void onError(String message) {
            readerView.setLoading("Error: " + message);
        }

        @Override
        public void onProgress(int page, int totalPages) {
            readerView.setLoading("Extracting " + page + "/" + totalPages + "...");
        }
    }
}
