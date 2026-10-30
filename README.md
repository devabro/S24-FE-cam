# S24 FE Native Camera — v1 scaffold

A native Android camera app purpose-built for the **Samsung Galaxy S24 FE**,
using the **Camera2 API directly** (not CameraX) so edge/noise processing can
be controlled for a natural, un-over-sharpened look — the same idea as Theo's
"N8" Native Camera project. It degrades gracefully on other devices, but the
S24 FE is the only device it is tuned for.

No ads. No analytics. No network calls — the app does not even request the
INTERNET permission.

## What v1 does

1. **Photo: JPEG + RAW (DNG) side by side** — when the current lens reports
   RAW support. Otherwise JPEG only, with an on-screen note saying so.
2. **Video up to 4K, with a LOG toggle** — LOG records 10-bit HEVC with
   BT.2020 + HLG transfer: a flat, grade-friendly file. The LOG toggle is
   **hidden automatically** if the lens does not report 10-bit support.
   Loading a `.cube` LUT is v2 — v1 files are graded in editing.
3. **Manual controls** — ISO slider (100–6400), shutter slider (1/8000–1s),
   white-balance presets, manual focus slider, and a "Reset to auto" button.
   Sliders sit at 0 = auto, so auto is always one swipe away.
4. **Natural mode** — sets `EDGE_MODE_OFF` + `NOISE_REDUCTION_MODE_OFF` on
   every capture request: no forced sharpening, no forced smoothing.
5. **Overlays** — zebra stripes (adjustable threshold) + live histogram,
   computed cheaply from a small YUV preview stream (luminance only, throttled
   to ~7 fps so the phone stays cool).
6. **Lens switcher** — enumerates all back cameras at runtime, classifies
   them by focal length (Ultrawide / Main / Tele 3x on the S24 FE), and falls
   back gracefully if a lens id is absent.
7. **Presets** — save/load named presets (manual settings + toggles + lens)
   in a local JSON file. No account, no cloud.
8. **Fast startup** — single activity, no splash, no SDKs.

## Honest limitations — read before judging the photos

- **A third-party app cannot reproduce Samsung's proprietary ISP/AI
  processing.** Samsung's automatic photo quality comes from tuning inside
  its own camera app. This app wins on **control and features** (manual
  settings, RAW, LOG video, natural processing, exposure tools) — not on
  beating Samsung's auto mode at its own game.
- **LOG here is 10-bit HLG, not sensor-level S-Log.** Samsung does not
  expose a true log gamma to third-party apps. HLG is the closest a
  third-party app can get, and it grades well — but it is not S-Log.
- **Features degrade by capability, by design.** No 10-bit support → no LOG
  toggle. No RAW capability → JPEG only, with a note. Nothing is faked.
- **Things not yet verified** (see below): this scaffold has never been
  built or run on a real S24 FE. Expect first-run bugs — most likely
  around preview orientation, the HLG encoder on this exact device, and
  focus-distance mapping. Report what you see and it gets fixed.

## How to build it (the only route that works)

Local Gradle builds are **blocked on this VM** (the sandbox blocks Java TCP,
which the Gradle daemon needs), so do not try `./gradlew` here. Build in
GitHub Actions instead — exactly like the user's other Android apps:

1. Create a **new, empty GitHub repo** (private is fine), e.g.
   `s24fe-native-camera`.
2. Push this whole folder to it:
   ```bash
   cd camera-app-s24fe
   git init
   git add .
   git commit -m "v1 scaffold"
   git branch -M main
   git remote add origin https://github.com/<you>/s24fe-native-camera.git
   git push -u origin main
   ```
3. Open the repo's **Actions** tab → workflow **"Build debug APK"** →
   wait for the green check.
4. Download the artifact **`s24fe-native-camera-debug`** → unzip →
   install `app-debug.apk` on the S24 FE (allow "install unknown apps"
   for the browser/Files app once).

## What to test first on the S24 FE

1. Open the app, grant Camera + Mic. Preview appears; lens buttons show
   Ultrawide / Main / Tele 3x. Switch lenses — preview must follow.
2. Take a photo: check the gallery for `IMG_*.jpg` (and `IMG_*.dng` next
   to it if RAW is supported).
3. Turn **Natural mode** on, shoot the same scene twice (on/off), and
   compare sharpening at 100% zoom.
4. Turn **LOG** on (only visible if 10-bit is supported), record 10 seconds
   of video, and confirm the file plays back (it will look flat — that is
   the point; grade it in CapCut/Lightroom/video editor).
5. Move the ISO / shutter / focus sliders and the zebra threshold; confirm
   the preview reacts and "Reset to auto" restores automatic behavior.
6. Save a preset, switch lenses, load it back — settings and lens must
   both restore.

## Project layout

```
app/src/main/java/com/s24fe/nativecamera/
  MainActivity.kt            Single screen: preview + all controls
  camera/
    CameraEnumerator.kt      Back-camera discovery + RAW / 10-bit detection
    CameraSession.kt         Open camera, preview, photo, record, settings
    VideoRecorder.kt         MediaCodec + MediaMuxer (AVC, or HEVC Main10 HLG)
    PreviewAnalyzer.kt       YUV luminance -> histogram + zebra bitmap
    ManualSettings.kt        Settings model + ISO/shutter tables
    PresetStore.kt           Named presets in local JSON
  ui/
    HistogramView.kt         Luminance histogram view
app/src/main/res/layout/activity_main.xml
.github/workflows/build-apk.yml   GitHub Actions debug-APK build (JDK 17)
```

## v2 candidates (deliberately not in v1)

`.cube` LUT loading + live LUT preview, waveform/vectorscope, focus
peaking, dual record, high-bitrate options, intervalometer, 8K (if the
device exposes it), and a proper settings screen.
