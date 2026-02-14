# Glass Monitor

MJPEG streaming server that captures a region of your X11 display and streams it to Google Glass (or any HTTP client). Uses `xrandr` to create a virtual monitor named GLASS, then captures that region and serves it as a live MJPEG feed.

## How It Works

1. Creates a 640x360 virtual monitor via `xrandr --setmonitor`
2. Captures the screen region at the virtual monitor's coordinates using `mss`
3. Encodes frames as JPEG and serves them over HTTP as a multipart MJPEG stream
4. Google Glass (or a browser) connects and receives the live feed

## Requirements

- Python 3
- X11 with `xrandr`
- Dependencies: `mss`, `Pillow`

## Setup

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

## Usage

```bash
# Default: creates GLASS monitor at bottom-right of largest display
python glass_monitor.py

# Custom capture region (x,y origin)
python glass_monitor.py --region 1080,1880

# Skip virtual monitor creation (use with existing monitor or --region)
python glass_monitor.py --no-monitor --region 1080,1880

# Adjust FPS, JPEG quality, or port
python glass_monitor.py --fps 20 --quality 80 --port 9090
```

### Options

| Flag | Default | Description |
|------|---------|-------------|
| `--fps` | 30 | Target frames per second |
| `--quality` | 70 | JPEG quality (1-100) |
| `--port` | 8080 | HTTP server port |
| `--region X,Y` | auto | Capture origin coordinates |
| `--no-monitor` | false | Skip `xrandr` virtual monitor setup |
| `--mode` | quarter | Capture mode: `full`, `quarter`, or `zoom` |

### Capture Modes

All modes output 640x360 to Glass. They differ in what region of the primary display is captured:

| Mode | Captures | Scaling | Use case |
|------|----------|---------|----------|
| `quarter` | 640x360 from bottom-left | None (1:1) | Default, native resolution |
| `full` | Entire display (e.g. 1280x800) | Scaled down | Overview of full screen |
| `zoom` | 320x180 from bottom-left | 2x upscale | Zoomed in, more readable |

```bash
# Full display scaled to fit Glass
python glass_monitor.py --no-monitor --mode full

# Bottom-left quadrant, native resolution (default)
python glass_monitor.py --no-monitor --mode quarter

# 2x zoom into bottom-left corner
python glass_monitor.py --no-monitor --mode zoom
```

### Assigning an Output for arandr

By default the virtual monitor is not tied to a physical output. To make it visible in arandr, delete and recreate it with a disconnected output:

```bash
xrandr --delmonitor GLASS
xrandr --setmonitor GLASS 640/169x360/95+1080+1880 DP-1-2
```

## Launch Script

`launch-glass-claude.sh` opens a `urxvt` terminal sized and positioned to the GLASS region, running Claude Code. Edit the `GLASS_X`, `GLASS_Y`, `COLS`, and `ROWS` variables to match your layout.

```bash
./launch-glass-claude.sh
```

## Stream URL

Once running, the MJPEG stream is available at:

```
http://<host-ip>:8080
```

Open in a browser to verify, or point Google Glass to this URL.
