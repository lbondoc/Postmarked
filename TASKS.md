# Postmarked — tasks

See SPEC.md for the why and the details. Work top to bottom (platforms first). A task is done when its
**Check** passes locally or in CI, and the existing CI jobs are still green:

```bash
./gradlew :core:domain:jvmTest :composeApp:testAndroidHostTest :androidApp:assembleDebug
```

```bash
deno test supabase/functions/
```

```bash
./supabase/tests/run.sh
```

From P2 on, every task must also pass `./gradlew :composeApp:compileKotlinWasmJs`. From P6 on, the `ios` CI job
must pass as well.

## Phase 0 — Platforms

- [x] **P1 Check web support of dependencies.** Add `wasmJs` to `composeApp` on a scratch branch and note in
  SPEC.md §1.1 which libraries compile (maplibre-compose, qrose, supabase-kt, Coil). Decide between the real map
  and the tile-free fallback.
  Check: `./gradlew :composeApp:compileKotlinWasmJs` (after the decision, it compiles with the map or the fallback)
- [x] **P2 Domain on all targets.** Add `wasmJs { nodejs() }`, `iosArm64` and `iosSimulatorArm64` to `core/domain`.
  Fix anything that only works on the JVM.
  Check: `./gradlew :core:domain:jvmTest :core:domain:wasmJsNodeTest`
- [x] **P3 composeApp on wasmJs.** Add the target and the Js Ktor engine. Put platform-only code behind
  `expect`/`actual` or the existing interfaces, and add `TripMap`'s web actual (real map or fallback per P1).
  Check: `./gradlew :composeApp:compileKotlinWasmJs :composeApp:testAndroidHostTest`
- [x] **P4 webApp module and smoke test.** A `webApp` wasmJs executable that runs `App()`, plus `web/smoke.mjs`
  (Playwright using the preinstalled Chromium). The smoke test serves `dist/`, opens it, and fails on console
  errors or if the sign-in screen doesn't render.
  Check: `./gradlew :webApp:wasmJsBrowserDistribution && node web/smoke.mjs`
- [x] **P5 Web upload.** File picker, a pure-Kotlin EXIF reader in `core/domain/media` (date and GPS), HEIC
  rejection, the 50 MB video limit, in-tab progress. `Exporter` on web downloads the files.
  Check: `./gradlew :core:domain:jvmTest --tests '*ExifReaderTest*'` (fixture JPEGs with and without GPS, and a
  truncated file) `&& node web/smoke.mjs`
- [x] **P6 iOS compiles in CI.** Add the iOS targets to `composeApp` with the Darwin engine and stubbed `actual`s
  (`// shortcut:` comments). Add an `ios` job on `macos-latest`, run on pushes to `main` and by
  `workflow_dispatch`.
  Check: CI job `ios` runs `./gradlew :core:domain:iosSimulatorArm64Test :composeApp:compileKotlinIosSimulatorArm64`
- [x] **P7 Deploy the website.** A CI step on `main` builds `webApp` and publishes it to GitHub Pages (or
  Cloudflare Pages if the repo is private). Add the site URL to Supabase Auth's redirect URLs and the README.
  Check: CI job `web` is green, and `curl -sf <site url> | grep -q '<canvas\|composeApp'`

## Phase 1 — Fixes, viewer, actions

- [x] **F1 Trip hues replace the muddy palette.** Add `tripHues()` in `domain/color` (SPEC §2), use it in
  `tripPalette`, and delete `mergePalettes` if unused.
  Check: `./gradlew :core:domain:jvmTest --tests '*TripHuesTest*'`. The test trip is 8 dark road photos, 1 sunset
  and 1 sky. The result must include orange and blue, have no swatch with $L < 0.3$, and have at most one neutral.
- [x] **F2 "More colours" wheel.** Add a fixed row of 12 named colours with counts, greyed out at 0, in the
  gallery filter and in the summary's hand-picked colours.
  Check: `./gradlew :composeApp:testAndroidHostTest --tests '*GalleryTest*'` (a new case: a wheel colour with
  matches filters to exactly those photos, and one with no matches has count 0)
- [x] **F3 Viewer zoom.** Pinch and double-tap zoom with pan clamping (SPEC §3.1). The clamp math is a pure
  function in commonMain.
  Check: `./gradlew :composeApp:testAndroidHostTest --tests '*ZoomTest*'` (scale is clamped to $[1, 5]$, pan to
  the bounds, and swipe goes to pan when $s > 1$)
- [x] **F4 Progressive load and prefetch.** Show the thumb first, swap in the display image, and prefetch the
  neighbours. Coil cache keys become storage paths.
  Check: `./gradlew :composeApp:testAndroidHostTest --tests '*ImageKeyTest*'` (two signed URLs for the same path
  give the same cache key)
- [x] **F5 Info panel.**
  Check: `./gradlew :composeApp:testAndroidHostTest --tests '*MediaInfoTest*'` (`infoLines()` gives the person,
  local time, Day N, stop name and place, and leaves out missing fields)
- [x] **F6 Share one photo, and save to phone.** The viewer shares through the share sheet or Web Share.
  `PhotoSaver`: MediaStore on Android, download on web, stub on iOS. Saving several shows progress and can be
  cancelled.
  Check: `./gradlew :androidApp:assembleDebug :composeApp:compileKotlinWasmJs && node web/smoke.mjs`, then a
  manual check on the phone: a saved photo appears in Pictures/Postmarked
- [x] **F7 Multi-select.** Long-press plus drag (reusing the import screen's drag-select). Save, share or delete,
  where delete only covers your own items.
  Check: `./gradlew :composeApp:testAndroidHostTest --tests '*SelectionTest*'` (drag range, toggle, and the
  "N not yours, skipped" count)
- [x] **F8 Trip cover.** Migration `trips.cover_media_id` (same trip, only editors can set it, `on delete set
  null`). Set it from the viewer and show it on the trip list card.
  Check: `./supabase/tests/run.sh` (new `cover_test.sql`: an editor can set it, a viewer can't, media from another
  trip is refused, deleting the media clears it)
- [x] **F9 Cancel uploads.** A queue list on the trip page with cancel for one item or all. An upload in
  progress is aborted and its partial files are deleted.
  Check: `./gradlew :composeApp:testAndroidHostTest --tests '*BatchProgressTest*'` (new cases: cancelling waiting
  or in-progress items updates the totals and finished items stay)

## Phase 2 — Offline and live mode

- [x] **O1 Trip cache.** Save the trip list and per-trip JSON after each load (`TripCacheStore`: files on Android,
  `localStorage` on web). Show the offline banner when falling back to it.
  Check: `./gradlew :composeApp:testAndroidHostTest --tests '*TripCacheTest*'` (save → load round-trip, a corrupt
  file loads as empty without crashing, a failed network load returns the cached copy)
- [x] **O2 Outbox for comments, reactions and day notes.** Persist it, send in order when back online, keep
  reactions idempotent, last write wins for notes, and show "Waiting to send".
  Check: `./gradlew :composeApp:testAndroidHostTest --tests '*OutboxTest*'` (order is kept, a failed send stays
  queued, a duplicate reaction becomes one, a newer note beats an older one)
- [x] **O3 Offline imports.** Confirm that queued imports wait for the network and survive a reboot. Fix whatever
  doesn't.
  Check: `./gradlew :androidApp:assembleDebug`, then on the phone: queue 3 photos in airplane mode, reboot, turn on
  Wi-Fi, and all 3 appear in the gallery
- [x] **L1 Live candidates.** A pure function: for the new MediaStore items since the last run, return those the
  trip matcher marks as taken on this trip.
  Check: `./gradlew :core:domain:jvmTest --tests '*LiveCandidatesTest*'` (it skips items outside the dates or area,
  items already queued, and items with no location)
- [x] **L2 Live mode worker and switch.** A WorkManager job with a content-URI trigger, the per-trip switch
  (default on for editors during the trip), and a "Review" notification that opens the queue.
  Check: `./gradlew :androidApp:assembleDebug`, then on the phone: with live on, take a photo inside the trip's
  area and dates, and it appears in the queue within a minute
- [x] **L3 "N new from friends".** Store each trip's last-opened time on the device and show the count on the
  trip list and trip page.
  Check: `./gradlew :composeApp:testAndroidHostTest --tests '*NewSinceTest*'` (your own uploads don't count, and
  the count resets on opening the trip)

## Phase 3 — Journal

- [x] **J1 day_notes table.** A migration with RLS: members read, editors write, `day` within the trip's dates.
  Check: `./supabase/tests/run.sh` (new `day_notes_test.sql`)
- [x] **J2 Notes in the By day view.** Edit in place, going through the outbox when offline.
  Check: `./gradlew :composeApp:testAndroidHostTest --tests '*GalleryTest*'` (a by-day group carries its note)
- [x] **J3 Notes in summaries and captions.** Add a "Day note" text tile, and the caption generator can quote it.
  Check: `./gradlew :core:domain:jvmTest --tests '*CaptionTest*'`

## Phase 4 — Summaries: presets, layouts, crop

- [x] **S1 Five new filters.** Mono, Moody, Vivid, Desert, Night Drive.
  Check: `./gradlew :core:domain:jvmTest --tests '*LooksTest*'` (Mono gives $R = G = B$, Vivid raises chroma,
  Moody lowers lightness, Desert warms, Night Drive cools and darkens)
- [x] **S2 New layouts: 2-up, 4-up, panorama split.**
  Check: `./gradlew :core:domain:jvmTest --tests '*LayoutsTest*'` (slots stay inside the canvas without
  overlapping, and the panorama's slices line up edge to edge across slides)
- [x] **S3 Justified rows and reflow by photo ratio.** A pure `justify(aspects, width, gap, targetHeight)`
  (SPEC §8.4). Adaptive layouts use each photo's ratio.
  Check: `./gradlew :core:domain:jvmTest --tests '*JustifyTest*'` (each row exactly fills $W$, a portrait stays
  portrait, and one very wide photo gets a row to itself)
- [x] **S4 Crop ratio picker.** Original (default), 1:1, 4:5, 3:4, 16:9 and Free in the per-photo editor. It
  reflows adaptive layouts and is locked to the tile's ratio in strict grids. Saved looks keep the ratio.
  Check: `./gradlew :composeApp:testAndroidHostTest --tests '*SavedLooksTest*'` and
  `./gradlew :core:domain:jvmTest --tests '*ExportGeometryTest*'` (the exported crop matches the chosen ratio)
- [x] **S5 Style presets.** About 6 JSON presets in `files/presets/` with `index.json`, applied with one tap.
  Check: `./gradlew :composeApp:testAndroidHostTest --tests '*PresetsTest*'` (every file parses, is listed in
  `index.json`, and names an existing layout, style and filter). The CI APK check also greps for
  `files/presets/index.json`.
- [x] **S6 Carousel presets.** Cover, photo slides, route slide (road line on paper), and closing slide. At most
  20 slides.
  Check: `./gradlew :core:domain:jvmTest --tests '*CarouselPlanTest*'` (photos stay in time order, slides split at
  day boundaries where they fit, there are never more than 20 slides, and the route slide is left out when there
  is no route)

## Phase 5 — Share link

- [x] **W1 trip_shares table.** A migration with RLS: editors create and revoke, nobody reads tokens through
  PostgREST except the creator.
  Check: `./supabase/tests/run.sh` (new `shares_test.sql`)
- [x] **W2 `share` Edge Function.** Check the token and return the trip, media and signed URLs for thumb, display
  and poster only.
  Check: `deno test supabase/functions/` (new `share/index_test.ts`: a revoked or unknown token gets 404, the
  response has no `original` path and no emails, and signed URLs expire in 1 hour)
- [x] **W3 Read-only share view on the website.** The `#/s/<token>` route with no sign-in. Editing controls are
  hidden. Turning the link on or off and the map warning live in trip settings.
  Check: `./gradlew :webApp:wasmJsBrowserDistribution && node web/smoke.mjs --share` (the smoke test stubs the
  `share` function and checks the gallery renders with no sign-in screen)

## Phase 6 — Editing trips, and photos with no date or place

See SPEC §9.

- [x] **E1 Trip edits keep day notes inside the dates.** A migration with a `before update` trigger on `trips`:
  changing `start_date` or `end_date` so a day note falls outside them is refused with the day in the message.
  Check: `./supabase/tests/run.sh` (new `trip_edit_test.sql`: an editor can rename the trip and change its dates,
  start and end points and regions through `set_trip_regions`; a viewer can do none of these; shortening the dates
  past a note is refused; shortening to a range that still holds every note works)
- [ ] **E2 Edit trip screen.** **Edit trip** on the trip page, for editors and the owner. It reuses the New trip
  form and `NewTripViewModel` filled in with the trip, and saves the name, dates, start and end points, and regions.
  A refusal from E1 is shown as the form's error.
  Check: `./gradlew :composeApp:testAndroidHostTest --tests '*EditTripTest*'` (the form starts with the trip's
  values, saving with no changes sends nothing, an end date before the start disables Save, and a changed region
  list is sent whole) `&& ./gradlew :composeApp:compileKotlinWasmJs`
- [ ] **E3 Media date and place can be edited.** A migration adding `media.date_by_hand` and
  `media.location_by_hand` (boolean, default false), an update policy for the uploader and the trip owner, and an
  update grant on only `captured_at`, `utc_offset_minutes`, `location`, `date_by_hand` and `location_by_hand`.
  Check: `./supabase/tests/run.sh` (new `media_edit_test.sql`: the uploader and the owner can change the date and
  location; another editor and a viewer can't; nobody can change `trip_id`, `contributor` or `sha256`; a
  location can be cleared to null)
- [ ] **E4 Files with no date.** `DeviceMedia` gains `dateKnown`. `pickedMedia` sets it false when the file has
  no EXIF date, and the modified time becomes only the suggested day. The review screen gets a **No date in the
  file** section, where each ticked item needs a trip day (time optional) before **Add** is enabled. Uploads send
  `date_by_hand = true` for these.
  Check: `./gradlew :composeApp:testAndroidHostTest --tests '*PickedFilesTest*' --tests '*StartTickedTest*'`
  (new cases: no EXIF date gives `dateKnown = false`, with the suggested day only when it is inside the trip;
  Add stays disabled while a ticked undated item has no day; a chosen day with no time gives noon on that day in
  the device zone with no UTC offset) `&& node web/smoke.mjs`
- [ ] **E5 Add from anywhere on Android.** The system photo picker (`PickMultipleVisualMedia`), EXIF read from
  each picked file with `readExif`, then `pickedMedia` and the same review screen as the web (`pickedByHand` true for
  this flow).
  Check: `./gradlew :androidApp:assembleDebug`, then on the phone: pick a screenshot and a photo from a year ago.
  The screenshot lands in No date in the file, and after choosing Day 2 it shows under Day 2 in the gallery.
- [ ] **E6 Edit date and place.** **Edit date and place** in the viewer's info panel, shown to the uploader and the
  owner. Day is chosen from the trip's days plus an optional time. Location is chosen with place search, then a
  draggable pin on Android, place search only on the web, or cleared. It saves through E3 and marks the cached route
  stale. The same place editor opens from the review screen for items in No location saved, before they upload.
  The info panel shows "Date set by hand" and "Location set by hand".
  Check: `./gradlew :composeApp:testAndroidHostTest --tests '*MediaInfoTest*'` (the by-hand lines appear only
  when the flags are set) `&& ./gradlew :core:domain:jvmTest --tests '*RouteTest*'` (a photo with a hand-set
  location becomes a stop like a GPS one) `&& ./gradlew :composeApp:compileKotlinWasmJs`
