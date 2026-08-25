# Build notes: every problem hit, and the fix

This app was built and tested with Claude Code on Windows, driving the Android SDK, an
emulator, and a real device (Samsung Galaxy S25 Ultra, Android 16). This is the honest log of
what broke and how it was solved, so you can reproduce it without re-hitting the walls.
Format: **Symptom -> Cause -> Fix.**

See [REPRODUCE.md](REPRODUCE.md) for the clean step-by-step.

---

## Toolchain and build

### 1. Gradle build fails: `The filename, directory name, or volume label syntax is incorrect`
- **Symptom:** `Could not determine the dependencies of task ':app:compileDebugJavaWithJavac'`
  with an `IOException` about filename syntax, thrown from `SdkLocator.validateSdkPath`.
- **Cause:** a corrupt `sdk.dir` in `local.properties`. It had been written with single
  backslashes (`C:\Users\...`); the Java `.properties` parser treats `\U`, `\P`, etc. as
  escape sequences and drops the backslash, producing an invalid path.
- **Fix:** write `sdk.dir` with **forward slashes**: `sdk.dir=C:/Users/you/AppData/Local/Android/Sdk`.

### 2. Same error suspected from spaces in the path
- **Symptom:** the project lived under `.../Ai Downloads/...` (a space).
- **Cause:** some AGP steps (JDK-image / jlink) mishandle spaces on Windows; it was a red
  herring here (the real cause was #1), but worth avoiding.
- **Fix:** build from a space-free path, e.g. `C:/Users/you/dev/presence-flip-clock`.

### 3. No committed Gradle wrapper; `gradle wrapper` fails URL validation
- **Symptom:** `Test of distribution url https://services.gradle.org/... failed` even though
  `curl` downloaded the same URL fine.
- **Cause:** the wrapper task's URL pre-check (and the wrapper's own Java downloader) time out
  on some networks/proxies where `curl` succeeds.
- **Fix:** generate the wrapper with `gradle wrapper --gradle-version 8.7 --no-validate-url`.
  If `./gradlew` still cannot self-download on your network, build with a system Gradle 8.7
  once; CI (clean network) validates the committed wrapper.

### 4. AGP 8.5 and JDK version
- **Symptom:** flaky failures in the JDK-image transform under JDK 21.
- **Cause:** AGP 8.5 targets JDK 17.
- **Fix:** point `JAVA_HOME` at a **JDK 17** for the build shell.

### 5. Missing SDK platform
- **Symptom:** build cannot find `android-34`.
- **Cause:** only other platforms were installed.
- **Fix:** `sdkmanager "platforms;android-34" "build-tools;34.0.0"`.

## Android 15 / 16 compatibility

### 6. `This app isn't 16 KB-compatible` warning on Android 16
- **Symptom:** system dialog: `libimage_processing_util_jni.so : LOAD segment not aligned`.
- **Cause:** CameraX 1.3.4 shipped native libraries not aligned to 16 KB pages (required by
  Android 15/16 large-page devices and Google Play from Nov 2025).
- **Fix:** upgrade to **CameraX 1.4.2**. Verify the `.so` LOAD alignment is `0x4000`:
  ```
  unzip -o app-debug.apk 'lib/arm64-v8a/*.so' -d /tmp/apk
  llvm-readelf -l /tmp/apk/lib/arm64-v8a/libimage_processing_util_jni.so | grep LOAD
  ```

## Device Admin (deep power-off mode)

### 7. Cannot uninstall / cannot remove the admin via adb
- **Symptom:** `adb uninstall` -> `DELETE_FAILED_DEVICE_POLICY_MANAGER`; and
  `adb shell dpm remove-active-admin ...` -> `SecurityException: Attempt to remove non-test admin`.
- **Cause:** Android will not uninstall an app that is an active Device Admin, and `dpm
  remove-active-admin` only works for test-only apps.
- **Fix:** the app removes its **own** admin (always allowed): call
  `DevicePolicyManager.removeActiveAdmin(...)` when the mode is turned off, and self-heal on
  launch (never hold admin unless deep-power-off is on). Then normal uninstall works.

### 8. True screen-off vs camera wake
- **Symptom:** in deep-power-off mode, movement does not wake the screen.
- **Cause:** a truly-off screen (Device Admin `lockNow()`) cannot run the camera on non-rooted
  Android, so presence cannot be detected while off. This is a hard platform limit.
- **Fix (by design):** in deep-power-off mode, wake with the **power button**; camera-presence
  wake exists only in the default **dim** mode. Documented, not worked around.

## Presence detection (the hard one)

### 9. Screen never wakes on movement
- **Symptom:** waving in front of the camera did nothing; `motion=false` always.
- **Cause:** the detector averaged pixel change over the whole 32x24 grid, which diluted a
  localized hand-wave to below the threshold.

### 10. Screen never dims (opposite failure)
- **Symptom:** after switching to per-cell change counting, it stayed awake forever.
- **Cause:** **mains-lighting flicker** (50/60 Hz beating with the camera frame rate)
  oscillates the whole frame's brightness every frame, lighting up most cells -> read as
  constant motion. Single-pixel-per-cell sampling made it worse (very noisy).
- **Fix:** each frame, subtract the **global brightness shift** (cancels flicker and
  auto-exposure drift), then count only cells that changed **locally** by more than 45 luma.

### 11. Calibrating from real data
- **Approach:** temporary `Log.d("PresenceClock", ...)` printing luma and changed-cell counts
  at several deltas, read live via `adb logcat -s PresenceClock` while still vs moving.
- **Measured on the S25 Ultra:** static/flicker residual at delta 45 -> **0-1 cells**; a close
  hand/person -> several cells. So: threshold on delta-45 local cells, `minCells` from the
  sensitivity slider (2-8). Reliable dim + wake.
- **Caveat:** a faint *distant* wave may still not register on a wide front-camera FOV. Move
  closer, or raise sensitivity. Front-camera presence is inherently scene-dependent.

### 12. Covered lens kept it awake
- **Symptom:** covering the camera did not dim it.
- **Cause:** a dark lens produces noisy frames whose diff read as motion.
- **Fix:** a dark-luminance gate - if mean luma is below ~12, treat as no presence (a dark
  scene cannot show anyone anyway).

## Testing and tooling

### 13. Screenshots always look bright even when "dimmed"
- **Cause:** presence dimming changes the **backlight** (`window.screenBrightness`), not the
  rendered pixels; `screencap` captures full-value pixels.
- **Fix:** judge dim/brighten on the physical panel, or via the `brightness -> 0.00 / 1.00`
  log lines. Do not expect a dark screenshot.

### 14. Capturing the flip animation as a GIF
- **Symptom:** the flip only happens on a minute change (HH:MM), easy to miss.
- **Fix:** align a `screenrecord` to a minute rollover (read `adb shell date +%S`, sleep to
  ~:50, record ~15 s), then `ffmpeg` to a cropped, palette-optimized GIF.

### 15. Git Bash mangles adb device paths
- **Symptom:** `screenrecord /sdcard/x.mp4` -> `Must specify output file`; `adb pull
  /sdcard/x.mp4` -> `C:/Program Files/Git/sdcard/x.mp4: No such file`.
- **Cause:** MSYS path conversion rewrites `/sdcard/...` into a Windows path.
- **Fix:** prefix with `MSYS_NO_PATHCONV=1` (and/or `MSYS2_ARG_CONV_EXCL='*'`).

## CI / release

### 16. Release step: `Resource not accessible by integration`
- **Symptom:** the tag build uploaded the APK artifact but failed to create the Release.
- **Cause:** the default `GITHUB_TOKEN` lacks permission to create Releases.
- **Fix:** add to the workflow:
  ```yaml
  permissions:
    contents: write
  ```

### 17. `gradlew` not executable on CI (Linux)
- **Symptom:** `./gradlew: Permission denied` on the runner.
- **Fix:** mark it executable in git: `git update-index --chmod=+x gradlew`.

### 18. Emulator has no front camera
- **Symptom:** on the emulator the app shows "presence sensing off (no camera)".
- **Cause:** AVDs usually lack a usable front camera.
- **Fix:** this is expected; test the camera path on a real device. The fallback (tap-to-wake)
  is the correct degraded behavior.
