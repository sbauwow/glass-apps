package com.example.glassterm;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.View;

public class TerminalView extends View {

    private ScreenBuffer screen;
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();

    private float cellWidth;
    private float cellHeight;
    private float textBaseline;

    private boolean cursorBlinkOn = true;
    private final Handler blinkHandler = new Handler();
    private final Runnable blinkRunnable = new Runnable() {
        @Override
        public void run() {
            cursorBlinkOn = !cursorBlinkOn;
            invalidate();
            blinkHandler.postDelayed(this, 500);
        }
    };

    private int scrollOffset = 0;

    // Debug: last key event info shown on screen
    private String debugKeyInfo = "No keys yet";

    public TerminalView(Context context) {
        super(context);
        init();
    }

    public TerminalView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setColor(0xFFAAAAAA);
        bgPaint.setStyle(Paint.Style.FILL);
    }

    public void setScreen(ScreenBuffer screen) {
        this.screen = screen;
        invalidate();
    }

    public void startCursorBlink() {
        blinkHandler.removeCallbacks(blinkRunnable);
        cursorBlinkOn = true;
        blinkHandler.postDelayed(blinkRunnable, 500);
    }

    public void stopCursorBlink() {
        blinkHandler.removeCallbacks(blinkRunnable);
    }

    public int getScrollOffset() {
        return scrollOffset;
    }

    public void scrollBack(int lines) {
        if (screen == null) return;
        scrollOffset = Math.min(scrollOffset + lines, screen.getScrollbackSize());
        invalidate();
    }

    public void scrollForward(int lines) {
        scrollOffset = Math.max(0, scrollOffset - lines);
        invalidate();
    }

    public void resetScroll() {
        scrollOffset = 0;
    }

    public void setDebugKeyInfo(String info) {
        debugKeyInfo = info;
        postInvalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (screen == null) return;

        // Size font to fit 80 columns in the view width
        float targetWidth = (float) w / screen.getColumns();
        // Start with a reasonable size and measure
        float fontSize = targetWidth * 1.6f; // Monospace chars are ~0.6x font size wide
        textPaint.setTextSize(fontSize);

        // Measure actual character width and adjust
        float measuredWidth = textPaint.measureText("M");
        fontSize = fontSize * (targetWidth / measuredWidth);
        textPaint.setTextSize(fontSize);

        // Verify and compute cell dimensions
        cellWidth = textPaint.measureText("M");
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        cellHeight = (float) h / screen.getRows();
        textBaseline = -fm.top; // Distance from top of cell to baseline
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (screen == null) return;

        canvas.drawColor(0xFF000000); // Black background

        int rows = screen.getRows();
        int cols = screen.getColumns();
        char[] charBuf = new char[1];

        synchronized (screen) {
            for (int row = 0; row < rows; row++) {
                int sourceRow = row - scrollOffset;
                float y = row * cellHeight;

                for (int col = 0; col < cols; col++) {
                    float x = col * cellWidth;
                    ScreenBuffer.Cell cell;

                    if (sourceRow >= 0) {
                        cell = screen.getCell(sourceRow, col);
                    } else {
                        // Drawing from scrollback
                        int scrollbackLine = screen.getScrollbackSize() + sourceRow;
                        cell = screen.getScrollbackCell(scrollbackLine, col);
                    }

                    int bgColor = screen.resolveBg(cell);
                    int fgColor = screen.resolveFg(cell);

                    // Draw cell background if not black
                    if (bgColor != 0xFF000000) {
                        bgPaint.setColor(bgColor);
                        canvas.drawRect(x, y, x + cellWidth, y + cellHeight, bgPaint);
                    }

                    // Draw cursor
                    if (scrollOffset == 0 && sourceRow == screen.getCursorRow()
                            && col == screen.getCursorCol()
                            && screen.isCursorVisible() && cursorBlinkOn) {
                        bgPaint.setColor(0xFFAAAAAA);
                        canvas.drawRect(x, y, x + cellWidth, y + cellHeight, bgPaint);
                        fgColor = 0xFF000000;
                    }

                    // Draw character
                    if (cell.ch != ' ' && cell.ch != 0) {
                        textPaint.setColor(fgColor);
                        textPaint.setFakeBoldText(cell.bold);
                        textPaint.setUnderlineText(cell.underline);
                        charBuf[0] = cell.ch;
                        canvas.drawText(charBuf, 0, 1, x, y + textBaseline, textPaint);
                    }
                }
            }
        }

        // Debug: draw key info bar at bottom
        if (debugKeyInfo != null) {
            bgPaint.setColor(0xFF333333);
            float barY = getHeight() - cellHeight;
            canvas.drawRect(0, barY, getWidth(), getHeight(), bgPaint);
            textPaint.setColor(0xFFFFFF00);
            textPaint.setFakeBoldText(false);
            textPaint.setUnderlineText(false);
            canvas.drawText(debugKeyInfo, 4, barY + textBaseline, textPaint);
        }
    }
}
