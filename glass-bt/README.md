# glass-bt

Bidirectional Bluetooth RFCOMM communication between Google Glass and Linux.

## Architecture

- **Glass**: Android app (`com.glassbt`) runs an RFCOMM server
- **Linux**: Python CLI client connects, sends/receives messages
- **Protocol**: 4-byte big-endian length prefix + UTF-8 JSON payload
- **UUID**: `5e3d4f8a-1b2c-3d4e-5f6a-7b8c9d0e1f2a`

## Message Types

```json
{"type": "text",         "ts": 1234567890, "from": "linux|glass", "text": "..."}
{"type": "command",      "ts": 1234567890, "cmd": "...", "args": {...}}
{"type": "notification", "ts": 1234567890, "app": "...", "title": "...", "text": "..."}
{"type": "heartbeat",    "ts": 1234567890}
```

## Build & Install

```bash
cd ~/glass-apps/glass-bt
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Usage

### First-time pairing

1. Launch GlassBT on Glass — it requests 300s discoverability
2. From Linux: `bluetoothctl pair <glass-mac>`
3. Long-press on Glass touchpad to re-enable discoverability if needed

### Connecting

```bash
# Direct (if you know the channel)
python3 linux/glass_bt.py <glass-mac> -c 5

# Auto-detect channel (scans 1-30, slower)
python3 linux/glass_bt.py <glass-mac>

# Scan for nearby devices (requires PyBluez)
python3 linux/glass_bt.py --scan
```

### Interactive commands

```
hello world                        # send text message
/notify Gmail|New Email|Body text  # send notification (app|title|text)
/cmd ping                          # send command
/quit                              # disconnect
```

### Glass gestures

- **Tap**: toggle message log, sends `tap` command to Linux
- **Swipe down**: close log or exit app
- **Long press**: re-enable Bluetooth discoverability

## Glass UI

- Main view shows the most recent message
- Tap to toggle scrollable message log (last 50 messages)
- Status bar at bottom: WAITING (orange) / CONNECTED (green) with channel number

## Dependencies

- **Glass**: No external dependencies (Android Bluetooth API, targetSdk 22)
- **Linux**: Python 3 stdlib only (`socket.AF_BLUETOOTH`). Optional PyBluez for SDP lookup and device scanning.
