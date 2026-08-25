# Testing report

## Environments

| Where | What |
|---|---|
| Build host | Windows PC, Android SDK (compileSdk 34, build-tools 34.0.0), **JDK 17**, Gradle 8.7 |
| Emulator | `Pixel 5, API 34` AVD (software GPU) - UI, launch, settings, no-camera fallback |
| Real device | **Samsung Galaxy S25 Ultra (SM-S938B), Android 16** - real front camera, i.e. the presence path the emulator cannot exercise |
| CI | GitHub Actions (Ubuntu) builds the debug APK via the committed `./gradlew` on every push |

> The Mi A2 (the marketing-server phone) was **not** used at any point.

## Results

| Check | Emulator (Pixel 5) | Real device (S25 Ultra) |
|---|---|---|
| `assembleDebug` builds | n/a (built on PC) | APK installs |
| Launches, no crash | PASS | PASS (foreground, no FATAL in logcat) |
| Flip clock + date render | PASS (portrait) | PASS (landscape, hi-res) |
| Responsive sizing | PASS | PASS (width-driven, height-capped) |
| Clock ticks | PASS | PASS (observed 22:49 -> 22:52) |
| Long-press settings dialog | PASS | PASS |
| Tap-to-wake | PASS | PASS |
| Front camera binds (presence path) | n/a (AVD has no camera) -> graceful fallback shown | **PASS** (`CameraService::connect ... camera ID 1`, CameraX streaming) |
| Presence keeps display awake | n/a | PASS (stayed bright 40 s untouched while camera saw room motion) |

## Known limitations / notes

- **Dim state is not screenshottable.** Presence dimming changes the backlight
  (`window screenBrightness`), not the rendered pixels, so `screencap` always shows a bright
  clock. The dim/brighten behaviour must be verified by eye on the physical panel: sit still
  or step away past the idle timeout -> the panel dims; move -> it brightens.
- **Emulators usually have no usable front camera**, so on the emulator the app correctly
  falls back to "presence sensing off - tap to wake". Real-device camera binding is confirmed
  on the S25 Ultra.
- Ships as a **debug** APK (unsigned debug key) for easy sideloading. A signed release build
  needs a signing config.

## How to reproduce the device test

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.presenceflipclock android.permission.CAMERA
adb shell am start -n com.presenceflipclock/.MainActivity
# watch the physical screen: still/away -> dims after the idle timeout; move -> brightens
adb uninstall com.presenceflipclock   # when done
```
