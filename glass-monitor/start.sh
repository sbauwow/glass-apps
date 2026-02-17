#!/bin/bash
# Start glass-monitor server with cleanup and $ADB reverse.
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

# Auto-detect Glass serial
GLASS_SERIAL=$(adb devices -l 2>/dev/null | grep 'model:Glass' | awk '{print $1}')
if [ -n "$GLASS_SERIAL" ]; then
    ADB="adb -s $GLASS_SERIAL"
else
    ADB="adb"
fi

# --- Cleanup ---

# Kill any existing glass_monitor processes
echo "Killing old glass-monitor processes..."
pkill -9 -f "glass_monitor.py" 2>/dev/null || true
sleep 0.5

# Ensure port is actually free
PID=$(ss -tlnp 2>/dev/null | grep ":$PORT " | grep -oP 'pid=\K[0-9]+' | head -1)
if [ -n "$PID" ]; then
    echo "Killing process $PID on port $PORT..."
    kill -9 "$PID" 2>/dev/null || true
    sleep 0.5
fi

# Remove stale $ADB reverse
$ADB reverse --remove tcp:$PORT 2>/dev/null || true

# --- Start ---

ADB_REVERSE=false
if $ADB reverse tcp:$PORT tcp:$PORT 2>/dev/null; then
    echo "$ADB reverse tcp:$PORT set up"
    ADB_REVERSE=true
else
    echo "$ADB reverse failed (no device?) — streaming on LAN only"
fi

echo "Starting glass-monitor (mode: $MODE)..."
"$VENV" "$SERVER" --mode "$MODE" --no-monitor --port "$PORT" "$@" &
SERVER_PID=$!

sleep 1

if kill -0 "$SERVER_PID" 2>/dev/null; then
    echo ""
    echo "Glass monitor running (pid $SERVER_PID, port $PORT, mode $MODE)"
    echo "Stop:             kill $SERVER_PID"
    # Auto-launch glass-display
    if $ADB_REVERSE; then
        # USB: use localhost via adb reverse tunnel
        $ADB shell am start -n com.glassdisplay/.MainActivity --es host localhost 2>/dev/null \
            && echo "Launched glass-display on Glass (USB)"
    else
        # WiFi: user must launch manually with host IP
        HOST_IP=$(hostname -I 2>/dev/null | awk '{print $1}')
        echo "Launch on Glass:  $ADB shell am start -n com.glassdisplay/.MainActivity --es host $HOST_IP"
    fi
    wait "$SERVER_PID"
else
    echo "Server failed to start. Check logs above."
    exit 1
fi
