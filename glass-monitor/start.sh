#!/bin/bash
# Start glass-monitor server with cleanup and adb reverse.
# Usage: ./start.sh [mode] [extra args...]
#   ./start.sh              # default: zoom mode
#   ./start.sh quarter      # quarter mode
#   ./start.sh full         # full mode
#   ./start.sh zoom --fps 15 --quality 50

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VENV="$SCRIPT_DIR/.venv/bin/python"
SERVER="$SCRIPT_DIR/glass_monitor.py"
PORT=8080

MODE="${1:-zoom}"
shift 2>/dev/null || true

# --- Cleanup ---

# Kill any existing glass_monitor processes
if pgrep -f "glass_monitor.py" > /dev/null 2>&1; then
    echo "Killing old glass-monitor processes..."
    pkill -f "glass_monitor.py" 2>/dev/null || true
    sleep 1
fi

# Kill anything else on port 8080
PID=$(ss -tlnp 2>/dev/null | grep ":$PORT " | grep -oP 'pid=\K[0-9]+' | head -1)
if [ -n "$PID" ]; then
    echo "Killing process $PID on port $PORT..."
    kill "$PID" 2>/dev/null || true
    sleep 1
fi

# Remove stale adb reverse
adb reverse --remove tcp:$PORT 2>/dev/null || true

# --- Start ---

if adb reverse tcp:$PORT tcp:$PORT 2>/dev/null; then
    echo "adb reverse tcp:$PORT set up"
else
    echo "adb reverse failed (no device?) — streaming on LAN only"
fi

echo "Starting glass-monitor (mode: $MODE)..."
"$VENV" "$SERVER" --mode "$MODE" --no-monitor --port "$PORT" "$@" &
SERVER_PID=$!

sleep 1

if kill -0 "$SERVER_PID" 2>/dev/null; then
    echo ""
    echo "Glass monitor running (pid $SERVER_PID, port $PORT, mode $MODE)"
    echo "Launch on Glass:  adb shell am start -n com.glassdisplay/.MainActivity"
    echo "Stop:             kill $SERVER_PID"
    wait "$SERVER_PID"
else
    echo "Server failed to start. Check logs above."
    exit 1
fi
