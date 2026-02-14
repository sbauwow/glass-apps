package com.vescglass;

/**
 * Parsed VESC telemetry from COMM_GET_VALUES response.
 * All values are converted to human-readable units.
 */
public class VescData {
    public double tempMos;        // °C (controller/FET)
    public double tempMotor;      // °C
    public double tempBatt;       // °C (from mos2/mos3 sensor if wired, else -1)
    public double currentMotor;   // A
    public double currentIn;      // A
    public double dutyCycle;      // 0.0 – 1.0
    public int    rpm;
    public double voltage;        // V
    public double ampHours;       // Ah
    public double ampHoursCharged;
    public double wattHours;      // Wh
    public double wattHoursCharged;
    public int    tachometer;     // ERPM counts
    public int    tachometerAbs;
    public int    faultCode;

    // --- Derived values (set by caller after config) ---

    public double speedMph;
    public double batteryPct;     // 0 – 100
    public double tripMiles;

    /** Absolute duty cycle as 0–100 percentage. */
    public double dutyPct() {
        return Math.abs(dutyCycle) * 100.0;
    }
}
