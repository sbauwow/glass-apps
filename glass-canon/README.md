# Glass Canon

> **WIP** — Not yet tested with hardware. The PTP protocol implementation is based on Canon's vendor extensions documentation but has not been verified on a real Canon T5.

Stream the Canon Rebel T5's live viewfinder to Google Glass over USB OTG, plus trigger the shutter via Glass tap.

## Architecture

```
Canon T5                         Google Glass
┌──────────────┐    USB OTG      ┌──────────────────┐
│  960x640     │ ◄────────────► │  CanonActivity     │
│  JPEG frames │  PTP/USB        │  SurfaceView       │
│  live view   │  Canon ext.     │  640x360 scaled    │
└──────────────┘                 └──────────────────┘
```

The app connects to the Canon camera via USB PTP (Picture Transfer Protocol), opens a session, enables live view output over USB, and streams JPEG viewfinder frames to Glass. The Glass touchpad triggers the shutter.

## How It Works

1. **USB PTP**: Canon cameras present as PTP imaging devices (VID `0x04A9`, interface class 6)
2. **Session Init**: Opens a PTP session, enables Canon remote mode and event mode
3. **Live View**: Sets EVF output device to host (`0xD1B0` = `0x02`), then polls `GetViewFinderData` (opcode `0x9153`) in a loop
4. **Frame Decode**: Extracts JPEG from the live view response data, decodes with `BitmapFactory`
5. **Rendering**: Draws aspect-fit scaled frames to `SurfaceView` on Glass's 640x360 display
6. **Shutter**: Glass tap sends `RemoteRelease` (opcode `0x910F`) — full press then release

## Setup

### Build and Install

```bash
cd ~/glass-apps/glass-canon
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Usage

1. Turn Canon T5 on, set to any shooting mode (not playback)
2. Connect Canon to Glass via USB OTG cable
3. App auto-launches and begins streaming the viewfinder

## Glass Controls

- **Tap**: Take a photo
- **Swipe down**: Exit
- **Back / Escape**: Exit

## Files

| File | Purpose |
|------|---------|
| `CanonActivity.java` | SurfaceView rendering, JPEG decode, shutter gesture, lifecycle |
| `CanonUsb.java` | USB PTP connection, IO thread, live view loop, shutter trigger |
| `CanonPtp.java` | PTP packet codec — command/data building, response parsing, JPEG extraction |
| `res/xml/device_filter.xml` | USB device filter for auto-launch (Canon VID) |

## Requirements

- **Glass**: API 19, no AndroidX
- **Canon**: Rebel T5 (or other Canon DSLR with PTP live view support)
- **Cable**: USB OTG adapter + USB Mini-B cable
