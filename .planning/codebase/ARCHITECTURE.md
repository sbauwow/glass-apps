# Architecture

**Analysis Date:** 2026-02-26

## Pattern Overview

**Overall:** Distributed app ecosystem for Google Glass Explorer Edition (AOSP 5.1.1)

**Key Characteristics:**
- **Modular monorepo:** 27 Glass apps + 6 companion projects (watch, phone, VESC, Python utilities) organized as standalone Gradle/Android projects
- **Standalone patterns:** Most apps require no companion; network-dependent apps have decoupled Python/Node servers
- **Platform isolation:** Glass apps (Java/Kotlin, minSdk 19-30), Watch apps (Kotlin, minSdk 30), PC utilities (Python)
- **Single-activity design:** Most Android apps use one Activity as the main entry point with gesture and key event handling
- **Real-time streaming:** MJPEG HTTP for video, length-prefixed JSON over TCP for telemetry, BLE GATT for wearables
- **Daemon patterns:** Foreground services for background sensing (bike-hud, notify, music), background HTTP servers (notify, clawd)

## Layers

**Presentation (Android Activity / Kotlin Activity):**
- Purpose: UI rendering, gesture/key input handling, lifecycle management
- Location: `<app>/app/src/main/java/com/<package>/<*>Activity.java`
- Contains: Activity subclasses, view inflation, event dispatch, on-screen rendering
- Depends on: Service/Manager layers, Android framework (Activity, Handler, SharedPreferences)
- Used by: End-user interaction via Glass touchpad, camera button, back key

**Manager / Protocol Layer:**
- Purpose: Business logic, device communication, data transformation
- Location: `<app>/app/src/main/java/com/<package>/<*>Manager.java`, `<app>/app/src/main/java/com/<package>/<*>Protocol.java`, `<app>/app/src/main/java/com/<package>/<*>Server.java`
- Contains: BLE client/server logic (BleManager.java, BleGattServer.kt), Bluetooth RFCOMM/SPP (ObdManager.java, ElmProtocol.java), HTTP client (MainActivity.java network calls), TCP servers (GlassServer.java), MJPEG decoders (MjpegView.java)
- Depends on: Android Bluetooth APIs, Java networking (Socket, HttpURLConnection), sensor APIs (SensorManager, LocationManager)
- Used by: Activity layer for data and state updates

**Data Model:**
- Purpose: Encapsulate device telemetry and state
- Location: `<app>/app/src/main/java/com/<package>/<*>Data.java`
- Contains: Plain data classes (VescData.java with speed, duty, battery; ObdData.java with RPM, throttle, fuel; NotificationData.java with app/title/body)
- Depends on: Nothing (no external deps)
- Used by: Manager/Activity layers for display and storage

**Utility / Helper:**
- Purpose: Reusable functions for app launching, preferences, gestures
- Location: `<app>/app/src/main/java/com/<package>/util/`, `<app>/app/src/main/java/com/<package>/service/`
- Contains: AppLaunchHelper.java (Intent launching), PrefsManager.java (SharedPreferences), GlassGestureHandler.java (touchpad event parsing), ButtonRemapService.java (accessibility service)
- Depends on: Android framework
- Used by: Activity and Manager layers

## Data Flow

**Glass Display (MJPEG streaming):**

1. User starts glass-display Activity with host parameter
2. Activity spawns background thread to probe host:8080
3. MjpegView created and URL set via startStream()
4. Background thread continuously reads HTTP response boundary markers
5. Each JPEG frame extracted and decoded via Android BitmapFactory
6. UI thread updates ImageView with decoded bitmap
7. FPS counter updated on status text overlay

**VESC-Glass (BLE telemetry):**

1. User launches MainActivity
2. BleManager.scan() starts BLE device discovery with timeout
3. Found VESC devices passed to listener callback onDevicesFound()
4. Activity shows device picker; user taps selection
5. BleManager.connect() establishes GATT connection
6. Android triggers BluetoothGattCallback.onConnectionStateChange()
7. Service discovery requests all GATT services
8. Notify characteristic subscription writes CCCD descriptor
9. On characteristic change, BleManager decodes packet bytes → VescData object
10. VescData passed to Activity listener onDataReceived()
11. Activity updates display fields (speed, RPM, battery, temps)
12. Poll loop continues every 200ms via Handler

**Glass-Notify (Notification forwarding + GPS):**

1. Phone app (glass-notify-client) connects to Glass IP:9876
2. GlassServer listening on port 9876 in background thread
3. Socket accepted, client sends length-prefixed JSON for notifications
4. NotifyActivity renders notification via onNotificationReceived()
5. Screen wakeLock acquired (8 second timeout)
6. JSON field extraction: app name, title, body displayed
7. Notification added to history list (max 50)
8. Simultaneously, GPS data JSON also received and passed to MockGPS
9. MockGPS registers as mock location provider via LocationManager
10. Any app using GPS_PROVIDER (e.g., glass-weather) gets phone's real coordinates

**OBD2 Telemetry (ELM327 Bluetooth):**

1. MainActivity invoked via adb or launcher
2. ObdManager.scan() initiates Bluetooth device discovery
3. Found ELM327 dongles passed to picker overlay
4. User taps device, ObdManager.connect() attempts RFCOMM binding
5. Connection succeeds → ELM327 init sequence: ATZ, ATE0, ATL0, ATS0, ATSP0
6. ElmProtocol queries supported PIDs via OBD commands (0100, 0120, 0140)
7. Poll loop: ElmProtocol.sendPid() writes OBD2 PID request over RFCOMM
8. Response parsed into ObdData fields (speed, RPM, coolant, fuel, etc.)
9. Data colored: green=OK, yellow=caution thresholds, red=critical
10. 4-page HUD updates via swipe gestures; dots and labels follow page state

## Key Abstractions

**GlassGestureHandler (glass-launcher, glass-display, others):**
- Purpose: Decouple Glass touchpad event parsing from Activity
- Examples: `glass-launcher/app/src/main/java/com/example/glasslauncher/gesture/GlassGestureHandler.java`
- Pattern: Implements `onMotionEvent()` to recognize swipe, tap, two-finger tap, long-press; posts callbacks to `GestureListener` interface
- Reusable: Callback interface allows multiple Activities to share same event logic

**BleManager (vesc-glass, glass-bike-hud, glass-watch-input):**
- Purpose: Encapsulate BLE lifecycle (scan, connect, subscribe, poll, disconnect)
- Examples: `vesc-glass/app/src/main/java/com/vescglass/BleManager.java`, `glass-bike-hud/app/src/main/java/com/glassbikeud/BleManager.java` (implicit in listener pattern)
- Pattern: Listener callback model with `onStatusChanged()`, `onDataReceived()`, `onDevicesFound()` for decoupling UI updates
- Trust model: Trusted devices persisted in SharedPreferences; auto-connects to strongest signal on restart

**BLE GATT Server (watch-bike-hud, watch-input):**
- Purpose: Advertise custom service UUID with notify characteristics
- Examples: `watch-bike-hud/app/src/main/java/com/watchbikehud/BleGattServer.kt`
- Pattern: Kotlin, implements `BluetoothGattServerCallback`, queues CCCD descriptor writes
- Characteristics: Separate UUIDs for HR (notify), location (notify), trip (notify)

**ObdManager + ElmProtocol (glass-obd):**
- Purpose: Separate Bluetooth transport (ObdManager) from OBD2 wire protocol (ElmProtocol)
- Examples: `glass-obd/app/src/main/java/com/glassobd/ObdManager.java`, `glass-obd/app/src/main/java/com/glassobd/ElmProtocol.java`
- Pattern: Manager handles RFCOMM socket lifecycle + trusted device persistence; Protocol encodes/decodes AT commands and PID requests
- Fallback: 3-strategy RFCOMM binding (secure channel → insecure → reflection)

**FrameBroadcaster (glass-monitor Python):**
- Purpose: Async frame capture + broadcast to multiple connected clients
- Examples: `glass-monitor/glass_monitor.py` (classes FrameBroadcaster, MjpegHttpServer)
- Pattern: asyncio for concurrent client handling; mss for screen capture; Pillow for resize/encode
- Real-time: 30 FPS capture loop with quality/fps/mode parameters; clients subscribe to stream boundary

**MockGPS (glass-notify):**
- Purpose: Inject phone GPS as mock location provider
- Examples: `glass-notify/app/src/main/java/com/glassnotify/MockGPS.java`
- Pattern: Implements LocationProvider interface; registers in LocationManager via `addTestProvider()`
- Usage: glass-weather and other location-dependent apps receive real coordinates from phone without code changes

## Entry Points

**Android Activities:**
- Location: `<app>/app/src/main/java/com/<package>/<*>Activity.java` or `.kt`
- Triggers: App icon tap (Intent ACTION_MAIN), ADB intent, companion app connection
- Responsibilities: Inflate layout, bind views, initialize managers, dispatch input events, update display

**Services:**
- Location: `<app>/app/src/main/java/com/<package>/<*>Service.java` or `.kt`
- Triggers: Started via `startService()` or bound via `bindService()`
- Examples: TiltToWakeService (glass-notify accelerometer watcher), BikeSensorService (watch-bike-hud foreground sensor reader)
- Responsibilities: Background sensing, BLE GATT server advertising, TCP server listening

**Accessibility Services:**
- Location: `glass-launcher/app/src/main/java/com/example/glasslauncher/service/ButtonRemapService.java`, `glass-watch-input/app/src/main/java/com/glasswatch/input/InputBridgeService.java`
- Triggers: User enables in Accessibility Settings
- Responsibilities: Intercept key events (camera button, D-pad), inject events, show dialog navigators

**Python Entry Points:**
- `glass-monitor/glass_monitor.py` - MJPEG server; started via `./start.sh`
- `glass-clawd/server/server.py` - Whisper transcription + Claude proxy; started via `python server.py`
- `glass-music/linux/glass_music.py` - PulseAudio capture → BT streaming; started via `python3 linux/glass_music.py <mac>`

## Error Handling

**Strategy:** Try-catch with toast/log, graceful fallback, listener callbacks for recoverable errors

**Patterns:**

- **Connection failures:** BLE disconnects → scan resumes; ELM327 no response → retry with fallback channels; HTTP timeouts → connection status shows "CONNECTING" or "OFFLINE"
- **Data parsing errors:** Incomplete MJPEG frame → discard; malformed JSON from server → log and skip; OBD PID not supported → mark with "--"
- **Permission denials:** Check at runtime (Android 6+); show banner if accessibility service not enabled (glass-launcher); log and exit if critical permission missing
- **Resource exhaustion:** BLE connection limit reached → queue new requests; MJPEG client backlog → drop oldest frames; TCP socket max reached → close oldest idle clients
- **Recovery:** No automatic retry loops; instead, user taps to reconnect (glass-obd, vesc-glass); handlers post delayed runnable for next poll attempt

## Cross-Cutting Concerns

**Logging:** Android `Log.d()` / `Log.e()` with TAG constants; Python `logging` module or print()

**Validation:** Intent extras type-checked (String vs. String[]); OBD2 data range-checked for color thresholds; BLE packet size validated before decode

**Authentication:** Bluetooth link-key pairing (vesc-glass, glass-obd auto-pair with PIN 1234); ELM327 no auth; TCP servers assume local network trust (glass-notify port 9876)

**Persistence:** SharedPreferences for trusted device MACs, last connection state, user preferences (selected index in launcher, city in weather); File I/O for PDFs (glass-reader), RSS feeds (glass-rss)

**Lifecycle:** Activity onCreate → onResume (load prefs, start managers) → onPause (save state, stop listeners); Service onStartCommand → onCreate (one-time setup) → onDestroy; Handler callbacks cleaned up in onDestroy / onPause

---

*Architecture analysis: 2026-02-26*
