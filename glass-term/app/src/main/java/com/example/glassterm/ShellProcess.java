package com.example.glassterm;

import android.util.Log;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

public class ShellProcess {

    public interface OutputCallback {
        void onOutput();
    }

    private static final String TAG = "ShellProcess";

    private Process process;
    private OutputStream stdin;
    private Thread readerThread;
    private volatile boolean running = false;
    private String lastError = null;
    private OutputCallback outputCallback;

    private final TerminalEmulator emulator;
    private final View view;
    private final int columns;
    private final int rows;

    public ShellProcess(TerminalEmulator emulator, View view, int columns, int rows) {
        this.emulator = emulator;
        this.view = view;
        this.columns = columns;
        this.rows = rows;
    }

    public void setOutputCallback(OutputCallback cb) {
        this.outputCallback = cb;
    }

    public void start() {
        if (running) return;

        try {
            // Use "sh -" to force reading commands from stdin even without a TTY
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-");
            pb.redirectErrorStream(true);
            pb.directory(new java.io.File("/"));

            Map<String, String> env = pb.environment();
            env.put("TERM", "dumb");
            env.put("COLUMNS", String.valueOf(columns));
            env.put("LINES", String.valueOf(rows));
            env.put("HOME", "/data/local");
            env.put("PATH", "/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin");
            env.put("PS1", "$ ");

            process = pb.start();
            stdin = process.getOutputStream();
            running = true;
            Log.i(TAG, "Shell started successfully");

            readerThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    readLoop();
                }
            }, "shell-reader");
            readerThread.setDaemon(true);
            readerThread.start();

        } catch (IOException e) {
            Log.e(TAG, "Failed to start shell", e);
            lastError = "start failed: " + e.getMessage();
        }
    }

    public void write(byte[] data) {
        if (stdin == null) {
            lastError = "stdin is null";
            return;
        }
        if (!running) {
            lastError = "shell not running";
            return;
        }
        try {
            stdin.write(data);
            stdin.flush();
            Log.d(TAG, "Wrote " + data.length + " bytes");
        } catch (IOException e) {
            Log.e(TAG, "Write failed", e);
            lastError = "write failed: " + e.getMessage();
        }
    }

    public void write(int b) {
        write(new byte[]{(byte) b});
    }

    public void write(String s) {
        write(s.getBytes());
    }

    public void destroy() {
        running = false;
        if (process != null) {
            process.destroy();
        }
        if (readerThread != null) {
            readerThread.interrupt();
        }
    }

    public boolean isRunning() {
        return running;
    }

    public String getLastError() {
        return lastError;
    }

    private void readLoop() {
        byte[] buf = new byte[4096];
        InputStream stdout = process.getInputStream();

        try {
            while (running) {
                int n = stdout.read(buf);
                if (n == -1) break;
                emulator.process(buf, 0, n);
                view.postInvalidate();
                if (outputCallback != null) {
                    outputCallback.onOutput();
                }
            }
        } catch (IOException e) {
            if (running) {
                Log.e(TAG, "Read error", e);
                lastError = "read error: " + e.getMessage();
            }
        } finally {
            // Get exit code
            int exitCode = -1;
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException e) {
                // ignore
            }
            running = false;
            lastError = "shell exited (code " + exitCode + ")";
            Log.i(TAG, "Shell process ended, exit code: " + exitCode);
            view.postInvalidate();
        }
    }
}
