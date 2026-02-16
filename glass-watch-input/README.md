# glass-watch-input

BLE input bridge for Google Glass Explorer Edition. Receives D-pad, gesture, and rotary input from a Galaxy Watch running [watch-input](../watch-input/) over Bluetooth LE and injects them as system-wide key events on Glass.

## How It Works

The app runs as an **AccessibilityService** (`InputBridgeService`) in the background. It auto-connects to the watch over BLE and receives input events. Different event types are handled differently:

| Input | Method | Why |
|-------|--------|-----|
| Home, Back, Menu | `AccessibilityService.performGlobalAction()` | System navigation — works without special permissions |
| D-pad, OK, rotary | Root daemon via FIFO → `input keyevent` | Requires `INJECT_EVENTS` permission (signature-only) |
| Gestures (tap, swipe, long-press) | Mapped to D-pad keys or global actions | See input mapping table below |

### The INJECT_EVENTS Problem

Android's `INJECT_EVENTS` permission has `signature` protection level — only apps signed with the platform key can get it. Even as a priv-app, even with SELinux permissive, the Android framework rejects key injection from non-platform apps.

**Solution: Root Key Bridge Daemon**

A boot script (`/system/bin/install-recovery.sh`) starts a persistent root shell process that reads key codes from a named pipe (FIFO) at `/data/local/tmp/keybridge`. Since the daemon runs as root, it has full permission to call `input keyevent`. The app simply writes key codes to the FIFO.

```
Watch → BLE → InputBridgeService → FIFO → root daemon → input keyevent → system
```

The daemon is started automatically on boot by Android's init system (`flash_recovery` service).

## Input Mapping

| Watch Input | Glass Action |
|-------------|-------------|
| D-Pad Up | `KEYCODE_DPAD_UP` |
| D-Pad Down | `KEYCODE_DPAD_DOWN` |
| D-Pad Left | `KEYCODE_DPAD_LEFT` |
| D-Pad Right | `KEYCODE_DPAD_RIGHT` |
| OK (center) | `KEYCODE_DPAD_CENTER` |
| Rotary CW | `KEYCODE_DPAD_RIGHT` |
| Rotary CCW | `KEYCODE_DPAD_LEFT` |
| Tap gesture | `KEYCODE_DPAD_CENTER` |
| Swipe left | `KEYCODE_DPAD_LEFT` |
| Swipe right | `KEYCODE_DPAD_RIGHT` |
| Swipe up | `KEYCODE_DPAD_UP` |
| Swipe down | `KEYCODE_DPAD_DOWN` |
| Long press | Back (global action) |
| Home button | Home (global action) |
| Back button | Back (global action) |
| Menu button | Recents (global action) |

## BLE Protocol

Custom GATT service `0000ff20-...` with a single notify characteristic (`0000ff21-...`). 4-byte payloads:

| Byte | Field | Values |
|------|-------|--------|
| 0 | Type | `0x01` KEY, `0x02` GESTURE, `0x03` ROTARY |
| 1 | Value | Gesture/rotary ID or Android keycode |
| 2 | Action | `0x00` DOWN, `0x01` UP (keys only) |
| 3 | Reserved | `0x00` |

## Setup

### Quick Setup

Run the setup script from a computer with Glass connected via USB:

```bash
cd glass-watch-input
./setup.sh
```

This builds the APK, installs it as a priv-app, sets up the root key bridge daemon, enables the accessibility service, and reboots Glass.

### Manual Setup

#### 1. Build

```bash
./gradlew assembleDebug
```

#### 2. Install as priv-app

Must be installed to `/system/priv-app/` (not `/data/app/`) so the AccessibilityService works properly.

```bash
# Remount system partition
adb shell mount -o rw,remount /system

# Create directory and push APK
adb shell mkdir -p /system/priv-app/GlassWatchInput
adb push app/build/outputs/apk/debug/app-debug.apk \
    /system/priv-app/GlassWatchInput/GlassWatchInput.apk
adb shell chmod 644 /system/priv-app/GlassWatchInput/GlassWatchInput.apk

# IMPORTANT: Remove any /data/app/ install (takes precedence over priv-app)
adb shell pm uninstall com.glasswatchinput 2>/dev/null

# Remount read-only
adb shell mount -o ro,remount /system
```

#### 3. Set up the root key bridge daemon

Create the boot script that starts the FIFO-based key injection daemon:

```bash
adb shell mount -o rw,remount /system

# Create the FIFO
adb shell "mknod /data/local/tmp/keybridge p 2>/dev/null; chmod 666 /data/local/tmp/keybridge"

# Create the boot script
adb shell "cat > /system/bin/install-recovery.sh << 'EOF'
#!/system/bin/sh
FIFO=/data/local/tmp/keybridge
if [ ! -p \$FIFO ]; then
    mknod \$FIFO p
    chmod 666 \$FIFO
fi
(
    while true; do
        while read code; do
            input keyevent \$code
        done < \$FIFO
    done
) &
exit 0
EOF"

adb shell chmod 755 /system/bin/install-recovery.sh
adb shell mount -o ro,remount /system
```

#### 4. Enable accessibility services

Both `InputBridgeService` and the launcher's `ButtonRemapService` must be enabled (colon-separated):

```bash
adb shell settings put secure enabled_accessibility_services \
    com.example.glasslauncher/com.example.glasslauncher.service.ButtonRemapService:com.glasswatchinput/com.glasswatchinput.InputBridgeService
adb shell settings put secure accessibility_enabled 1
```

#### 5. Reboot

```bash
adb reboot
```

After reboot, the key bridge daemon starts automatically and the accessibility service connects to the watch.

## Troubleshooting

### Verify the daemon is running

```bash
# Should show a root sh process reading from the FIFO
adb shell "ls -la /proc/*/fd/0 2>/dev/null | grep keybridge"
```

### Test key injection manually

```bash
# Write a key code to the FIFO (22 = DPAD_RIGHT)
adb shell "echo 22 > /data/local/tmp/keybridge"
```

### Check accessibility services

```bash
adb shell settings get secure enabled_accessibility_services
# Should contain: com.glasswatchinput/com.glasswatchinput.InputBridgeService
```

### Check service logs

```bash
adb logcat -s InputBridge
```

### Common issues

| Problem | Cause | Fix |
|---------|-------|-----|
| D-pad doesn't work | Key bridge daemon not running | Reboot, or check `/system/bin/install-recovery.sh` exists |
| Home/Back/Menu don't work | Accessibility service not enabled | Re-run the `settings put` command in step 4 |
| "FIFO not found" in logs | FIFO was deleted | `adb shell "mknod /data/local/tmp/keybridge p; chmod 666 /data/local/tmp/keybridge"` then reboot |
| Old code still running | `/data/app/` install overriding priv-app | `adb shell pm uninstall com.glasswatchinput` then reboot |
| Dalvik cache stale | Old dex cached | `adb shell rm -rf /data/dalvik-cache/arm/system@priv-app@GlassWatchInput@GlassWatchInput.apk*` then reboot |

## Architecture Notes

### Why not simpler approaches?

| Approach | Why it doesn't work |
|----------|-------------------|
| `Instrumentation.sendKeySync()` | Requires `INJECT_EVENTS` (signature permission) |
| `Runtime.exec("input keyevent")` from app | `input` spawns `app_process` (Dalvik VM) which calls `InputManager.injectInputEvent()` — rejected because app UID lacks `INJECT_EVENTS` |
| `Runtime.exec("su")` from app | SELinux blocks `untrusted_app` from executing `su` binary |
| `sendevent` to `/dev/input/` | Kernel filters key codes not in device capabilities (Glass only has power + camera buttons) |
| Grant permission via `pm grant` | `INJECT_EVENTS` is not a changeable permission type |
| Grant permission via `packages.xml` | PackageManager re-validates on boot, reverts non-platform grants |
| `appops set` | No AppOps entry for INJECT_EVENTS on API 22 |

### Why the FIFO approach works

- The boot script runs as **root** via init's `flash_recovery` service
- Root has all permissions, so `input keyevent` succeeds
- The FIFO is world-writable (`chmod 666`), so the app can write to it
- SELinux is permissive on Glass, so audit denials are logged but not enforced
- The daemon persists across reboots (started by init on every boot)
- The daemon persists across adb disconnects (owned by init, not adb)

## Files

```
glass-watch-input/
├── README.md
├── setup.sh                        # Full setup script
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/glasswatchinput/
│   │   ├── InputBridgeService.java # Accessibility service — BLE→FIFO→keys
│   │   ├── BleManager.java        # BLE GATT client for watch connection
│   │   └── SetupActivity.java     # Manual BLE device picker UI
│   └── res/
│       └── xml/accessibility_service_config.xml
```

## Requirements

- Google Glass Explorer Edition (rooted, AOSP 5.1.1)
- Galaxy Watch 4/5/6/7 running [watch-input](../watch-input/)
- Android SDK with API 34
- Java 11
