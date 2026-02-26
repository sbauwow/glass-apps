# External Integrations

**Analysis Date:** 2026-02-26

## APIs & External Services

**Anthropic Claude API:**
- **Service**: Claude text generation
- **What it's used for**: Conversational AI backend for glass-clawd chat client
- **SDK/Client**: Standard HTTP API via `urllib.request` (no SDK)
- **Auth**: `ANTHROPIC_API_KEY` environment variable
- **Endpoint**: `https://api.anthropic.com/v1/messages`
- **Model Used**: claude-sonnet-4-5-20250929 (configurable)
- **Location**: `glass-clawd/server/server.py`

**OpenAI Whisper (faster-whisper):**
- **Service**: Speech-to-text transcription
- **What it's used for**: Converting audio from Glass to text for glass-clawd
- **SDK/Client**: faster-whisper Python library (local inference)
- **Auth**: None (runs locally)
- **Models**: tiny (~75MB), small (~500MB), base (default)
- **Location**: `glass-clawd/server/server.py`

## Data Storage

**Databases:**
- **Not detected** - No persistent database integrations (SQLite, PostgreSQL, etc.)

**Local Storage:**
- **In-Memory Only**: glass-clawd maintains conversation history in a Python list, reset on server restart
- **Android SharedPreferences**: Used by glass-launcher for preferences (PrefsManager.java)
- **File System**: Temporary audio files stored in system temp directories

**File Storage:**
- **Local filesystem only** - No cloud storage integrations detected

**Caching:**
- **None** - No Redis or Memcached detected

## Authentication & Identity

**Auth Provider:**
- **Custom/None** - glass-clawd uses simple HTTP endpoints with no user authentication
- **Wear OS pairing**: Standard Android Bluetooth pairing for watch-bike-hud and watch-input

**Implementation:**
- API Key-based: ANTHROPIC_API_KEY for Claude API calls
- BLE GATT/RFCOMM: Standard Android Bluetooth protocol for Glass-to-watch communication
- No user accounts or login system implemented
- No session management beyond in-memory conversation history

## Monitoring & Observability

**Error Tracking:**
- **None detected** - No Sentry, Rollbar, or similar integrations

**Logs:**
- **Android Logcat**: Standard Android logging via `Log.d()`, `Log.e()` in Java code
- **Python print/stderr**: glass-clawd server logs to console via `print()` statements
- **Location**: `glass-clawd/app/src/main/java/com/example/glassclawd/MainActivity.java` uses `Log.d("GlassClawd", ...)`

## Network Protocols

**HTTP:**
- Port 8080 (default) - HTTP servers for streaming and proxying
  - glass-monitor: MJPEG stream server
  - glass-clawd: Chat server and Whisper proxy
  - glass-display: MJPEG client

**TCP Sockets:**
- Port 9876 - glass-notify: Notification server (custom JSON protocol)
- Port 9877 - watch-linux-input: Keyboard/mouse input injection

**Bluetooth:**
- RFCOMM - glass-bt bidirectional messaging
- BLE GATT - watch-bike-hud sensor data push to Glass
- RFCOMM Channel lookup via SDP (Service Discovery Protocol)

## CI/CD & Deployment

**Hosting:**
- **Self-hosted** - Manual ADB deployment to local Glass devices
- **No cloud deployment** detected

**CI Pipeline:**
- **None detected** - No GitHub Actions, GitLab CI, or other CI/CD system

**Build Process:**
- Manual via `./gradlew assembleDebug` for Android apps
- Manual via `python server.py` for Python servers

## Environment Configuration

**Required env vars (Production):**
- `ANTHROPIC_API_KEY` - Claude API authentication (glass-clawd server)

**Optional env vars:**
- Port configuration via command-line args (Python servers support `--port`, `--host`)
- Whisper model size selection via `--model` (glass-clawd server)

**Secrets location:**
- `.env` file in `glass-clawd/server/` for API keys (not committed to git)
- Loaded at runtime via `load_dotenv()` function

## Webhooks & Callbacks

**Incoming:**
- **POST /chat** - glass-clawd text input endpoint
- **POST /voice** - glass-clawd audio transcription + reply endpoint
- **POST /clear** - glass-clawd conversation history reset
- TCP socket for glass-notify and watch-linux-input (custom binary protocols)

**Outgoing:**
- **None detected** - No webhooks sent to external services

## Device Communication

**Glass-to-Server:**
- HTTP/MJPEG for streaming (glass-monitor)
- HTTP/multipart-form for voice + chat (glass-clawd)
- TCP socket for notifications (glass-notify)

**Glass-to-Watch:**
- BLE GATT for sensor data (watch-bike-hud pushes heart rate/GPS to Glass)
- RFCOMM for bidirectional messaging (glass-bt)

**Watch-to-Linux Server:**
- TCP socket with 4-byte input packets mapped to evdev keycodes (watch-linux-input)

## API Documentation

**glass-clawd Server Endpoints:**

| Method | Path | Input | Output |
|--------|------|-------|--------|
| GET | `/` | - | Chat web UI (HTML) |
| GET | `/history` | - | JSON array of conversation messages |
| POST | `/chat` | `{"message": "..."}` | `{"reply": "..."}` |
| POST | `/voice` | Multipart WAV audio | `{"transcription": "...", "reply": "..."}` |
| POST | `/clear` | - | `{"status": "cleared"}` |

**glass-notify Protocol:**
- Binary: 4-byte big-endian length prefix + JSON payload
- JSON: `{"app": "...", "title": "...", "text": "...", "time": milliseconds}`

**watch-linux-input Protocol:**
- Binary: 1-byte type + 1-byte subtype + 2-byte value
- Types: 0x00 (heartbeat), 0x01 (key), 0x02 (gesture), 0x03 (rotary)

## Integration Notes

**No Third-Party Services:**
- This codebase uses minimal external integrations (only Anthropic Claude)
- Most functionality implemented in-house (Bluetooth, networking, UI)
- Emphasis on local-first, offline-capable architecture
- Faster-whisper runs locally, not relying on cloud speech APIs

**Network Architecture:**
- Glass devices connect to a local Linux server running Python services
- Server proxies Claude API requests, adds caching/processing layer
- No direct Glass-to-Anthropic connections (goes through local proxy)

---

*Integration audit: 2026-02-26*
