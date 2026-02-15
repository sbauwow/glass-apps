package com.glassnotify;

import android.content.Context;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;
import android.util.Log;

public class MockGPS {

    private static final String TAG = "MockGPS";
    private static final String PROVIDER = LocationManager.GPS_PROVIDER;

    private final LocationManager locationManager;
    private boolean active;

    public MockGPS(Context context) {
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    public void start() {
        if (active) return;
        try {
            locationManager.addTestProvider(PROVIDER,
                    false, false, false, false,
                    true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
            locationManager.setTestProviderEnabled(PROVIDER, true);
            active = true;
            Log.d(TAG, "Mock GPS provider registered");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register mock GPS provider: " + e.getMessage());
        }
    }

    public void publish(double lat, double lon, double alt, float accuracy, long time) {
        if (!active) return;
        try {
            Location location = new Location(PROVIDER);
            location.setLatitude(lat);
            location.setLongitude(lon);
            location.setAltitude(alt);
            location.setAccuracy(accuracy);
            location.setTime(time);
            location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            locationManager.setTestProviderLocation(PROVIDER, location);
        } catch (Exception e) {
            Log.e(TAG, "Failed to publish location: " + e.getMessage());
        }
    }

    public void stop() {
        if (!active) return;
        try {
            locationManager.setTestProviderEnabled(PROVIDER, false);
            locationManager.removeTestProvider(PROVIDER);
            Log.d(TAG, "Mock GPS provider removed");
        } catch (Exception e) {
            Log.e(TAG, "Failed to remove mock GPS provider: " + e.getMessage());
        }
        active = false;
    }
}
