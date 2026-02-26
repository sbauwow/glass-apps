# Coding Conventions

**Analysis Date:** 2026-02-26

## Naming Patterns

**Files:**
- Python: lowercase with underscores (`glass_monitor.py`, `test_notify.py`, `server.py`)
- Java/Kotlin: PascalCase for classes (`MainActivity.java`, `WatchBikeActivity.kt`, `BikeSensorService.kt`)
- Kotlin: PascalCase for Services and Activities following Android conventions
- Gradle: `build.gradle` for build configuration

**Functions/Methods:**
- Python: snake_case (`list_monitors()`, `find_primary_monitor()`, `setup_virtual_monitor()`)
- Java/Kotlin: camelCase (`onCreate()`, `onStartCommand()`, `toggleRecording()`, `handleGestureEvent()`)
- Private methods prefix with underscore in Python (`_shutdown()`) or `private` modifier in Java/Kotlin
- Callback/listener methods follow Android naming (`onXxxx`): `onServiceConnected()`, `onLocationResult()`, `onSensorChanged()`

**Variables:**
- Python: snake_case (`start_time_ms`, `monitor_list`, `pcm_buffer`)
- Java/Kotlin: camelCase (`isRecording`, `audioRecord`, `fusedLocationClient`, `startTimeMs`)
- Constants: UPPER_SNAKE_CASE (`SAMPLE_RATE = 16000`, `MAX_TOKENS = 1024`, `TAG = "GlassClawd"`)
- Private fields in Kotlin use camelCase with clear purpose names

**Types:**
- Java/Kotlin: PascalCase classes (`MainActivity`, `BikeSensorService`, `LocalBinder`, `FrameBroadcaster`)
- Use interfaces for callbacks in Kotlin (implicit function types when possible)
- Companion objects in Kotlin for static-like behavior

## Code Style

**Formatting:**
- Python: 4-space indentation (PEP 8 convention observed)
- Java/Kotlin: 4-space indentation, standard Android Studio style
- Java: Consistent use of braces on same line for blocks
- Kotlin: Consistent use of braces, compact lambda expressions

**Linting:**
- Not detected (no .eslintrc, .prettierrc, or linter configs found)
- Gradle lint enabled but allows errors: `lint { abortOnError false }` in `build.gradle`
- No strict enforcement, manually review for consistency

**File Organization:**
- Package names follow Java convention: `com.packagename.module` (e.g., `com.watchbikehud`, `com.phoneinput`)
- Kotlin and Java files mixed in same projects, both in `src/main/java/`

## Import Organization

**Order:**
1. Standard library imports (Python: `import argparse`, `import asyncio`)
2. Third-party library imports (Python: `import mss`, `from PIL import Image`)
3. Android framework imports (Java/Kotlin: `android.app.*`, `android.content.*`)
4. Local application imports (Java/Kotlin: `com.package.*`)

**Android imports pattern:**
- Manifest permissions first
- Android framework (app, content, etc.)
- Google Play Services (com.google.android.gms)
- Application-specific classes

**Path Aliases:**
- Not used (no tsconfig or similar)
- Direct imports from package structure

## Error Handling

**Patterns:**

**Python (HTTP server):**
- Try/except with specific exception types: `except HTTPError as e:`, `except SecurityException as e:`
- Error messages logged to stderr: `print(f"...", file=sys.stderr)`
- Graceful degradation on missing data (check for None/empty before use)
- Connection errors caught in socket operations: `except (ConnectionResetError, BrokenPipeError, ConnectionAbortedError):`

**Java/Kotlin:**
- Try/catch with specific types: `try { ... } catch (SecurityException e) { Log.e(TAG, "message", e) }`
- Silent catches with fallback UI updates when appropriate
- Log.e() for errors: `Log.e(TAG, "Error message", exception)`
- Null checks before dereferencing: `if (audioRecord != null) { ... }`
- Permission checks before operations: `if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)`
- Service lifecycle errors handled gracefully (return START_STICKY or START_NOT_STICKY)

**Patterns observed:**
- Kotlin uses Elvis operator: `val loc = result.lastLocation ?: return`
- Java uses null-coalescing: `binder as LocalBinder; binder.service`
- Errors logged with TAG constant for filtering
- User-facing errors shown in UI (toast, status text, webview messages)

## Logging

**Framework:** Android Log class or Python print()

**Patterns:**

**Kotlin/Java:**
- Constant TAG per class: `private const val TAG = "BikeSensor"`
- Log levels: `Log.d()` for debug, `Log.i()` for info, `Log.e()` for error, `Log.w()` for warning
- Logged at lifecycle points: onCreate, onStart, onDestroy
- Logged on sensor data changes: `Log.i(TAG, "Heart rate sensor registered")`
- Error conditions with exception: `Log.e(TAG, "Failed to start BLE server", exception)`

**Python:**
- Simple print statements to stdout for status: `print(f"Creating virtual monitor: {cmd}")`
- Errors to stderr: `print(f"...", file=sys.stderr)`
- No structured logging framework

## Comments

**When to Comment:**
- Complex gesture detection logic is commented: `// Vertical swipe: scroll chat`
- Algorithm-heavy sections explained: multipart form data parsing, protobuf varint encoding
- Non-obvious design decisions documented: "Glass touchpad is classified as SOURCE_TOUCHSCREEN"
- Intent of large blocks marked: `// --- Audio recording ---`, `// --- Touchpad gesture handling ---`

**JSDoc/Javadoc patterns:**
- Class-level documentation common: `/** Glass UI for the bike HUD system. */`
- Method-level docs for public/important methods
- Kotlin uses triple-slash comments: `/** Description */`
- HTML comments in server.py for complex sections

**Example:**
```kotlin
/**
 * Watch UI for the bike HUD system.
 * Shows current sensor data and provides start/stop control.
 */
class WatchBikeActivity : Activity() {
```

```java
/** JavaScript interface exposed to the web page. */
private class VoiceBridge {
```

## Function Design

**Size:**
- Python functions typically 10-50 lines (capture loops, request handling)
- Kotlin/Java methods 5-40 lines for core logic, longer (100+) for complex operations like audio handling
- Helper methods extracted for reuse: `createNotificationChannel()`, `setupKeyboardCapture()`, `setupWebView()`

**Parameters:**
- Kotlin uses callback properties: `var onDataUpdated: (() -> Unit)? = null`
- Java uses interface/listener patterns: `connection: ServiceConnection`, `touchpad.onMove = { dx, dy -> ... }`
- Python accepts config via argparse: `args.fps`, `args.quality`, `args.port`
- Few parameters (typically 1-3); larger configs passed as objects

**Return Values:**
- Kotlin: Return early pattern observed (`val svc = service ?: return`)
- Java: Explicit null returns or status constants (`START_STICKY`, `START_NOT_STICKY`)
- Python: Functions return computed values or raise on error
- Boolean returns for success/failure: `bleServer.start()` returns Boolean

## Module Design

**Exports:**
- Kotlin: Public classes with `companion object` for static members
- Java: Static final constants in classes
- Python: Module-level functions and classes, no explicit exports

**Barrel Files:**
- Not used (no index.ts or similar re-export files)
- Direct imports from source files

**Service Architecture:**
- Android Services use inner `LocalBinder` class for direct reference
- Services expose public properties for UI binding: `val bleServer`, `val distanceTracker`
- Activities bind to services via `ServiceConnection` interface
- Callback properties on services for loose coupling: `onDataUpdated`, `onStatusChanged`

---

*Convention analysis: 2026-02-26*
