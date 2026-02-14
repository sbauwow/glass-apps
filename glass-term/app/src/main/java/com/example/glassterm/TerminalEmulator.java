package com.example.glassterm;

import java.util.ArrayList;
import java.util.List;

public class TerminalEmulator {

    private static final int STATE_NORMAL = 0;
    private static final int STATE_ESCAPE = 1;
    private static final int STATE_CSI = 2;
    private static final int STATE_OSC = 3;

    private final ScreenBuffer screen;
    private int state = STATE_NORMAL;

    // CSI parameter accumulation
    private StringBuilder csiParams = new StringBuilder();
    private boolean csiPrivate = false;

    // Saved cursor position
    private int savedCursorRow = 0;
    private int savedCursorCol = 0;

    public TerminalEmulator(ScreenBuffer screen) {
        this.screen = screen;
    }

    public void process(byte[] data, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            processByte(data[i] & 0xFF);
        }
    }

    private void processByte(int b) {
        switch (state) {
            case STATE_NORMAL:
                processNormal(b);
                break;
            case STATE_ESCAPE:
                processEscape(b);
                break;
            case STATE_CSI:
                processCSI(b);
                break;
            case STATE_OSC:
                processOSC(b);
                break;
        }
    }

    private void processNormal(int b) {
        if (b == 0x1B) { // ESC
            state = STATE_ESCAPE;
            return;
        }

        switch (b) {
            case 0x07: // BEL - ignore
                break;
            case 0x08: // BS
                screen.backspace();
                break;
            case 0x09: // HT
                screen.tab();
                break;
            case 0x0A: // LF
            case 0x0B: // VT
            case 0x0C: // FF
                // Newline mode: implicit CR since we have no PTY
                screen.carriageReturn();
                screen.lineFeed();
                break;
            case 0x0D: // CR
                screen.carriageReturn();
                break;
            default:
                if (b >= 0x20) {
                    screen.putChar((char) b);
                }
                break;
        }
    }

    private void processEscape(int b) {
        switch (b) {
            case '[': // CSI
                state = STATE_CSI;
                csiParams.setLength(0);
                csiPrivate = false;
                break;
            case ']': // OSC
                state = STATE_OSC;
                break;
            case '7': // Save cursor
                savedCursorRow = screen.getCursorRow();
                savedCursorCol = screen.getCursorCol();
                state = STATE_NORMAL;
                break;
            case '8': // Restore cursor
                screen.setCursor(savedCursorRow, savedCursorCol);
                state = STATE_NORMAL;
                break;
            case 'D': // Index (scroll up)
                screen.lineFeed();
                state = STATE_NORMAL;
                break;
            case 'M': // Reverse index (scroll down)
                if (screen.getCursorRow() == 0) {
                    screen.scrollDown(1);
                } else {
                    screen.cursorUp(1);
                }
                state = STATE_NORMAL;
                break;
            case 'c': // Full reset
                screen.resetAttributes();
                screen.resetScrollRegion();
                screen.setCursor(0, 0);
                screen.eraseInDisplay(2);
                screen.setCursorVisible(true);
                state = STATE_NORMAL;
                break;
            case '(': // Designate G0 charset - consume next byte
            case ')': // Designate G1 charset - consume next byte
                // We need to consume one more byte, just go to normal and ignore
                state = STATE_NORMAL;
                break;
            default:
                // Unknown escape, return to normal
                state = STATE_NORMAL;
                break;
        }
    }

    private void processCSI(int b) {
        if (b == '?') {
            csiPrivate = true;
            return;
        }

        // Accumulate parameters (digits and semicolons)
        if ((b >= '0' && b <= '9') || b == ';') {
            csiParams.append((char) b);
            return;
        }

        // Final byte — execute the CSI command
        int[] params = parseParams();
        state = STATE_NORMAL;

        if (csiPrivate) {
            executeCSIPrivate(b, params);
            return;
        }

        switch (b) {
            case 'A': // Cursor up
                screen.cursorUp(Math.max(1, param(params, 0, 1)));
                break;
            case 'B': // Cursor down
                screen.cursorDown(Math.max(1, param(params, 0, 1)));
                break;
            case 'C': // Cursor forward
                screen.cursorForward(Math.max(1, param(params, 0, 1)));
                break;
            case 'D': // Cursor backward
                screen.cursorBackward(Math.max(1, param(params, 0, 1)));
                break;
            case 'H': // Cursor position
            case 'f': // Horizontal & vertical position
                screen.setCursor(
                    param(params, 0, 1) - 1,
                    param(params, 1, 1) - 1
                );
                break;
            case 'J': // Erase in display
                screen.eraseInDisplay(param(params, 0, 0));
                break;
            case 'K': // Erase in line
                screen.eraseInLine(param(params, 0, 0));
                break;
            case 'L': // Insert lines
                screen.insertLines(Math.max(1, param(params, 0, 1)));
                break;
            case 'M': // Delete lines
                screen.deleteLines(Math.max(1, param(params, 0, 1)));
                break;
            case 'G': // Cursor horizontal absolute
                screen.setCursor(screen.getCursorRow(), param(params, 0, 1) - 1);
                break;
            case 'd': // Cursor vertical absolute
                screen.setCursor(param(params, 0, 1) - 1, screen.getCursorCol());
                break;
            case 'm': // SGR
                executeSGR(params);
                break;
            case 'r': // Set scroll region
                int top = param(params, 0, 1) - 1;
                int bottom = param(params, 1, screen.getRows()) - 1;
                screen.setScrollRegion(top, bottom);
                screen.setCursor(0, 0);
                break;
            case 'P': // Delete characters
                deleteChars(Math.max(1, param(params, 0, 1)));
                break;
            case '@': // Insert characters
                insertChars(Math.max(1, param(params, 0, 1)));
                break;
            case 'X': // Erase characters
                eraseChars(Math.max(1, param(params, 0, 1)));
                break;
            case 'S': // Scroll up
                screen.scrollUp(Math.max(1, param(params, 0, 1)));
                break;
            case 'T': // Scroll down
                screen.scrollDown(Math.max(1, param(params, 0, 1)));
                break;
            case 'n': // Device status report
                // DSR 6 = cursor position report - we can't respond without PTY
                break;
            case 'c': // Device attributes - ignore
                break;
            default:
                // Unknown CSI command, ignore
                break;
        }
    }

    private void executeCSIPrivate(int b, int[] params) {
        switch (b) {
            case 'h': // Set mode
                for (int p : params) {
                    if (p == 25) screen.setCursorVisible(true);
                    // Other modes ignored
                }
                break;
            case 'l': // Reset mode
                for (int p : params) {
                    if (p == 25) screen.setCursorVisible(false);
                    // Other modes ignored
                }
                break;
            default:
                break;
        }
    }

    private void executeSGR(int[] params) {
        if (params.length == 0) {
            screen.resetAttributes();
            return;
        }

        for (int i = 0; i < params.length; i++) {
            int p = params[i];
            switch (p) {
                case 0:
                    screen.resetAttributes();
                    break;
                case 1:
                    screen.setBold(true);
                    break;
                case 4:
                    screen.setUnderline(true);
                    break;
                case 7:
                    screen.setInverse(true);
                    break;
                case 22:
                    screen.setBold(false);
                    break;
                case 24:
                    screen.setUnderline(false);
                    break;
                case 27:
                    screen.setInverse(false);
                    break;
                case 38: // Set foreground extended
                    if (i + 1 < params.length && params[i + 1] == 5 && i + 2 < params.length) {
                        screen.setFg(params[i + 2]);
                        i += 2;
                    } else if (i + 1 < params.length && params[i + 1] == 2 && i + 4 < params.length) {
                        int r = params[i + 2];
                        int g = params[i + 3];
                        int b = params[i + 4];
                        screen.setFgDirect(0xFF000000 | (r << 16) | (g << 8) | b);
                        i += 4;
                    }
                    break;
                case 48: // Set background extended
                    if (i + 1 < params.length && params[i + 1] == 5 && i + 2 < params.length) {
                        screen.setBg(params[i + 2]);
                        i += 2;
                    } else if (i + 1 < params.length && params[i + 1] == 2 && i + 4 < params.length) {
                        int r = params[i + 2];
                        int g = params[i + 3];
                        int b = params[i + 4];
                        screen.setBgDirect(0xFF000000 | (r << 16) | (g << 8) | b);
                        i += 4;
                    }
                    break;
                case 39: // Default foreground
                    screen.setFg(7);
                    break;
                case 49: // Default background
                    screen.setBg(0);
                    break;
                default:
                    if (p >= 30 && p <= 37) {
                        screen.setFg(p - 30);
                    } else if (p >= 40 && p <= 47) {
                        screen.setBg(p - 40);
                    } else if (p >= 90 && p <= 97) {
                        screen.setFg(p - 90 + 8);
                    } else if (p >= 100 && p <= 107) {
                        screen.setBg(p - 100 + 8);
                    }
                    break;
            }
        }
    }

    private void processOSC(int b) {
        // Consume until BEL or ST (ESC \)
        if (b == 0x07) { // BEL terminates OSC
            state = STATE_NORMAL;
        } else if (b == 0x1B) {
            // Could be ST (\033\\), just return to normal
            state = STATE_NORMAL;
        }
    }

    // --- Helpers for character insert/delete/erase ---

    private void deleteChars(int n) {
        int row = screen.getCursorRow();
        int col = screen.getCursorCol();
        int cols = screen.getColumns();
        for (int c = col; c < cols; c++) {
            if (c + n < cols) {
                screen.getCell(row, c).copyFrom(screen.getCell(row, c + n));
            } else {
                screen.getCell(row, c).reset();
            }
        }
    }

    private void insertChars(int n) {
        int row = screen.getCursorRow();
        int col = screen.getCursorCol();
        int cols = screen.getColumns();
        for (int c = cols - 1; c >= col; c--) {
            if (c - n >= col) {
                screen.getCell(row, c).copyFrom(screen.getCell(row, c - n));
            } else {
                screen.getCell(row, c).reset();
            }
        }
    }

    private void eraseChars(int n) {
        int row = screen.getCursorRow();
        int col = screen.getCursorCol();
        int cols = screen.getColumns();
        for (int c = col; c < col + n && c < cols; c++) {
            screen.getCell(row, c).reset();
        }
    }

    // --- Parameter parsing ---

    private int[] parseParams() {
        String s = csiParams.toString();
        if (s.isEmpty()) {
            return new int[0];
        }
        String[] parts = s.split(";", -1);
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                result[i] = 0;
            }
        }
        return result;
    }

    private int param(int[] params, int index, int defaultValue) {
        if (index < params.length && params[index] > 0) {
            return params[index];
        }
        return defaultValue;
    }
}
