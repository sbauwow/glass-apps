# glass-weather

Weather app for Google Glass Explorer Edition. Shows current conditions and hourly forecast using the free Open-Meteo API.

## Features

- Current temperature (large thin font), condition, wind speed, humidity
- Hourly forecast in a horizontally scrollable row
- Auto-refresh every 15 minutes
- Default location: Liberty Hill, TX (no GPS required)
- Override location via intent extras

## Usage

```bash
# Build and install
cd glass-weather
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch (uses default location)
adb shell am start -n com.glassweather/.MainActivity

# Launch with custom coordinates
adb shell am start -n com.glassweather/.MainActivity --ef lat 40.7128 --ef lon -74.0060
```

## Controls

- **Tap** — Force refresh
- **Swipe down** — Exit
- **Long press** — Exit

## API

Uses [Open-Meteo](https://open-meteo.com/) — free, no API key needed. Returns current conditions and hourly forecast with WMO weather codes.

## Notes

- Glass has outdated CA certificates, so SSL verification is bypassed for the API call
- GPS/network location providers are typically unavailable on Glass; the app falls back to a hardcoded default location (Liberty Hill, TX)
- To change the default location, edit `DEFAULT_LAT`, `DEFAULT_LON`, and `DEFAULT_LOCATION_NAME` in `MainActivity.java`
