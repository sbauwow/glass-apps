#!/bin/bash
# View Glass camera stream via ffplay
# Usage: ./view.sh          (USB — uses localhost via adb forward)
#        ./view.sh wifi      (WiFi — auto-detects Glass IP from adb)
#        ./view.sh 192.168.x.x  (WiFi — manual IP)

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

URL="http://${HOST}:8080/stream"
echo "Streaming from $URL"
echo "Press q to quit"
exec ffplay -fflags nobuffer -flags low_delay -window_title "Glass Stream" "$URL"
