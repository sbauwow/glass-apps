#!/usr/bin/env python3
"""MJPEG streaming server that captures a virtual monitor region for Google Glass."""

import argparse
import asyncio
import atexit
import io
import signal
import subprocess
import sys
import time

import mss
from PIL import Image

WIDTH, HEIGHT = 640, 360
BOUNDARY = b"frame"


def list_monitors():
    """Parse all monitors from xrandr. Returns list of (w, h, x, y, name)."""
    result = subprocess.run(
        ["xrandr", "--listmonitors"], capture_output=True, text=True
    )
    monitors = []
    for line in result.stdout.strip().split("\n")[1:]:
        # Format: " 0: +*eDP-1 1920/344x1080/194+0+0  eDP-1"
        parts = line.split()
        geom = parts[2].lstrip("+").lstrip("*")
        # Parse WIDTHxHEIGHT+X+Y (ignoring physical size)
        dims, pos = geom.split("+", 1) if "+" in geom else (geom, "0+0")
        # dims may contain /physical like "1920/344x1080/194"
        w_part, h_part = dims.split("x")
        w = int(w_part.split("/")[0])
        h = int(h_part.split("/")[0])
        pos_parts = pos.split("+")
        x, y = int(pos_parts[0]), int(pos_parts[1])
        monitors.append((w, h, x, y, parts[-1]))
    if not monitors:
        print("No monitors found via xrandr", file=sys.stderr)
        sys.exit(1)
    return monitors


def find_primary_monitor():
    """Find the largest monitor and return its geometry."""
    monitors = list_monitors()
    monitors.sort(key=lambda m: m[0] * m[1], reverse=True)
    return monitors[0]


def find_monitor_by_name(name):
    """Find a monitor by name (case-insensitive). Returns (w, h, x, y, name) or None."""
    for mon in list_monitors():
        if mon[4].lower() == name.lower():
            return mon
    return None


def get_virtual_screen_bounds():
    """Get the bounding box of the entire virtual screen."""
    monitors = list_monitors()
    max_x = max(m[2] + m[0] for m in monitors)
    max_y = max(m[3] + m[1] for m in monitors)
    return max_x, max_y


def setup_virtual_monitor(region):
    """Create a GLASS virtual monitor via xrandr. Returns capture (x, y)."""
    if region:
        x, y = region
    else:
        w, h, mx, my, _ = find_primary_monitor()
        x = mx + w - WIDTH
        y = my + h - HEIGHT
    # Physical size ~169x95mm for correct DPI reporting
    cmd = f"xrandr --setmonitor GLASS {WIDTH}/169x{HEIGHT}/95+{x}+{y} none"
    print(f"Creating virtual monitor: {cmd}")
    subprocess.run(cmd, shell=True, check=True)
    return x, y


def cleanup_virtual_monitor():
    """Remove the GLASS virtual monitor."""
    print("Removing virtual monitor GLASS")
    subprocess.run(
        ["xrandr", "--delmonitor", "GLASS"],
        capture_output=True,
    )


class FrameBroadcaster:
    """Captures frames and broadcasts to connected clients."""

    def __init__(self, x, y, fps, quality, src_w=WIDTH, src_h=HEIGHT):
        self.x = x
        self.y = y
        self.fps = fps
        self.quality = quality
        self.src_w = src_w
        self.src_h = src_h
        self.needs_resize = (src_w != WIDTH or src_h != HEIGHT)
        self.frame = b""
        self.frame_id = 0
        self.event = asyncio.Event()
        self._running = True

    async def capture_loop(self):
        interval = 1.0 / self.fps
        region = {"left": self.x, "top": self.y, "width": self.src_w, "height": self.src_h}

        with mss.mss() as sct:
            while self._running:
                t0 = time.monotonic()
                raw = sct.grab(region)
                img = Image.frombytes("RGB", (raw.width, raw.height), raw.rgb)
                if self.needs_resize:
                    img = img.resize((WIDTH, HEIGHT), Image.LANCZOS)
                buf = io.BytesIO()
                img.save(buf, "JPEG", quality=self.quality)
                self.frame = buf.getvalue()
                self.frame_id += 1
                self.event.set()
                self.event = asyncio.Event()
                elapsed = time.monotonic() - t0
                await asyncio.sleep(max(0, interval - elapsed))

    def stop(self):
        self._running = False


async def handle_client(broadcaster, reader, writer):
    """Serve MJPEG stream to one HTTP client."""
    # Read and discard the HTTP request
    while True:
        line = await reader.readline()
        if line == b"\r\n" or line == b"\n" or not line:
            break

    header = (
        b"HTTP/1.1 200 OK\r\n"
        b"Content-Type: multipart/x-mixed-replace; boundary=frame\r\n"
        b"Cache-Control: no-cache\r\n"
        b"Connection: close\r\n"
        b"\r\n"
    )
    writer.write(header)

    addr = writer.get_extra_info("peername")
    print(f"Client connected: {addr}")
    last_id = 0

    try:
        while True:
            await broadcaster.event.wait()
            if broadcaster.frame_id == last_id:
                continue
            last_id = broadcaster.frame_id
            jpeg = broadcaster.frame

            part = (
                b"--frame\r\n"
                b"Content-Type: image/jpeg\r\n"
                b"Content-Length: " + str(len(jpeg)).encode() + b"\r\n"
                b"\r\n"
            )
            writer.write(part)
            writer.write(jpeg)
            writer.write(b"\r\n")
            await writer.drain()
    except (ConnectionResetError, BrokenPipeError, ConnectionAbortedError):
        pass
    finally:
        print(f"Client disconnected: {addr}")
        writer.close()


async def main():
    parser = argparse.ArgumentParser(description="Glass Monitor MJPEG Server")
    parser.add_argument("--fps", type=int, default=30, help="Target FPS (default: 30)")
    parser.add_argument(
        "--quality", type=int, default=70, help="JPEG quality 1-100 (default: 70)"
    )
    parser.add_argument(
        "--port", type=int, default=8080, help="HTTP port (default: 8080)"
    )
    parser.add_argument(
        "--region",
        type=str,
        default=None,
        help="Capture origin X,Y (default: bottom-right of largest monitor)",
    )
    parser.add_argument(
        "--no-monitor",
        action="store_true",
        help="Skip xrandr virtual monitor setup",
    )
    parser.add_argument(
        "--mode",
        choices=["full", "quarter", "half", "zoom"],
        default="quarter",
        help="Capture mode: full (scale entire display), quarter (640x360 1:1), half (480x270 scaled up), zoom (320x180 scaled up)",
    )
    parser.add_argument(
        "--display",
        type=str,
        default=None,
        help="Capture an entire display by name (e.g. DSI-1, DP-1-1). Scales to 640x360.",
    )
    args = parser.parse_args()

    region = None
    if args.region:
        parts = args.region.split(",")
        region = (int(parts[0]), int(parts[1]))

    # --display mode: capture the entire named display, scaled to 640x360
    if args.display:
        mon = find_monitor_by_name(args.display)
        if not mon:
            available = [m[4] for m in list_monitors()]
            print(f"Display '{args.display}' not found. Available: {', '.join(available)}", file=sys.stderr)
            sys.exit(1)
        mw, mh, mx, my, mname = mon
        x, y = mx, my
        src_w, src_h = mw, mh
        print(f"Display: {mname} ({mw}x{mh} at +{mx}+{my})")
    elif not args.no_monitor:
        x, y = setup_virtual_monitor(region)
        atexit.register(cleanup_virtual_monitor)
        src_w, src_h = WIDTH, HEIGHT
    else:
        if region:
            x, y = region
        else:
            # Default: bottom-right of primary
            w, h, mx, my, _ = find_primary_monitor()
            x = mx + w - WIDTH
            y = my + h - HEIGHT

        # Determine capture source size based on mode
        if args.mode == "full":
            if region:
                src_w, src_h = WIDTH, HEIGHT
            else:
                pw, ph, px, py, _ = find_primary_monitor()
                x, y = px, py
                src_w, src_h = pw, ph
        elif args.mode == "zoom":
            src_w, src_h = 320, 180
            if not region:
                pw, ph, px, py, _ = find_primary_monitor()
                x = px
                y = py + ph - 180 - 50
        elif args.mode == "half":
            src_w, src_h = 480, 270
            if not region:
                pw, ph, px, py, _ = find_primary_monitor()
                x = px
                y = py + ph - 270 - 50
        else:
            # quarter: capture 640x360 (default)
            src_w, src_h = WIDTH, HEIGHT
            if not region:
                pw, ph, px, py, _ = find_primary_monitor()
                x = px
                y = py + ph - HEIGHT

    # Clamp capture region to virtual screen bounds to prevent XGetImage() crash
    vw, vh = get_virtual_screen_bounds()
    if x + src_w > vw:
        old_x = x
        x = max(0, vw - src_w)
        print(f"Warning: Clamped X from {old_x} to {x} (virtual screen width: {vw})")
    if y + src_h > vh:
        old_y = y
        y = max(0, vh - src_h)
        print(f"Warning: Clamped Y from {old_y} to {y} (virtual screen height: {vh})")

    print(f"Mode: {args.mode}")
    print(f"Capturing region: ({x}, {y}) {src_w}x{src_h} -> {WIDTH}x{HEIGHT}")
    print(f"Settings: {args.fps} fps, quality {args.quality}, port {args.port}")

    broadcaster = FrameBroadcaster(x, y, args.fps, args.quality, src_w, src_h)

    server = await asyncio.start_server(
        lambda r, w: handle_client(broadcaster, r, w),
        "0.0.0.0",
        args.port,
    )

    loop = asyncio.get_event_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, lambda: asyncio.ensure_future(_shutdown(broadcaster, server)))

    print(f"Serving MJPEG on http://0.0.0.0:{args.port}")
    print("Open in browser or connect Google Glass to verify")

    capture_task = asyncio.ensure_future(broadcaster.capture_loop())

    async with server:
        await server.serve_forever()


async def _shutdown(broadcaster, server):
    print("\nShutting down...")
    broadcaster.stop()
    server.close()


if __name__ == "__main__":
    asyncio.run(main())
