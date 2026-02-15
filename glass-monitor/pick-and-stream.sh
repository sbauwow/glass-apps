#!/bin/bash
# pick-and-stream.sh — Pick a screen region then start glass-monitor
#
# Move your mouse to the desired top-left corner, press Enter to start streaming.
# Extra arguments are forwarded to glass_monitor.py (--fps, --quality, --mode).
#
# Usage:
#   ./pick-and-stream.sh
#   ./pick-and-stream.sh --fps 20 --quality 80 --mode zoom

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VENV="$SCRIPT_DIR/.venv/bin/python"
SERVER="$SCRIPT_DIR/glass_monitor.py"
PORT=8080

CYAN='\033[1;36m'
GREEN='\033[1;32m'
YELLOW='\033[1;33m'
RED='\033[1;31m'
RESET='\033[0m'

# Auto-detect Glass serial
GLASS_SERIAL=$(adb devices -l 2>/dev/null | grep 'model:Glass' | awk '{print $1}')
if [ -n "$GLASS_SERIAL" ]; then
    ADB="adb -s $GLASS_SERIAL"
else
    ADB="adb"
fi

# --- Cleanup old processes ---

if pgrep -f "glass_monitor.py" > /dev/null 2>&1; then
    echo -e "${YELLOW}Killing old glass-monitor processes...${RESET}"
    pkill -f "glass_monitor.py" 2>/dev/null || true
    sleep 1
fi

PID=$(ss -tlnp 2>/dev/null | grep ":$PORT " | grep -oP 'pid=\K[0-9]+' | head -1)
if [ -n "$PID" ]; then
    echo -e "${YELLOW}Killing process $PID on port $PORT...${RESET}"
    kill "$PID" 2>/dev/null || true
    sleep 1
fi

$ADB reverse --remove tcp:$PORT 2>/dev/null || true

# --- Pick region ---

echo -e "${CYAN}Displays:${RESET}"
xrandr --listmonitors | tail -n +2 | while read line; do
    name=$(echo "$line" | awk '{print $2}' | sed 's/^+\*//' | sed 's/^+//')
    geom=$(echo "$line" | awk '{print $3}')
    res=$(echo "$geom" | sed 's|/[0-9]*||g' | sed 's/+/ @ /;s/+/,/')
    echo -e "  ${GREEN}$name${RESET}  $res"
done

echo
echo -e "${YELLOW}Move mouse to capture origin. You have 5 seconds...${RESET}"
echo

for i in 5 4 3 2 1; do
    eval $(xdotool getmouselocation --shell)
    printf "\r  ${CYAN}%d${RESET}  Position: ${CYAN}%-5d${RESET}, ${CYAN}%-5d${RESET}  " "$i" "$X" "$Y"
    sleep 1
done

eval $(xdotool getmouselocation --shell)
REGION="$X,$Y"
echo
echo -e "  ${GREEN}>>> Captured --region $REGION${RESET}"

# --- Start server ---

echo
if $ADB reverse tcp:$PORT tcp:$PORT 2>/dev/null; then
    echo -e "${GREEN}$ADB reverse tcp:$PORT set up${RESET}"
else
    echo -e "${YELLOW}$ADB reverse failed (no device?) — streaming on LAN only${RESET}"
fi

echo -e "${GREEN}Starting glass-monitor with --region $REGION $@${RESET}"
"$VENV" "$SERVER" --region "$REGION" --no-monitor --port "$PORT" "$@" &
SERVER_PID=$!

sleep 1

if kill -0 "$SERVER_PID" 2>/dev/null; then
    echo ""
    echo -e "${GREEN}Glass monitor running (pid $SERVER_PID, port $PORT)${RESET}"
    echo "Launch on Glass:  $ADB shell am start -n com.glassdisplay/.MainActivity"
    echo "Stop:             kill $SERVER_PID"
    wait "$SERVER_PID"
else
    echo -e "${RED}Server failed to start. Check logs above.${RESET}"
    exit 1
fi
