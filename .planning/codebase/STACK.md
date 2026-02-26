# Technology Stack

**Analysis Date:** 2026-02-26

## Languages

**Primary:**
- **Java** 8-11 - Android app development across 20+ glass and watch applications
- **Kotlin** 2.1.0 - Modern Android applications (watch-bike-hud, watch-input, watch-linux-input)
- **Python 3** (3.10+) - Server-side services and utility scripts

**Secondary:**
- **HTML/CSS/JavaScript** - Web UI served by companion servers (e.g., glass-clawd chat interface)
- **Bash/Shell** - Utility scripts for system configuration and automation

## Runtime

**Environment:**
- **Android** 5.1.1 to 14 (API 19-34) - Google Glass and Wear OS devices
- **Linux** - Server-side Python applications (x86_64)
- **Gradle** 8.7.3 - Android build system

**Package Manager:**
- **Gradle** - Android dependencies and build management
- **pip** - Python package management
- **Lockfile:** `requirements.txt` for Python projects

## Frameworks

**Core (Android):**
- **Android SDK** - Native Android development, minimum API 19 (Glass Explorer Edition), target API 19-34
- **Android Framework** - Activity, Service, Intent, BroadcastReceiver for inter-app communication
- **WebView** - For embedded web interfaces (glass-clawd, glass-display)

**Backend/Server:**
- **Python http.server** - Built-in HTTP server for companion services
- **Socket API** - TCP/UDP networking for Glass notifications and input control

**Testing:**
- **No test frameworks detected** - Limited test infrastructure

**Build/Dev:**
- **Gradle Wrapper** - Gradle 8.7.3 with Android Gradle Plugin
- **Android Studio DSL** - Standard Android app configuration

## Key Dependencies

**Critical (Python):**
- **faster-whisper** - Speech-to-text transcription (glass-clawd)
- **Pillow** (PIL) - Image processing and JPEG encoding for MJPEG streaming (glass-monitor)
- **mss** - Screen capture for MJPEG streaming (glass-monitor)
- **evdev** - Linux input device handling for uinput injection (watch-linux-input)

**Android Libraries (Google Play Services):**
- **com.google.android.gms:play-services-location** - GPS/location services (watch-bike-hud for cycling metrics)
- **AndroidX** - Not used (Glass targets API 19, pre-AndroidX era)

**Bluetooth/Connectivity:**
- Built-in Android Bluetooth APIs for BLE GATT and RFCOMM (glass-bt, watch-bike-hud)
- No external BLE libraries detected (using native android.bluetooth)

## Configuration

**Environment:**
- **ANTHROPIC_API_KEY** - Required for glass-clawd server (stored in `.env` file, not committed)
- **SDK Location** - `local.properties` specifies Android SDK path per project
- **No central configuration management** - Each project has independent gradle.properties

**Build:**
- `build.gradle` - Root project build config with Android Gradle Plugin 8.9.0
- `app/build.gradle` - Per-app configuration with namespace, SDK versions, compile options
- `gradle.properties` - VM arguments and project-level settings
- `settings.gradle` - Module configuration (typically single app per project)
- `.gradle/` - Cached build artifacts (not committed)

## Platform Requirements

**Development:**
- **Android SDK** - API 19-34 installed
- **Java 11** - Most projects target Java 11 (some use Java 8)
- **Gradle 8.7.3** - Included via gradlew
- **Python 3.10+** - For server-side applications
- **X11** (xrandr) - For glass-monitor virtual display management
- **Linux input subsystem** (evdev) - For keyboard/mouse injection

**Production:**
- **Google Glass Explorer Edition** - API 19 base (minSdk 19 standard across glass-* projects)
- **Wear OS** - For watch applications (watch-bike-hud, watch-input, watch-linux-input)
- **Linux Server** - For companion servers running Python
- **Network connectivity** - HTTP/TCP/BLE for Glass-to-server communication

## Deployment

**Android Apps:**
- Built via `./gradlew assembleDebug` to `app/build/outputs/apk/debug/app-debug.apk`
- Installed via `adb install -r <apk>`
- No automatic deployment pipeline detected

**Server Services:**
- Python venv-based deployment (`.venv/` directory)
- Manual execution via `python server.py` with optional args (port, host, model)
- Example: `glass-clawd/server/` runs with `ANTHROPIC_API_KEY` environment variable

## Notable Technology Choices

**Why Java 8-11?**
- Glass Explorer Edition targets API 19 (Android 4.4) which predates modern Java features
- Newer projects (API 34) upgrade to Java 11 for current toolchain compatibility

**Why no external HTTP framework?**
- Python `http.server` used for simplicity in companion services
- No need for full Flask/Django overhead for simple proxy/streaming servers

**Why no AndroidX?**
- Glass targets API 19 (pre-AndroidX support)
- Legacy Android Framework APIs sufficient for use cases

---

*Stack analysis: 2026-02-26*
