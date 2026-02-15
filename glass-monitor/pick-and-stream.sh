#!/bin/bash
# pick-and-stream.sh — Pick a screen region then start glass-monitor
#
# Move your mouse to the desired top-left corner, press Enter to start streaming.
# All extra arguments are forwarded to glass_monitor.py (--fps, --quality, --port, --mode).
#
# Usage:
#   ./pick-and-stream.sh
#   ./pick-and-stream.sh --fps 20 --quality 80 --mode zoom

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

CYAN='\033[1;36m'
GREEN='\033[1;32m'
YELLOW='\033[1;33m'
DIM='\033[2m'
RESET='\033[0m'

echo -e "${CYAN}Displays:${RESET}"
xrandr --listmonitors | tail -n +2 | while read line; do
    name=$(echo "$line" | awk '{print $2}' | sed 's/^+\*//' | sed 's/^+//')
    geom=$(echo "$line" | awk '{print $3}')
    res=$(echo "$geom" | sed 's|/[0-9]*||g' | sed 's/+/ @ /;s/+/,/')
    echo -e "  ${GREEN}$name${RESET}  $res"
done

echo
echo -e "${YELLOW}Move mouse to capture origin. Enter=start streaming, q=quit${RESET}"
echo

stty -echo -icanon min 0 time 0

cleanup() {
    stty echo icanon 2>/dev/null
}
trap cleanup EXIT INT

while true; do
    eval $(xdotool getmouselocation --shell)
    printf "\r  Position: ${CYAN}%-5d${RESET}, ${CYAN}%-5d${RESET}  " "$X" "$Y"

    key=$(dd bs=1 count=1 2>/dev/null)
    if [ "$key" = "q" ]; then
        echo
        exit 0
    elif [ "$key" = "" ]; then
        eval $(xdotool getmouselocation --shell)
        REGION="$X,$Y"
        break
    fi

    sleep 0.05
done

# Restore terminal before launching server
stty echo icanon

echo
echo -e "${GREEN}Starting glass-monitor with --region $REGION $@${RESET}"
echo

exec python3 "$SCRIPT_DIR/glass_monitor.py" --region "$REGION" --no-monitor "$@"
