# Postmarked

A collaborative road-trip photo album. Kotlin Multiplatform + Compose Multiplatform, Android first.

## Status: Phase 0 (GPS import check)

The app is currently one diagnostic screen. It scans the newest 150 photos and 150 videos on the
phone and reports how many still carry GPS, which is the thing the whole route feature depends on.

## Layout

| Module | What lives there |
|---|---|
| `core/domain` | Pure Kotlin logic and its unit tests (geo math so far). No Android code. |
| `composeApp` | Shared Compose UI. |
| `androidApp` | Android entry point: Activity, manifest, camera-roll access. |

## Build

Needs JDK 21 and the Android SDK (`ANDROID_HOME` set, or `sdk.dir=...` in `local.properties`).

```
./gradlew :core:domain:jvmTest          # unit tests
./gradlew :androidApp:assembleDebug     # APK in androidApp/build/outputs/apk/debug/
```

No Android Studio? Every push builds the APK in GitHub Actions; download `postmarked-debug-apk`
from the run's page and open it on the phone. Each CI build is signed with a fresh debug key, so
uninstall the previous build before installing a new one.

## Running the GPS check

1. Install the APK and open Postmarked.
2. Tap **Scan camera roll** and choose **Allow all** when Android asks for photo access.
3. Note the three lines at the top (device, library access, media location) and the two
   "GPS on X of Y" totals.

Photos saved from chat apps and screenshots never have GPS; photos from the phone's own camera
should, if location tagging is on in the camera app.
