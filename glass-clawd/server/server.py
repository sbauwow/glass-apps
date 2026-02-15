#!/usr/bin/env python3
"""
Glass Clawd proxy server.
Serves a minimal chat UI and proxies requests to the Anthropic Messages API.
Supports voice input via faster-whisper transcription.

Usage:
    python3 server.py [--port 8080] [--host 0.0.0.0] [--model base]

Reads ANTHROPIC_API_KEY from .env file in the server directory or from environment.
The Glass WebView connects to http://<host-ip>:8080/
"""

import argparse
import json
import os
import re
import sys
import tempfile
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from pathlib import Path

from faster_whisper import WhisperModel


def load_dotenv():
    """Load key=value pairs from .env file next to this script."""
    env_path = Path(__file__).parent / ".env"
    if not env_path.exists():
        return
    for line in env_path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        line = line.strip("'\"")
        if "=" in line:
            key, _, value = line.partition("=")
            key = key.strip()
            value = value.strip().strip("'\"")
            if key and key not in os.environ:
                os.environ[key] = value


load_dotenv()

API_URL = "https://api.anthropic.com/v1/messages"
CLAUDE_MODEL = "claude-sonnet-4-5-20250929"
MAX_TOKENS = 1024

# Conversation history (single-session, in-memory)
conversation = []

# Whisper model (loaded on startup)
whisper_model = None


def call_claude(user_text):
    """Send a message to Claude and return the assistant's reply.

    Appends both the user and assistant messages to the conversation history.
    On error, removes the failed user message and raises.
    """
    api_key = os.environ.get("ANTHROPIC_API_KEY", "")
    if not api_key:
        raise RuntimeError("ANTHROPIC_API_KEY not set on server")

    conversation.append({"role": "user", "content": user_text})

    payload = json.dumps({
        "model": CLAUDE_MODEL,
        "max_tokens": MAX_TOKENS,
        "messages": conversation,
    }).encode()

    req = Request(API_URL, data=payload, method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("x-api-key", api_key)
    req.add_header("anthropic-version", "2023-06-01")

    try:
        with urlopen(req) as resp:
            data = json.loads(resp.read())
            assistant_text = ""
            for block in data.get("content", []):
                if block.get("type") == "text":
                    assistant_text += block["text"]
            conversation.append({"role": "assistant", "content": assistant_text})
            return assistant_text
    except HTTPError as e:
        err_body = e.read().decode()
        print(f"API error {e.code}: {err_body}", file=sys.stderr)
        if conversation and conversation[-1]["role"] == "user":
            conversation.pop()
        raise RuntimeError(err_body)
    except Exception:
        if conversation and conversation[-1]["role"] == "user":
            conversation.pop()
        raise


class Handler(BaseHTTPRequestHandler):

    def do_GET(self):
        if self.path == "/" or self.path == "/index.html":
            self.serve_file("index.html", "text/html")
        elif self.path == "/history":
            self.send_json(200, {"messages": conversation})
        else:
            self.send_error(404)

    def do_POST(self):
        if self.path == "/chat":
            self.handle_chat()
        elif self.path == "/voice":
            self.handle_voice()
        elif self.path == "/clear":
            conversation.clear()
            self.send_json(200, {"status": "cleared"})
        else:
            self.send_error(404)

    def handle_chat(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length)) if length else {}
        user_text = body.get("message", "").strip()

        if not user_text:
            self.send_json(400, {"error": "Empty message"})
            return

        try:
            reply = call_claude(user_text)
            self.send_json(200, {"reply": reply})
        except RuntimeError as e:
            self.send_json(500, {"error": str(e)})

    def handle_voice(self):
        content_type = self.headers.get("Content-Type", "")
        if "multipart/form-data" not in content_type:
            self.send_json(400, {"error": "Expected multipart/form-data"})
            return

        # Extract boundary from Content-Type
        m = re.search(r"boundary=(.+)", content_type)
        if not m:
            self.send_json(400, {"error": "No boundary in Content-Type"})
            return
        boundary = m.group(1).strip().encode()

        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)

        # Find the audio part between boundaries
        delimiter = b"--" + boundary
        parts = body.split(delimiter)
        audio_data = None
        for part in parts:
            if b'name="audio"' in part:
                # Split headers from body at double CRLF
                sep = part.find(b"\r\n\r\n")
                if sep >= 0:
                    audio_data = part[sep + 4:]
                    # Strip trailing \r\n
                    if audio_data.endswith(b"\r\n"):
                        audio_data = audio_data[:-2]
                break

        if audio_data is None:
            self.send_json(400, {"error": "No audio file provided"})
            return
        if len(audio_data) < 44:
            self.send_json(400, {"error": "Audio data too small"})
            return

        # Write to temp file for faster-whisper
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
            tmp.write(audio_data)
            tmp_path = tmp.name

        try:
            segments, info = whisper_model.transcribe(tmp_path, language="en")
            transcription = " ".join(seg.text.strip() for seg in segments).strip()
        except Exception as e:
            print(f"Whisper error: {e}", file=sys.stderr)
            self.send_json(500, {"error": f"Transcription failed: {e}"})
            return
        finally:
            os.unlink(tmp_path)

        if not transcription:
            self.send_json(200, {"transcription": "", "reply": ""})
            return

        print(f"Transcribed: {transcription}")

        try:
            reply = call_claude(transcription)
            self.send_json(200, {"transcription": transcription, "reply": reply})
        except RuntimeError as e:
            self.send_json(500, {"error": str(e), "transcription": transcription})

    def send_json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def serve_file(self, filename, content_type):
        filepath = Path(__file__).parent / filename
        if not filepath.exists():
            self.send_error(404)
            return
        data = filepath.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", content_type + "; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        print(f"[{self.log_date_time_string()}] {fmt % args}")


def main():
    global whisper_model

    parser = argparse.ArgumentParser(description="Glass Clawd proxy server")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--model", default="base",
                        choices=["tiny", "base", "small", "medium"],
                        help="Whisper model size (default: base)")
    args = parser.parse_args()

    if not os.environ.get("ANTHROPIC_API_KEY"):
        print("WARNING: ANTHROPIC_API_KEY not set. Set it before sending messages.", file=sys.stderr)

    print(f"Loading Whisper model '{args.model}'...")
    whisper_model = WhisperModel(args.model, device="cpu", compute_type="int8")
    print("Whisper model loaded.")

    server = HTTPServer((args.host, args.port), Handler)
    print(f"Glass Clawd server running on http://{args.host}:{args.port}")
    print(f"Point your Glass WebView to http://<your-ip>:{args.port}/")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down.")
        server.server_close()


if __name__ == "__main__":
    main()
