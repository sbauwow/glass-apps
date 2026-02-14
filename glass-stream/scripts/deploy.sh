#!/bin/bash
# Build, install, launch Glass Stream, and set up USB forwarding
# Usage: ./deploy.sh

set -e
cd "$(dirname "$0")/.."

echo "==> Building..."
./gradlew assembleDebug -q

echo "==> Installing..."
adb install -r app/build/outputs/apk/debug/app-debug.apk

echo "==> Launching..."
adb shell am start -n com.example.glassstream/.StreamActivity

echo "==> Setting up USB port forwarding..."
adb forward tcp:8080 tcp:8080

echo ""
echo "Ready! View the stream:"
echo "  ffplay -fflags nobuffer -flags low_delay http://localhost:8080/stream"
echo "  or open http://localhost:8080/ in a browser"
