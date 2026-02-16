#!/bin/bash
# Battery charge limiter for any ADB-connected device
# Caps charging at 80%, resumes at 75%
# Monitors all connected devices in parallel

CHARGE_LIMIT=80
CHARGE_RESUME=75
POLL_INTERVAL=60

# Common sysfs paths for disabling charging (write 1 to disable, 0 to enable)
# Listed in order of preference
SYSFS_DISABLE_PATHS=(
    "/sys/devices/platform/omap_i2c.1/i2c-1/1-0049/twl6030_charger/charge_disabled"  # Glass 1 (OMAP/TWL6030)
    "/sys/class/power_supply/battery/charging_enabled"    # Qualcomm (inverted: 0=disable, 1=enable)
    "/sys/class/power_supply/battery/input_suspend"       # Qualcomm newer
    "/sys/class/power_supply/battery/charge_disable"      # Some MediaTek
    "/sys/class/power_supply/usb/device/charge"           # Some older devices (inverted)
)

# Paths where 0 = disable charging (inverted logic)
INVERTED_PATHS=(
    "/sys/class/power_supply/battery/charging_enabled"
    "/sys/class/power_supply/usb/device/charge"
)

is_inverted() {
    local path="$1"
    for p in "${INVERTED_PATHS[@]}"; do
        [ "$path" = "$p" ] && return 0
    done
    return 1
}

# Find a working sysfs path on a device. Sets FOUND_PATH or returns 1.
find_charging_control() {
    local serial="$1"
    for path in "${SYSFS_DISABLE_PATHS[@]}"; do
        if adb -s "$serial" shell "[ -f '$path' ]" 2>/dev/null; then
            echo "$path"
            return 0
        fi
    done
    return 1
}

get_battery_level() {
    local serial="$1"
    adb -s "$serial" shell dumpsys battery 2>/dev/null | grep 'level:' | awk '{print $2}' | tr -d '\r'
}

get_battery_status() {
    local serial="$1"
    adb -s "$serial" shell dumpsys battery 2>/dev/null | grep 'status:' | awk '{print $2}' | tr -d '\r'
}

disable_charging() {
    local serial="$1" path="$2"
    if is_inverted "$path"; then
        adb -s "$serial" shell "echo 0 > '$path'" 2>/dev/null
    else
        adb -s "$serial" shell "echo 1 > '$path'" 2>/dev/null
    fi
}

enable_charging() {
    local serial="$1" path="$2"
    if is_inverted "$path"; then
        adb -s "$serial" shell "echo 1 > '$path'" 2>/dev/null
    else
        adb -s "$serial" shell "echo 0 > '$path'" 2>/dev/null
    fi
}

get_device_model() {
    local serial="$1"
    adb -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r'
}

# Monitor a single device (runs in background)
monitor_device() {
    local serial="$1"
    local model
    model=$(get_device_model "$serial")
    local label="${model:-$serial}"

    local sysfs_path
    sysfs_path=$(find_charging_control "$serial")
    if [ -z "$sysfs_path" ]; then
        echo "[$label] No charging control path found (device may need root)"
        return 1
    fi
    echo "[$label] Using: $sysfs_path"

    local charging_disabled=false

    while true; do
        # Check device is still connected
        if ! adb devices | grep -q "$serial"; then
            echo "$(date +%H:%M:%S) [$label] Device disconnected"
            return 0
        fi

        local level
        level=$(get_battery_level "$serial")

        if [ -z "$level" ]; then
            echo "$(date +%H:%M:%S) [$label] Not reachable, retrying..."
            sleep 10
            continue
        fi

        if [ "$level" -ge "$CHARGE_LIMIT" ] && [ "$charging_disabled" = false ]; then
            disable_charging "$serial" "$sysfs_path"
            charging_disabled=true
            echo "$(date +%H:%M:%S) [$label] Battery ${level}% — charging DISABLED"
        elif [ "$level" -le "$CHARGE_RESUME" ] && [ "$charging_disabled" = true ]; then
            enable_charging "$serial" "$sysfs_path"
            charging_disabled=false
            echo "$(date +%H:%M:%S) [$label] Battery ${level}% — charging ENABLED"
        else
            local state
            [ "$charging_disabled" = true ] && state="off" || state="on"
            echo "$(date +%H:%M:%S) [$label] Battery ${level}% — charging $state"
        fi

        sleep "$POLL_INTERVAL"
    done
}

# --- Main ---

echo "Charge limiter: limit=${CHARGE_LIMIT}%, resume=${CHARGE_RESUME}%"
echo "Scanning for devices..."

pids=()
serials=()

# Collect all connected device serials
while IFS=$'\t ' read -r serial state rest; do
    [ -z "$serial" ] && continue
    [ "$state" != "device" ] && continue
    serials+=("$serial")
done < <(adb devices | tail -n +2)

# Launch a monitor for each device
for serial in "${serials[@]}"; do
    model=$(get_device_model "$serial")
    echo "Found: $serial ($model)"
    monitor_device "$serial" &
    pids+=($!)
done

if [ ${#pids[@]} -eq 0 ]; then
    echo "No devices found. Check adb connection."
    exit 1
fi

echo "Monitoring ${#pids[@]} device(s). Ctrl+C to stop."

# Re-enable charging on all devices when interrupted
cleanup() {
    echo ""
    echo "Stopping — re-enabling charging on all devices..."
    for serial in "${serials[@]}"; do
        local path
        path=$(find_charging_control "$serial")
        if [ -n "$path" ]; then
            enable_charging "$serial" "$path"
            echo "  $(get_device_model "$serial"): charging re-enabled"
        fi
    done
    kill "${pids[@]}" 2>/dev/null
    exit 0
}

trap cleanup SIGINT SIGTERM

wait
