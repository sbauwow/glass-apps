package com.example.glassterm;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Window;
import android.view.WindowManager;

public class TerminalActivity extends Activity {

    private static final String TAG = "GlassTerm";
    private static final int COLUMNS = 53;
    private static final int ROWS = 18;
    private static final String PROMPT = "$ ";

    private ScreenBuffer screen;
    private TerminalEmulator emulator;
    private ShellProcess shell;
    private TerminalView terminalView;

    // Local line buffer for echo (no PTY = no echo from shell)
    private StringBuilder lineBuffer = new StringBuilder();

    // Debounced prompt: only show after output settles
    private final Handler promptHandler = new Handler();
    private boolean waitingForOutput = false;
    private final Runnable showPrompt = new Runnable() {
        @Override
        public void run() {
            if (shell.isRunning()) {
                waitingForOutput = false;
                localEcho(PROMPT);
            }
        }
    };

    // QWERTY → Dvorak character mapping
    private static final String QWERTY =       "-=qwertyuiop[]asdfghjkl;'\\zxcvbnm,./";
    private static final String DVORAK =       "[]',.pyfgcrl/=aoeuidhtns-\\;qjkxbmwvz";
    private static final String QWERTY_SHIFT = "_+QWERTYUIOP{}ASDFGHJKL:\"|ZXCVBNM<>?";
    private static final String DVORAK_SHIFT = "{}\"<>PYFGCRL?+AOEUIDHTNS_|:QJKXBMWVZ";

    private static char qwertyToDvorak(char c) {
        int idx = QWERTY.indexOf(c);
        if (idx >= 0) return DVORAK.charAt(idx);
        idx = QWERTY_SHIFT.indexOf(c);
        if (idx >= 0) return DVORAK_SHIFT.charAt(idx);
        return c; // numbers, space, etc unchanged
    }

    // Glass touchpad swipe detection
    private float touchStartY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        screen = new ScreenBuffer(COLUMNS, ROWS);
        emulator = new TerminalEmulator(screen);

        terminalView = new TerminalView(this);
        terminalView.setScreen(screen);
        setContentView(terminalView);

        shell = new ShellProcess(emulator, terminalView, COLUMNS, ROWS);
        shell.setOutputCallback(new ShellProcess.OutputCallback() {
            @Override
            public void onOutput() {
                // Debounce: reset timer on each output chunk,
                // only show prompt after 150ms of silence
                promptHandler.removeCallbacks(showPrompt);
                promptHandler.postDelayed(showPrompt, 150);
            }
        });
        shell.start();

        terminalView.startCursorBlink();

        // Show initial prompt
        localEcho(PROMPT);
    }

    @Override
    protected void onDestroy() {
        terminalView.stopCursorBlink();
        shell.destroy();
        super.onDestroy();
    }

    /**
     * Echo text locally on screen (since shell has no PTY to echo for us).
     */
    private void localEcho(String s) {
        byte[] bytes = s.getBytes();
        emulator.process(bytes, 0, bytes.length);
        terminalView.postInvalidate();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP) {
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }

        int keyCode = event.getKeyCode();
        boolean ctrl = (event.getMetaState() & KeyEvent.META_CTRL_ON) != 0;
        boolean shift = (event.getMetaState() & KeyEvent.META_SHIFT_ON) != 0;

        // Debug bar
        String shellStatus = shell.isRunning() ? "alive" : "DEAD";
        String err = shell.getLastError();
        terminalView.setDebugKeyInfo("KEY:" + keyCode + " sh:" + shellStatus
                + (err != null ? " ERR:" + err : ""));

        // Reset scroll on any keypress (except shift+pgup/pgdn)
        if (keyCode != KeyEvent.KEYCODE_PAGE_UP && keyCode != KeyEvent.KEYCODE_PAGE_DOWN) {
            terminalView.resetScroll();
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
                localEcho("\r\n");
                promptHandler.removeCallbacks(showPrompt);
                waitingForOutput = true;
                shell.write(lineBuffer.toString() + "\n");
                lineBuffer.setLength(0);
                return true;

            case KeyEvent.KEYCODE_DEL: // Backspace
                if (lineBuffer.length() > 0) {
                    lineBuffer.deleteCharAt(lineBuffer.length() - 1);
                    // Erase character on screen: back, space, back
                    localEcho("\b \b");
                }
                return true;

            case KeyEvent.KEYCODE_TAB:
                // Just insert spaces for tab (no completion without PTY)
                localEcho("    ");
                lineBuffer.append("    ");
                return true;

            case KeyEvent.KEYCODE_ESCAPE:
                shell.write(new byte[]{0x1B});
                return true;

            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // No history without PTY, ignore
                return true;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                // No cursor movement in line buffer, ignore
                return true;

            case KeyEvent.KEYCODE_MOVE_HOME:
            case KeyEvent.KEYCODE_MOVE_END:
                return true;

            case KeyEvent.KEYCODE_PAGE_UP:
                if (shift) {
                    terminalView.scrollBack(ROWS / 2);
                }
                return true;

            case KeyEvent.KEYCODE_PAGE_DOWN:
                if (shift) {
                    terminalView.scrollForward(ROWS / 2);
                }
                return true;

            default:
                // Ctrl+C — send interrupt and start new line
                if (ctrl && keyCode == KeyEvent.KEYCODE_C) {
                    lineBuffer.setLength(0);
                    localEcho("^C\r\n" + PROMPT);
                    shell.write(new byte[]{0x03});
                    return true;
                }

                // Ctrl+D — send EOF
                if (ctrl && keyCode == KeyEvent.KEYCODE_D) {
                    shell.write(new byte[]{0x04});
                    return true;
                }

                // Ctrl+L — clear screen
                if (ctrl && keyCode == KeyEvent.KEYCODE_L) {
                    screen.eraseInDisplay(2);
                    screen.setCursor(0, 0);
                    localEcho(PROMPT + lineBuffer.toString());
                    return true;
                }

                // Other Ctrl combos — send raw
                if (ctrl && keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
                    int ctrlChar = keyCode - KeyEvent.KEYCODE_A + 1;
                    shell.write(ctrlChar);
                    return true;
                }

                // Regular character input — remap QWERTY to Dvorak
                int unicodeChar = event.getUnicodeChar(event.getMetaState());
                if (unicodeChar != 0) {
                    char mapped = qwertyToDvorak((char) unicodeChar);
                    String s = String.valueOf(mapped);
                    lineBuffer.append(s);
                    localEcho(s);
                    return true;
                }
                break;
        }

        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                touchStartY = event.getY();
                break;
            case MotionEvent.ACTION_UP:
                float deltaY = event.getY() - touchStartY;
                if (deltaY > 50) {
                    finish();
                    return true;
                }
                break;
        }
        return super.dispatchTouchEvent(event);
    }
}
