#!/usr/bin/env python3
"""
Glass Clawd proxy server.
Serves a minimal chat UI and proxies requests to the Anthropic Messages API.

Usage:
    ANTHROPIC_API_KEY=sk-... python3 server.py [--port 8080] [--host 0.0.0.0]

The Glass WebView connects to http://<host-ip>:8080/
"""

import argparse
import json
import os
import sys
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from pathlib import Path

API_URL = "https://api.anthropic.com/v1/messages"
MODEL = "claude-sonnet-4-5-20250929"
MAX_TOKENS = 1024

# Conversation history (single-session, in-memory)
conversation = []


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

        api_key = os.environ.get("ANTHROPIC_API_KEY", "")
        if not api_key:
            self.send_json(500, {"error": "ANTHROPIC_API_KEY not set on server"})
            return

        conversation.append({"role": "user", "content": user_text})

        payload = json.dumps({
            "model": MODEL,
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
                self.send_json(200, {"reply": assistant_text})
        except HTTPError as e:
            err_body = e.read().decode()
            print(f"API error {e.code}: {err_body}", file=sys.stderr)
            # Remove the failed user message from history
            if conversation and conversation[-1]["role"] == "user":
                conversation.pop()
            self.send_json(e.code, {"error": err_body})
        except Exception as e:
            if conversation and conversation[-1]["role"] == "user":
                conversation.pop()
            self.send_json(500, {"error": str(e)})

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
    parser = argparse.ArgumentParser(description="Glass Clawd proxy server")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--host", default="0.0.0.0")
    args = parser.parse_args()

    if not os.environ.get("ANTHROPIC_API_KEY"):
        print("WARNING: ANTHROPIC_API_KEY not set. Set it before sending messages.", file=sys.stderr)

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
