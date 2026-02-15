#!/bin/bash
# Battery charge limiter for Google Glass via ADB
# Caps charging at 80%, resumes at 75%

CHARGE_LIMIT=80
CHARGE_RESUME=75
POLL_INTERVAL=60
SYSFS="/sys/devices/platform/omap_i2c.1/i2c-1/1-0049/twl6030_charger/charge_disabled"

# Auto-detect Glass serial from connected devices
GLASS_SERIAL=$(adb devices -l 2>/dev/null | grep 'model:Glass' | awk '{print $1}')
if [ -z "$GLASS_SERIAL" ]; then
    echo "Error: No Glass device found. Check adb connection."
    exit 1
fi
ADB="adb -s $GLASS_SERIAL"

charging_disabled=false

echo "Glass charge limiter: limit=${CHARGE_LIMIT}%, resume=${CHARGE_RESUME}% (device: $GLASS_SERIAL)"

while true; do
    # Read battery level
    level=$($ADB shell cat /sys/class/power_supply/bq27520-0/capacity 2>/dev/null | tr -d '\r')
    status=$($ADB shell cat /sys/class/power_supply/bq27520-0/status 2>/dev/null | tr -d '\r')

    if [ -z "$level" ]; then
        echo "$(date +%H:%M:%S) Device not reachable, retrying..."
        sleep 10
        continue
    fi

    if [ "$level" -ge "$CHARGE_LIMIT" ] && [ "$charging_disabled" = false ]; then
        $ADB shell "echo 1 > $SYSFS"
        charging_disabled=true
        echo "$(date +%H:%M:%S) Battery ${level}% — charging DISABLED"
    elif [ "$level" -le "$CHARGE_RESUME" ] && [ "$charging_disabled" = true ]; then
        $ADB shell "echo 0 > $SYSFS"
        charging_disabled=false
        echo "$(date +%H:%M:%S) Battery ${level}% — charging ENABLED"
    else
        echo "$(date +%H:%M:%S) Battery ${level}% (${status}) — $([ "$charging_disabled" = true ] && echo 'charging off' || echo 'charging on')"
    fi

    sleep "$POLL_INTERVAL"
done
