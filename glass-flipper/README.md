# Glass Flipper

Mirror the Flipper Zero's 128x64 monochrome OLED display onto Google Glass in real-time over USB OTG.

## Architecture

```
Flipper Zero                     Google Glass
┌──────────────┐    USB OTG      ┌──────────────────┐
│  128x64 1bit │ ◄────────────► │  FlipperActivity  │
│  OLED screen │  CDC Serial     │  SurfaceView      │
│              │  Protobuf RPC   │  640x320 scaled   │
└──────────────┘                 └──────────────────┘
```

The app connects to the Flipper via USB CDC serial, starts a Protobuf RPC session, requests screen streaming, and renders the incoming XBM frames scaled 5x with nearest-neighbor filtering for crisp pixels.

## How It Works

1. **USB CDC**: Flipper Zero presents as a CDC ACM serial device (VID `0x0483`, PID `0x5740`)
2. **RPC Init**: Sends `start_rpc_session\r`, waits for `\n` acknowledgment
3. **Screen Stream**: Sends a hand-coded protobuf `gui_start_screen_stream_request` message
4. **Frame Decode**: Receives varint-prefixed protobuf `Main` messages containing 1024-byte XBM frames (128x64, 1-bit, vertical byte layout, LSB = top row of each 8-pixel page)
5. **Rendering**: Decodes vertical byte layout → `Bitmap` → draws to `SurfaceView` at 640x320, centered on Glass's 640x360 display

## Setup

### Build and Install

```bash
cd ~/glass-apps/glass-flipper
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### First-Time USB Permission

The first time Glass sees the Flipper, Android shows a USB permission dialog. Since Glass's touchpad can't interact with system dialogs, use ADB over WiFi to tap OK remotely:

```bash
# 1. With Glass connected via USB, enable wireless ADB
adb tcpip 5555

# 2. Get Glass's WiFi IP
adb shell ip addr show wlan0 | grep "inet "

# 3. Connect over WiFi
adb connect <glass-ip>:5555

# 4. Unplug Glass from laptop, plug Flipper into Glass via USB OTG
#    The permission dialog will appear

# 5. Tick "Use by default" and tap OK remotely
adb -s <glass-ip>:5555 shell input tap 148 270   # tick checkbox
adb -s <glass-ip>:5555 shell input tap 476 322   # tap OK
```

After checking "Use by default", the dialog won't appear again for this device.

### Usage

Plug the Flipper into Glass via USB OTG — the app auto-launches. Navigate the Flipper normally and its screen is mirrored onto Glass in real-time.

## Glass Controls

- **Swipe down**: Exit
- **Back / Escape**: Exit

## Files

| File | Purpose |
|------|---------|
| `FlipperActivity.java` | SurfaceView rendering, XBM decode, lifecycle |
| `FlipperUsb.java` | USB CDC connection, RPC state machine, frame reassembly |
| `FlipperProto.java` | Hand-coded protobuf encoder/decoder (no library needed) |
| `FlipperBle.java` | BLE connection (unused — Glass can't pair with Flipper over BLE) |
| `res/xml/device_filter.xml` | USB device filter for auto-launch (VID/PID match) |

## Requirements

- **Glass**: API 19, no AndroidX
- **Flipper Zero**: Any firmware with USB serial RPC (stock or custom)
- **Cable**: USB OTG adapter/cable
