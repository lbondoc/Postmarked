# Postmarked — next round (spec)

Written 2026-11-23 from an interview with the owner. The MVP (milestones 1–6 in the README) is done.
This round adds polish and new features, and makes Postmarked run on Android, web and iOS (iOS built in CI only).

## Context and constraints

- **Users:** the owner and friends (sideloaded APK and a website). No public launch.
- **Deadline:** a road trip about a month away. Everything below is wanted for it; TASKS.md runs in the
  order chosen (platforms first) so whatever slips is at the end.
- **Free tiers only:** Supabase free (50 MB per file, 1 GB storage), Stadia free, OpenFreeMap tiles, free static hosting.
  No paid services, no Apple Developer account.
- **No new heavy dependencies:** no Room, Firebase or SQLDelight. Use what is already in `libs.versions.toml`
  (Compose MP, supabase-kt, Ktor, Coil 3, kotlinx-serialization/datetime, maplibre-compose, WorkManager, Media3),
  plus the matching per-platform Ktor engines.
- **CI stays green:** every task passes the existing jobs (`database`, `functions`, `build`) and any job it adds.
- **No Mac:** iOS is compiled and its domain tests run on a GitHub macOS runner. Nobody tests it on a device this round.

## 1. Platforms (do first)

New features from here on are written once in shared code and must build for every target.

### 1.1 Targets
- `core/domain`: add `wasmJs` (Node tests), `iosArm64` and `iosSimulatorArm64` next to `jvm`.
- `composeApp`: add `wasmJs` and the two iOS targets. Ktor engines: OkHttp (Android), Js (web), Darwin (iOS).
- Platform-specific behaviour stays behind the existing interfaces (`CameraRoll` in `data/Media.kt`, `Exporter` in
  `ui/Export.kt`), plus new ones only where a feature needs them (`PhotoSaver`, `TripCacheStore`, `Connectivity`).
- **Known risk:** maplibre-compose, qrose and supabase-kt may not all support `wasmJs` at the pinned versions.
  Task P1 checks this first. If the map has no web support, web gets a tile-free route drawing (stops and the road
  line on paper, the same drawing the carousel route slide uses, §6.4), behind an `expect`/`actual` `TripMap`.

### 1.2 Website (full app)
- A `webApp` module (wasmJs executable) running the same `App()`: sign in, trip list, create/join, gallery, viewer,
  map, journal, summaries and export.
- **Upload on web:** a file picker (`<input type=file multiple accept="image/*,video/*">`). Uploads run while the
  tab is open, with progress and cancel. Dates and GPS come from a small pure-Kotlin EXIF reader in `core/domain`
  (shared with iOS later). Browsers can't decode HEIC, so HEIC files are rejected with a clear message. Videos
  are uploaded as they are if under 50 MB and refused with the existing message otherwise (no transcoding on web).
- **No** live mode or background upload on web.
- Export on web: the files download instead of opening the share sheet.
- **Hosting:** a GitHub Pages deploy from CI on pushes to `main`. GitHub Pages is free only for public repos. If
  this repo is private, deploy the same `dist/` folder to Cloudflare Pages (free) instead. Supabase Auth gets the
  site URL added to its redirect allow-list.
- Routing uses the URL hash: `#/trip/<id>`, `#/s/<share token>` (§7).

### 1.3 iOS (compile only)
- iOS `actual`s that need native APIs (camera roll, saving photos, background upload) are minimal stubs that
  say "Not available on iOS yet", each marked `// shortcut: stub, implement when a Mac is available`.
- A CI job `ios` on `macos-latest` compiles `composeApp` and runs `core:domain`'s iOS simulator tests. It runs on
  pushes to `main` and by manual dispatch only, because macOS runner minutes are scarce on the free plan.

## 2. Colour fix: palettes only offer dark colours

**Bug:** `tripPalette()` (`ui/TripScreen.kt:320`) runs one area-weighted k-means over every photo's swatches
(`mergePalettes`). Averaging pulls the 5 centres toward the large dark and muted areas (road, shadow, night), so
the gallery colour filter, the summary styles and the Auto Palette accent all get muddy, dark choices.

**Fix (one place, `core/domain/color`):** a new `tripHues(palettes)` replaces `mergePalettes` in `tripPalette`.
1. Each photo counts equally: its swatch weights $w_i$ sum to 1 for that photo.
2. Keep colourful, mid-light swatches: chroma $C_i = \sqrt{a_i^2 + b_i^2} \ge C_{\min}$ (the existing
   `NEUTRAL_CHROMA`) and lightness $0.3 \le L_i \le 0.92$.
3. Put them in 12 hue sectors of $30^\circ$ and score each sector by
   $$S_k = \sum_{i \in k} w_i \, C_i .$$
4. Return up to 6 sectors with $S_k \ge 0.02 \cdot \max_j S_j$, highest score first. Each sector is shown by its
   real swatch $\arg\max_{i \in k} w_i C_i$, a colour that appears in a photo rather than an average.
5. Add at most one neutral (white, grey or black, whichever has the most weight), and never one with $L < 0.2$.

**Plus a fixed wheel:** after the trip hues, a "More colours" row offers 12 named colours (red, orange, yellow,
lime, green, teal, cyan, blue, purple, pink, white, black). Each shows its photo count from the existing `share()`
match and is greyed out at 0. This applies to the gallery filter and the summary "chosen by hand" picker.
The theme accent uses the trip hues only.

`mergePalettes` is deleted if nothing else uses it.

## 3. Gallery and viewer

### 3.1 Viewer
- **Zoom:** pinch from $1\times$ to $5\times$. Double-tap toggles $1\times \leftrightarrow 2.5\times$ around the
  tapped point. While zoomed, panning is clamped so the photo never leaves the screen:
  $$|t_x| \le \tfrac{(s-1)\,W}{2}, \qquad |t_y| \le \tfrac{(s-1)\,H}{2},$$
  and horizontal swipes pan the photo instead of changing photos. The zoom resets when changing photos.
- **Progressive loading:** show `thumb.jpg` at once and replace it with `display.jpg` when that loads.
  Prefetch the display images of the photos either side.
- **Info panel** (an ⓘ button or swiping up): who took it, the local time, "Day N", the stop name (from the
  route), the place name, pixel size and file size for photos, length for videos. Camera model is left out
  because it isn't stored.

### 3.2 Actions
- **Share one photo** from the viewer through the platform share (Android share sheet; web Web Share API when
  available, otherwise a download).
- **Save to phone:** Android writes the original photo or `video.mp4` to `Pictures/Postmarked` through MediaStore,
  web downloads the file, iOS is a stub. Saving several shows progress and can be cancelled.
- **Multi-select:** long-press a tile to start selecting, then drag to select more (reuse the import screen's drag
  to select). Selected items can be saved, shared or deleted. Delete only covers your own items, and the bar shows
  how many are skipped.
- **Trip cover:** editors choose "Set as trip cover" in the viewer, and the trip list card shows it.
  New column `trips.cover_media_id`: it must point to media in the same trip, only editors can set it, and it is
  cleared when that media is deleted.

### 3.3 Cancel uploads
The upload queue is visible on the trip page. Each waiting or uploading item can be cancelled, and so can all of
them. Cancelling one that is uploading stops the request and deletes any partly uploaded files in Storage.
Items that already finished stay. Cancel applies to the web upload too.

## 4. Offline and poor signal

- **Browse the cached trip:** after each successful load, the trip list, each trip's media rows, members, route
  and day notes are saved as JSON (kotlinx-serialization) per trip. On Android this goes in app files, on web in
  `localStorage` (small JSON only). When a load fails for lack of network, screens show the saved copy under an
  "Offline, showing what you last saw" banner.
- **Images offline:** Coil's disk cache keys images on the **storage path**, not the signed URL, so cached
  thumbnails and display images still show after the signed URLs expire.
- **Imports queue offline:** the selection already queues in WorkManager with a network constraint. This round
  checks that it works in airplane mode and survives a restart.
- **Outbox for writes:** comments, reactions and day notes go into a persisted outbox that is sent in order once
  the network is back (a WorkManager job with a network constraint on Android, and on the next app start or
  successful request on web and iOS). Comments are append-only. Reactions are idempotent upserts. Day notes use
  last write wins by `updated_at`. Items still in the outbox show as "Waiting to send".
- Offline covers what you have already seen. There is no "download the whole trip".

## 5. Live trip mode (Android)

- Each trip gets a "Live: add new photos automatically" switch. It is on by default for editors while today is
  within the trip's dates.
- A WorkManager job with a MediaStore content-URI trigger runs when new photos or videos appear. It runs the
  existing trip matcher (`domain/trip/TripMatch.kt`) and queues only items that are "taken on this trip"
  (respecting the Wi-Fi-only setting).
- A notification ("Added 4 photos to *Trip* · Review") opens the queue, where items can be cancelled (§3.3).
  Items already uploaded can be deleted as usual.
- **In-app updates, no push:** the trip list and trip page show "N new from friends" for media added by others
  since you last opened that trip. The last-opened time is stored on the device.

## 6. Journal: notes per day

- New table `day_notes(trip_id, day date, body text, updated_by, updated_at)`, primary key `(trip_id, day)`.
  Members read and editors write (RLS). `day` must be within the trip's dates.
- They show at the top of each day in the gallery's "By day" view, where editors can edit in place, also offline
  through the outbox.
- In summaries, a day's note can be put in as a text tile, and the caption generator can use it.

## 7. Shareable web album (anyone with the link)

- Editors turn on "Share link" for a trip. This creates `trip_shares(token, trip_id, created_by, created_at,
  revoked_at)`, where the token is 128 random bits in base64url. Turning it off revokes the token, and creating
  a new link makes a new token.
- The web route `#/s/<token>` opens a read-only view (gallery, viewer, day notes, map) with no sign-in.
- A Supabase Edge Function `share` checks the token and returns the trip, its media rows and **signed URLs for
  `thumb.jpg`/`display.jpg`/`poster.jpg` only** (valid 1 hour). It never returns `original` files, which hold GPS,
  or member emails. The bucket stays private.
- The share screen warns that people with the link can see the map of where you went.

## 8. Summaries: presets, layouts and crop

### 8.1 More photo filters
Add Mono, Moody, Vivid, Desert and Night Drive to `Filter` in `domain/color/Looks.kt` (there are 5 today).

### 8.2 More layouts
Add 2-up, 4-up, justified rows (adaptive, §8.4) and panorama split (one wide photo cut across 2–3 carousel slides
so it joins up when swiped).

### 8.3 Style presets and carousel presets
- **Style presets** are JSON files in `composeResources/files/presets/` with an `index.json`, the same pattern as
  album themes. Each sets layout, style, filter, paper/border and text style. Ship about 6 (e.g. Scrapbook,
  Minimal White, Film Strip, Postcard, Night Drive, Sun-faded), applied with one tap.
- **Carousel presets** are a list of slide templates applied together with one look across every slide: a cover
  (title, dates, hero photo), photo slides (layout per slide, photos in time order, split by day where possible),
  an optional route slide (the trip's road line and stops drawn on paper with total miles, no map tiles), and a
  closing slide (caption, song). At most 20 slides, Instagram's limit.

### 8.4 Crop follows the photo's shape
- In adaptive layouts (filmstrip, polaroid, magazine, justified rows) each tile takes its photo's own aspect ratio
  by default, and the layout reflows. For justified rows of width $W$ and photo aspects $r_i = w_i/h_i$, a row's
  height is
  $$h = \frac{W - g\,(n-1)}{\sum_{i=1}^{n} r_i}$$
  for gap $g$. Rows are broken so that $h$ stays close to a target height.
- The per-photo editor's Crop tab gains a ratio picker: **Original** (default), 1:1, 4:5, 3:4, 16:9 and Free.
  Changing it reflows the layout.
- Strict grids (3 × 3, 2 × 3 and the Instagram profile grid) keep fixed tile shapes. There the picker shows only
  the tile's ratio, because the profile grid has to line up.

## Out of scope this round
Push notifications (FCM), App Store/TestFlight, testing on an iOS device, background upload and live mode on web,
HEIC and video transcoding on web, downloading a whole trip for offline use, notes per stop, camera model in the
info panel, and anything that needs a paid plan.

## Open items to settle while building
- Is the repo public (GitHub Pages) or private (Cloudflare Pages)? This changes only the deploy step.
- maplibre-compose on wasmJs: task P1 decides between the real map and the tile-free fallback.
