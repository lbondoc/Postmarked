# Postmarked

A collaborative road-trip photo album. Kotlin Multiplatform + Compose Multiplatform, Android first.

## Status: MVP milestone 1 (accounts, trips, invites)

Sign in, create a trip (name, dates, one or more regions found by place search), invite people
by QR code or link as editor or viewer, join, leave, delete. No photos yet; import is next.

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

1. Run each file in `supabase/migrations` in the project's SQL editor, oldest first.
2. Authentication → Sign In / Providers → Email: turn **Confirm email** off. The app signs in
   with email + password, and Supabase's built-in mailer is too limited to confirm friends' addresses.

The project URL and publishable key live in `composeApp/.../data/Backend.kt`.

## Earlier: GPS import check

Phase 0 was a diagnostic screen proving the app can read GPS from camera-roll photos and videos
(confirmed on a Samsung running Android 17, once the camera's "Location tags" setting is on).
It needs `ACCESS_MEDIA_LOCATION` plus `MediaStore.setRequireOriginal`. The code is in git
history (`MediaLibrary.kt`) and comes back as the importer in milestone 2.
