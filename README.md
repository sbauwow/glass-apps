# Glass Apps

> **Beta:** All apps in this collection are under active development. Expect bugs, breaking changes, and incomplete features. Use at your own risk.

A collection of Android apps and utilities for **Google Glass Explorer Edition** running AOSP 5.1.1.

All Android apps target `minSdk 19` / `targetSdk 19`, use Java 11, AGP 8.9.0, and have no AndroidX dependencies. Built with `./gradlew assembleDebug` and installed via `adb install -r`.

---

## Apps

| App | Description | Network | Companion Required |
|-----|-------------|---------|-------------------|
| [glass-display](#glass-display) | Fullscreen MJPEG stream viewer | WiFi/USB | No (connects to any MJPEG source) |
| [glass-kill](#glass-kill) | Kill all non-essential background processes | None | No |
| [glass-launcher](#glass-launcher) | Custom home screen with gesture navigation | None | No |
| [glass-monitor](#glass-monitor) | Desktop screen capture → MJPEG stream | WiFi/USB | Standalone Python server |
| [glass-pomodoro](#glass-pomodoro) | Pomodoro timer (15min work / 5min break) | None | No |
| [glass-stream](#glass-stream) | Camera MJPEG streaming server | WiFi/USB | Optional - Python viewer & shell scripts |
| [glass-term](#glass-term) | Terminal emulator with SSH client & favorites | None | No |
| [glass-vnc](#glass-vnc) | VNC remote desktop viewer with zoom modes | WiFi/USB | No (connects to any VNC server) |
| [glass-stocks](#glass-stocks) | StockCharts Voyeur slideshow with 3 zoom levels | WiFi/USB | No |
| [glass-weather](#glass-weather) | Current conditions & hourly forecast via Open-Meteo | WiFi/USB | No |
| [vesc-glass](#vesc-glass) | Electric skateboard telemetry HUD | Bluetooth LE | No (connects to VESC BLE dongle) |
| [glass-notify](#glass-notify) | Notification forwarder + GPS passthrough + tilt-to-wake | WiFi/USB | Yes - glass-notify-client on phone |
| [glass-clawd](#glass-clawd) | Voice-powered Claude AI chat client | WiFi/USB | Yes - Python proxy + Whisper server |
| [glass-dashboard](#glass-dashboard) | News, sports scores, and stock quotes on 3 swipeable pages | WiFi/USB | No |
| [glass-bike-hud](#glass-bike-hud) | Biking HUD with heart rate, speed, distance | Bluetooth LE | Yes - watch-bike-hud on Galaxy Watch |
| [watch-bike-hud](#watch-bike-hud) | Galaxy Watch sensor broadcaster for glass-bike-hud | Bluetooth LE | Yes - glass-bike-hud on Glass |
| [glass-flipper](#glass-flipper) | Flipper Zero screen mirror via USB OTG | USB OTG | No (direct USB CDC serial) |
| [glass-watch-input](#glass-watch-input) | BLE input bridge receiver — injects watch events as keys | Bluetooth LE | Yes — watch-input on Galaxy Watch |
| [watch-input](#watch-input) | Galaxy Watch D-pad remote control for Glass | Bluetooth LE | Yes — glass-watch-input on Glass |

---

## glass-display

Fullscreen MJPEG viewer for watching live video streams on Glass. Connects to any HTTP MJPEG source (glass-stream, glass-monitor, or any standard MJPEG server). Displays real-time FPS and connection status.

**Permissions:** `INTERNET`, `WAKE_LOCK`

### Usage

```bash
# Via USB (requires adb reverse on host)
adb reverse tcp:8080 tcp:8080
adb shell am start -n com.glassdisplay/.MainActivity

# Via WiFi (pass host IP)
adb shell am start -n com.glassdisplay/.MainActivity --es host 192.168.1.100
```

**Gestures:** Swipe down, long-press, or right-click to exit. Default port is 8080.

---

## glass-kill

Quick utility that kills all non-essential background processes on Glass to free up memory. Launches, kills everything, shows results, and auto-exits after 3 seconds.

**Permissions:** `KILL_BACKGROUND_PROCESSES`

Uses both `killBackgroundProcesses()` (API) and `am force-stop` (shell) for thorough cleanup. Protects Android system, Google services, Glass system apps, and the custom launcher.

```bash
adb shell am start -n com.glasskill/.MainActivity
```

No network or companion required.

---

## glass-launcher

Custom home screen launcher that displays installed apps in a horizontally scrolling carousel. Replaces the stock Glass launcher with gesture-driven navigation.

### Features

- Horizontal app carousel with visual selection highlight
- Status bar with date/time and battery percentage
- Tap to launch, swipe left/right to browse, swipe down to dismiss
- Two-finger tap and long-press gesture support
- Camera button remapped to Home via accessibility service (`ButtonRemapService`): short press opens the camera as normal, long press returns to the launcher
- Pinned apps appear first (configurable in source)
- **System dialog navigator**: Detects system dialogs (USB permissions, install confirmations, etc.) and shows a touchpad-navigable overlay with cyan selection highlight. Swipe left/right to cycle elements, tap or camera button to click. Solves the problem of Glass's touchpad being unable to interact with standard Android dialogs.

**Permissions:** `SYSTEM_ALERT_WINDOW`, `BIND_ACCESSIBILITY_SERVICE`

No network or companion required — runs entirely on-device.

---

## glass-monitor

**Python utility (not an Android app).** Captures a region of your Linux desktop and streams it as MJPEG to Glass via HTTP. Creates a virtual 640x360 monitor using `xrandr` for proper resolution targeting.

### Requirements

```
pip install mss Pillow
```

Requires X11 with `xrandr`.

### Usage

```bash
# Default: virtual GLASS monitor at bottom-right, 30fps, quality 70
python glass_monitor.py

# Custom source region
python glass_monitor.py --region 1080,1880

# Capture modes: full, quarter (1:1), half, zoom
python glass_monitor.py --mode zoom --fps 20 --quality 80

# Skip virtual monitor creation
python glass_monitor.py --no-monitor --region X,Y
```

Streams on port 8080 by default. View on Glass with [glass-display](#glass-display).

### Companion Scripts

- `start.sh [mode] [args...]` — Clean start: kills old processes, sets up adb reverse, launches server. Default mode: zoom
- `pick-and-stream.sh [args...]` — 5-second countdown to position mouse, then captures region and starts streaming
- `pick-region.sh` — Find X,Y coordinates for `--region` without starting the server
- `launch-glass-claude.sh` — Opens a terminal window sized to the GLASS monitor region

---

## glass-pomodoro

Simple Pomodoro timer for Glass. 15-minute work phases and 5-minute break phases cycling indefinitely. Large countdown display with phase label (WORK / BREAK).

**Permissions:** `WAKE_LOCK`

**Controls:** Tap to pause/resume. Swipe down or long-press to exit.

No network or companion required.

---

## glass-stocks

StockCharts Voyeur viewer for Glass. Fetches the 10 rotating community stock charts from [StockCharts Voyeur](https://stockcharts.com/voyeur.html) and displays them as a fullscreen slideshow. Three tap-to-cycle zoom modes: Fit (full chart), Fill (center-crop), and Close-up (2.5x top-left for reading ticker symbols).

**Permissions:** `INTERNET`, `WAKE_LOCK`

### Usage

```bash
adb shell am start -n com.glassstocks/.MainActivity
```

**Controls:** Tap to cycle zoom. Swipe left/right for prev/next chart. Swipe down or long-press to exit.

Auto-cycles every 10 seconds, re-fetches all images every 60 seconds. Counter overlay shows position (e.g. `3 / 10`).

No companion required.

---

## glass-stream

Turns Glass into an MJPEG camera streaming server. Captures from the device camera and serves the feed over HTTP on port 8080. Supports multiple simultaneous viewers.

**Permissions:** `CAMERA`, `INTERNET`, `WAKE_LOCK`, `ACCESS_WIFI_STATE`, `ACCESS_NETWORK_STATE`

### Endpoints

- `http://<glass-ip>:8080/` — HTML viewer page
- `http://<glass-ip>:8080/stream` — Raw MJPEG stream
- `http://<glass-ip>:8080/snapshot` — Single JPEG frame

**On-device controls:** Camera button cycles JPEG quality (50% → 70% → 85%).

### Companion Tools (Optional)

**Python viewer** with recording support:
```bash
cd glass-stream/viewer
python glass_viewer.py <glass-ip> [port]
# Keys: r=record, s=snapshot, q=quit
```

**Shell scripts** in `glass-stream/scripts/`:
```bash
./scripts/view.sh              # View stream via ffplay
./scripts/deploy.sh            # Build, install, and launch
./scripts/record.sh            # Record stream to file
./scripts/snap.sh              # Capture single frame
```

---

## glass-term

Terminal emulator for Glass with USB keyboard support. Spawns `/system/bin/sh` and provides a VT100-compatible display with local echo, scrollback, and QWERTY-to-Dvorak keyboard remapping. Includes a bundled SSH client (Dropbear `dbclient`) with quick-connect favorites.

**Permissions:** None (SSH requires network access from the shell)

### Features

- 53x18 character grid sized for the 640x360 Glass display
- VT100/ANSI terminal emulation (cursor movement, colors, erase, scroll regions)
- 16-color and 256-color ANSI palette
- Local echo and line editing (no PTY)
- QWERTY and Dvorak keyboard layouts (Ctrl+K to toggle, persisted)
- 500-line scrollback buffer (Shift+PgUp/PgDn)
- Ctrl+C (interrupt), Ctrl+D (EOF), Ctrl+L (clear screen)
- Cursor blink, backspace, tab

### SSH Client & Favorites

A static ARM Dropbear `dbclient` binary is bundled in the APK and extracted on first launch. Five SSH favorite slots are shown in a bar at the bottom of the screen.

**Keyboard shortcuts:** Ctrl+1 through Ctrl+5 to connect to the corresponding favorite.

**Configuring favorites** via adb intent extras (persisted in SharedPreferences):

```bash
adb shell "am start -n com.example.glassterm/.TerminalActivity \
  --es ssh_fav_0 'mypc|user|192.168.0.100|22' \
  --es ssh_fav_1 'server|root|10.0.0.1|22'"
```

Format: `name|user|host|port`

**Note:** The bundled Dropbear client supports public-key authentication only. Use `dropbearkey` to generate keys or convert existing OpenSSH keys.

**Gestures:** Swipe down on Glass touchpad to exit.

**Limitation:** Without a PTY, interactive programs like `vi`, `less`, and `top` won't work. Standard commands (`ls`, `cd`, `cat`, `echo`, `ps`, `grep`, etc.) all work fine.

No companion required — SSH connects directly from Glass over WiFi.

---

## glass-vnc

VNC (Remote Framebuffer) viewer for Glass. Connects to any VNC server using the RFB protocol and renders the remote desktop fullscreen. Supports no-auth and VNC password authentication, with Zlib, Raw, and CopyRect encodings. Only requests the viewport region for efficient bandwidth on large displays. Four zoom modes let you trade off between seeing the full desktop or readable detail.

**Permissions:** `INTERNET`, `WAKE_LOCK`

### Zoom Modes

| Mode | Source Crop | Effect |
|------|-----------|--------|
| `full` | Entire desktop | Scale whole screen to 640x360 |
| `quarter` | 640x360 | 1:1 pixel crop (no scaling) |
| `half` | 960x540 | Crop scaled down to 640x360 |
| `zoom` | 1280x720 | Crop scaled down to 640x360 |

### Usage

```bash
# Basic — connect to VNC server on default port 5900
adb shell am start -n com.glassvnc/.MainActivity --es host 192.168.1.100

# With password and initial zoom mode
adb shell am start -n com.glassvnc/.MainActivity --es host 192.168.1.100 --es password secret --es mode zoom

# Custom port
adb shell am start -n com.glassvnc/.MainActivity --es host 192.168.1.100 --ei port 5901
```

**Controls:** Tap to cycle zoom mode. Swipe down, long-press, or back to exit. Connection settings and zoom mode persist across launches.

No companion required — connects directly to any standard VNC server.

---

## vesc-glass

Real-time heads-up display for electric skateboard telemetry. Connects to a VESC motor controller via Bluetooth LE (Nordic UART Service) and displays live data on Glass.

**Permissions:** `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_COARSE_LOCATION`, `WAKE_LOCK`

### Telemetry Display

- Speed (mph)
- Duty cycle (%)
- Battery percentage with auto cell-count detection
- Pack voltage
- MOSFET and motor temperatures (C/F)
- Trip distance
- Compass heading (8-point cardinal directions)

### Features

- Auto-connects to trusted VESC devices (persisted via SharedPreferences)
- Device picker for new VESCs (shows name + MAC suffix)
- Color-coded warnings: green (OK), yellow (caution), red (critical)
- 200ms polling interval
- Long-press [X] to forget a trusted device

### Board Configuration

Default parameters in `VescProtocol.java`:
```
Motor poles: 30
Wheel diameter: 280mm
Gear ratio: 1.0
Cell count: auto-detect
Cell full: 4.2V / Cell empty: 3.0V
```

No companion required — connects directly to the VESC BLE dongle.

---

## glass-notify

Phone-to-Glass notification forwarding system with GPS passthrough and tilt-to-wake. Two apps work together: a phone companion (`glass-notify-client`) captures notifications and GPS, and the Glass app (`glass-notify`) receives and displays them.

**Permissions (Glass):** `INTERNET`, `WAKE_LOCK`, `ACCESS_WIFI_STATE`, `ACCESS_FINE_LOCATION`, `ACCESS_MOCK_LOCATION`

### Features

- **Notification forwarding:** Phone notifications appear on Glass with app name, title, and body. Screen wakes for 8 seconds per notification. History view holds last 50 entries.
- **GPS passthrough:** Phone GPS is forwarded to Glass as a mock location provider. Any app using `LocationManager.GPS_PROVIDER` (e.g. glass-weather) automatically gets real coordinates.
- **Tilt-to-wake:** Accelerometer-based head-tilt detection wakes the Glass screen when you look up. 300ms sustain threshold, 2-second cooldown, rejects motion and sideways tilt.

### Protocol

Length-prefixed JSON over TCP port 9876:
```
Notification: {"type":"notification","app":"Gmail","title":"New mail","text":"...","time":123}
Location:     {"type":"location","lat":35.08,"lon":-106.65,"alt":1600,"acc":10,"time":123}
Heartbeat:    {}
```

### Setup

1. Install `glass-notify` on Glass, `glass-notify-client` on phone
2. Enable mock locations on Glass (one-time):
   ```bash
   adb shell settings put secure mock_location 1
   ```
3. Launch glass-notify on Glass — note the IP:port shown on screen
4. Launch glass-notify-client on phone, enter Glass IP, tap Start
5. Grant notification access and location permissions when prompted

---

## glass-clawd

Voice assistant client for Claude AI on Glass. Records audio locally via `AudioRecord` (16kHz 16-bit mono), sends the WAV to a companion server which transcribes with `faster-whisper` and forwards the text to Claude — returning both the transcription and response in a single round-trip. Auto-restarts recording after each response for continuous conversation.

**Permissions:** `INTERNET`, `RECORD_AUDIO`

**Controls:** Tap or camera button to start/stop recording. Swipe up/down to scroll chat.

### Companion Server (Required)

```bash
cd glass-clawd/server
python3 -m venv venv && source venv/bin/activate
pip install -r requirements.txt
echo 'ANTHROPIC_API_KEY=sk-ant-...' > .env && chmod 600 .env
python server.py                    # default: base Whisper model
python server.py --model small      # or: tiny, base, small, medium
```

**Endpoints:**
- `GET /` — Chat web UI
- `GET /history` — Conversation history (JSON)
- `POST /chat` — Text input → `{"reply": "..."}`
- `POST /voice` — Multipart WAV audio → `{"transcription": "...", "reply": "..."}`
- `POST /clear` — Reset conversation

**Note:** The server IP is hardcoded in `MainActivity.java`. Update it to match your host machine's IP.

---

## glass-dashboard

Information dashboard for Glass with three swipeable pages: news headlines, live sports scores, and stock quotes. All data comes from free, no-API-key sources.

**Permissions:** `INTERNET`, `WAKE_LOCK`

### Pages

| Page | Source | Refresh |
|------|--------|---------|
| News | Google News RSS (via feed2json) | 15 min |
| Sports | ESPN hidden API (NFL, NBA, MLB, NHL) | 5 min |
| Stocks | Yahoo Finance quotes | 5 min |

### Usage

```bash
# Launch with default stock watchlist (AAPL, MSFT, GOOGL, AMZN, TSLA, META, NVDA, SPY, QQQ, DIA)
adb shell am start -n com.glassdashboard/.MainActivity

# Custom stock watchlist (persisted across launches)
adb shell am start -n com.glassdashboard/.MainActivity --es symbols "AAPL,GME,BTC-USD"
```

**Controls:** Swipe left/right to switch pages. Tap to refresh current page. Swipe down, long-press, or back to exit.

No companion required.

---

## glass-weather

Current weather conditions and hourly forecast for Glass. Uses the free [Open-Meteo](https://open-meteo.com/) API — no API key needed. Displays large current temperature, condition, wind speed, humidity, and a horizontally scrollable hourly forecast.

**Permissions:** `INTERNET`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `WAKE_LOCK`

### Usage

```bash
# Launch with default location (Albuquerque, NM)
adb shell am start -n com.glassweather/.MainActivity

# Set location by city name (resolved via geocoding, persisted across launches)
adb shell am start -n com.glassweather/.MainActivity --es city "Denver, CO"

# Launch with custom coordinates
adb shell am start -n com.glassweather/.MainActivity --ef lat 40.7128 --ef lon -74.0060
```

**Controls:** Tap to refresh. Swipe down or long-press to exit.

**Location priority:** City name intent → lat/lon intent → saved location (SharedPreferences) → GPS/network → default (Albuquerque, NM). City names are resolved via the [Open-Meteo geocoding API](https://open-meteo.com/en/docs/geocoding-api) and persisted, so subsequent launches remember the location.

Auto-refreshes every 15 minutes. When [glass-notify](#glass-notify) is running with GPS passthrough, glass-weather automatically uses the phone's real location.

No companion required (but benefits from glass-notify GPS passthrough).

---

## glass-bike-hud

Biking heads-up display that receives heart rate, GPS speed, distance, and elapsed time from a Galaxy Watch over Bluetooth LE. The watch runs a companion app ([watch-bike-hud](#watch-bike-hud)) that acts as a BLE GATT server, streaming sensor data in real-time. Glass acts as a receive-only BLE GATT client — no polling, the watch pushes all data via notifications.

**Permissions:** `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_COARSE_LOCATION`, `WAKE_LOCK`

### HUD Layout (640x360)

```
┌──────────────────────────────────────┐
│  14.2 mph                      [X]  │
│  mph                                │
│              ♥ 142                   │
│               bpm                   │
│  3.42 mi   00:45:12   CONNECTED     │
└──────────────────────────────────────┘
```

- **Center:** Heart rate (hero metric, 72sp bold, color-coded by HR zone)
- **Top-left:** Speed in mph (56sp bold, color-coded)
- **Bottom-left:** Trip distance (miles)
- **Bottom-center:** Elapsed ride time (HH:MM:SS)
- **Bottom-right:** BLE connection status
- **Top-right:** [X] close button

### Color Thresholds

| Metric | Green | Yellow | Red |
|--------|-------|--------|-----|
| Heart rate | < 130 bpm | 130–160 bpm | > 160 bpm |
| Speed | — (white) | > 20 mph | > 30 mph |

### BLE Protocol

Custom GATT service `0000ff10-...` with three notify characteristics:

| Characteristic | UUID | Format |
|----------------|------|--------|
| Heart Rate | `0000ff11-...` | 1 byte: bpm (uint8) |
| Location | `0000ff12-...` | 24 bytes: lat(f64) + lon(f64) + speed_mps(f32) + bearing(f32) |
| Trip | `0000ff13-...` | 8 bytes: distance_m(f32) + elapsed_s(uint32) |

### Features

- Auto-discovers watches advertising the bike HUD service UUID
- Trusted device persistence (auto-reconnects across app restarts)
- Single device auto-trust, picker UI for multiple watches
- Queued CCCD descriptor writes for reliable characteristic subscription
- Auto-reconnect on disconnect (rescan after 2s)

### Controls

| Input | Action |
|-------|--------|
| Tap | Reconnect (stop + rescan) |
| Long-press | Exit |
| Swipe down | Exit |
| [X] tap | Exit |
| [X] long-press | Forget trusted watches + rescan |
| Back / Escape | Exit |

### Usage

```bash
# Build and install on Glass
cd glass-bike-hud && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires [watch-bike-hud](#watch-bike-hud) running on a Galaxy Watch.

---

## watch-bike-hud

Wear OS companion app for [glass-bike-hud](#glass-bike-hud). Runs on Galaxy Watch 4/5/6/7 as a foreground service, reading the heart rate sensor and GPS, then broadcasting all data to Glass via BLE GATT notifications. The watch acts as a BLE GATT peripheral (server); Glass connects as the client.

**Platform:** Wear OS, Kotlin, minSdk 30, targetSdk 34, Java 17

**Permissions:** `BODY_SENSORS`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_HEALTH`, `WAKE_LOCK`, `POST_NOTIFICATIONS`

### Architecture

```
WatchBikeActivity (UI: start/stop, current stats)
  └── BikeSensorService (Foreground Service)
        ├── SensorManager → TYPE_HEART_RATE
        ├── FusedLocationProviderClient → GPS (1s interval, HIGH_ACCURACY)
        ├── DistanceTracker → accumulates GPS distance with jitter filter
        └── BleGattServer → advertise + notify connected clients
```

### Sensor Data

- **Heart rate:** Android `TYPE_HEART_RATE` sensor, filters unreliable readings, pushes via BLE on each change
- **GPS:** Google Play Services FusedLocation, 1s interval / 500ms fastest, pushes lat/lon/speed/bearing
- **Distance:** Accumulates `Location.distanceTo()` with jitter filter — rejects < 2m (noise) and > 100m (teleports), ignores points with > 20m accuracy
- **Elapsed time:** Counts from service start, updates every 1s

### BLE GATT Server

Advertises custom service UUID `0000ff10-...` with `LOW_LATENCY` mode and `HIGH` TX power. Supports multiple concurrent Glass connections. All three characteristics are notify-only with proper CCCD descriptor handling.

### Watch UI

Simple dark screen showing current HR, GPS status, BLE connection count, distance, elapsed time, and a START/STOP button to control the foreground service.

### Usage

```bash
# Build
cd watch-bike-hud && ./gradlew assembleDebug

# Install via ADB over WiFi to watch
adb connect <watch-ip>:5555
adb -s <watch-ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

1. Launch on watch, grant all permissions (body sensors, location, BLE, notifications)
2. Tap **START** — foreground notification appears, BLE advertising begins
3. Launch glass-bike-hud on Glass — auto-discovers and connects
4. Tap **STOP** to end the session

### Dependencies

- `com.google.android.gms:play-services-location:21.0.1` (FusedLocation)
- `com.google.android.wearable:wearable:2.9.0` (compileOnly)

---

## glass-flipper

Mirrors the Flipper Zero's 128x64 monochrome OLED display onto Glass in real-time over USB OTG. Connects via USB CDC serial, starts a Protobuf RPC session, and streams screen frames at ~18fps with pixel-perfect 5x nearest-neighbor scaling.

**Permissions:** `WAKE_LOCK`, `USB_HOST`

### Usage

Plug the Flipper into Glass via USB OTG cable — the app auto-launches. Navigate the Flipper normally and its screen appears on Glass.

**First-time setup:** Glass shows a USB permission dialog that can't be tapped via touchpad. Use ADB over WiFi to approve it remotely:

```bash
# Enable WiFi ADB while Glass is on USB
adb tcpip 5555
adb connect $(adb shell ip addr show wlan0 | grep -oP '(?<=inet )\S+(?=/)'):.5555

# Unplug Glass, plug in Flipper via OTG, then tap OK remotely:
adb -s <glass-ip>:5555 shell input tap 148 270   # tick "Use by default"
adb -s <glass-ip>:5555 shell input tap 476 322   # tap OK
```

After checking "Use by default", future connections are automatic.

**Gestures:** Swipe down or back to exit.

No companion required — connects directly via USB.

---

## glass-watch-input

BLE input bridge for Glass. Runs as an AccessibilityService, connects to a Galaxy Watch running [watch-input](#watch-input) over Bluetooth LE, and injects received input events as system-wide key presses using the `Instrumentation` API.

**Permissions:** `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_COARSE_LOCATION`, `INJECT_EVENTS`

**Important:** Must be installed as a priv-app (`/system/priv-app/`) for `INJECT_EVENTS` permission.

### Input Mapping

| Watch Input | Glass Key Event |
|-------------|----------------|
| D-Pad Up | `DPAD_UP` |
| D-Pad Down | `DPAD_DOWN` |
| D-Pad Left | `DPAD_LEFT` |
| D-Pad Right | `DPAD_RIGHT` |
| OK (center) | `DPAD_CENTER` |
| Rotary CW | `DPAD_RIGHT` |
| Rotary CCW | `DPAD_LEFT` |
| Back / Home / Menu | Raw keycode injection |

### Setup

```bash
# Install as priv-app on Glass (requires root / remount)
adb root && adb remount
adb push app/build/outputs/apk/debug/app-debug.apk /system/priv-app/GlassWatchInput/GlassWatchInput.apk
adb reboot

# Enable the accessibility service
adb shell settings put secure enabled_accessibility_services \
  com.glasswatchinput/com.glasswatchinput.InputBridgeService
adb shell settings put secure accessibility_enabled 1
```

A setup activity is included for manual BLE scanning and device selection, but in headless mode the service auto-connects to the strongest discovered watch.

### BLE Protocol

Custom GATT service `0000ff20-...` with a single notify characteristic (`0000ff21-...`). 4-byte payloads:

| Byte | Field | Values |
|------|-------|--------|
| 0 | Type | `0x01` KEY, `0x02` GESTURE, `0x03` ROTARY |
| 1 | Value | Gesture/rotary ID or Android keycode |
| 2 | Action | `0x00` DOWN, `0x01` UP (keys only) |
| 3 | Reserved | `0x00` |

Requires [watch-input](#watch-input) running on a Galaxy Watch.

---

## watch-input

Wear OS remote control app for Glass. Displays a D-pad with directional buttons (up/down/left/right/OK) and system buttons (Back, Home, Menu). Sends all input to Glass over BLE using a GATT server. Also forwards hardware stem button presses and rotary encoder input.

**Platform:** Wear OS, Kotlin, minSdk 30, targetSdk 34, Java 17

**Permissions:** `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `WAKE_LOCK`, `POST_NOTIFICATIONS`

### UI Layout

```
        [  UP  ]
  [LEFT] [ OK ] [RIGHT]
        [ DOWN ]

  [BACK] [HOME] [MENU]
```

- **D-Pad buttons** send gesture events (mapped to DPAD keys on Glass)
- **System buttons** send key down+up pairs (BACK, HOME, MENU)
- **Rotary encoder** sends CW/CCW events
- **Stem buttons** forwarded as raw key events

### Usage

```bash
# Build
cd watch-input && ./gradlew assembleDebug

# Install on watch via WiFi ADB
adb connect <watch-ip>:5555
adb -s <watch-ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

1. Launch on watch — BLE advertising starts automatically
2. Enable glass-watch-input accessibility service on Glass
3. Glass auto-connects to the watch
4. Use the on-screen buttons to control Glass remotely

### Dependencies

- `com.google.android.wearable:wearable:2.9.0` (compileOnly)

Requires [glass-watch-input](#glass-watch-input) running on Glass.

---

## Pre-built APKs

Pre-built debug APKs for all apps are in the [`apks/`](apks/) directory.

The easiest way to install is with the interactive installer — it walks through each app with a description and lets you pick which ones to install:

```bash
./install.sh        # interactive — pick apps with y/n
./install.sh -y     # install everything without prompting
./install.sh -h     # list all apps with descriptions
```

Or install individual APKs manually:

```bash
adb install -r apks/glass-launcher.apk
```

For watch apps, install via WiFi ADB to the watch:

```bash
adb connect <watch-ip>:5555
adb -s <watch-ip>:5555 install -r apks/watch-input.apk
```

---

## Building

Each Android app is a standalone Gradle project. To build any app:

```bash
cd <app-name>
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

To install on Glass:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Build Requirements

- Android SDK with API 34 (compileSdk) and API 19 (targetSdk)
- Java 11
- Gradle (wrapper included in each project)

Set your SDK path in `local.properties`:
```
sdk.dir=/path/to/your/android-sdk
```
