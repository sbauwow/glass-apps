# Glass VNC

VNC remote desktop viewer for Google Glass Explorer Edition (640x360, AOSP 5.1.1).

Connects to any VNC server using the RFB protocol (versions 3.3, 3.7, 3.8) and renders the remote framebuffer fullscreen on Glass. Supports no-auth and VNC password authentication, with raw and copyrect encodings.

## Zoom Modes

Four zoom modes control how the remote desktop maps to the 640x360 Glass display:

| Mode | Source Crop | Effect |
|------|-----------|--------|
| `full` | Entire desktop | Scale whole screen to 640x360 |
| `quarter` | 640x360 | 1:1 pixel crop from top-left (no scaling) |
| `half` | 960x540 | Crop scaled down to 640x360 |
| `zoom` | 1280x720 | Crop scaled down to 640x360 |

Tap the touchpad to cycle through modes. The selected mode persists across launches.

## Usage

```bash
# Build
./gradlew assembleDebug

# Install
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch — basic
adb shell am start -n com.glassvnc/.MainActivity --es host 192.168.1.100

# Launch — with password and initial zoom mode
adb shell am start -n com.glassvnc/.MainActivity \
  --es host 192.168.1.100 \
  --es password secret \
  --es mode zoom

# Launch — custom port
adb shell am start -n com.glassvnc/.MainActivity \
  --es host 192.168.1.100 \
  --ei port 5901
```

### Intent Extras

| Extra | Type | Default | Description |
|-------|------|---------|-------------|
| `host` | string | `localhost` | VNC server hostname or IP |
| `port` | int | `5900` | VNC server port |
| `password` | string | *(empty)* | VNC password (if server requires auth) |
| `mode` | string | `full` | Initial zoom mode: `full`, `quarter`, `half`, `zoom` |

All settings are saved to SharedPreferences and reused on next launch.

## Controls

| Input | Action |
|-------|--------|
| Tap | Cycle zoom mode |
| Swipe down | Exit |
| Long-press | Exit |
| Back button | Exit |
| D-pad center | Cycle zoom mode |

## Server Setup

Any standard VNC server works. Examples:

```bash
# x11vnc (mirror existing display)
x11vnc -display :0 -nopw -forever

# TigerVNC (virtual display)
vncserver :1 -geometry 1920x1080

# macOS built-in (System Preferences → Sharing → Screen Sharing)
```

## Build Requirements

- Android SDK with API 28 (compileSdk) and API 19 (minSdk)
- Java 8+
- Gradle (wrapper included)
