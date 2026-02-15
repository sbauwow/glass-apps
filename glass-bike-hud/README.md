# glass-bike-hud

Biking heads-up display for Google Glass Explorer Edition. Receives heart rate, GPS speed, distance, and elapsed time from a Galaxy Watch over Bluetooth LE and displays them in a fullscreen HUD optimized for the 640x360 Glass prism.

The watch runs a companion app ([watch-bike-hud](../watch-bike-hud/)) that acts as a BLE GATT server, streaming sensor data in real-time via notifications. Glass acts as a receive-only BLE GATT client — no polling needed.

```
[Galaxy Watch]  --BLE GATT-->  [Google Glass]
 HR sensor                      HUD overlay
 GPS + speed
 Distance calc
```

## Build & Install

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Build config:** compileSdk 28, minSdk 19, targetSdk 22, Java 8, no AndroidX, no external dependencies.

## HUD Layout

```
┌──────────────────────────────────────┐
│  14.2 mph                      [X]  │
│  mph                                │
│              ♥ 142                   │
│               bpm                   │
│  3.42 mi   00:45:12   CONNECTED     │
└──────────────────────────────────────┘
```

| Element | Position | Size | Color |
|---------|----------|------|-------|
| Heart rate | Center (hero) | 72sp bold, sans-serif-condensed | Green/Yellow/Red by zone |
| Speed | Top-left | 56sp bold | White/Yellow/Red by threshold |
| "mph" label | Below speed | 12sp | Dim gray |
| Distance | Bottom-left | 20sp | Dim gray |
| Elapsed time | Bottom-center | 20sp | Dim gray |
| Status | Bottom-right | 14sp | Dim gray |
| [X] close | Top-right | 18sp | Dim red |

## Color Thresholds

### Heart Rate (Zone-Based)

| Zone | BPM | Color |
|------|-----|-------|
| Easy (1-2) | < 130 | Green `#00E676` |
| Tempo (3) | 130–160 | Yellow `#FFEB3B` |
| Threshold+ (4-5) | > 160 | Red `#FF1744` |

### Speed

| Range | Color |
|-------|-------|
| Normal | White |
| > 20 mph | Yellow `#FFEB3B` |
| > 30 mph | Red `#FF1744` |

## BLE Protocol

Custom GATT service with three notify-only characteristics. All values are little-endian.

| Characteristic | UUID | Size | Format | Update Rate |
|----------------|------|------|--------|-------------|
| Heart Rate | `0000ff11-0000-1000-8000-00805f9b34fb` | 1 byte | uint8 bpm | On sensor change (~1s) |
| Location | `0000ff12-0000-1000-8000-00805f9b34fb` | 24 bytes | f64 lat + f64 lon + f32 speed_mps + f32 bearing | Every 1s |
| Trip | `0000ff13-0000-1000-8000-00805f9b34fb` | 8 bytes | f32 distance_m + uint32 elapsed_s | Every 1s |

**Service UUID:** `0000ff10-0000-1000-8000-00805f9b34fb`

## BLE Connection Flow

1. **Scan** using deprecated `startLeScan()` (required for API 19)
2. **Filter** by service UUID in advertising data (parses AD type 0x06/0x07 for 128-bit UUIDs)
3. **Trusted devices** connect immediately on discovery (MAC persisted in SharedPreferences)
4. **New devices:** single device auto-trusts; multiple shows picker overlay
5. **Subscribe** to all 3 characteristics via queued CCCD descriptor writes (BLE allows one write at a time)
6. **Receive** data via `onCharacteristicChanged()` notifications — each update refreshes the HUD
7. **Reconnect** on disconnect: close GATT, rescan after 2s delay

## Controls

| Input | Action |
|-------|--------|
| Tap Glass touchpad | Reconnect (stop + rescan) |
| Long-press | Exit app |
| Swipe down | Exit app |
| Right-click / BUTTON_SECONDARY | Exit app |
| DPAD_CENTER | Reconnect |
| Back / Escape | Exit app |
| [X] tap | Exit app |
| [X] long-press | Forget all trusted watches + rescan |

## Source Files

```
app/src/main/
├── java/com/glassbikehud/
│   ├── BikeHudActivity.java    # Main activity, HUD UI, gesture handling, BLE listener
│   ├── BleManager.java         # BLE scanning, GATT client, characteristic parsing
│   └── BikeData.java           # Data model (HR, GPS, speed, distance, elapsed)
├── res/
│   ├── layout/activity_bike_hud.xml   # HUD layout (RelativeLayout, 640x360)
│   └── values/
│       ├── colors.xml          # HUD palette (green, yellow, red, white, dim, black)
│       ├── strings.xml         # App name
│       └── styles.xml          # HudTheme (fullscreen, no action bar, black background)
└── AndroidManifest.xml         # Permissions, landscape, keepScreenOn
```

## Permissions

```xml
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
```

No runtime permission prompts needed — targetSdk 22 (pre-Marshmallow).

## Companion App

Requires [watch-bike-hud](../watch-bike-hud/) running on a Galaxy Watch (4/5/6/7) to provide sensor data. Without the watch app, Glass will show the HUD with placeholder values and continuously scan for a watch.
