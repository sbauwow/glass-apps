package com.glassweather;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class MainActivity extends Activity {

    private static final String TAG = "GlassWeather";
    private static final long REFRESH_INTERVAL_MS = 15 * 60 * 1000; // 15 minutes

    private static final String DEFAULT_LOCATION_NAME = "Liberty Hill, TX";
    private static final double DEFAULT_LAT = 30.6644;
    private static final double DEFAULT_LON = -97.9253;

    private TextView statusText;
    private View weatherContent;
    private TextView currentTemp;
    private TextView currentCondition;
    private TextView currentDetails;
    private TextView locationName;
    private LinearLayout hourlyContainer;

    private LocationManager locationManager;
    private Handler handler;
    private double lastLat = Double.NaN;
    private double lastLon = Double.NaN;

    private float touchDownX;
    private float touchDownY;
    private long touchDownTime;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!Double.isNaN(lastLat)) {
                fetchWeather(lastLat, lastLon);
            }
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            lastLat = location.getLatitude();
            lastLon = location.getLongitude();
            fetchWeather(lastLat, lastLon);
            // Stop updates after getting a fix - we'll use the refresh timer
            locationManager.removeUpdates(this);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override
        public void onProviderEnabled(String provider) {}
        @Override
        public void onProviderDisabled(String provider) {}
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_weather);

        statusText = (TextView) findViewById(R.id.statusText);
        weatherContent = findViewById(R.id.weatherContent);
        currentTemp = (TextView) findViewById(R.id.currentTemp);
        currentCondition = (TextView) findViewById(R.id.currentCondition);
        currentDetails = (TextView) findViewById(R.id.currentDetails);
        locationName = (TextView) findViewById(R.id.locationName);
        hourlyContainer = (LinearLayout) findViewById(R.id.hourlyContainer);

        handler = new Handler(Looper.getMainLooper());
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        // Check for location passed via intent (adb shell am start --ef lat X --ef lon Y)
        double intentLat = getIntent().getFloatExtra("lat", Float.NaN);
        double intentLon = getIntent().getFloatExtra("lon", Float.NaN);
        if (!Double.isNaN(intentLat) && !Double.isNaN(intentLon)) {
            lastLat = intentLat;
            lastLon = intentLon;
            fetchWeather(lastLat, lastLon);
            handler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS);
        } else {
            requestLocation();
        }
    }

    @SuppressWarnings("MissingPermission")
    private void requestLocation() {
        statusText.setVisibility(View.VISIBLE);
        statusText.setText("Getting location...");

        // Try all providers for last known location
        Location lastKnown = null;
        String[] providers = { LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER, "fused" };

        for (String provider : providers) {
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    Location loc = locationManager.getLastKnownLocation(provider);
                    if (loc != null && (lastKnown == null || loc.getTime() > lastKnown.getTime())) {
                        lastKnown = loc;
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "Provider " + provider + " not available");
            }
        }

        if (lastKnown != null) {
            lastLat = lastKnown.getLatitude();
            lastLon = lastKnown.getLongitude();
            fetchWeather(lastLat, lastLon);
        }

        // Request fresh location updates from any available provider
        boolean subscribed = false;
        for (String provider : providers) {
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(provider, 0, 0, locationListener);
                    subscribed = true;
                    Log.d(TAG, "Subscribed to " + provider);
                    break;
                }
            } catch (Exception e) {
                Log.d(TAG, "Cannot subscribe to " + provider);
            }
        }

        if (!subscribed && lastKnown == null) {
            Log.w(TAG, "No location providers, using default location");
            lastLat = DEFAULT_LAT;
            lastLon = DEFAULT_LON;
            locationName.setText(DEFAULT_LOCATION_NAME);
            fetchWeather(lastLat, lastLon);
        }

        // Start auto-refresh timer
        handler.removeCallbacks(refreshRunnable);
        handler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS);
    }

    private void fetchWeather(final double lat, final double lon) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String urlStr = String.format(Locale.US,
                            "https://api.open-meteo.com/v1/forecast?"
                                    + "latitude=%.4f&longitude=%.4f"
                                    + "&current=temperature_2m,weathercode,windspeed_10m,relative_humidity_2m"
                                    + "&hourly=temperature_2m,weathercode"
                                    + "&forecast_days=1&temperature_unit=fahrenheit&windspeed_unit=mph",
                            lat, lon);

                    // Glass has outdated CA certs — trust all for weather API
                    TrustManager[] trustAll = new TrustManager[] {
                        new X509TrustManager() {
                            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                            public void checkClientTrusted(X509Certificate[] certs, String type) {}
                            public void checkServerTrusted(X509Certificate[] certs, String type) {}
                        }
                    };
                    SSLContext sc = SSLContext.getInstance("TLS");
                    sc.init(null, trustAll, new java.security.SecureRandom());

                    URL url = new URL(urlStr);
                    HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
                    conn.setSSLSocketFactory(sc.getSocketFactory());
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);

                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();
                    conn.disconnect();

                    final JSONObject json = new JSONObject(sb.toString());
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            updateUI(json);
                        }
                    });
                } catch (final Exception e) {
                    Log.e(TAG, "Weather fetch failed", e);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setVisibility(View.VISIBLE);
                            statusText.setText("Error: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void updateUI(JSONObject json) {
        try {
            JSONObject current = json.getJSONObject("current");
            int temp = (int) Math.round(current.getDouble("temperature_2m"));
            int code = current.getInt("weathercode");
            int wind = (int) Math.round(current.getDouble("windspeed_10m"));
            int humidity = current.getInt("relative_humidity_2m");

            currentTemp.setText(temp + "°");
            currentCondition.setText(weatherCodeToLabel(code));
            currentDetails.setText("Wind " + wind + " mph   Humidity " + humidity + "%");

            // Hourly forecast
            JSONObject hourly = json.getJSONObject("hourly");
            JSONArray times = hourly.getJSONArray("time");
            JSONArray temps = hourly.getJSONArray("temperature_2m");
            JSONArray codes = hourly.getJSONArray("weathercode");

            hourlyContainer.removeAllViews();

            // Find current hour index
            Calendar cal = Calendar.getInstance();
            int currentHour = cal.get(Calendar.HOUR_OF_DAY);

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);

            for (int i = 0; i < times.length(); i++) {
                String timeStr = times.getString(i);
                Date date = sdf.parse(timeStr);
                Calendar entryCal = Calendar.getInstance();
                entryCal.setTime(date);
                int entryHour = entryCal.get(Calendar.HOUR_OF_DAY);

                // Only show from current hour onward
                if (entryHour < currentHour) continue;

                int hTemp = (int) Math.round(temps.getDouble(i));
                int hCode = codes.getInt(i);

                addHourlyColumn(
                        i == currentHour ? "Now" : formatHour(entryHour),
                        hTemp + "°",
                        weatherCodeToShort(hCode));
            }

            statusText.setVisibility(View.GONE);
            weatherContent.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            Log.e(TAG, "Parse error", e);
            statusText.setText("Parse error");
        }
    }

    private void addHourlyColumn(String timeLabel, String tempLabel, String condLabel) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        col.setPadding(pad, 4, pad, 4);

        TextView timeView = new TextView(this);
        timeView.setText(timeLabel);
        timeView.setTextColor(0xFF999999);
        timeView.setTextSize(14);
        timeView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        timeView.setGravity(Gravity.CENTER);
        col.addView(timeView);

        TextView tempView = new TextView(this);
        tempView.setText(tempLabel);
        tempView.setTextColor(0xFFFFFFFF);
        tempView.setTextSize(20);
        tempView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        tempView.setGravity(Gravity.CENTER);
        col.addView(tempView);

        TextView condView = new TextView(this);
        condView.setText(condLabel);
        condView.setTextColor(0xFF999999);
        condView.setTextSize(12);
        condView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        condView.setGravity(Gravity.CENTER);
        col.addView(condView);

        hourlyContainer.addView(col);
    }

    private String formatHour(int hour) {
        if (hour == 0) return "12AM";
        if (hour < 12) return hour + "AM";
        if (hour == 12) return "12PM";
        return (hour - 12) + "PM";
    }

    private static String weatherCodeToLabel(int code) {
        if (code == 0) return "Clear";
        if (code <= 3) return "Cloudy";
        if (code >= 45 && code <= 48) return "Fog";
        if (code >= 51 && code <= 55) return "Drizzle";
        if (code >= 56 && code <= 57) return "Freezing Drizzle";
        if (code >= 61 && code <= 65) return "Rain";
        if (code >= 66 && code <= 67) return "Freezing Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code >= 85 && code <= 86) return "Snow Showers";
        if (code >= 95 && code <= 99) return "Thunderstorm";
        return "Unknown";
    }

    private static String weatherCodeToShort(int code) {
        if (code == 0) return "CLR";
        if (code <= 3) return "CLD";
        if (code >= 45 && code <= 48) return "FOG";
        if (code >= 51 && code <= 57) return "DRZ";
        if (code >= 61 && code <= 67) return "RN";
        if (code >= 71 && code <= 77) return "SNW";
        if (code >= 80 && code <= 82) return "SHR";
        if (code >= 85 && code <= 86) return "SSH";
        if (code >= 95 && code <= 99) return "THN";
        return "?";
    }

    // Glass touchpad gestures
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = event.getX();
                touchDownY = event.getY();
                touchDownTime = System.currentTimeMillis();
                return true;
            case MotionEvent.ACTION_UP:
                float dx = event.getX() - touchDownX;
                float dy = event.getY() - touchDownY;
                long dt = System.currentTimeMillis() - touchDownTime;

                if (Math.abs(dy) > 100 && Math.abs(dy) > Math.abs(dx)) {
                    // Swipe down = exit
                    finish();
                    return true;
                }
                if (dt > 800) {
                    // Long press = exit
                    finish();
                    return true;
                }
                if (Math.abs(dx) < 50 && Math.abs(dy) < 50) {
                    // Tap = refresh
                    if (!Double.isNaN(lastLat)) {
                        fetchWeather(lastLat, lastLon);
                    } else {
                        requestLocation();
                    }
                    return true;
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                finish();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(refreshRunnable);
        locationManager.removeUpdates(locationListener);
    }
}
