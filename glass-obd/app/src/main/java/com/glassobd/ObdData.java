package com.glassobd;

/**
 * Parsed OBD2 telemetry data.
 * All values converted to human-readable units.
 */
public class ObdData {
    // Drive page
    public int rpm;
    public int speedKmh;
    public double speedMph;
    public int coolantTempC;
    public int coolantTempF;
    public int engineLoad;        // 0–100 %
    public int throttlePos;       // 0–100 %
    public double voltage;        // V (control module voltage)

    // Engine page
    public int intakeAirTempC;
    public int intakeAirTempF;
    public double timingAdvance;  // degrees before TDC
    public double mafRate;        // g/s

    // Fuel page
    public int fuelLevel;         // 0–100 %
    public double fuelRateLph;    // L/h
    public int fuelPressure;      // kPa (gauge)
    public double stft;           // % short-term fuel trim
    public double ltft;           // % long-term fuel trim
    public String fuelSystem;     // "OL", "CL", "OL-D", "OL-F", "CL-F"

    // Diagnostics page
    public boolean milOn;
    public int dtcCount;
    public int runtimeSec;        // seconds since engine start
    public int distSinceClearedKm;
    public double distSinceClearedMi;
}
