# Glass Clawd

Claude chat client for Google Glass, with local audio recording and server-side Whisper transcription.

## Architecture

```
Glass (AudioRecord 16kHz PCM) --POST /voice (WAV)--> Companion Server
                                                      ├── faster-whisper transcribe
                                                      ├── send text to Claude API
                                                      └── return { transcription, reply }
```

Glass records audio locally, sends it to the companion server which transcribes with faster-whisper and forwards the text to Claude. A single round-trip returns both the transcription and Claude's response.

## Server Setup

```bash
cd server
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt

# Add your API key
echo 'ANTHROPIC_API_KEY=sk-ant-...' > .env
chmod 600 .env

# Start (default: base Whisper model)
python server.py

# Or pick a different model size
python server.py --model tiny    # ~75MB, fastest
python server.py --model small   # ~500MB, more accurate
```

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/` | Chat web UI |
| GET | `/history` | Conversation history JSON |
| POST | `/chat` | Text input `{"message": "..."}` → `{"reply": "..."}` |
| POST | `/voice` | Multipart WAV audio → `{"transcription": "...", "reply": "..."}` |
| POST | `/clear` | Clear conversation history |

## Glass Controls

- **Tap**: Toggle recording (tap to start, tap to stop and send)
- **Camera / D-pad center**: Toggle recording
- **Swipe up/down**: Scroll chat
- **Back**: Exit

## Building

```bash
# Set Android SDK path
echo 'sdk.dir=/path/to/android-sdk' > local.properties

./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Requirements

- **Glass**: API 19, no AndroidX
- **Server**: Python 3.10+, faster-whisper
- **Audio**: 16kHz 16-bit mono WAV (optimal for Whisper)
