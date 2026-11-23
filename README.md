# Postmarked

A collaborative road-trip photo album. Kotlin Multiplatform + Compose Multiplatform, Android first.

## Status: MVP complete (milestone 6: export, music, themes)

Milestone 1: sign in, create a trip (name, dates, one or more regions found by place search),
invite people by QR code or link as editor or viewer, join, leave, delete.

Milestone 2: "Add photos and videos" on a trip scans the camera roll around the trip's dates,
sorts items into "taken on this trip" (pre-ticked) and ones to check (no location, somewhere
else, other days), and queues the chosen ones. They upload in the background, on Wi-Fi unless
mobile data is allowed, and continue after the app is closed or the phone restarts. The trip
page shows a grid of everything uploaded. The same file added by two people is stored once.

Milestone 3: "Map and route" on a trip rebuilds where the group went from everyone's located
photos: photos taken in a row at one place become a stop, GPS glitches are dropped, flights and
ferries are drawn dashed and not counted, and the stops are joined along real roads. The map
colours each day differently and groups nearby stops into numbered circles; tapping a stop shows
its photos. Below the map: total miles and drive time, and each day's.

Milestone 4: the trip page is a gallery. View everything, by day, by stop, or by who took it,
and filter by photos/videos, person, day and named place (filters combine). Photos taken
seconds apart that look alike fold into one tile showing the sharpest. Tapping opens a
full-screen viewer: swipe, see who took it and when, play videos, react, comment, and delete
your own items.

Milestone 5: every photo gets a five-colour palette (k-means in OKLab), worked out at upload;
older photos get theirs from their thumbnails. The gallery gains a colour filter. Summaries
lay photos out in six layouts (3 × 3, 2 × 3, 1 large + 4 small, filmstrip, polaroid collage,
magazine) in four styles: one colour, trip palette gradient, rainbow, or chosen by hand, with
text and colour tiles. Tiles can be swapped by dragging, and "harmonize colours" gives the
photos one look (white balance, saturation, split toning) without changing the originals.

Milestone 6: summaries export for Instagram as one post, a carousel, or a 3 × 3 profile grid
numbered in posting order, at 4:5, 3:4 or square, shared through the share sheet or saved to
the phone's photos, with a generated caption and hashtags. Trips can carry Spotify songs
(pasted links; title and artwork from Spotify's link preview). The app has its road-trip look
(paper and ink, night-drive dark mode, road-sign headings, postmark logo and stamp), and each
trip an album theme: Auto Palette, Postcard or Film. Themes are JSON files in
`composeApp/src/commonMain/composeResources/files/themes`; add one by adding a file and its id
to `index.json`.

## Layout

| Path | What lives there |
|---|---|
| `core/domain` | Pure Kotlin logic and its unit tests: geo math, place search, trip date/location matching, EXIF dates. No Android code. |
| `composeApp` | Shared app: `data/` talks to Supabase, `ui/` holds the Compose screens. |
| `androidApp` | Android entry point: Activity, manifest, share sheet, QR scanner, camera-roll reading, background uploads. |
| `supabase/migrations` | Database schema and access rules, applied to the Supabase project in filename order. |
| `supabase/functions/route` | Server function that joins stops along roads via Stadia Maps, keeping the Stadia key off phones. Tests: `deno test supabase/functions/`. |
| `supabase/tests` | SQL tests for those rules; `supabase/tests/run.sh` runs them on any local Postgres with PostGIS. |

## Build

Needs JDK 21 and the Android SDK (`ANDROID_HOME` set, or `sdk.dir=...` in `local.properties`).

```
./gradlew :core:domain:jvmTest :composeApp:testAndroidHostTest   # unit tests
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

3. Road routing (without it the map draws straight lines between stops):
   - Create a free account at stadiamaps.com and an API key.
   - Supabase dashboard → Edge Functions → Secrets: add `STADIA_API_KEY` with that key.
   - Edge Functions → Deploy a new function → Via Editor: name it `route`, paste
     `supabase/functions/route/index.ts`, turn off JWT verification (the function checks the
     sign-in itself), deploy.

Stadia's free plan covers development and non-commercial use; commercial use starts at their
Starter plan. Map tiles come from OpenFreeMap, which needs no key.

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
