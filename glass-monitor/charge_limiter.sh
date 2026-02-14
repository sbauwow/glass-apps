#!/bin/bash
# Battery charge limiter for Google Glass via ADB
# Caps charging at 80%, resumes at 75%

CHARGE_LIMIT=80
CHARGE_RESUME=75
POLL_INTERVAL=60
SYSFS="/sys/devices/platform/omap_i2c.1/i2c-1/1-0049/twl6030_charger/charge_disabled"

charging_disabled=false

echo "Glass charge limiter: limit=${CHARGE_LIMIT}%, resume=${CHARGE_RESUME}%"

while true; do
    # Read battery level
    level=$(adb shell cat /sys/class/power_supply/bq27520-0/capacity 2>/dev/null | tr -d '\r')
    status=$(adb shell cat /sys/class/power_supply/bq27520-0/status 2>/dev/null | tr -d '\r')

    if [ -z "$level" ]; then
        echo "$(date +%H:%M:%S) Device not reachable, retrying..."
        sleep 10
        continue
    fi

    if [ "$level" -ge "$CHARGE_LIMIT" ] && [ "$charging_disabled" = false ]; then
        adb shell "echo 1 > $SYSFS"
        charging_disabled=true
        echo "$(date +%H:%M:%S) Battery ${level}% — charging DISABLED"
    elif [ "$level" -le "$CHARGE_RESUME" ] && [ "$charging_disabled" = true ]; then
        adb shell "echo 0 > $SYSFS"
        charging_disabled=false
        echo "$(date +%H:%M:%S) Battery ${level}% — charging ENABLED"
    else
        echo "$(date +%H:%M:%S) Battery ${level}% (${status}) — $([ "$charging_disabled" = true ] && echo 'charging off' || echo 'charging on')"
    fi

    sleep "$POLL_INTERVAL"
done
