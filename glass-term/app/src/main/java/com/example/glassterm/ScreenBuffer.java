package com.example.glassterm;

import java.util.ArrayList;
import java.util.List;

public class ScreenBuffer {

    public static class Cell {
        public char ch;
        public int fg;
        public int bg;
        public boolean bold;
        public boolean underline;
        public boolean inverse;

        public Cell() {
            reset();
        }

        public void reset() {
            ch = ' ';
            fg = 7;  // white
            bg = 0;  // black
            bold = false;
            underline = false;
            inverse = false;
        }

        public void copyFrom(Cell other) {
            ch = other.ch;
            fg = other.fg;
            bg = other.bg;
            bold = other.bold;
            underline = other.underline;
            inverse = other.inverse;
        }
    }

    // Standard 16-color ANSI palette (0xAARRGGBB)
    public static final int[] PALETTE = {
        0xFF000000, // 0 black
        0xFFAA0000, // 1 red
        0xFF00AA00, // 2 green
        0xFFAA5500, // 3 yellow/brown
        0xFF0000AA, // 4 blue
        0xFFAA00AA, // 5 magenta
        0xFF00AAAA, // 6 cyan
        0xFFAAAAAA, // 7 white
        0xFF555555, // 8 bright black
        0xFFFF5555, // 9 bright red
        0xFF55FF55, // 10 bright green
        0xFFFFFF55, // 11 bright yellow
        0xFF5555FF, // 12 bright blue
        0xFFFF55FF, // 13 bright magenta
        0xFF55FFFF, // 14 bright cyan
        0xFFFFFFFF, // 15 bright white
    };

    private final int columns;
    private final int rows;
    private Cell[][] grid;

    private int cursorRow;
    private int cursorCol;
    private boolean cursorVisible = true;

    // Current text attributes
    private int currentFg = 7;
    private int currentBg = 0;
    private boolean currentBold = false;
    private boolean currentUnderline = false;
    private boolean currentInverse = false;

    // Scroll region (inclusive, 0-indexed)
    private int scrollTop;
    private int scrollBottom;

    // Scrollback
    private static final int MAX_SCROLLBACK = 500;
    private final List<Cell[]> scrollback = new ArrayList<Cell[]>();

    // 256-color support: -1 means use palette index, otherwise direct ARGB
    private int currentFgDirect = -1;
    private int currentBgDirect = -1;

    public ScreenBuffer(int columns, int rows) {
        this.columns = columns;
        this.rows = rows;
        this.scrollTop = 0;
        this.scrollBottom = rows - 1;

        grid = new Cell[rows][columns];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                grid[r][c] = new Cell();
            }
        }
    }

    public synchronized int getColumns() { return columns; }
    public synchronized int getRows() { return rows; }
    public synchronized int getCursorRow() { return cursorRow; }
    public synchronized int getCursorCol() { return cursorCol; }
    public synchronized boolean isCursorVisible() { return cursorVisible; }
    public synchronized void setCursorVisible(boolean v) { cursorVisible = v; }

    public synchronized Cell getCell(int row, int col) {
        if (row >= 0 && row < rows && col >= 0 && col < columns) {
            return grid[row][col];
        }
        return new Cell();
    }

    public synchronized int getScrollbackSize() {
        return scrollback.size();
    }

    public synchronized Cell getScrollbackCell(int line, int col) {
        if (line >= 0 && line < scrollback.size() && col >= 0 && col < columns) {
            return scrollback.get(line)[col];
        }
        return new Cell();
    }

    // --- Cursor movement ---

    public synchronized void setCursor(int row, int col) {
        cursorRow = clampRow(row);
        cursorCol = clampCol(col);
    }

    public synchronized void cursorUp(int n) {
        cursorRow = Math.max(scrollTop, cursorRow - n);
    }

    public synchronized void cursorDown(int n) {
        cursorRow = Math.min(scrollBottom, cursorRow + n);
    }

    public synchronized void cursorForward(int n) {
        cursorCol = Math.min(columns - 1, cursorCol + n);
    }

    public synchronized void cursorBackward(int n) {
        cursorCol = Math.max(0, cursorCol - n);
    }

    // --- Writing ---

    public synchronized void putChar(char ch) {
        if (cursorCol >= columns) {
            // Auto-wrap
            cursorCol = 0;
            cursorRow++;
            if (cursorRow > scrollBottom) {
                cursorRow = scrollBottom;
                scrollUp(1);
            }
        }
        Cell cell = grid[cursorRow][cursorCol];
        cell.ch = ch;
        cell.fg = currentFg;
        cell.bg = currentBg;
        cell.bold = currentBold;
        cell.underline = currentUnderline;
        cell.inverse = currentInverse;
        cursorCol++;
    }

    public synchronized void carriageReturn() {
        cursorCol = 0;
    }

    public synchronized void lineFeed() {
        if (cursorRow == scrollBottom) {
            scrollUp(1);
        } else if (cursorRow < rows - 1) {
            cursorRow++;
        }
    }

    public synchronized void backspace() {
        if (cursorCol > 0) {
            cursorCol--;
        }
    }

    public synchronized void tab() {
        int next = ((cursorCol / 8) + 1) * 8;
        cursorCol = Math.min(next, columns - 1);
    }

    // --- Scroll region ---

    public synchronized void setScrollRegion(int top, int bottom) {
        if (top >= 0 && bottom < rows && top <= bottom) {
            scrollTop = top;
            scrollBottom = bottom;
        }
    }

    public synchronized void resetScrollRegion() {
        scrollTop = 0;
        scrollBottom = rows - 1;
    }

    public synchronized void scrollUp(int n) {
        for (int i = 0; i < n; i++) {
            // Save top line to scrollback if scrolling the full screen
            if (scrollTop == 0) {
                Cell[] saved = new Cell[columns];
                for (int c = 0; c < columns; c++) {
                    saved[c] = new Cell();
                    saved[c].copyFrom(grid[scrollTop][c]);
                }
                scrollback.add(saved);
                if (scrollback.size() > MAX_SCROLLBACK) {
                    scrollback.remove(0);
                }
            }

            // Shift lines up within scroll region
            for (int r = scrollTop; r < scrollBottom; r++) {
                Cell[] temp = grid[r];
                grid[r] = grid[r + 1];
                grid[r + 1] = temp;
            }
            // Clear the bottom line
            clearLine(scrollBottom);
        }
    }

    public synchronized void scrollDown(int n) {
        for (int i = 0; i < n; i++) {
            // Shift lines down within scroll region
            for (int r = scrollBottom; r > scrollTop; r--) {
                Cell[] temp = grid[r];
                grid[r] = grid[r - 1];
                grid[r - 1] = temp;
            }
            clearLine(scrollTop);
        }
    }

    // --- Insert/Delete lines ---

    public synchronized void insertLines(int n) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return;
        int savedBottom = scrollBottom;
        // Temporarily adjust scroll region
        int savedTop = scrollTop;
        scrollTop = cursorRow;
        scrollDown(n);
        scrollTop = savedTop;
    }

    public synchronized void deleteLines(int n) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return;
        int savedTop = scrollTop;
        scrollTop = cursorRow;
        scrollUp(n);
        scrollTop = savedTop;
    }

    // --- Erase ---

    public synchronized void eraseInDisplay(int mode) {
        switch (mode) {
            case 0: // cursor to end
                clearRange(cursorRow, cursorCol, rows - 1, columns - 1);
                break;
            case 1: // start to cursor
                clearRange(0, 0, cursorRow, cursorCol);
                break;
            case 2: // entire display
                clearRange(0, 0, rows - 1, columns - 1);
                break;
        }
    }

    public synchronized void eraseInLine(int mode) {
        switch (mode) {
            case 0: // cursor to end of line
                for (int c = cursorCol; c < columns; c++) {
                    grid[cursorRow][c].reset();
                }
                break;
            case 1: // start of line to cursor
                for (int c = 0; c <= cursorCol && c < columns; c++) {
                    grid[cursorRow][c].reset();
                }
                break;
            case 2: // entire line
                clearLine(cursorRow);
                break;
        }
    }

    // --- SGR (text attributes) ---

    public synchronized void setFg(int colorIndex) {
        currentFg = colorIndex;
        currentFgDirect = -1;
    }

    public synchronized void setBg(int colorIndex) {
        currentBg = colorIndex;
        currentBgDirect = -1;
    }

    public synchronized void setFgDirect(int argb) {
        currentFgDirect = argb;
    }

    public synchronized void setBgDirect(int argb) {
        currentBgDirect = argb;
    }

    public synchronized void setBold(boolean v) { currentBold = v; }
    public synchronized void setUnderline(boolean v) { currentUnderline = v; }
    public synchronized void setInverse(boolean v) { currentInverse = v; }

    public synchronized void resetAttributes() {
        currentFg = 7;
        currentBg = 0;
        currentBold = false;
        currentUnderline = false;
        currentInverse = false;
        currentFgDirect = -1;
        currentBgDirect = -1;
    }

    /**
     * Resolve a cell's foreground color to an ARGB int.
     */
    public int resolveFg(Cell cell) {
        if (cell.inverse) {
            return resolveBgColor(cell);
        }
        return resolveFgColor(cell);
    }

    /**
     * Resolve a cell's background color to an ARGB int.
     */
    public int resolveBg(Cell cell) {
        if (cell.inverse) {
            return resolveFgColor(cell);
        }
        return resolveBgColor(cell);
    }

    private int resolveFgColor(Cell cell) {
        int idx = cell.fg;
        if (cell.bold && idx < 8) {
            idx += 8; // Bold brightens normal colors
        }
        if (idx >= 0 && idx < 16) {
            return PALETTE[idx];
        }
        if (idx >= 16 && idx < 256) {
            return color256(idx);
        }
        return PALETTE[7];
    }

    private int resolveBgColor(Cell cell) {
        int idx = cell.bg;
        if (idx >= 0 && idx < 16) {
            return PALETTE[idx];
        }
        if (idx >= 16 && idx < 256) {
            return color256(idx);
        }
        return PALETTE[0];
    }

    /**
     * Convert 256-color index to ARGB.
     */
    private int color256(int idx) {
        if (idx < 16) {
            return PALETTE[idx];
        } else if (idx < 232) {
            // 6x6x6 color cube
            int v = idx - 16;
            int b = (v % 6) * 51;
            int g = ((v / 6) % 6) * 51;
            int r = (v / 36) * 51;
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        } else {
            // Grayscale ramp
            int gray = 8 + (idx - 232) * 10;
            return 0xFF000000 | (gray << 16) | (gray << 8) | gray;
        }
    }

    // --- Private helpers ---

    private void clearLine(int row) {
        for (int c = 0; c < columns; c++) {
            grid[row][c].reset();
        }
    }

    private void clearRange(int r1, int c1, int r2, int c2) {
        for (int r = r1; r <= r2 && r < rows; r++) {
            int startC = (r == r1) ? c1 : 0;
            int endC = (r == r2) ? c2 : columns - 1;
            for (int c = startC; c <= endC && c < columns; c++) {
                grid[r][c].reset();
            }
        }
    }

    private int clampRow(int r) {
        return Math.max(0, Math.min(rows - 1, r));
    }

    private int clampCol(int c) {
        return Math.max(0, Math.min(columns - 1, c));
    }
}
