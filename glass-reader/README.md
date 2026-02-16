# Glass Reader

PDF and EPUB reader for Google Glass Enterprise Edition. Extracts text from PDFs and EPUBs and displays them as paginated readable text on the 640x360 display. Two reading modes: **book mode** (swipe through pages) and **teleprompter mode** (auto-scrolling text).

## Setup

Place PDF or EPUB files in `/sdcard/glass-reader/` on the device:

```
adb shell mkdir -p /sdcard/glass-reader
adb push mybook.epub /sdcard/glass-reader/
adb push document.pdf /sdcard/glass-reader/
```

Launch "Glass Reader" from the app launcher. If only one file is present, it opens automatically. Otherwise a file picker is shown.

## Controls

### File Picker

| Gesture | Action |
|---------|--------|
| Swipe forward | Select next file |
| Swipe backward | Select previous file |
| Tap | Open selected file |
| Swipe down | Exit |

### Book Mode

| Gesture | Action |
|---------|--------|
| Swipe forward | Next page |
| Swipe backward | Previous page |
| Tap | Switch to teleprompter |
| Long press | Toggle status bar |
| Swipe down | Exit to file picker |
| DPAD left/right | Previous/next page |

### Teleprompter Mode

Auto-scrolling starts automatically when entering this mode.

| Gesture | Action |
|---------|--------|
| Tap | Switch to book mode |
| Swipe forward | Speed up scrolling |
| Swipe backward | Slow down scrolling |
| Long press | Pause / resume scrolling |
| Swipe down | Exit to file picker |
| DPAD up/down | Increase/decrease speed |

## Features

- **PDF extraction** via PdfBox-Android — page-by-page with progress indicator
- **EPUB extraction** via built-in `java.util.zip` + `XmlPullParser` — parses OPF spine and converts XHTML chapters to plain text, no extra dependencies
- **Word-wrap** using `Paint.measureText()` for pixel-accurate line breaking
- **Reading state persistence** — saves page, scroll offset, and mode per file via SharedPreferences; resumes on relaunch
- **Status bar** — shows filename, page number (book) or scroll percentage (teleprompter), and mode indicator (BOOK/SCROLL/PAUSED)
- **Canvas rendering** — direct pixel control, no dp/sp (avoids Glass hdpi scaling trap)

## Building

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires Android SDK at `~/android-sdk/`. Uses Gradle wrapper symlinked from glass-launcher.

## Architecture

```
com.example.glassreader/
  FilePickerActivity    Launcher — scans /sdcard/glass-reader/ for PDFs
  ReaderActivity        Main reading UI, gesture dispatch, mode switching
  ReaderView            Custom Canvas View — book pages + teleprompter scroll
  TextPaginator         Word-wrap + page splitting via Paint.measureText()
  PdfTextExtractor      PdfBox wrapper, background thread extraction
  ReadingState          SharedPreferences persistence for position per file
```

## Dependencies

- `com.tom-roush:pdfbox-android:2.0.27.0` — pure Java PDF text extraction, no native .so
- compileSdk 34, minSdk 19, targetSdk 19, Java 11, AGP 8.9.0, no AndroidX
