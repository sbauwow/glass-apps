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

echo -e "${YELLOW}Killing old glass-monitor processes...${RESET}"
pkill -9 -f "glass_monitor.py" 2>/dev/null || true
sleep 0.5

# Ensure port is actually free
PID=$(ss -tlnp 2>/dev/null | grep ":$PORT " | grep -oP 'pid=\K[0-9]+' | head -1)
if [ -n "$PID" ]; then
    echo -e "${YELLOW}Killing process $PID on port $PORT...${RESET}"
    kill -9 "$PID" 2>/dev/null || true
    sleep 0.5
fi

$ADB reverse --remove tcp:$PORT 2>/dev/null || true

# --- Pick region or display ---

echo -e "${CYAN}Displays:${RESET}"
DISPLAY_NAMES=()
while read line; do
    name=$(echo "$line" | awk '{print $2}' | sed 's/^+\*//' | sed 's/^+//')
    geom=$(echo "$line" | awk '{print $3}')
    res=$(echo "$geom" | sed 's|/[0-9]*||g' | sed 's/+/ @ /;s/+/,/')
    echo -e "  ${GREEN}$name${RESET}  $res"
    DISPLAY_NAMES+=("$name")
done < <(xrandr --listmonitors | tail -n +2)

# Check if --display was passed in extra args
DISPLAY_MODE=""
for arg in "$@"; do
    case "$arg" in
        --display) DISPLAY_MODE="next" ;;
        --display=*) DISPLAY_MODE="${arg#--display=}" ;;
        *) [ "$DISPLAY_MODE" = "next" ] && DISPLAY_MODE="$arg" ;;
    esac
done

EXTRA_ARGS=()
REGION_ARG=""

if [ -n "$DISPLAY_MODE" ] && [ "$DISPLAY_MODE" != "next" ]; then
    # Stream an entire display
    echo
    echo -e "  ${GREEN}>>> Streaming display: $DISPLAY_MODE${RESET}"
    EXTRA_ARGS+=(--display "$DISPLAY_MODE")
    # Filter --display from forwarded args
    SKIP_NEXT=false
    for arg in "$@"; do
        if $SKIP_NEXT; then SKIP_NEXT=false; continue; fi
        case "$arg" in
            --display) SKIP_NEXT=true ;;
            --display=*) ;;
            *) EXTRA_ARGS+=("$arg") ;;
        esac
    done
else
    # Interactive region picker
    echo
    echo -e "${YELLOW}Move mouse to capture origin. You have 5 seconds...${RESET}"
    echo

    for i in 5 4 3 2 1; do
        eval $(xdotool getmouselocation --shell)
        printf "\r  ${CYAN}%d${RESET}  Position: ${CYAN}%-5d${RESET}, ${CYAN}%-5d${RESET}  " "$i" "$X" "$Y"
        sleep 1
    done

    eval $(xdotool getmouselocation --shell)
    REGION_ARG="$X,$Y"
    echo
    echo -e "  ${GREEN}>>> Captured --region $REGION_ARG${RESET}"
    EXTRA_ARGS=("$@")
fi

# --- Start server ---

echo
ADB_REVERSE=false
if $ADB reverse tcp:$PORT tcp:$PORT 2>/dev/null; then
    echo -e "${GREEN}$ADB reverse tcp:$PORT set up${RESET}"
    ADB_REVERSE=true
else
    echo -e "${YELLOW}$ADB reverse failed (no device?) — streaming on LAN only${RESET}"
fi

if [ -n "$REGION_ARG" ]; then
    echo -e "${GREEN}Starting glass-monitor with --region $REGION_ARG ${EXTRA_ARGS[*]}${RESET}"
    "$VENV" "$SERVER" --region "$REGION_ARG" --no-monitor --port "$PORT" "${EXTRA_ARGS[@]}" &
else
    echo -e "${GREEN}Starting glass-monitor with ${EXTRA_ARGS[*]}${RESET}"
    "$VENV" "$SERVER" --no-monitor --port "$PORT" "${EXTRA_ARGS[@]}" &
fi
SERVER_PID=$!

sleep 1

if kill -0 "$SERVER_PID" 2>/dev/null; then
    echo ""
    echo -e "${GREEN}Glass monitor running (pid $SERVER_PID, port $PORT)${RESET}"
    echo "Stop:             kill $SERVER_PID"
    # Auto-launch glass-display
    if $ADB_REVERSE; then
        # USB: use localhost via adb reverse tunnel
        $ADB shell am start -n com.glassdisplay/.MainActivity --es host localhost 2>/dev/null \
            && echo -e "${GREEN}Launched glass-display on Glass (USB)${RESET}"
    else
        # WiFi: user must launch manually with host IP
        HOST_IP=$(hostname -I 2>/dev/null | awk '{print $1}')
        echo -e "${YELLOW}Launch on Glass:  $ADB shell am start -n com.glassdisplay/.MainActivity --es host $HOST_IP${RESET}"
    fi
    wait "$SERVER_PID"
else
    echo -e "${RED}Server failed to start. Check logs above.${RESET}"
    exit 1
fi
