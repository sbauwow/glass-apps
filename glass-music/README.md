# glass-music

Stream Linux system audio to Google Glass over Bluetooth RFCOMM.

## Architecture

- **Glass**: Android app (`com.glassmusic`) runs an RFCOMM server, plays received PCM via `AudioTrack`
- **Linux**: Python client captures system audio via `parec` (PulseAudio) and streams it to Glass
- **Format**: 44.1kHz mono 16-bit PCM (~706 kbps) — fits within RFCOMM's ~2 Mbps capacity
- **UUID**: `f47ac10b-58cc-4372-a567-0e02b2c3d479`

```
Linux (Python)                          Glass (Android)
─────────────────                       ─────────────────
parec (PulseAudio)                      AudioServer (RFCOMM server)
  │ raw PCM stdout                        │ accept + read loop
  ▼                                       ▼
glass_music.py ──── RFCOMM ────────►  AudioPlayer (AudioTrack)
  send_frame()      4-byte len prefix     write() blocks until
  ~706 kbps         + type byte + body    audio buffer has room
```

## Wire Protocol

```
Frame: [4 bytes BE uint32: payload length] [1 byte: type] [N-1 bytes: body]

Types:
  0x01 CONFIG    Linux→Glass  JSON: {"sample_rate":44100,"channels":1,"encoding":"pcm_16bit_le"}
  0x02 AUDIO     Linux→Glass  Raw PCM bytes (4096 bytes per chunk ≈ 46ms)
  0x03 COMMAND   Glass→Linux  JSON: {"cmd":"pause"} or {"cmd":"resume"}
  0x04 HEARTBEAT Both dirs    No body (length=1)
```

## Build & Install

```bash
cd ~/glass-apps/glass-music
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Usage

### Streaming

```bash
# Auto-detect PulseAudio monitor source
python3 linux/glass_music.py <glass-mac>

# Specify RFCOMM channel (faster than auto-detect)
python3 linux/glass_music.py <glass-mac> -c 5

# Specify audio source manually
python3 linux/glass_music.py <glass-mac> -d alsa_output.pci-0000_00_1f.3.analog-stereo.monitor

# Custom sample rate / channels
python3 linux/glass_music.py <glass-mac> --rate 22050 --channels 2

# Scan for nearby devices (requires PyBluez)
python3 linux/glass_music.py --scan
```

### First-time pairing

1. Launch GlassMusic on Glass — it requests 300s discoverability
2. From Linux: `bluetoothctl scan on`, then `bluetoothctl pair <glass-mac>`
3. Long-press on Glass touchpad to re-enable discoverability if needed

### Glass gestures

- **Tap**: toggle pause/resume (sends COMMAND frame to Linux)
- **Swipe down**: exit app
- **Long press**: re-enable Bluetooth discoverability

## Glass UI

- Music note icon with app name centered on screen
- Status bar at bottom: WAITING (orange) → CONNECTED (green) → PLAYING (green) / PAUSED (amber)
- Shows RFCOMM channel number while waiting

## Audio Pipeline

1. `parec` captures the PulseAudio monitor source (all system audio output)
2. Python reads 4096-byte chunks and sends as AUDIO frames over RFCOMM
3. Glass pre-fills the AudioTrack buffer (~370ms) before starting playback
4. `AudioTrack.write()` in MODE_STREAM naturally back-pressures the read loop
5. When paused, Linux continues reading from parec but drops chunks

## Dependencies

- **Glass**: No external dependencies (Android Bluetooth + AudioTrack APIs, targetSdk 22)
- **Linux**: Python 3 stdlib only (`socket.AF_BLUETOOTH`). Requires `parec` (PulseAudio) and optionally PyBluez for SDP lookup.
