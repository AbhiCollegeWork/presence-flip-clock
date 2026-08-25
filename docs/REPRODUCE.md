# Reproduce this project end to end

This guide takes you from a fresh clone to a built APK running on a real phone, on Windows.
It pairs with [BUILD_NOTES.md](BUILD_NOTES.md), which lists every problem hit while building
this (with the fix), so you should not have to rediscover them.

> This app was built and tested with Claude Code driving the Android SDK and adb. Everything
> below is the distilled, repeatable path.

## 1. Prerequisites

- **JDK 17** (Temurin/Adoptium works). AGP 8.5 is happiest on 17; newer JDKs can trip the
  build. Point `JAVA_HOME` at a 17 JDK for the build shell.
- **Android SDK** with, at minimum:
  - `platform-tools` (adb)
  - `platforms;android-34`
  - `build-tools;34.0.0`
  Install missing pieces with `sdkmanager`:
  ```
  sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"
  ```
- **Gradle 8.7** if you are not using the committed wrapper. The repo does include a wrapper
  (`./gradlew`), which downloads Gradle 8.7 on first run.
- A device or emulator on **API 21+**. For the presence (camera) feature you need a **real
  device** - emulators usually have no usable front camera.

## 2. Clone and point at your SDK

```
git clone https://github.com/AbhiCollegeWork/presence-flip-clock.git
cd presence-flip-clock
```

Create `local.properties` in the project root with your SDK path. **Use forward slashes** -
backslashes get mangled by the Java `.properties` parser and produce a corrupt path:

```
sdk.dir=C:/Users/you/AppData/Local/Android/Sdk
```

`local.properties` is git-ignored; it is machine-specific.

> Avoid a project path containing spaces on Windows if you can. It is not fatal, but some
> Android build steps handle spaces poorly. See BUILD_NOTES.

## 3. Build the APK

With the wrapper:
```
./gradlew :app:assembleDebug
```
Or with a system Gradle 8.7 (if the wrapper cannot download on your network):
```
gradle :app:assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`.

CI does the same on every push - see `.github/workflows/build.yml`. The Actions artifact and
tagged Releases carry a prebuilt debug APK if you just want to install without building.

## 4. Run on an emulator (UI only)

```
emulator -avd <your_avd> -no-window -gpu swiftshader_indirect &
adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.presenceflipclock/.MainActivity
adb exec-out screencap -p > shot.png
```
The clock, settings, and tap-to-wake work here. Camera presence does not (no front camera on
most AVDs) - the app falls back to "tap to wake", which is expected.

## 5. Run and verify on a real device

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.presenceflipclock android.permission.CAMERA
adb shell am start -n com.presenceflipclock/.MainActivity
```

Watch the built-in diagnostics while you test (they are throttled and harmless):
```
adb logcat -s PresenceClock
```
You will see lines like `luma=.. cells=.. dark=.. motion=..` and `brightness -> 1.00 / 0.00`.

- **Dim mode (default):** stay still -> it dims to black after the idle timeout; move close to
  the front camera -> it brightens. On the logs: still -> `cells=0`; movement -> `cells>=`
  the sensitivity threshold -> `motion=true` -> `brightness -> 1.00`.
- **Deep power-off mode (Settings, for LCD / old phones):** idle -> the screen truly powers
  off (Device Admin). Wake with the **power button**.

> Presence dimming changes the **backlight**, not the pixels, so `screencap` always shows a
> bright clock. Judge dim/brighten by eye on the panel, or by the `brightness ->` log lines.

## 6. Windows / Git Bash gotcha for adb paths

If you drive adb from Git Bash, on-device paths like `/sdcard/x.mp4` get rewritten to a
Windows path. Prefix commands with `MSYS_NO_PATHCONV=1`:
```
MSYS_NO_PATHCONV=1 adb shell screenrecord --time-limit 12 /sdcard/clip.mp4
```

## 7. Release (optional)

Tag it; CI attaches the APK to a GitHub Release (the workflow has `contents: write`):
```
git tag v1.5 && git push origin v1.5
```
Or publish directly:
```
gh release create v1.5 app/build/outputs/apk/debug/app-debug.apk --title "..." --notes "..."
```

## 8. Tuning the presence detector

Front-camera presence sensing is inherently scene-dependent. If it wakes too easily or not
easily enough, use the **Motion sensitivity** slider (long-press the clock). The detector and
its calibration are explained in [ARCHITECTURE.md](ARCHITECTURE.md) and BUILD_NOTES.md.
