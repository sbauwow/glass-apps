package com.glassbikehud;

/**
 * Data model for bike telemetry received from the watch over BLE.
 */
public class BikeData {
    public int heartRate;       // bpm
    public double lat, lon;     // GPS coordinates
    public float speedMps;      // m/s from GPS
    public float bearing;       // degrees
    public float distanceM;     // trip distance in meters
    public int elapsedSec;      // trip elapsed seconds

    public float speedMph() {
        return speedMps * 2.23694f;
    }

    public float distanceMi() {
        return distanceM * 0.000621371f;
    }

    public String elapsedStr() {
        int h = elapsedSec / 3600;
        int m = (elapsedSec % 3600) / 60;
        int s = elapsedSec % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }
}
