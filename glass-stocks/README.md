# glass-stocks

StockCharts Voyeur viewer for Google Glass. Displays the 10 rotating community stock charts from [StockCharts Voyeur](https://stockcharts.com/voyeur.html) as a fullscreen slideshow with three zoom levels.

## Build & Install

```bash
cd ~/glass-apps/glass-stocks
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Launch

```bash
adb shell am start -n com.glassstocks/.MainActivity
```

## Controls

| Input | Action |
|-------|--------|
| Tap | Cycle zoom: Fit → Fill → Close-up |
| Swipe right | Next chart |
| Swipe left | Previous chart |
| Swipe down | Exit |
| Long press | Exit |

## Zoom Modes

| Mode | Description |
|------|-------------|
| Fit | Full chart scaled to fit screen (default) |
| Fill | Center-crop to fill screen |
| Close-up | 2.5x zoom anchored top-left to read the ticker symbol |

Slideshow pauses while zoomed. Tap back to Fit to resume, or swipe to the next chart.

## How it works

- Fetches 10 PNG charts from `stockcharts.com/voyeur/voyeur1.png` through `voyeur10.png`
- Auto-cycles every 10 seconds
- Re-fetches all images every 60 seconds (matches server refresh interval)
- Counter overlay shows current position (e.g. `3 / 10`)

**Permissions:** `INTERNET`, `WAKE_LOCK`

No companion required.
