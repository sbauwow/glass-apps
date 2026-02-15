# Glass Kill

Quick process killer for Google Glass Explorer Edition. Launches, kills all non-essential background processes, shows results, and auto-exits.

## Usage

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.glasskill/.MainActivity
```

## What It Does

1. Enumerates all installed apps and running processes
2. Filters out protected system packages
3. Kills each non-essential process via `killBackgroundProcesses()` and `am force-stop`
4. Shows kill count, auto-exits after 3 seconds

## Protected Packages

The following are never killed:

- `android` / `com.android.*` — Android system
- `com.google.android.*` — Google services
- `com.google.glass.*` — Glass system
- `com.example.glasslauncher` — Custom launcher
- `com.glasskill` — Itself

## Build Requirements

- Android SDK with API 28 (compileSdk) and API 19 (minSdk)
- Java 8+
- Gradle (wrapper included)
