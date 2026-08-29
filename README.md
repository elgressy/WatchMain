# PulseWave Relax — Galaxy Z Flip7 + Galaxy Watch Ultra

Heart-rate-driven relaxation app: a phone app that plays the "גל פועם" (Pulse
Wave) web experience in a fullscreen WebView, and a companion Wear OS app
that streams live BPM from the watch's heart-rate sensor over to the phone.

```
.
├── mobile/    Phone app (Galaxy Z Flip7 — Android, minSdk 26 / targetSdk 35)
├── wear/      Watch app (Galaxy Watch Ultra — Wear OS 3+, minSdk 30 / targetSdk 35)
└── settings.gradle.kts
```

Both modules share `applicationId = "com.pulsewave.relax"` and must be signed
with the same key — required by the Wearable Data Layer for the phone/watch
pairing to auto-resolve.

## How it works

- **`:wear`** measures heart rate with Health Services (`MeasureClient`,
  `DataType.HEART_RATE_BPM`) from a foreground service (`HeartRateForegroundService`,
  type `health`), throttled to ~1 sample/sec, and sends each reading to the
  phone via `MessageClient` on path **`/hr`**. A Compose screen
  (`MainActivity`) shows the live BPM, phone-connection status, and a
  Start/Stop button that requests `BODY_SENSORS` (and `POST_NOTIFICATIONS` on
  API 33+) on first use, with a settings-deeplink fallback if permission was
  permanently denied.
- **`:mobile`** loads `assets/index.html` (the finished web experience,
  unmodified) into a fullscreen, screen-always-on `WebView`. `MainActivity`
  registers a `MessageClient.OnMessageReceivedListener` while resumed; on
  each `/hr` message it calls `webView.evaluateJavascript("window.onExternalBpm(bpm)")`.
  The page itself handles the manual/live slider lock and the 10s live→manual
  fallback — see `mobile/src/main/assets/index.html`.
- Orientation is locked to portrait and `configChanges` covers
  `screenLayout|smallestScreenSize` so folding/unfolding the Z Flip7 doesn't
  restart the activity or the JS/audio state.

## Building the APKs

This source tree was authored and validated for structure in a sandboxed
environment **without access to the Android SDK or Google's Maven
repository** (`dl.google.com` / `maven.google.com`, which serve the Android
Gradle Plugin, AndroidX, Play Services and Health Services artifacts, are not
reachable from here) — so the APKs themselves could not be compiled in this
session. Build them on a machine with normal internet access and Android
Studio / an Android SDK installed:

```bash
./gradlew :mobile:assembleDebug :wear:assembleDebug
```

(The Gradle wrapper is committed; first run downloads Gradle 8.9 and the
Android/Kotlin plugins.) Requires JDK 17+ and the Android SDK
(`compileSdk 35`) — Android Studio installs both automatically on first
open, or set `ANDROID_HOME`/`local.properties` manually.

### Install

```bash
# Phone (Z Flip7): enable Developer Options → USB debugging
adb install mobile/build/outputs/apk/debug/mobile-debug.apk

# Watch (Watch Ultra): Settings → About watch → tap Software version 5x →
# Developer Options → Wireless debugging → adb pair <ip:port> → adb connect <ip:port>
adb -s <watch-serial> install wear/build/outputs/apk/debug/wear-debug.apk
```

Both APKs must come from the same build/debug keystore for the Data Layer
pairing to work.

## Notes

- Tone.js is loaded from cdnjs at runtime (`INTERNET` permission granted) —
  bundling it into assets for full offline use was left out since it
  required fetching the library from cdnjs, which this sandbox also could
  not reach; it's a drop-in follow-up (vendor `Tone.js` into
  `mobile/src/main/assets/` and rewrite the `<script src>` in `index.html`).
- The web experience in `mobile/src/main/assets/index.html` is unmodified
  from the provided handoff — do not edit it without updating both copies.
