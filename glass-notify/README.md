# glass-notify

Glass-side receiver for the notification forwarding system. Runs a TCP server on port 9876 that accepts connections from [glass-notify-client](../glass-notify-client/) on the phone.

## Features

- **Notification display:** Shows incoming notifications fullscreen with app name, title, and body. Wakes screen for 8 seconds per notification.
- **History view:** Tap to toggle — last 50 notifications with timestamps.
- **Mock GPS provider:** Publishes phone GPS coordinates as a mock location provider on `GPS_PROVIDER`. Other apps (e.g. glass-weather) pick up real location automatically via standard `LocationManager` APIs.
- **Tilt-to-wake:** `TiltToWakeService` uses the accelerometer to detect a head-tilt-up gesture and wakes the screen. Rejects motion (device moving), sideways tilt, and has a 2-second cooldown.

## Setup

```bash
# Build and install
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Enable mock locations (one-time)
adb shell settings put secure mock_location 1
```

## Architecture

- `GlassServer` — TCP server that reads length-prefixed JSON messages, routes by `"type"` field to notification or location handlers
- `MockGPS` — Registers an Android test provider on `GPS_PROVIDER`, publishes locations received from the phone
- `TiltToWakeService` — Standalone service with accelerometer listener, wakes screen via WakeLock
- `NotifyActivity` — Main UI, implements `GlassServer.Listener`

## Protocol

Wire format: `[4-byte big-endian length][UTF-8 JSON]`

| Type | Fields |
|------|--------|
| Notification | `type`, `app`, `title`, `text`, `time` |
| Location | `type`, `lat`, `lon`, `alt`, `acc`, `time` |
| Heartbeat | `{}` (empty object) |

Messages without a `"type"` field are treated as notifications for backward compatibility.
