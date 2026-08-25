# Architecture

Presence Flip Clock is a single-activity Android app in Kotlin using plain Android Views
(no Compose, no third-party UI libraries). It targets old, non-rooted phones (minSdk 21).

## The core design decision: brightness, not power

The goal is "screen bright when someone is near, dark when the room is empty." The obvious
implementation - turn the screen off/on - is a dead end on a non-rooted phone:

- Turning the screen **off** needs root, or a Device Admin `lockNow()`.
- Once the screen is off, a non-root app **cannot read the camera** (Android restricts
  background camera access from API 28+), so it could never detect the motion needed to turn
  back on.

So the app never changes the screen's power state. Instead it:

1. Stays in the foreground with `FLAG_KEEP_SCREEN_ON` (the OS never sleeps the screen).
2. Modulates **window brightness** (`WindowManager.LayoutParams.screenBrightness`) between
   full (present) and ~2% (idle). This is a per-window value that needs **no permission**.

Because the app stays foreground, the camera keeps delivering frames on every Android
version, so presence detection is reliable. The cost is that the panel is always on
(dimmed) - acceptable for a charger-powered desk clock, mitigated by an anti-burn-in shift.

> Note: window brightness controls the **backlight**, not the rendered pixels. A screenshot
> always captures full-value pixels, so the dimmed state cannot be screenshotted - it is only
> visible on the physical panel.

## Components

| File | Responsibility |
|---|---|
| `MainActivity.kt` | Orchestration: fullscreen + keep-screen-on + show-over-lockscreen; 1 Hz clock tick; idle/brightness state machine; CameraX wiring; permission handling; long-press settings; anti-burn-in shift. |
| `MotionAnalyzer.kt` | `ImageAnalysis.Analyzer` that reads the camera's luminance plane into a 32x24 grid and compares consecutive frames. Above a sensitivity-derived threshold, it fires `onMotion()`. |
| `FlipClockView.kt` | `LinearLayout` assembling `HH : MM` from four `FlipDigitView`s + a colon; sizes itself to the screen (width-driven, height-capped). |
| `FlipDigitView.kt` | One digit as a rounded dark card with a centre divider; animates a short vertical flip (`rotationX`) on change. |
| `Prefs.kt` | `SharedPreferences` wrapper: 12/24h, idle timeout, motion sensitivity, dim %. |

## Data flow

```
front camera ──frames──▶ MotionAnalyzer (luma frame-diff)
                              │ motion?
                              ▼
                     MainActivity.onMotion() ──▶ brightness = FULL, lastMotion = now
                              ▲
   1 Hz tick ──▶ updateClock() + checkIdle():
                 if now - lastMotion > idleTimeout ──▶ brightness = DIM
                 (fade via ValueAnimator on window.attributes.screenBrightness)

   tap anywhere ──▶ onMotion()          (fallback wake; works with no camera)
   long-press ──▶ settings dialog       (format / sensitivity / timeout / dim %)
```

## Threading

- Camera frames are analysed on a dedicated single-thread `Executor`; `onMotion` is marshalled
  back to the UI thread via `runOnUiThread`.
- The clock tick is a `Handler` posting itself every 1000 ms on the main looper.
- A bad camera frame can never crash the clock - `MotionAnalyzer.analyze` is fully guarded and
  always closes the `ImageProxy`.

## Permissions

- `CAMERA` only, and only for on-device motion sensing. Frames are reduced to a few hundred
  brightness samples in memory and discarded immediately - never decoded to a bitmap, saved,
  or transmitted. Deny it and the clock still runs (dims on a timer, wakes on tap).

## Build

Kotlin + AGP 8.5.2, Gradle 8.7 (wrapper committed), JDK 17, compileSdk 34, minSdk 21.
`./gradlew :app:assembleDebug` -> `app/build/outputs/apk/debug/app-debug.apk`.
