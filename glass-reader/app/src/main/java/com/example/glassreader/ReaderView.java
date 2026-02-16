package com.example.glassreader;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.view.View;

import java.util.List;

public class ReaderView extends View {

    public static final int MODE_BOOK = 0;
    public static final int MODE_TELEPROMPTER = 1;

    private int mode = MODE_BOOK;

    // Text data
    private String rawText = "";
    private TextPaginator paginator;

    // Layout — all in raw pixels, computed in onSizeChanged
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint statusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();
    private float lineHeight;
    private float textBaseline;
    private float statusBaseline;
    private int usableWidth;
    private int usableHeight;
    private int marginLeft = 12;
    private int marginTop = 8;
    private int statusBarHeight;

    // State
    private int currentPage = 0;
    private float scrollOffsetY = 0f;
    private boolean statusBarVisible = true;
    private String filename = "";

    // Pending position restore (applied after pagination completes)
    private int pendingPage = -1;
    private float pendingScroll = -1;
    private int pendingMode = -1;

    // Teleprompter
    private boolean teleprompterRunning = false;
    private float scrollSpeed = 1.5f;
    private final Handler scrollHandler = new Handler();
    private final Runnable scrollRunnable = new Runnable() {
        @Override
        public void run() {
            if (teleprompterRunning && mode == MODE_TELEPROMPTER) {
                scrollOffsetY += scrollSpeed;
                float maxScroll = getMaxScrollOffset();
                if (scrollOffsetY >= maxScroll) {
                    scrollOffsetY = maxScroll;
                    teleprompterRunning = false;
                }
                invalidate();
                if (teleprompterRunning) {
                    scrollHandler.postDelayed(this, 16);
                }
            }
        }
    };

    // Loading state
    private boolean loading = false;
    private String loadingMessage = "Loading...";

    public ReaderView(Context context) {
        super(context);
        init();
    }

    private void init() {
        textPaint.setTypeface(Typeface.SERIF);
        textPaint.setColor(0xFFDDDDDD);
        statusPaint.setTypeface(Typeface.DEFAULT);
        statusPaint.setColor(0xFF888888);
        bgPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        // Raw pixel sizes — no dp/sp (Glass hdpi: 1dp = 1.5px)
        textPaint.setTextSize(18f);
        statusPaint.setTextSize(14f);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        lineHeight = -fm.top + fm.bottom + 2f;
        textBaseline = -fm.top;

        Paint.FontMetrics sfm = statusPaint.getFontMetrics();
        statusBarHeight = (int) (-sfm.top + sfm.bottom + 8);
        statusBaseline = -sfm.top + 4;

        computeUsableArea();
        repaginate();
    }

    private void computeUsableArea() {
        int w = getWidth();
        int h = getHeight();
        usableWidth = w - (2 * marginLeft);
        usableHeight = h - marginTop - (statusBarVisible ? statusBarHeight : 0);
    }

    private void repaginate() {
        if (rawText.length() > 0 && usableWidth > 0 && usableHeight > 0) {
            paginator = new TextPaginator();
            paginator.paginate(rawText, textPaint, usableWidth, usableHeight, lineHeight);
            applyPendingPosition();
        }
    }

    private void applyPendingPosition() {
        if (paginator == null || pendingPage < 0) return;
        currentPage = Math.max(0, Math.min(pendingPage, paginator.getPageCount() - 1));
        scrollOffsetY = Math.max(0, Math.min(pendingScroll, getMaxScrollOffset()));
        if (pendingMode == MODE_TELEPROMPTER) {
            mode = MODE_TELEPROMPTER;
        }
        pendingPage = -1;
        pendingScroll = -1;
        pendingMode = -1;
        invalidate();
    }

    // -- Public API --

    public void setText(String text, String filename) {
        this.rawText = text;
        this.filename = filename;
        this.loading = false;
        repaginate();
        invalidate();
    }

    public void setLoading(String message) {
        this.loading = true;
        this.loadingMessage = message;
        invalidate();
    }

    public void restorePosition(int page, float scrollOffset, int savedMode) {
        this.pendingPage = page;
        this.pendingScroll = scrollOffset;
        this.pendingMode = savedMode;
        applyPendingPosition();
    }

    // -- Navigation --

    public void nextPage() {
        if (paginator == null) return;
        if (currentPage < paginator.getPageCount() - 1) {
            currentPage++;
            invalidate();
        }
    }

    public void prevPage() {
        if (currentPage > 0) {
            currentPage--;
            invalidate();
        }
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public float getScrollOffsetY() {
        return scrollOffsetY;
    }

    public int getMode() {
        return mode;
    }

    // -- Teleprompter --

    public void toggleTeleprompter() {
        if (teleprompterRunning) {
            stopTeleprompter();
        } else {
            startTeleprompter();
        }
    }

    public void startTeleprompter() {
        teleprompterRunning = true;
        scrollHandler.removeCallbacks(scrollRunnable);
        scrollHandler.postDelayed(scrollRunnable, 16);
    }

    public void stopTeleprompter() {
        teleprompterRunning = false;
        scrollHandler.removeCallbacks(scrollRunnable);
    }

    public void adjustSpeed(float delta) {
        scrollSpeed = Math.max(0.5f, Math.min(scrollSpeed + delta, 5f));
    }

    public void jumpForward() {
        if (paginator == null) return;
        scrollOffsetY += paginator.getLinesPerPage() * lineHeight;
        scrollOffsetY = Math.min(scrollOffsetY, getMaxScrollOffset());
        invalidate();
    }

    public void jumpBackward() {
        if (paginator == null) return;
        scrollOffsetY -= paginator.getLinesPerPage() * lineHeight;
        scrollOffsetY = Math.max(0, scrollOffsetY);
        invalidate();
    }

    private float getMaxScrollOffset() {
        if (paginator == null) return 0;
        float totalHeight = paginator.getTotalLines() * lineHeight;
        return Math.max(0, totalHeight - usableHeight);
    }

    // -- Mode toggle --

    public void toggleMode() {
        if (mode == MODE_BOOK) {
            // Convert page to scroll offset
            if (paginator != null) {
                scrollOffsetY = currentPage * paginator.getLinesPerPage() * lineHeight;
            }
            mode = MODE_TELEPROMPTER;
        } else {
            // Convert scroll offset to page
            if (paginator != null && paginator.getLinesPerPage() > 0) {
                int lineIndex = (int) (scrollOffsetY / lineHeight);
                currentPage = lineIndex / paginator.getLinesPerPage();
                currentPage = Math.max(0, Math.min(currentPage, paginator.getPageCount() - 1));
            }
            stopTeleprompter();
            mode = MODE_BOOK;
        }
        invalidate();
    }

    public void toggleStatusBar() {
        statusBarVisible = !statusBarVisible;
        computeUsableArea();
        repaginate();
        invalidate();
    }

    // -- Drawing --

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xFF000000);

        if (loading) {
            drawCenteredText(canvas, loadingMessage);
            return;
        }

        if (paginator == null || paginator.getPageCount() == 0) {
            drawCenteredText(canvas, "No text content");
            return;
        }

        if (mode == MODE_BOOK) {
            drawBookMode(canvas);
        } else {
            drawTeleprompterMode(canvas);
        }

        if (statusBarVisible) {
            drawStatusBar(canvas);
        }
    }

    private void drawCenteredText(Canvas canvas, String text) {
        textPaint.setColor(0xFF888888);
        float textWidth = textPaint.measureText(text);
        float x = (getWidth() - textWidth) / 2f;
        float y = getHeight() / 2f;
        canvas.drawText(text, x, y, textPaint);
        textPaint.setColor(0xFFDDDDDD);
    }

    private void drawBookMode(Canvas canvas) {
        List<String> lines = paginator.getPage(currentPage);
        textPaint.setColor(0xFFDDDDDD);
        for (int i = 0; i < lines.size(); i++) {
            float y = marginTop + i * lineHeight + textBaseline;
            canvas.drawText(lines.get(i), marginLeft, y, textPaint);
        }
    }

    private void drawTeleprompterMode(Canvas canvas) {
        List<String> allLines = paginator.getWrappedLines();
        textPaint.setColor(0xFFDDDDDD);

        int maxVisibleY = statusBarVisible ? (getHeight() - statusBarHeight) : getHeight();

        int firstLine = (int) (scrollOffsetY / lineHeight);
        float offsetInLine = scrollOffsetY - firstLine * lineHeight;

        for (int i = firstLine; i < allLines.size(); i++) {
            float y = marginTop + (i - firstLine) * lineHeight - offsetInLine + textBaseline;
            if (y - textBaseline > maxVisibleY) break;
            if (y > 0) {
                canvas.drawText(allLines.get(i), marginLeft, y, textPaint);
            }
        }
    }

    private void drawStatusBar(Canvas canvas) {
        int barY = getHeight() - statusBarHeight;

        bgPaint.setColor(0xFF1A1A1A);
        canvas.drawRect(0, barY, getWidth(), getHeight(), bgPaint);

        // Divider
        bgPaint.setColor(0xFF333333);
        canvas.drawRect(0, barY, getWidth(), barY + 1, bgPaint);

        statusPaint.setColor(0xFF888888);

        // Left: filename (truncated)
        String name = filename;
        float maxNameWidth = getWidth() * 0.4f;
        while (statusPaint.measureText(name) > maxNameWidth && name.length() > 5) {
            name = name.substring(0, name.length() - 4) + "...";
        }
        canvas.drawText(name, marginLeft, barY + statusBaseline, statusPaint);

        // Center: page info
        String pageInfo;
        if (mode == MODE_BOOK) {
            pageInfo = "Page " + (currentPage + 1) + "/" + paginator.getPageCount();
        } else {
            int percent = 0;
            float maxScroll = getMaxScrollOffset();
            if (maxScroll > 0) {
                percent = (int) (scrollOffsetY / maxScroll * 100);
            }
            pageInfo = percent + "%";
        }
        float pageInfoWidth = statusPaint.measureText(pageInfo);
        canvas.drawText(pageInfo, (getWidth() - pageInfoWidth) / 2f, barY + statusBaseline, statusPaint);

        // Right: mode indicator
        String modeStr = mode == MODE_BOOK ? "BOOK"
                : (teleprompterRunning ? "SCROLL" : "PAUSED");
        float modeWidth = statusPaint.measureText(modeStr);
        canvas.drawText(modeStr, getWidth() - marginLeft - modeWidth, barY + statusBaseline, statusPaint);
    }
}
