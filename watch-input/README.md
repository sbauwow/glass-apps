# Watch Input

Wear OS remote control for Google Glass. Provides a button-based D-pad and system keys, sent to Glass over Bluetooth LE.

## How It Works

The watch runs a BLE GATT server (foreground service) that advertises a custom input service. The Glass companion app ([glass-watch-input](../glass-watch-input/)) connects as a GATT client and injects received events as system-wide key presses.

## UI

```
        [  UP  ]
  [LEFT] [ OK ] [RIGHT]
        [ DOWN ]

  [BACK] [HOME] [MENU]
```

| Button | BLE Event |
|--------|-----------|
| Up / Down / Left / Right | Gesture → DPAD key on Glass |
| OK | Gesture (tap) → DPAD_CENTER on Glass |
| Back | Key event → KEYCODE_BACK |
| Home | Key event → KEYCODE_HOME |
| Menu | Key event → KEYCODE_MENU |

Hardware rotary encoder and stem buttons are also forwarded.

## BLE Protocol

- **Service UUID:** `0000ff20-0000-1000-8000-00805f9b34fb`
- **Characteristic UUID:** `0000ff21-0000-1000-8000-00805f9b34fb`
- **Payload:** 4 bytes — `[type, value, action, reserved]`

| Type | Byte 0 | Values (Byte 1) |
|------|--------|-----------------|
| Key | `0x01` | Android keycode; Byte 2 = 0 DOWN / 1 UP |
| Gesture | `0x02` | 1=tap, 2=long press, 3-6=swipe L/R/U/D |
| Rotary | `0x03` | 1=CW, 2=CCW |

## Build & Install

```bash
./gradlew assembleDebug

# Install on watch via WiFi ADB
adb connect <watch-ip>:5555
adb -s <watch-ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

## Requirements

- Galaxy Watch 4/5/6/7 (Wear OS, API 30+)
- [glass-watch-input](../glass-watch-input/) installed as priv-app on Glass
