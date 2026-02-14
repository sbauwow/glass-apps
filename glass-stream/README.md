# Glass Stream

MJPEG camera streaming app for Google Glass Explorer Edition. Streams the camera feed over WiFi as MJPEG over HTTP — viewable in any browser, VLC, ffplay, or the included Python viewer. No third-party dependencies on the Glass side.

## How it works

```
Glass app → Camera API v1 → NV21 frames → JPEG compress → HTTP server (port 8080)
Linux/Mac → ffplay / VLC / browser / Python viewer
```

The app opens the camera, compresses each preview frame to JPEG, and serves them as a standard MJPEG stream over HTTP. Any client that speaks HTTP can connect — browsers, media players, ffmpeg, curl, custom scripts.

## Building

Requires Android SDK with build-tools 35 and platform 34 installed. The project targets API 19 (Glass XE).

```bash
cd ~/glass-stream
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Installing

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Usage

Launch **Glass Stream** from the Glass launcher. The camera preview appears with a status bar at the bottom showing:

```
http://192.168.1.42:8080/stream  |  1280x720  Q:70  15.0 fps  2 clients
```

### Controls (on Glass)

| Input | Action |
|-------|--------|
| Camera button | Cycle JPEG quality: 50 → 70 → 85 |
| Swipe down | Exit app |

### USB connection (recommended)

For the best latency and reliability, use ADB port forwarding over USB. This tunnels the stream through the USB cable — no WiFi needed:

```bash
adb forward tcp:8080 tcp:8080
```

Then use `localhost` instead of the Glass IP for all commands below. This works even if Glass has no WiFi connection.

### Quick-start scripts

The `scripts/` directory has ready-to-use launcher scripts:

```bash
scripts/view.sh              # View stream via ffplay (USB, lowest latency)
scripts/view.sh wifi         # View stream via ffplay (WiFi, auto-detects Glass IP)
scripts/record.sh            # Record to MP4 (USB)
scripts/record.sh wifi       # Record to MP4 (WiFi)
scripts/snap.sh              # Save a snapshot (USB)
scripts/snap.sh wifi         # Save a snapshot (WiFi)
scripts/deploy.sh            # Build, install, launch, and set up USB forwarding
```

### Viewing the stream

**Browser** — open `http://localhost:8080/` (USB) or `http://<glass-ip>:8080/` (WiFi) for a full-page viewer.

**ffplay** (lowest latency):
```bash
ffplay -fflags nobuffer -flags low_delay http://localhost:8080/stream
```

**VLC**:
```bash
vlc http://localhost:8080/stream --network-caching=100
```

**mpv**:
```bash
mpv --no-cache http://localhost:8080/stream
```

### Recording

**ffmpeg** — record to MP4:
```bash
ffmpeg -i http://localhost:8080/stream -c:v libx264 -preset fast output.mp4
```

**ffmpeg** — record 30 seconds:
```bash
ffmpeg -i http://localhost:8080/stream -c:v libx264 -t 30 clip.mp4
```

**ffmpeg** — record lossless (re-wrap JPEGs, no re-encoding):
```bash
ffmpeg -i http://localhost:8080/stream -c:v copy output.avi
```

### Snapshots

```bash
curl -o snap.jpg http://localhost:8080/snapshot
```

### Python viewer

Interactive viewer with recording and snapshot support. Requires Python 3, OpenCV, and NumPy.

```bash
pip install opencv-python numpy
python viewer/glass_viewer.py localhost            # USB
python viewer/glass_viewer.py <glass-ip>           # WiFi
```

| Key | Action |
|-----|--------|
| `r` | Toggle recording (saves `glass_recording_TIMESTAMP.mp4`) |
| `s` | Save snapshot (saves `glass_snap_TIMESTAMP.jpg`) |
| `q` / `ESC` | Quit |

## HTTP endpoints

| Endpoint | Content-Type | Description |
|----------|-------------|-------------|
| `/` | `text/html` | Browser viewer page with embedded `<img>` stream |
| `/stream` | `multipart/x-mixed-replace` | MJPEG stream (continuous) |
| `/snapshot` | `image/jpeg` | Single JPEG frame (returns 503 if no frame yet) |

## Architecture

```
StreamActivity
├── CameraManager        Camera v1, NV21→JPEG via YuvImage
│   └── FrameBuffer      Thread-safe latest-frame holder (wait/notify)
└── MjpegHttpServer      ServerSocket on :8080, thread-per-client
    └── FrameBuffer      Shared reference, readers block until new frame
```

### Thread model

- **Main thread** — Activity lifecycle, UI updates (status bar refreshed every 1s via Handler)
- **Camera callback thread** — `onPreviewFrame` converts NV21→JPEG, publishes to FrameBuffer
- **Server accept thread** — Listens on port 8080, spawns a thread per client
- **Client threads** — One per connected viewer, blocks on `FrameBuffer.waitForFrame()`

### Thread safety

- `FrameBuffer` — `synchronized(lock)` with `wait/notifyAll`. Single writer (camera), N readers (HTTP clients).
- `CameraManager.running` — `volatile boolean`
- `MjpegHttpServer.running` — `volatile boolean`
- Client count — `AtomicInteger`

### Power management

The app acquires a partial `WakeLock` and a high-performance `WifiLock` to prevent the device from sleeping or throttling WiFi during streaming. Both are released in `onDestroy`.

## Build configuration

| Setting | Value |
|---------|-------|
| compileSdk | 34 |
| minSdk | 19 |
| targetSdk | 19 |
| Java | 11 |
| AGP | 8.9.0 |
| AndroidX | disabled |

## Permissions

- `CAMERA` — camera access
- `INTERNET` — HTTP server
- `WAKE_LOCK` — prevent sleep during streaming
- `ACCESS_WIFI_STATE` — display IP address in status bar
- `ACCESS_NETWORK_STATE` — network status

All permissions are install-time (API 19), no runtime prompts.

## Troubleshooting

**Stream URL shows "no wifi"** — Glass isn't connected to WiFi. You can still stream over USB with `adb forward tcp:8080 tcp:8080`.

**Can't connect over WiFi** — Make sure Glass and your machine are on the same network. Try `adb shell ping <your-machine-ip>` to verify connectivity. Or just use USB forwarding instead.

**High latency** — Use ffplay with `-fflags nobuffer -flags low_delay` for minimal latency. Lower JPEG quality (press camera button on Glass) reduces frame size. Browser viewing has inherently higher latency than ffplay.

**Port already in use** — Another instance may still be running. Force-stop via `adb shell am force-stop com.example.glassstream`.

**Black frames** — Some Glass units need a moment for the camera to warm up. Wait 1-2 seconds after launch.
