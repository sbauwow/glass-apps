# glass-notify-client

Android phone companion app that forwards notifications and GPS location to Google Glass via [glass-notify](../glass-notify/).

## Features

- **Notification capture:** `NotificationListenerService` captures all phone notifications, filters out ongoing/group summaries, and forwards to Glass over TCP.
- **GPS forwarding:** Subscribes to phone GPS (5s/10m update intervals) and sends location data over the same connection.
- **Foreground service:** Runs as a persistent foreground service with connection status in the notification shade.
- **Auto-reconnect:** Exponential backoff (1s to 30s) on connection loss. Heartbeat every 30 seconds to keep the connection alive.

## Setup

1. Install on phone
2. Launch and enter the Glass IP address (shown on glass-notify's status bar)
3. Tap Start
4. Grant notification access when prompted (redirects to system settings)
5. Grant location permission when prompted

## Permissions

- `INTERNET` — TCP connection to Glass
- `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` — GPS for location forwarding
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` — Background service
- `POST_NOTIFICATIONS` — Android 13+ foreground service notification
- Notification listener access — System-level grant required

## Build

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Targets minSdk 24 (Android 7.0+), Java 11.
