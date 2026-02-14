# Glass Display

Use Google Glass Explorer Edition as an external 640x360 display for a Linux laptop. A Python server captures a virtual monitor region and streams it as MJPEG over HTTP. The Glass app renders the stream fullscreen.

## Setup

### Server (Linux laptop)

```bash
cd ~/glass-monitor
pip install -r requirements.txt
python3 glass_monitor.py
```

This creates a 640x360 virtual monitor via xrandr at the bottom-right of your largest display and starts streaming on port 8080. Verify by opening `http://localhost:8080` in Firefox.

Options:
```
--fps 30          target framerate (default: 30)
--quality 70      JPEG quality 1-100 (default: 70)
--port 8080       HTTP port (default: 8080)
--region X,Y      manual capture origin instead of auto-placement
--no-monitor      skip xrandr setup, just capture
```

### Glass app

Build the APK:
```bash
cd ~/glass-display
gradle assembleDebug
```

Install:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Connect via USB

```bash
adb reverse tcp:8080 tcp:8080
adb shell am start -n com.glassdisplay/.MainActivity
```

### Connect via WiFi

```bash
adb shell am start -n com.glassdisplay/.MainActivity --es host 192.168.1.X
```

The host is saved to SharedPreferences — subsequent launches reuse it without the `--es` flag.

## Usage

- Drag windows into the Glass monitor region on your laptop — they appear on Glass
- Status overlay shows CONNECTING → CONNECTED (auto-hides after 3s) → DISCONNECTED
- FPS counter in top-right corner
- Auto-reconnects if the server drops

### Exit the app

Back key, swipe down, long-press, right-click, or escape.
