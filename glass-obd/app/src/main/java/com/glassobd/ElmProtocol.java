package com.glassobd;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * ELM327 OBD2 protocol handler.
 *
 * Sends AT init commands, polls PIDs, parses responses.
 * All I/O is blocking — call from a background thread.
 */
public class ElmProtocol {

    private static final String TAG = "ElmProto";
    private static final long CMD_TIMEOUT_MS = 3000;

    private final InputStream in;
    private final OutputStream out;

    // Supported PID bitmasks
    private long supportedPids00; // PIDs 0x01-0x20
    private long supportedPids20; // PIDs 0x21-0x40
    private long supportedPids40; // PIDs 0x41-0x60

    public ElmProtocol(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    /**
     * Run ELM327 init sequence. Returns true if device responds.
     */
    public boolean init() throws IOException {
        // Reset — may take up to 2s
        String atz = sendCommand("ATZ", 4000);
        if (atz == null || !atz.contains("ELM")) {
            Log.w(TAG, "ATZ did not return ELM identifier: " + atz);
        }
        Log.i(TAG, "ATZ: " + atz);

        sendCommand("ATE0", CMD_TIMEOUT_MS);   // echo off
        sendCommand("ATL0", CMD_TIMEOUT_MS);   // linefeeds off
        sendCommand("ATS0", CMD_TIMEOUT_MS);   // spaces off
        sendCommand("ATSP0", CMD_TIMEOUT_MS);  // auto protocol detect

        // Query supported PIDs (3 ranges)
        String pids00 = sendCommand("0100", 5000);
        supportedPids00 = parseSupportedPids(pids00);
        Log.i(TAG, "Supported PIDs 01-20: " + Long.toHexString(supportedPids00));

        if (isPidSupported(0x20)) {
            String pids20 = sendCommand("0120", 5000);
            supportedPids20 = parseSupportedPids(pids20);
            Log.i(TAG, "Supported PIDs 21-40: " + Long.toHexString(supportedPids20));
        }

        if (isPidSupported(0x40)) {
            String pids40 = sendCommand("0140", 5000);
            supportedPids40 = parseSupportedPids(pids40);
            Log.i(TAG, "Supported PIDs 41-60: " + Long.toHexString(supportedPids40));
        }

        return true;
    }

    /** True if any PIDs were reported as supported (i.e. ECU is responding). */
    public boolean hasEcu() {
        return supportedPids00 != 0;
    }

    /** Re-query supported PIDs (e.g. when ignition was off during init). */
    public boolean refreshSupportedPids() throws IOException {
        String pids00 = sendCommand("0100", 5000);
        supportedPids00 = parseSupportedPids(pids00);
        if (supportedPids00 == 0) return false;

        Log.i(TAG, "Refreshed PIDs 01-20: " + Long.toHexString(supportedPids00));

        if (isPidSupported(0x20)) {
            String pids20 = sendCommand("0120", 5000);
            supportedPids20 = parseSupportedPids(pids20);
            Log.i(TAG, "Refreshed PIDs 21-40: " + Long.toHexString(supportedPids20));
        }
        if (isPidSupported(0x40)) {
            String pids40 = sendCommand("0140", 5000);
            supportedPids40 = parseSupportedPids(pids40);
            Log.i(TAG, "Refreshed PIDs 41-60: " + Long.toHexString(supportedPids40));
        }
        return true;
    }

    /**
     * Poll all supported PIDs and return populated ObdData.
     */
    public ObdData poll() throws IOException {
        ObdData d = new ObdData();

        // --- Drive page PIDs ---

        // PID 0C: Engine RPM
        if (isPidSupported(0x0C)) {
            int[] b = queryPid(0x0C);
            if (b != null && b.length >= 2) {
                d.rpm = ((b[0] << 8) | b[1]) / 4;
            }
        }

        // PID 0D: Vehicle speed
        if (isPidSupported(0x0D)) {
            int[] b = queryPid(0x0D);
            if (b != null && b.length >= 1) {
                d.speedKmh = b[0];
                d.speedMph = d.speedKmh * 0.621371;
            }
        }

        // PID 05: Coolant temperature
        if (isPidSupported(0x05)) {
            int[] b = queryPid(0x05);
            if (b != null && b.length >= 1) {
                d.coolantTempC = b[0] - 40;
                d.coolantTempF = d.coolantTempC * 9 / 5 + 32;
            }
        }

        // PID 04: Engine load
        if (isPidSupported(0x04)) {
            int[] b = queryPid(0x04);
            if (b != null && b.length >= 1) {
                d.engineLoad = b[0] * 100 / 255;
            }
        }

        // PID 11: Throttle position
        if (isPidSupported(0x11)) {
            int[] b = queryPid(0x11);
            if (b != null && b.length >= 1) {
                d.throttlePos = b[0] * 100 / 255;
            }
        }

        // PID 42: Control module voltage
        if (isPidSupported(0x42)) {
            int[] b = queryPid(0x42);
            if (b != null && b.length >= 2) {
                d.voltage = ((b[0] << 8) | b[1]) / 1000.0;
            }
        }

        // --- Engine page PIDs ---

        // PID 0F: Intake air temperature
        if (isPidSupported(0x0F)) {
            int[] b = queryPid(0x0F);
            if (b != null && b.length >= 1) {
                d.intakeAirTempC = b[0] - 40;
                d.intakeAirTempF = d.intakeAirTempC * 9 / 5 + 32;
            }
        }

        // PID 0E: Timing advance
        if (isPidSupported(0x0E)) {
            int[] b = queryPid(0x0E);
            if (b != null && b.length >= 1) {
                d.timingAdvance = b[0] / 2.0 - 64;
            }
        }

        // PID 10: MAF air flow rate
        if (isPidSupported(0x10)) {
            int[] b = queryPid(0x10);
            if (b != null && b.length >= 2) {
                d.mafRate = ((b[0] << 8) | b[1]) / 100.0;
            }
        }

        // --- Fuel page PIDs ---

        // PID 2F: Fuel tank level
        if (isPidSupported(0x2F)) {
            int[] b = queryPid(0x2F);
            if (b != null && b.length >= 1) {
                d.fuelLevel = b[0] * 100 / 255;
            }
        }

        // PID 5E: Engine fuel rate
        if (isPidSupported(0x5E)) {
            int[] b = queryPid(0x5E);
            if (b != null && b.length >= 2) {
                d.fuelRateLph = ((b[0] << 8) | b[1]) / 20.0;
            }
        }

        // PID 0A: Fuel pressure (gauge)
        if (isPidSupported(0x0A)) {
            int[] b = queryPid(0x0A);
            if (b != null && b.length >= 1) {
                d.fuelPressure = b[0] * 3;
            }
        }

        // PID 06: Short-term fuel trim bank 1
        if (isPidSupported(0x06)) {
            int[] b = queryPid(0x06);
            if (b != null && b.length >= 1) {
                d.stft = b[0] / 1.28 - 100;
            }
        }

        // PID 07: Long-term fuel trim bank 1
        if (isPidSupported(0x07)) {
            int[] b = queryPid(0x07);
            if (b != null && b.length >= 1) {
                d.ltft = b[0] / 1.28 - 100;
            }
        }

        // PID 03: Fuel system status
        if (isPidSupported(0x03)) {
            int[] b = queryPid(0x03);
            if (b != null && b.length >= 1) {
                d.fuelSystem = decodeFuelSystem(b[0]);
            }
        }

        // --- Diagnostics page PIDs ---

        // PID 01: Monitor status (MIL + DTC count)
        if (isPidSupported(0x01)) {
            int[] b = queryPid(0x01);
            if (b != null && b.length >= 1) {
                d.milOn = (b[0] & 0x80) != 0;
                d.dtcCount = b[0] & 0x7F;
            }
        }

        // PID 1F: Run time since engine start
        if (isPidSupported(0x1F)) {
            int[] b = queryPid(0x1F);
            if (b != null && b.length >= 2) {
                d.runtimeSec = (b[0] << 8) | b[1];
            }
        }

        // PID 31: Distance since codes cleared
        if (isPidSupported(0x31)) {
            int[] b = queryPid(0x31);
            if (b != null && b.length >= 2) {
                d.distSinceClearedKm = (b[0] << 8) | b[1];
                d.distSinceClearedMi = d.distSinceClearedKm * 0.621371;
            }
        }

        return d;
    }

    // ---- PID query helper ----

    private int[] queryPid(int pid) throws IOException {
        String cmd = String.format("01%02X", pid);
        String r = sendCommand(cmd, CMD_TIMEOUT_MS);
        return parseResponse(r, pid);
    }

    // ---- Command I/O ----

    private String sendCommand(String cmd, long timeoutMs) throws IOException {
        while (in.available() > 0) in.read();

        out.write((cmd + "\r").getBytes());
        out.flush();

        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            if (in.available() > 0) {
                int b = in.read();
                if (b < 0) break;
                char c = (char) b;
                if (c == '>') break;
                sb.append(c);
            } else {
                try { Thread.sleep(10); } catch (InterruptedException e) { break; }
            }
        }

        String response = sb.toString().trim();
        Log.d(TAG, cmd + " -> " + response);
        return response;
    }

    // ---- Response parsing ----

    private int[] parseResponse(String raw, int expectedPid) {
        if (raw == null) return null;

        String hex = raw.replaceAll("[\\s\\r\\n]", "").toUpperCase();

        if (hex.contains("NODATA") || hex.contains("ERROR")
                || hex.contains("UNABLETOCONNECT") || hex.contains("?")
                || hex.contains("CANERROR") || hex.contains("BUSERROR")) {
            return null;
        }

        String header = String.format("41%02X", expectedPid);
        int idx = hex.indexOf(header);
        if (idx < 0) return null;

        String dataHex = hex.substring(idx + 4);
        if (dataHex.length() < 2) return null;

        int count = dataHex.length() / 2;
        int[] bytes = new int[count];
        for (int i = 0; i < count; i++) {
            try {
                bytes[i] = Integer.parseInt(dataHex.substring(i * 2, i * 2 + 2), 16);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return bytes;
    }

    // ---- Supported PID helpers ----

    private long parseSupportedPids(String raw) {
        if (raw == null) return 0;
        String hex = raw.replaceAll("[\\s\\r\\n]", "").toUpperCase();

        // Find "41XX" header where XX is 00, 20, or 40
        int idx = -1;
        for (String h : new String[]{"4100", "4120", "4140"}) {
            idx = hex.indexOf(h);
            if (idx >= 0) break;
        }
        if (idx < 0) return 0;

        String dataHex = hex.substring(idx + 4);
        if (dataHex.length() < 8) return 0;

        try {
            return Long.parseLong(dataHex.substring(0, 8), 16);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Check if a PID is supported across all three ranges.
     */
    public boolean isPidSupported(int pid) {
        if (pid >= 0x01 && pid <= 0x20) {
            return (supportedPids00 & (1L << (32 - pid))) != 0;
        } else if (pid >= 0x21 && pid <= 0x40) {
            return (supportedPids20 & (1L << (32 - (pid - 0x20)))) != 0;
        } else if (pid >= 0x41 && pid <= 0x60) {
            return (supportedPids40 & (1L << (32 - (pid - 0x40)))) != 0;
        }
        return false;
    }

    // ---- Decode helpers ----

    private static String decodeFuelSystem(int val) {
        switch (val) {
            case 1:  return "OL";
            case 2:  return "CL";
            case 4:  return "OL-D";
            case 8:  return "OL-F";
            case 16: return "CL-F";
            default: return "--";
        }
    }
}
