# Glass Launcher

Custom home screen launcher for Google Glass Explorer Edition with gesture-driven app carousel and system-wide dialog navigation.

## Features

### App Carousel
- Horizontal scrolling carousel of installed apps
- Visual selection highlight on the active card
- Pinned apps appear first (configurable in source)
- Status bar with date/time and battery percentage

### System Dialog Navigator
Glass's touchpad can't interact with system dialogs (USB permissions, app install confirmations, etc.). The `ButtonRemapService` accessibility service detects dialog windows and shows a transparent overlay that translates touchpad gestures into dialog actions:

- **Swipe right/left**: Cycle between clickable elements (buttons, checkboxes)
- **Tap touchpad**: Click the selected element
- **Camera button**: Click the selected element (most reliable)
- **Swipe down**: Dismiss dialog

A cyan highlight rectangle shows which element is currently selected. Defaults to the last element (typically OK/positive button).

### Camera Button Remap
System-wide via `ButtonRemapService` (AccessibilityService):
- **Short press**: Open camera
- **Long press**: Go home
- **During dialog**: Click selected element

## Glass Controls

| Gesture | Launcher | Dialog |
|---------|----------|--------|
| Tap | Launch selected app | Click selected element |
| Swipe right | Next app | Next element |
| Swipe left | Previous app | Previous element |
| Swipe down | Open settings | Dismiss dialog |
| Camera press | Open camera | Click selected element |
| Camera long press | Go home | — |

## Setup

### Build and Install

```bash
cd ~/glass-apps/glass-launcher
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Enable Accessibility Service

Required for camera button remap and dialog navigation. Without this, the camera long-press-to-home and system dialog navigation will not work.

**Option A — On-device (recommended):** On first launch, a cyan banner appears at the bottom of the home screen saying "Tap to enable camera button & dialog navigation". Tap the touchpad to open Accessibility Settings, then select "Glass Launcher Button Remap" and enable it.

**Option B — Via adb:**

```bash
adb shell settings put secure enabled_accessibility_services \
  com.example.glasslauncher/com.example.glasslauncher.service.ButtonRemapService
adb shell settings put secure accessibility_enabled 1
```

The banner disappears automatically once the service is enabled.

## Files

| File | Purpose |
|------|---------|
| `LauncherActivity.java` | Home screen carousel, gesture handling, status bar |
| `AppInfo.java` | App metadata model |
| `gesture/GlassGestureHandler.java` | Touchpad gesture detection (swipe, tap, long press) |
| `gesture/GestureActionMap.java` | Configurable gesture-to-action mapping |
| `service/ButtonRemapService.java` | AccessibilityService: camera remap + dialog detection |
| `service/DialogNavigator.java` | Dialog overlay: node scanning, gesture capture, selection highlight |
| `util/AppLaunchHelper.java` | App/camera/settings launch utilities |
| `util/PrefsManager.java` | SharedPreferences wrapper |

## How Dialog Navigation Works

1. `ButtonRemapService` listens for `TYPE_WINDOW_STATE_CHANGED` accessibility events
2. When a dialog class is detected (AlertDialog, PermissionActivity, etc.), it scans the accessibility node tree for clickable elements
3. A `TYPE_SYSTEM_ERROR` overlay (highest window layer) is placed above the dialog to capture touchpad events
4. Swipe gestures cycle a cyan highlight between elements; tap or camera button triggers `performAction(ACTION_CLICK)` on the selected node
5. Overlay auto-hides when the dialog is dismissed or a click is performed

## Requirements

- **Glass**: API 19, no AndroidX
- **Permissions**: `SYSTEM_ALERT_WINDOW` (for dialog overlay), `BIND_ACCESSIBILITY_SERVICE`
