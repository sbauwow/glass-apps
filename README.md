# Glass Apps

A collection of Android apps and utilities for **Google Glass Explorer Edition** running AOSP 5.1.1.

All Android apps target `minSdk 19` / `targetSdk 19`, use Java 11, AGP 8.9.0, and have no AndroidX dependencies. Built with `./gradlew assembleDebug` and installed via `adb install -r`.

---

## Apps

| App | Description | Network | Companion Required |
|-----|-------------|---------|-------------------|
| [glass-display](#glass-display) | Fullscreen MJPEG stream viewer | WiFi/USB | No (connects to any MJPEG source) |
| [glass-launcher](#glass-launcher) | Custom home screen with gesture navigation | None | No |
| [glass-monitor](#glass-monitor) | Desktop screen capture → MJPEG stream | WiFi/USB | Standalone Python server |
| [glass-pomodoro](#glass-pomodoro) | Pomodoro timer (15min work / 5min break) | None | No |
| [glass-stream](#glass-stream) | Camera MJPEG streaming server | WiFi/USB | Optional - Python viewer & shell scripts |
| [glass-term](#glass-term) | Terminal emulator with SSH client & favorites | None | No |
| [glass-vnc](#glass-vnc) | VNC remote desktop viewer with zoom modes | WiFi/USB | No (connects to any VNC server) |
| [vesc-glass](#vesc-glass) | Electric skateboard telemetry HUD | Bluetooth LE | No (connects to VESC BLE dongle) |
| [glass-clawd](#glass-clawd) | Voice-powered Claude AI chat client (WIP) | WiFi/USB | Yes - Python proxy server |

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

## glass-launcher

Custom home screen launcher that displays installed apps in a horizontally scrolling carousel. Replaces the stock Glass launcher with gesture-driven navigation.

**Permissions:** `RECEIVE_BOOT_COMPLETED`, `SYSTEM_ALERT_WINDOW`

### Features

- Horizontal app carousel with visual selection highlight
- Tap to launch, swipe left/right to browse, swipe down to dismiss
- Two-finger tap and long-press gesture support
- Auto-starts on boot via `BootReceiver`
- Optional floating home button overlay (`OverlayService`)
- Camera button remapping via accessibility service (`ButtonRemapService`)
- Pinned apps appear first (configurable in source)

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

- `launch-glass-claude.sh` — Opens a terminal window sized to the GLASS monitor region

---

## glass-pomodoro

Simple Pomodoro timer for Glass. 15-minute work phases and 5-minute break phases cycling indefinitely. Large countdown display with phase label (WORK / BREAK).

**Permissions:** `WAKE_LOCK`

**Controls:** Tap to pause/resume. Swipe down or long-press to exit.

No network or companion required.

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

VNC (Remote Framebuffer) viewer for Glass. Connects to any VNC server using the RFB protocol and renders the remote desktop fullscreen. Supports no-auth and VNC password authentication. Four zoom modes let you trade off between seeing the full desktop or readable detail.

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

## glass-clawd (WIP)

Voice assistant client for Claude AI on Glass. Uses a WebView chat interface with native Android speech recognition. Press the camera button, tap the touchpad, or press D-pad center to speak — your voice is transcribed and sent to Claude via a proxy server. Auto-detects when Claude responds and re-opens the mic for continuous conversation.

**Status:** Work in progress — functional but under active development.

**Permissions:** `INTERNET`, `RECORD_AUDIO`

### Companion Server (Required)

The app connects to a Python HTTP proxy server that relays messages to the Anthropic API.

```bash
cd glass-clawd/server
ANTHROPIC_API_KEY=sk-... python3 server.py --host 0.0.0.0 --port 8080
```

**Endpoints:**
- `GET /` — Chat web UI
- `GET /history` — Conversation history (JSON)
- `POST /chat` — Send a message to Claude
- `POST /clear` — Reset conversation

**Note:** The server IP is hardcoded in `MainActivity.java` as `http://192.168.0.196:8080/`. Update this to match your host machine's IP.

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
