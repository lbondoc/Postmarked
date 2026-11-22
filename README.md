# Postmarked

A collaborative road-trip photo album. Kotlin Multiplatform + Compose Multiplatform, Android first.

## Status: MVP milestone 2 (photo import and upload)

Milestone 1: sign in, create a trip (name, dates, one or more regions found by place search),
invite people by QR code or link as editor or viewer, join, leave, delete.

Milestone 2: "Add photos and videos" on a trip scans the camera roll around the trip's dates,
sorts items into "taken on this trip" (pre-ticked) and ones to check (no location, somewhere
else, other days), and queues the chosen ones. They upload in the background, on Wi-Fi unless
mobile data is allowed, and continue after the app is closed or the phone restarts. The trip
page shows a grid of everything uploaded. The same file added by two people is stored once.

## Layout

| Path | What lives there |
|---|---|
| `core/domain` | Pure Kotlin logic and its unit tests (geo math, place-search parsing). No Android code. |
| `composeApp` | Shared app: `data/` talks to Supabase, `ui/` holds the Compose screens. |
| `androidApp` | Android entry point: Activity, manifest, share sheet, QR scanner. |
| `supabase/migrations` | Database schema and access rules, applied to the Supabase project in filename order. |
| `supabase/tests` | SQL tests for those rules; `supabase/tests/run.sh` runs them on any local Postgres with PostGIS. |

## Build

Needs JDK 21 and the Android SDK (`ANDROID_HOME` set, or `sdk.dir=...` in `local.properties`).

```
./gradlew :core:domain:jvmTest          # unit tests
./gradlew :androidApp:assembleDebug     # APK in androidApp/build/outputs/apk/debug/
./supabase/tests/run.sh                 # database access-rule tests
```

No Android Studio? Every push builds the APK in GitHub Actions; download `postmarked-debug-apk`
from the run's page and open it on the phone. Each CI build is signed with a fresh debug key, so
uninstall the previous build before installing a new one.

## Supabase project setup

1. Run each file in `supabase/migrations` in the project's SQL editor, oldest first. Each runs once;
   running one twice fails with "already exists" and changes nothing.
2. Authentication → Sign In / Providers → Email: turn **Confirm email** off. The app signs in
   with email + password, and Supabase's built-in mailer is too limited to confirm friends' addresses.

The project URL and publishable key live in `composeApp/.../data/Backend.kt`.

## What is stored

Each item gets a folder `<trip id>/<media id>/` in the private Storage bucket `media`:

| Photo | Video |
|---|---|
| `original`: the camera's file, GPS included | `video.mp4`: H.264, at most 1080p, about 45 MB a minute |
| `display.jpg`: 2048 px | `poster.jpg`: a frame about a second in |
| `thumb.jpg`: 400 px | `thumb.jpg`: 400 px |

Original videos stay on the phone. Supabase's free plan refuses files over 50 MB, so videos over
about a minute fail with a message; raise the file size limit in Storage settings on a paid plan.

Reading GPS from the camera roll needs `ACCESS_MEDIA_LOCATION` plus `MediaStore.setRequireOriginal`
(Phase 0 proved this on a Samsung running Android 17, once the camera's "Location tags" setting is on).
