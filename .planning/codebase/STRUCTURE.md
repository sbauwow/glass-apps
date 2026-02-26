# Codebase Structure

**Analysis Date:** 2026-02-26

## Directory Layout

```
glass-apps/
├── glass-launcher/          # Custom home screen launcher
├── glass-display/           # MJPEG fullscreen viewer
├── glass-kill/              # Process killer utility
├── glass-monitor/           # Python MJPEG streaming server (desktop capture)
├── glass-pomodoro/          # Pomodoro timer
├── glass-stream/            # Camera MJPEG streaming server
├── glass-term/              # Terminal emulator with SSH
├── glass-vnc/               # VNC remote desktop viewer
├── glass-stocks/            # StockCharts Voyeur slideshow
├── glass-weather/           # Weather + hourly forecast (Open-Meteo)
├── vesc-glass/              # Electric skateboard telemetry HUD (BLE)
├── glass-notify/            # Notification forwarder (TCP server)
├── glass-notify-client/     # Phone companion for glass-notify
├── glass-clawd/             # Claude AI voice assistant (WebView + proxy)
├── glass-dashboard/         # News, sports, stocks dashboard (3 pages)
├── glass-reader/            # PDF reader (book + teleprompter modes)
├── glass-rss/               # Multi-feed RSS reader (cards)
├── glass-bike-hud/          # Biking HUD (heart rate, GPS, distance from watch)
├── watch-bike-hud/          # Galaxy Watch sensor broadcaster (BLE server)
├── glass-flipper/           # Flipper Zero screen mirror (USB OTG + Protobuf)
├── glass-music/             # Linux audio streaming to Glass (Bluetooth)
├── glass-watch-input/       # BLE input bridge (accessibility service)
├── watch-input/             # Galaxy Watch remote control (D-pad)
├── phone-flipper/           # Phone Flipper mirror (incomplete)
├── phone-input/             # Phone input bridge (incomplete)
├── glass-vision/            # Computer vision app (incomplete)
├── glass-vision-client/     # Computer vision client (incomplete)
├── glass-baseball/          # Baseball app (incomplete)
├── glass-bt/                # Bluetooth utility (incomplete)
├── glass-canon/             # Canon camera integration (incomplete)
├── glass-radio/             # Radio app (incomplete)
├── watch-linux-input/       # Linux input bridge for watch (incomplete)
├── apks/                    # Pre-built APK binaries (all apps)
├── install.sh               # Interactive APK installer script
├── README.md                # Main project documentation
└── .planning/               # Analysis documents (this directory)
    └── codebase/
        ├── ARCHITECTURE.md
        ├── STRUCTURE.md
        ├── CONVENTIONS.md
        └── TESTING.md
```

## Directory Purposes

**Android App Project (`<app>/`):**
- Purpose: Standalone Gradle-based Android app
- Structure: Standard Gradle layout with `app/`, `build.gradle`, `settings.gradle`, `gradlew`
- Key files: `app/src/main/AndroidManifest.xml`, `app/build.gradle`, `app/src/main/java/`, `app/src/main/res/`, `app/src/main/assets/`

**`app/src/main/java/com/<package>/`:**
- Purpose: Java/Kotlin source code for the app
- Contains: Activity, Service, Manager, Protocol, Data, Util classes
- Subdirectories: `gesture/`, `service/`, `util/` for logical grouping

**`app/src/main/res/`:**
- Purpose: Android resource files
- Contains: `layout/` (XML layouts), `drawable/` (images, colors), `values/` (strings, colors, dimensions), `menu/` (menu definitions)
- Key files: `layout/activity_main.xml` (main Activity layout), `colors.xml` (color definitions)

**`app/build.gradle`:**
- Purpose: App-level Gradle build configuration
- Key settings: `namespace`, `compileSdk 34`, `minSdk 19/30`, `targetSdk 23/34`, Java version, dependencies

**Python Utility (`<app>/` with .py files):**
- Purpose: Desktop/server companion for Glass apps
- Examples: `glass-monitor/glass_monitor.py`, `glass-clawd/server/server.py`, `glass-music/linux/glass_music.py`
- Structure: Single script or multiple .py files; `requirements.txt` for pip dependencies; shell scripts for launching

**`apks/` directory:**
- Purpose: Pre-built APK binaries for easy installation
- Contents: One APK per app (glass-launcher.apk, glass-display.apk, etc.)
- Files: Named as `<app-name>.apk`

## Key File Locations

**Entry Points:**

- `glass-launcher/app/src/main/java/com/example/glasslauncher/LauncherActivity.java` - Home screen app launcher
- `glass-display/app/src/main/java/com/glassdisplay/MainActivity.java` - MJPEG stream viewer
- `glass-clawd/app/src/main/java/com/example/glassclawd/MainActivity.java` - Claude voice chat
- `glass-obd/app/src/main/java/com/glassobd/MainActivity.java` - OBD2 telemetry HUD
- `glass-monitor/glass_monitor.py` - Desktop MJPEG server entry point
- `watch-bike-hud/app/src/main/java/com/watchbikehud/WatchBikeActivity.kt` - Watch app main activity

**Configuration Files:**

- `glass-launcher/app/build.gradle` - Gradle build, Java 11, minSdk 19
- `watch-bike-hud/app/build.gradle` - Gradle build, Kotlin, minSdk 30, Java 17, Play Services Location
- `glass-monitor/requirements.txt` - Python dependencies (mss, Pillow)
- `glass-monitor/.venv/` - Python virtual environment
- All apps: `local.properties` - Android SDK path (created locally, not committed)

**Core Logic:**

- `vesc-glass/app/src/main/java/com/vescglass/BleManager.java` - BLE device scan/connect/poll pattern
- `glass-obd/app/src/main/java/com/glassobd/ObdManager.java` - Bluetooth RFCOMM + device discovery
- `glass-obd/app/src/main/java/com/glassobd/ElmProtocol.java` - OBD2 AT command encoding/decoding
- `glass-notify/app/src/main/java/com/glassnotify/GlassServer.java` - TCP server for receiving notifications
- `glass-launcher/app/src/main/java/com/example/glasslauncher/gesture/GlassGestureHandler.java` - Touchpad event parsing
- `glass-monitor/glass_monitor.py` - MJPEG frame capture + HTTP streaming

**Testing:**

- None detected. No test files or test configuration found.

**Utilities & Helpers:**

- `glass-launcher/app/src/main/java/com/example/glasslauncher/util/AppLaunchHelper.java` - App launching via Intent
- `glass-launcher/app/src/main/java/com/example/glasslauncher/util/PrefsManager.java` - SharedPreferences wrapper
- `glass-launcher/app/src/main/java/com/example/glasslauncher/service/ButtonRemapService.java` - Accessibility service for camera button
- `glass-launcher/app/src/main/java/com/example/glasslauncher/gesture/GestureActionMap.java` - Gesture-to-action mapping
- `glass-monitor/pick-region.sh` - Helper to find screen region coordinates
- `glass-monitor/start.sh` - Start script with adb reverse setup

## Naming Conventions

**Java Files:**
- Activities: `*Activity.java` (LauncherActivity.java, MainActivity.java)
- Services: `*Service.java` (ButtonRemapService.java, TiltToWakeService.java)
- Managers: `*Manager.java` (BleManager.java, ObdManager.java)
- Protocols: `*Protocol.java` (VescProtocol.java, ElmProtocol.java)
- Data models: `*Data.java` (VescData.java, ObdData.java, NotificationData.java)
- Servers: `*Server.java` (GlassServer.java, MjpegHttpServer.py)
- Utilities: Descriptive name in `util/` directory (AppLaunchHelper.java, PrefsManager.java)
- Interfaces/Callbacks: `Listener.java` or `Callback.java` (implied by inner interface definitions)

**Kotlin Files:**
- Same conventions as Java but `.kt` extension
- Activity: `WatchBikeActivity.kt`
- Service: `BikeSensorService.kt`
- Server: `BleGattServer.kt`

**Android Layouts (XML):**
- Activities: `activity_<name>.xml` (activity_launcher.xml, activity_main.xml, activity_hud.xml)
- Items: `item_<name>.xml` (item_app.xml, item_notification.xml)
- Reusable: No fragments detected; monolithic layouts per Activity

**Python Files:**
- Entry point: `<app>_<type>.py` (glass_monitor.py, glass_music.py, server.py)
- Classes: camelCase (FrameBroadcaster, MjpegHttpServer, VoiceBridge)
- Script helpers: `<action>-<object>.sh` (pick-region.sh, start.sh, deploy.sh)

**Package Names:**
- Glass apps: `com.glass<appname>` or `com.example.glass<appname>` (com.glassdisplay, com.glassnotify, com.example.glassterm)
- Watch apps: `com.watch<appname>` (com.watchbikehud, com.watchinput)
- VESC: `com.vesc<appname>` (com.vescglass)

**Directories:**
- Source: Always `app/src/main/java/com/<package>/`
- Resources: Always `app/src/main/res/`
- Subpackages: Use only when 5+ related classes exist (e.g., `gesture/`, `service/`, `util/`)

## Where to Add New Code

**New Glass App:**
1. Create directory: `/home/stathis/glass-apps/glass-<appname>/`
2. Copy Gradle structure from existing app (glass-launcher recommended)
3. Update `build.gradle`: `namespace`, `applicationId`, `minSdk 19`, dependencies
4. Update `AndroidManifest.xml`: package name, app name, permissions
5. Create `app/src/main/java/com/<package>/<*>Activity.java`
6. Create `app/src/main/res/layout/activity_main.xml`
7. Build with `./gradlew assembleDebug`

**New Activity/Screen in Existing App:**
1. Create: `app/src/main/java/com/<package>/<Name>Activity.java`
2. Implement: Extend Activity, implement GestureListener if needed
3. Create layout: `app/src/main/res/layout/activity_<name>.xml`
4. Register in `AndroidManifest.xml`
5. Navigation: Use Intent from existing Activity

**New Manager/Protocol (for connectivity/devices):**
1. Create: `app/src/main/java/com/<package>/<Device>Manager.java`
2. Pattern: Listener callback interface for state/data changes
3. Create: Parallel `<Device>Protocol.java` if wire protocol is complex
4. Use in Activity: Instantiate in onCreate(), set listener, call start/scan/connect methods

**New Data Model:**
1. Create: `app/src/main/java/com/<package>/<Name>Data.java`
2. No external dependencies; only private fields + getter methods
3. Example: `class ObdData { private int speed; public int getSpeed() { return speed; } }`

**New Utility/Helper:**
1. Create: `app/src/main/java/com/<package>/util/<Name>Helper.java` or `service/<Name>Service.java`
2. Static methods for stateless utilities (AppLaunchHelper)
3. Service subclass for background work (ButtonRemapService, TiltToWakeService)

**New Layout:**
1. Create: `app/src/main/res/layout/<name>.xml`
2. Use fixed-size constraints (no match_parent except root); Glass is 640x360
3. Test on emulator with `-skin WVGA400` or actual device

**New Python Companion:**
1. Create: `<app>/server/` or `<app>/linux/` directory
2. Create: `<app_name>.py` with main entry point
3. Create: `requirements.txt` with pip dependencies
4. Create: `.venv/` with `python3 -m venv .venv && source .venv/bin/activate && pip install -r requirements.txt`
5. Usage: Document in app's README.md

## Special Directories

**`apks/`:**
- Purpose: Pre-built debug APKs for quick installation
- Generated: Yes (via CI or manual `./gradlew assembleDebug` + copy)
- Committed: Yes (convenience distribution)
- Update: Run `./gradlew assembleDebug` in each app, copy output APK here, commit

**`.gradle/`:**
- Purpose: Gradle daemon cache and task graphs
- Generated: Yes (auto-created by gradlew)
- Committed: No (in .gitignore)

**`build/`, `app/build/`:**
- Purpose: Gradle output directory (intermediate classes, resources, APK)
- Generated: Yes (auto-created by gradlew)
- Committed: No (in .gitignore)
- Location of APK: `app/build/outputs/apk/debug/app-debug.apk`

**`.planning/codebase/`:**
- Purpose: GSD codebase analysis documents
- Generated: Yes (via `/gsd:map-codebase`)
- Committed: Yes (shared reference for implementation)
- Contents: ARCHITECTURE.md, STRUCTURE.md, CONVENTIONS.md, TESTING.md, CONCERNS.md

**`<app>/.venv/`** (Python apps):**
- Purpose: Python virtual environment
- Generated: Yes (via `python3 -m venv .venv`)
- Committed: No (in .gitignore)

**`<app>/.claude/`** (Some apps):**
- Purpose: Claude Code session metadata
- Generated: Yes (auto-created by Claude Code)
- Committed: Typically no

## Gradle Project Structure

All Android apps follow standard Gradle project layout:

```
glass-<app>/
├── app/
│   ├── build.gradle                    # App-level config (minSdk, targetSdk, deps)
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/<package>/    # Source code
│   │   │   ├── res/                    # Resources (layout, drawable, values)
│   │   │   ├── assets/                 # Raw assets (Dropbear binary, fonts)
│   │   │   └── AndroidManifest.xml    # Permissions, activities, services
│   │   └── test/                       # Unit tests (rarely used)
│   └── libs/                           # JAR files (gdk.jar for Glass APIs)
├── build.gradle                        # Root-level config
├── settings.gradle                     # Include app module
├── gradle/
│   └── wrapper/                        # Gradle wrapper (version pinned here)
├── gradlew                             # Gradle wrapper executable
├── gradle.properties                   # Global settings (org.gradle.jvmargs)
├── local.properties                    # SDK path (created locally, .gitignored)
└── README.md                           # App-specific documentation
```

---

*Structure analysis: 2026-02-26*
