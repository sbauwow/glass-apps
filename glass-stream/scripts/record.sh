#!/bin/bash
# Record Glass camera stream to MP4
# Usage: ./record.sh             (USB — uses localhost via adb forward)
#        ./record.sh wifi         (WiFi — auto-detects Glass IP from adb)
#        ./record.sh 192.168.x.x  (WiFi — manual IP)
#
# Output: glass_YYYYMMDD_HHMMSS.mp4 in current directory
# Press q to stop recording.

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

FILENAME="glass_$(date +%Y%m%d_%H%M%S).mp4"
URL="http://${HOST}:8080/stream"

echo "Recording from $URL"
echo "Output: $FILENAME"
echo "Press q to stop"
exec ffmpeg -i "$URL" -c:v libx264 -preset fast "$FILENAME"
