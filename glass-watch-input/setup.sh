#!/usr/bin/env bash
#
# Full setup script for glass-watch-input on Google Glass Explorer Edition.
#
# Builds the APK, installs as priv-app, sets up the root key bridge daemon,
# enables the accessibility service, and reboots Glass.
#
# Usage:
#   ./setup.sh              # build + full setup
#   ./setup.sh --no-build   # skip build, use existing APK
#   ./setup.sh --daemon-only # only set up the key bridge daemon
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK="$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"
PRIV_APP_DIR="/system/priv-app/GlassWatchInput"
PRIV_APP_PATH="$PRIV_APP_DIR/GlassWatchInput.apk"
FIFO_PATH="/data/local/tmp/keybridge"
BOOT_SCRIPT="/system/bin/install-recovery.sh"

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

ok()   { echo -e "  ${GREEN}✓${NC} $1"; }
fail() { echo -e "  ${RED}✗${NC} $1"; exit 1; }
warn() { echo -e "  ${YELLOW}!${NC} $1"; }
info() { echo -e "  ${CYAN}→${NC} $1"; }

# --- Parse args ---
DO_BUILD=true
DAEMON_ONLY=false

for arg in "$@"; do
    case "$arg" in
        --no-build)    DO_BUILD=false ;;
        --daemon-only) DAEMON_ONLY=true; DO_BUILD=false ;;
        -h|--help)
            echo "Usage: $0 [--no-build] [--daemon-only]"
            echo ""
            echo "  --no-build     Skip Gradle build, use existing APK"
            echo "  --daemon-only  Only set up the key bridge daemon (skip APK install)"
            echo ""
            echo "Requires Glass connected via USB with ADB."
            exit 0
            ;;
    esac
done

# --- Preflight checks ---
echo -e "${BOLD}glass-watch-input setup${NC}"
echo ""

if ! command -v adb &>/dev/null; then
    fail "adb not found in PATH"
fi

if ! adb get-state >/dev/null 2>&1; then
    fail "No ADB device connected"
fi

DEVICE_MODEL=$(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')
info "Connected to: $DEVICE_MODEL"

# --- Step 1: Build ---
if $DO_BUILD; then
    echo ""
    echo -e "${CYAN}Building APK...${NC}"
    cd "$SCRIPT_DIR"
    if ./gradlew assembleDebug -q 2>&1; then
        ok "Build successful"
    else
        fail "Build failed"
    fi
fi

if ! $DAEMON_ONLY; then
    if [[ ! -f "$APK" ]]; then
        fail "APK not found at $APK — run ./gradlew assembleDebug first"
    fi

    # --- Step 2: Install as priv-app ---
    echo ""
    echo -e "${CYAN}Installing as priv-app...${NC}"

    # Remove any /data/app/ install (takes precedence over priv-app)
    adb shell pm uninstall com.glasswatchinput >/dev/null 2>&1 || true
    ok "Cleared /data/app/ install (if any)"

    # Remount and push
    adb shell mount -o rw,remount /system 2>/dev/null || true
    adb shell "mkdir -p $PRIV_APP_DIR" 2>/dev/null
    adb push "$APK" "$PRIV_APP_PATH" >/dev/null 2>&1
    adb shell "chmod 644 $PRIV_APP_PATH"
    ok "Pushed APK to $PRIV_APP_PATH"

    # Clear dalvik cache
    adb shell "rm -rf /data/dalvik-cache/arm/system@priv-app@GlassWatchInput@GlassWatchInput.apk*" 2>/dev/null
    ok "Cleared dalvik cache"
fi

# --- Step 3: Set up key bridge daemon ---
echo ""
echo -e "${CYAN}Setting up key bridge daemon...${NC}"

# Create FIFO
adb shell "
if [ ! -p $FIFO_PATH ]; then
    mknod $FIFO_PATH p
    chmod 666 $FIFO_PATH
fi
" 2>/dev/null
ok "FIFO at $FIFO_PATH"

# Create boot script
adb shell mount -o rw,remount /system 2>/dev/null || true

adb shell "cat > $BOOT_SCRIPT << 'BOOTSCRIPT'
#!/system/bin/sh
# Key bridge daemon for glass-watch-input
# Reads key codes from FIFO and injects them via 'input keyevent' as root.
# Started on boot by init (flash_recovery service).
FIFO=/data/local/tmp/keybridge
if [ ! -p \$FIFO ]; then
    mknod \$FIFO p
    chmod 666 \$FIFO
fi
(
    while true; do
        while read code; do
            input keyevent \$code
        done < \$FIFO
    done
) &
exit 0
BOOTSCRIPT"

adb shell "chmod 755 $BOOT_SCRIPT"
ok "Boot script at $BOOT_SCRIPT"

adb shell mount -o ro,remount /system 2>/dev/null || true

# --- Step 4: Enable accessibility services ---
if ! $DAEMON_ONLY; then
    echo ""
    echo -e "${CYAN}Enabling accessibility services...${NC}"

    # Get current services and add ours if not present
    CURRENT=$(adb shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')
    OUR_SERVICE="com.glasswatchinput/com.glasswatchinput.InputBridgeService"
    LAUNCHER_SERVICE="com.example.glasslauncher/com.example.glasslauncher.service.ButtonRemapService"

    if [[ "$CURRENT" == *"$OUR_SERVICE"* ]]; then
        ok "InputBridgeService already enabled"
    else
        if [[ -z "$CURRENT" || "$CURRENT" == "null" ]]; then
            NEW_SERVICES="$LAUNCHER_SERVICE:$OUR_SERVICE"
        else
            NEW_SERVICES="$CURRENT:$OUR_SERVICE"
        fi
        adb shell settings put secure enabled_accessibility_services "$NEW_SERVICES"
        adb shell settings put secure accessibility_enabled 1
        ok "InputBridgeService enabled"
    fi
fi

# --- Step 5: Reboot ---
echo ""
echo -e "${CYAN}Rebooting Glass...${NC}"
adb reboot
ok "Reboot initiated"

echo ""
echo -e "${GREEN}${BOLD}Setup complete!${NC}"
echo ""
echo "  After Glass boots (~30s), the service will:"
echo "  1. Start the key bridge daemon (root, reads from FIFO)"
echo "  2. Start InputBridgeService (accessibility service)"
echo "  3. Auto-connect to the Galaxy Watch over BLE"
echo ""
echo "  Verify with: adb logcat -s InputBridge"
