#!/bin/bash
# pick-region.sh — Find X,Y coordinates for glass-monitor --region
#
# Move your mouse to the desired top-left corner of the capture region,
# then press Enter to capture the coordinates. Press q to quit.
#
# Usage:
#   ./pick-region.sh
#   python glass_monitor.py --region <captured X,Y>

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
echo -e "${YELLOW}Move mouse to position. Enter=capture, q=quit${RESET}"
echo

# Put terminal in raw mode for non-blocking reads
stty -echo -icanon min 0 time 0

cleanup() {
    stty echo icanon
    echo
    exit 0
}
trap cleanup EXIT INT

while true; do
    eval $(xdotool getmouselocation --shell)
    printf "\r  Position: ${CYAN}%-5d${RESET}, ${CYAN}%-5d${RESET}  " "$X" "$Y"

    key=$(dd bs=1 count=1 2>/dev/null)
    if [ "$key" = "q" ]; then
        break
    elif [ "$key" = "" ]; then
        # Enter key (newline)
        eval $(xdotool getmouselocation --shell)
        echo
        echo -e "  ${GREEN}>>>  --region $X,$Y${RESET}"
        echo
    fi

    sleep 0.05
done
