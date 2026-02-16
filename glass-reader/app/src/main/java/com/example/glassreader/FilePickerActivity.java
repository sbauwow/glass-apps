package com.example.glassreader;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FilePickerActivity extends Activity {

    private View pickerView;
    private final List<File> pdfFiles = new ArrayList<>();
    private int selectedIndex = 0;

    // Gesture tracking
    private float downX, downY;
    private static final float SWIPE_THRESHOLD = 100f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        scanForFiles();

        if (pdfFiles.size() == 1) {
            openPdf(pdfFiles.get(0));
            finish();
            return;
        }

        pickerView = new PickerView(this);
        setContentView(pickerView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        scanForFiles();
        selectedIndex = Math.min(selectedIndex, Math.max(0, pdfFiles.size() - 1));
        if (pickerView != null) pickerView.invalidate();
    }

    private void scanForFiles() {
        pdfFiles.clear();
        File dir = new File(Environment.getExternalStorageDirectory(), "glass-reader");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                Arrays.sort(files);
                for (File f : files) {
                    if (f.isFile()) {
                        String name = f.getName().toLowerCase();
                        if (name.endsWith(".pdf") || name.endsWith(".epub")) {
                            pdfFiles.add(f);
                        }
                    }
                }
            }
        }
    }

    private void openPdf(File file) {
        Intent intent = new Intent(this, ReaderActivity.class);
        intent.putExtra("pdf_path", file.getAbsolutePath());
        startActivity(intent);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                return true;

            case MotionEvent.ACTION_UP:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;

                if (Math.abs(dx) > SWIPE_THRESHOLD || Math.abs(dy) > SWIPE_THRESHOLD) {
                    if (Math.abs(dx) > Math.abs(dy)) {
                        // Swipe forward/backward to navigate list
                        if (dx < 0) selectNext();
                        else selectPrev();
                    } else if (dy > 0) {
                        finish();
                    }
                } else {
                    // Tap to open
                    if (!pdfFiles.isEmpty()) {
                        openPdf(pdfFiles.get(selectedIndex));
                    }
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
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    selectNext();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    selectPrev();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (!pdfFiles.isEmpty()) {
                        openPdf(pdfFiles.get(selectedIndex));
                    }
                    return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void selectNext() {
        if (selectedIndex < pdfFiles.size() - 1) {
            selectedIndex++;
            pickerView.invalidate();
        }
    }

    private void selectPrev() {
        if (selectedIndex > 0) {
            selectedIndex--;
            pickerView.invalidate();
        }
    }

    // -- Inner Canvas View for drawing the file picker --

    private class PickerView extends View {

        private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint itemPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bgPaint = new Paint();

        PickerView(FilePickerActivity context) {
            super(context);
            titlePaint.setTextSize(22f);
            titlePaint.setColor(0xFFFFFFFF);
            titlePaint.setTypeface(Typeface.DEFAULT_BOLD);

            itemPaint.setTextSize(18f);
            itemPaint.setColor(0xFFCCCCCC);

            hintPaint.setTextSize(14f);
            hintPaint.setColor(0xFF666666);

            bgPaint.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(0xFF000000);

            if (pdfFiles.isEmpty()) {
                drawEmpty(canvas);
                return;
            }

            // Title
            String title = "Glass Reader";
            Paint.FontMetrics tfm = titlePaint.getFontMetrics();
            float titleY = -tfm.top + 6f;
            float titleWidth = titlePaint.measureText(title);
            canvas.drawText(title, (getWidth() - titleWidth) / 2f, titleY, titlePaint);

            // Divider
            float dividerY = titleY + tfm.bottom + 4f;
            bgPaint.setColor(0xFF333333);
            canvas.drawRect(12f, dividerY, getWidth() - 12f, dividerY + 1f, bgPaint);

            // File list
            Paint.FontMetrics ifm = itemPaint.getFontMetrics();
            float itemHeight = -ifm.top + ifm.bottom + 10f;
            float itemBaseline = -ifm.top;
            float listStartY = dividerY + 8f;

            // Scroll to keep selection visible
            float hintReserve = 24f;
            float visibleHeight = getHeight() - listStartY - hintReserve;
            float listScrollOffset = 0f;
            float selectedTop = selectedIndex * itemHeight;
            if (selectedTop + itemHeight > visibleHeight) {
                listScrollOffset = selectedTop + itemHeight - visibleHeight;
            }

            for (int i = 0; i < pdfFiles.size(); i++) {
                float y = listStartY + i * itemHeight - listScrollOffset;
                if (y + itemHeight < listStartY || y > getHeight() - hintReserve) continue;

                if (i == selectedIndex) {
                    bgPaint.setColor(0xFF1A3A5C);
                    canvas.drawRect(8f, y, getWidth() - 8f, y + itemHeight, bgPaint);
                    itemPaint.setColor(0xFFFFFFFF);
                } else {
                    itemPaint.setColor(0xFFAAAAAA);
                }

                String name = pdfFiles.get(i).getName();
                int dot = name.lastIndexOf('.');
                if (dot > 0) name = name.substring(0, dot);
                canvas.drawText(name, 16f, y + itemBaseline + 5f, itemPaint);
            }

            // Bottom hint
            String hint = "Swipe to select \u2022 Tap to open";
            float hintWidth = hintPaint.measureText(hint);
            canvas.drawText(hint, (getWidth() - hintWidth) / 2f, getHeight() - 6f, hintPaint);
        }

        private void drawEmpty(Canvas canvas) {
            String msg1 = "No PDFs found";
            String msg2 = "Place files in /sdcard/glass-reader/";

            itemPaint.setColor(0xFF888888);
            float w1 = itemPaint.measureText(msg1);
            canvas.drawText(msg1, (getWidth() - w1) / 2f, getHeight() / 2f - 12f, itemPaint);

            hintPaint.setColor(0xFF555555);
            float w2 = hintPaint.measureText(msg2);
            canvas.drawText(msg2, (getWidth() - w2) / 2f, getHeight() / 2f + 14f, hintPaint);
        }
    }
}
