package com.example.glassterm;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Window;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class TerminalActivity extends Activity {

    private static final String TAG = "GlassTerm";
    private static final int COLUMNS = 53;
    private static final int ROWS = 18;
    private static final String PROMPT = "$ ";
    private static final int NUM_FAVORITES = 5;
    private static final String PREFS_NAME = "ssh_favorites";

    private ScreenBuffer screen;
    private TerminalEmulator emulator;
    private ShellProcess shell;
    private TerminalView terminalView;

    // Local line buffer for echo (no PTY = no echo from shell)
    private StringBuilder lineBuffer = new StringBuilder();

    // SSH favorites
    private SshFavorite[] favorites = new SshFavorite[NUM_FAVORITES];
    private String dbclientPath;

    // Keyboard layout
    private boolean dvorakMode = false;

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

        // Extract dbclient binary from assets
        extractDbclient();

        // Load SSH favorites from SharedPreferences, then override from intent
        loadFavorites();
        parseFavoritesFromIntent(getIntent());

        // Load keyboard layout preference
        dvorakMode = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean("dvorak_mode", false);

        screen = new ScreenBuffer(COLUMNS, ROWS);
        emulator = new TerminalEmulator(screen);

        terminalView = new TerminalView(this);
        terminalView.setScreen(screen);
        terminalView.setFavorites(favorites);
        terminalView.setKeyboardLayout(dvorakMode);
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

    private void extractDbclient() {
        File dbclient = new File(getFilesDir(), "dbclient");
        dbclientPath = dbclient.getAbsolutePath();
        if (dbclient.exists()) return;
        try {
            InputStream in = getAssets().open("dbclient");
            FileOutputStream out = new FileOutputStream(dbclient);
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.close();
            in.close();
            dbclient.setExecutable(true);
            Log.i(TAG, "Extracted dbclient to " + dbclientPath);
        } catch (IOException e) {
            Log.w(TAG, "No dbclient in assets: " + e.getMessage());
        }
    }

    private void loadFavorites() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        for (int i = 0; i < NUM_FAVORITES; i++) {
            String name = prefs.getString("ssh_fav_" + i + "_name", null);
            String user = prefs.getString("ssh_fav_" + i + "_user", null);
            String host = prefs.getString("ssh_fav_" + i + "_host", null);
            int port = prefs.getInt("ssh_fav_" + i + "_port", 22);
            if (host != null && !host.isEmpty()) {
                favorites[i] = new SshFavorite(name, user, host, port);
            }
        }
    }

    private void saveFavorites() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        for (int i = 0; i < NUM_FAVORITES; i++) {
            SshFavorite fav = favorites[i];
            if (fav != null && !fav.isEmpty()) {
                editor.putString("ssh_fav_" + i + "_name", fav.name);
                editor.putString("ssh_fav_" + i + "_user", fav.user);
                editor.putString("ssh_fav_" + i + "_host", fav.host);
                editor.putInt("ssh_fav_" + i + "_port", fav.port);
            } else {
                editor.remove("ssh_fav_" + i + "_name");
                editor.remove("ssh_fav_" + i + "_user");
                editor.remove("ssh_fav_" + i + "_host");
                editor.remove("ssh_fav_" + i + "_port");
            }
        }
        editor.apply();
    }

    private void parseFavoritesFromIntent(Intent intent) {
        if (intent == null || intent.getExtras() == null) return;
        boolean changed = false;
        for (int i = 0; i < NUM_FAVORITES; i++) {
            String val = intent.getStringExtra("ssh_fav_" + i);
            if (val != null) {
                SshFavorite fav = SshFavorite.parse(val);
                if (fav != null) {
                    favorites[i] = fav;
                    changed = true;
                }
            }
        }
        if (changed) {
            saveFavorites();
            if (terminalView != null) {
                terminalView.setFavorites(favorites);
            }
        }
    }

    private void launchSshFavorite(int slot) {
        if (slot < 0 || slot >= NUM_FAVORITES) return;
        SshFavorite fav = favorites[slot];
        if (fav == null || fav.isEmpty()) {
            terminalView.flashSlot(slot, false);
            return;
        }
        terminalView.flashSlot(slot, true);

        // Clear current line buffer
        for (int i = 0; i < lineBuffer.length(); i++) {
            localEcho("\b \b");
        }
        lineBuffer.setLength(0);

        // Build and inject the command
        String cmd = dbclientPath + " -y " + fav.user + "@" + fav.host + " -p " + fav.port;
        lineBuffer.append(cmd);
        localEcho(cmd);

        // Send it
        localEcho("\r\n");
        promptHandler.removeCallbacks(showPrompt);
        waitingForOutput = true;
        shell.write(cmd + "\n");
        lineBuffer.setLength(0);
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

        // Ctrl+1 through Ctrl+5 — SSH favorite
        if (ctrl && keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_5) {
            int slot = keyCode - KeyEvent.KEYCODE_1;
            launchSshFavorite(slot);
            return true;
        }

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
                // Ctrl+K — toggle keyboard layout
                if (ctrl && keyCode == KeyEvent.KEYCODE_K) {
                    dvorakMode = !dvorakMode;
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                            .putBoolean("dvorak_mode", dvorakMode).apply();
                    terminalView.setKeyboardLayout(dvorakMode);
                    return true;
                }

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

                // Regular character input
                int unicodeChar = event.getUnicodeChar(event.getMetaState());
                if (unicodeChar != 0) {
                    char ch = (char) unicodeChar;
                    if (dvorakMode) ch = qwertyToDvorak(ch);
                    String s = String.valueOf(ch);
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
