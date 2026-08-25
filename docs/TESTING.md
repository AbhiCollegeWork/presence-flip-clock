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
| Presence keeps display awake | n/a | PASS (stayed bright while camera saw room motion) |
| Idle/covered -> dim to 0 | n/a | **PASS** - covered camera, logcat: `brightness -> 0.00` ~30 s later |
| Motion/tap -> wake | PASS | **PASS** - logcat: `brightness -> 1.00` on tap |
| 16 KB-aligned native libs (Android 15/16) | n/a | **PASS** - both CameraX `.so` LOAD segments align `0x4000` |

### Verified presence loop (real device, logcat, tag `PresenceClock`)

```
brightness -> 1.00 (launch)
luma=7.7 diff=0.0 dark=true motion=false   (camera covered)
brightness -> 0.00 (animate=true)          (~30 s idle -> dims fully, looks off on OLED)
brightness -> 1.00 (animate=true)          (tap/motion -> wakes)
```

### Deep power-off mode (v1.2, real device)

Verified on the S25 Ultra: enabling the mode + Device Admin, then idle ->
`mWakefulness=Dozing` / `mScreenState=DOZE` (panel off; on an LCD phone with no always-on
display this is fully off). Power button -> `mWakefulness=Awake` and the clock is the
`topResumedActivity`, shown over the keyguard - so it returns instantly without unlocking.
Trade-off documented: a truly-off screen can't run the camera, so wake is via the power
button, not presence.

Two bugs found and fixed during device testing:
1. **Covered camera never dimmed** - a dark lens produces noisy frames whose diff read as
   constant motion. Fixed with a dark-luminance gate (`luma < 12` -> no motion).
2. **16 KB compatibility warning** on Android 16 - CameraX 1.3.4 shipped non-16 KB-aligned
   native libs. Fixed by upgrading to CameraX 1.4.2. Default dim is now 0 % (fully dark).

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
