#!/usr/bin/env bash
#
# Interactive installer for the Glass apps suite.
#
# Usage:
#   ./install.sh           # interactive — pick apps to install
#   ./install.sh -y        # install all without prompting
#   ./install.sh -h        # show help
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK_DIR="$SCRIPT_DIR/apks"

# --- App catalog: name|target|description ---
CATALOG=(
    "glass-launcher|glass|Custom home screen with gesture nav, status bar, dialog navigator"
    "glass-term|glass|Terminal emulator with SSH client and USB keyboard support"
    "glass-vnc|glass|VNC remote desktop viewer with 4 zoom modes"
    "glass-display|glass|Fullscreen MJPEG stream viewer"
    "glass-stream|glass|Camera MJPEG streaming server (port 8080)"
    "glass-kill|glass|Kill all non-essential background processes to free memory"
    "glass-stocks|glass|StockCharts Voyeur slideshow with 3 zoom levels"
    "glass-weather|glass|Current conditions and hourly forecast via Open-Meteo"
    "glass-dashboard|glass|News headlines, sports scores, and stock quotes — 3 swipeable pages"
    "glass-rss|glass|Multi-feed RSS reader with swipeable cards"
    "glass-pomodoro|glass|Pomodoro timer — 15min work / 5min break"
    "glass-clawd|glass|Voice-powered Claude AI chat (needs companion server)"
    "glass-notify|glass|Phone notification forwarder + GPS passthrough + tilt-to-wake"
    "glass-flipper|glass|Flipper Zero screen mirror via USB OTG"
    "glass-canon|glass|Canon T5 live viewfinder + shutter control"
    "glass-bike-hud|glass|Biking HUD — heart rate, speed, distance from Galaxy Watch"
    "vesc-glass|glass|Electric skateboard telemetry HUD via VESC BLE"
    "glass-watch-input|priv|BLE input bridge — receives watch D-pad events as key injections"
    "glass-notify-client|phone|Phone companion for glass-notify (notifications + GPS)"
    "watch-input|watch|Galaxy Watch D-pad remote control for Glass"
    "watch-bike-hud|watch|Galaxy Watch sensor broadcaster for glass-bike-hud"
)

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
DIM='\033[2m'
NC='\033[0m'

ok()   { echo -e "  ${GREEN}✓${NC} $1"; }
fail() { echo -e "  ${RED}✗${NC} $1"; }
warn() { echo -e "  ${YELLOW}!${NC} $1"; }

target_label() {
    case "$1" in
        glass) echo "Glass" ;;
        priv)  echo "Glass (priv-app)" ;;
        phone) echo "Phone" ;;
        watch) echo "Watch" ;;
    esac
}

install_apk() {
    local name="$1"
    local apk="$APK_DIR/${name}.apk"

    if [[ ! -f "$apk" ]]; then
        fail "$name — apk not found"
        return 1
    fi

    if adb install -r "$apk" >/dev/null 2>&1; then
        ok "$name"
    else
        fail "$name"
        return 1
    fi
}

install_priv_app() {
    local name="$1"
    local apk="$APK_DIR/${name}.apk"
    local dest="/system/priv-app/${name}/${name}.apk"

    if [[ ! -f "$apk" ]]; then
        fail "$name — apk not found"
        return 1
    fi

    if ! adb root >/dev/null 2>&1; then
        warn "$name — needs root (skipped). Install manually:"
        echo "         adb root && adb remount"
        echo "         adb push apks/${name}.apk $dest"
        echo "         adb reboot"
        return 1
    fi

    adb remount >/dev/null 2>&1 || true
    adb shell "mkdir -p /system/priv-app/${name}" 2>/dev/null
    if adb push "$apk" "$dest" >/dev/null 2>&1; then
        ok "$name (priv-app — reboot required)"
    else
        fail "$name (priv-app push failed)"
        return 1
    fi
}

setup_services() {
    echo ""
    echo -e "${CYAN}Setting up services...${NC}"

    local services="com.example.glasslauncher/com.example.glasslauncher.service.ButtonRemapService"

    # Add InputBridgeService if glass-watch-input was installed
    if $watchinput_selected; then
        services="${services}:com.glasswatchinput/com.glasswatchinput.InputBridgeService"
    fi

    adb shell settings put secure enabled_accessibility_services "$services" \
        >/dev/null 2>&1 && ok "Accessibility services enabled" || warn "Accessibility services — set manually"

    adb shell settings put secure accessibility_enabled 1 \
        >/dev/null 2>&1 || true
}

ask() {
    local prompt="$1"
    local default="${2:-y}"
    local hint
    if [[ "$default" == "y" ]]; then hint="Y/n"; else hint="y/N"; fi

    while true; do
        echo -ne "$prompt ${DIM}[$hint]${NC} " >&2
        read -r reply </dev/tty
        reply="${reply:-$default}"
        case "${reply,,}" in
            y|yes) return 0 ;;
            n|no)  return 1 ;;
            *) echo "  Please enter y or n" >&2 ;;
        esac
    done
}

# --- Main ---

YES_ALL=false

for arg in "$@"; do
    case "$arg" in
        -y|--yes) YES_ALL=true ;;
        -h|--help)
            echo "Usage: $0 [-y] [-h]"
            echo ""
            echo "  -y, --yes    Install all apps without prompting"
            echo "  -h, --help   Show this help"
            echo ""
            echo "Apps:"
            for entry in "${CATALOG[@]}"; do
                IFS='|' read -r name target desc <<< "$entry"
                printf "  %-22s %-16s %s\n" "$name" "[$(target_label "$target")]" "$desc"
            done
            exit 0
            ;;
    esac
done

# Check adb
if ! command -v adb &>/dev/null; then
    echo -e "${RED}Error: adb not found in PATH${NC}"
    exit 1
fi

if ! adb get-state >/dev/null 2>&1; then
    echo -e "${RED}Error: no adb device connected${NC}"
    exit 1
fi

echo -e "${BOLD}Glass Apps Installer${NC}"
echo ""

# Collect selections
selected=()
launcher_selected=false
watchinput_selected=false

for entry in "${CATALOG[@]}"; do
    IFS='|' read -r name target desc <<< "$entry"
    label="$(target_label "$target")"

    echo -e "  ${BOLD}${name}${NC} ${DIM}[${label}]${NC}"
    echo -e "  ${desc}"

    if $YES_ALL; then
        selected+=("$entry")
        [[ "$name" == "glass-launcher" ]] && launcher_selected=true
        [[ "$name" == "glass-watch-input" ]] && watchinput_selected=true
        echo -e "  ${GREEN}→ selected${NC}"
    else
        if ask "  Install?"; then
            selected+=("$entry")
            [[ "$name" == "glass-launcher" ]] && launcher_selected=true
            [[ "$name" == "glass-watch-input" ]] && watchinput_selected=true
        fi
    fi
    echo ""
done

if [[ ${#selected[@]} -eq 0 ]]; then
    echo "Nothing selected."
    exit 0
fi

# Summary
echo -e "${CYAN}Installing ${#selected[@]} app(s)...${NC}"
echo ""

installed=0
failed=0
needs_reboot=false

for entry in "${selected[@]}"; do
    IFS='|' read -r name target desc <<< "$entry"

    case "$target" in
        glass)
            if install_apk "$name"; then ((installed++)); else ((failed++)); fi
            ;;
        priv)
            if install_priv_app "$name"; then
                ((installed++))
                needs_reboot=true
            else
                ((failed++))
            fi
            ;;
        phone)
            if install_apk "$name"; then ((installed++)); else ((failed++)); fi
            ;;
        watch)
            warn "$name — install on watch manually:"
            echo "         adb connect <watch-ip>:5555"
            echo "         adb -s <watch-ip>:5555 install -r apks/${name}.apk"
            ;;
    esac
done

# Enable accessibility services if launcher or watch-input was installed
if $launcher_selected || $watchinput_selected; then
    setup_services
fi

echo ""
echo -e "${GREEN}Done — ${installed} installed${NC}$([ $failed -gt 0 ] && echo -e ", ${RED}${failed} failed${NC}")"

if $needs_reboot; then
    echo ""
    warn "Priv-app installed — reboot Glass to activate:"
    echo "         adb reboot"
fi
