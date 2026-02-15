# watch-bike-hud

Wear OS companion app for [glass-bike-hud](../glass-bike-hud/). Runs on Galaxy Watch 4/5/6/7 as a foreground service, reading the heart rate sensor and GPS, then broadcasting all data to Google Glass via BLE GATT notifications.

The watch acts as a BLE GATT peripheral (server); Glass connects as the GATT client.

```
[Galaxy Watch]  --BLE GATT-->  [Google Glass]
 HR sensor                      HUD overlay
 GPS + speed
 Distance calc
```

## Build & Install

```bash
./gradlew assembleDebug

# Install via ADB over WiFi to watch
adb connect <watch-ip>:5555
adb -s <watch-ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

**Build config:** compileSdk 34, minSdk 30, targetSdk 34, Kotlin, Java 17, AGP 8.7.3.

## Architecture

```
WatchBikeActivity (UI: start/stop, current stats, BLE status)
  └── BikeSensorService (Foreground Service, type=health)
        ├── SensorManager → TYPE_HEART_RATE listener
        ├── FusedLocationProviderClient → GPS (1s interval, HIGH_ACCURACY)
        ├── DistanceTracker → accumulates GPS distance with jitter filter
        └── BleGattServer → advertise + notify connected Glass clients
```

### Data Flow

1. Heart rate sensor fires → `BleGattServer.notifyHeartRate(bpm)` → Glass gets 1-byte notification
2. GPS location callback (1s) → `DistanceTracker.addPoint()` → `notifyLocation()` + `notifyTrip()` → Glass gets location + trip notifications
3. Elapsed timer (1s) → `notifyTrip()` → Glass gets updated elapsed time

## BLE GATT Server

### Service & Characteristics

All values are little-endian. Service UUID: `0000ff10-0000-1000-8000-00805f9b34fb`

| Characteristic | UUID | Size | Format | Source |
|----------------|------|------|--------|--------|
| Heart Rate | `0000ff11-...` | 1 byte | uint8 bpm | Heart rate sensor |
| Location | `0000ff12-...` | 24 bytes | f64 lat + f64 lon + f32 speed_mps + f32 bearing | FusedLocation GPS |
| Trip | `0000ff13-...` | 8 bytes | f32 distance_m + uint32 elapsed_s | DistanceTracker + timer |

### Advertising

- Mode: `LOW_LATENCY` (fastest discovery)
- TX Power: `HIGH`
- Timeout: 0 (advertise indefinitely until `stop()`)
- Includes device name and service UUID
- Supports multiple concurrent Glass connections

## Sensor Details

### Heart Rate

- Uses Android `Sensor.TYPE_HEART_RATE` via `SensorManager`
- Sampling: `SENSOR_DELAY_NORMAL`
- Filters: skips `SENSOR_STATUS_UNRELIABLE` readings and zero values
- Pushes to BLE on each valid reading

### GPS

- Uses Google Play Services `FusedLocationProviderClient`
- Priority: `HIGH_ACCURACY`
- Interval: 1000ms, fastest interval: 500ms
- Extracts: latitude, longitude, speed (m/s), bearing (degrees)

### Distance Tracking

`DistanceTracker` accumulates GPS distance with intelligent jitter filtering:

- **Accuracy filter:** Rejects points with > 20m GPS accuracy
- **Jitter filter:** Rejects distance increments < 2m (GPS noise when stationary)
- **Teleport filter:** Rejects jumps > 100m between 1s updates (GPS glitches)
- Uses `Location.distanceTo()` for distance calculation

### Elapsed Time

- Tracks time from service start using `SystemClock.elapsedRealtime()`
- Updates every 1 second via `Handler.postDelayed()`
- Included in Trip characteristic alongside distance

## Watch UI

Simple dark screen optimized for small watch displays:

```
┌─────────────────────┐
│   142 bpm           │  green, 32sp bold
│  GPS: OK | BLE: 1   │  gray, 12sp
│  3.42 mi  00:45:12  │  white, 14sp
│     [ STOP ]        │  button
└─────────────────────┘
```

- Heart rate display (green `#00E676`, 32sp bold)
- GPS fix status and BLE connection count
- Distance (miles) and elapsed time (HH:MM:SS)
- START/STOP button to control the foreground service

## Usage

1. Install on Galaxy Watch
2. Launch the app
3. Grant all permissions when prompted:
   - Body sensors (heart rate)
   - Location (GPS)
   - Bluetooth (advertising + connections)
   - Notifications (foreground service)
4. Tap **START** — foreground notification appears, BLE advertising begins
5. Launch [glass-bike-hud](../glass-bike-hud/) on Glass — it auto-discovers and connects
6. Go for a ride
7. Tap **STOP** when done

## Source Files

```
app/src/main/
├── java/com/watchbikehud/
│   ├── WatchBikeActivity.kt    # Watch UI, permission requests, service binding
│   ├── BikeSensorService.kt    # Foreground service: HR + GPS + BLE + timer
│   ├── BleGattServer.kt        # GATT server: advertise, characteristics, notify
│   └── DistanceTracker.kt      # GPS distance accumulator with jitter filter
├── res/
│   ├── layout/activity_watch_bike.xml   # Watch layout (dark, centered)
│   └── values/strings.xml               # App name
└── AndroidManifest.xml                  # Permissions, service, activity
```

## Permissions

```xml
<uses-permission android:name="android.permission.BODY_SENSORS" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_HEALTH" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

Runtime permission requests handled in `WatchBikeActivity` on launch.

## Dependencies

- `com.google.android.gms:play-services-location:21.0.1` — FusedLocationProviderClient for GPS
- `com.google.android.wearable:wearable:2.9.0` (compileOnly) — Wear OS library

## Companion App

Requires [glass-bike-hud](../glass-bike-hud/) on Google Glass to display the HUD. The watch app works independently as a data broadcaster — it will advertise and collect sensor data even without a connected Glass client.
