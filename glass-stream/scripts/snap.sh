#!/bin/bash
# Save a single snapshot from Glass camera
# Usage: ./snap.sh              (USB — uses localhost via adb forward)
#        ./snap.sh wifi          (WiFi — auto-detects Glass IP from adb)
#        ./snap.sh 192.168.x.x   (WiFi — manual IP)
#
# Output: glass_snap_YYYYMMDD_HHMMSS.jpg in current directory

set -e

get_glass_ip() {
    ip=$(adb shell ip route 2>/dev/null | grep -oP 'src \K[0-9.]+' | head -1)
    if [ -z "$ip" ]; then
        echo "Error: Could not detect Glass IP. Is it connected to WiFi?" >&2
        exit 1
    fi
    echo "$ip"
}

if [ "$1" = "wifi" ]; then
    HOST=$(get_glass_ip)
    echo "Glass WiFi IP: $HOST"
elif [ -n "$1" ]; then
    HOST="$1"
else
    adb forward tcp:8080 tcp:8080 2>/dev/null
    HOST="localhost"
    echo "USB mode (adb forward)"
fi

FILENAME="glass_snap_$(date +%Y%m%d_%H%M%S).jpg"
URL="http://${HOST}:8080/snapshot"

curl -s -o "$FILENAME" "$URL"
echo "Saved: $FILENAME"
