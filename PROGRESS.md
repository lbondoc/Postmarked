# Progress

Work log for TASKS.md, one entry per task, in order.

**How tasks are verified.** The goal as first written gated every task on `npm test`, `npm run lint` and
`npx tsc --noEmit`, but this repo is Kotlin/Gradle with no `package.json`: those commands exit 254, 254 and 1
before any task code runs. The owner chose (2026-11-23) to gate each task on its own **Check** line from TASKS.md
plus the CI commands TASKS.md lists (Gradle unit tests + debug APK + the APK resource check, `deno test
supabase/functions/`, `./supabase/tests/run.sh`, and `:composeApp:compileKotlinWasmJs` once that task exists).
Steps that need a phone, a Mac, or a CI run on `main` are written up as **needs manual check** rather than
BLOCKED, and nothing gets deployed from this sandbox.

**Sandbox notes (they affect local runs only, not CI).**
- Kotlin's wasm npm tooling depends on `github:Kotlin/karma`, which this sandbox can't fetch
  (`codeload.github.com` answers 403 for repositories not attached to the session). Locally, that tooling folder
  (`~/.kotlin/kotlin-npm-tooling`) was installed by hand with karma 6.4.4 from the npm registry, and Gradle runs
  with `-x kotlinWasmToolingSetup`. Karma is a browser test runner that nothing here uses: Node tests run on
  mocha, and the web build uses webpack. CI fetches the real tooling.
- Maven Central sometimes answers 429 here. Gradle runs are retried when that happens.
- Deno 2.9.6 (from npm) and PostGIS (apt, `postgresql-16-postgis-3`) were installed in the sandbox to run the
  `functions` and `database` jobs. They are tools only, not project dependencies.

## P1 Check web support of dependencies
- Changes:
  - `SPEC.md` §1.1: added the P1 result table (which pinned libraries compile for `wasmJs`) and the decision. The
    task asks for the findings to be recorded there, and SPEC's "Open items" says P1 settles the map question,
    which P3 then builds on.
- Approach: on a local scratch branch (`scratch/p1-wasm`, not pushed), added `wasmJs { browser() }` to
  `composeApp` (and `wasmJs { nodejs() }` to `core/domain`, which composeApp needs to resolve). Then:
  1. `./gradlew :composeApp:dependencies --configuration wasmJsCompileClasspath`: every dependency resolved
     except `org.maplibre.compose:maplibre-compose:0.20.0 FAILED`. Its Gradle module metadata lists only android,
     jvm, js and Apple variants. Versions 0.17.0 to 0.20.0, and 0.20.0 is the newest on Maven Central, all have
     no wasmJs.
  2. With maplibre moved to `androidMain`, `./gradlew :composeApp:compileKotlinWasmJs` gave 70 errors, all in
     `ui/MapScreen.kt` and all maplibre symbols. So supabase-kt 3.9.0, qrose 1.3.1 and Coil 3.6.3 all compile.
  3. Split the map behind `expect fun TripMap(...)`: the Android actual keeps the MapLibre code, the web actual
     draws the route on a Canvas (`RouteDrawing`). Then `compileKotlinWasmJs` and `compileAndroidMain` both
     succeeded (`> Task :composeApp:compileKotlinWasmJs` ran and produced its klib).
  - Decision: **tile-free fallback on web.** Alternatives considered:
    - Bumping maplibre-compose: no release has wasmJs.
    - Wrapping MapLibre GL JS through JS interop from Kotlin/Wasm: a canvas-in-canvas overlay plus hand-written
      externals. That's a lot of fragile code for a feature SPEC already gives a fallback for.
    - Leaflet: a new dependency, which SPEC's "No new heavy dependencies" rules out.
    The fallback is also the drawing the carousel route slide needs (§8.3), so it gets reused.
  - The scratch code is reused in P3. P1's own commit records only the decision, as the task says to do the
    probe on a scratch branch.
- Verification:
  - Scratch: `./gradlew :composeApp:compileKotlinWasmJs :composeApp:compileAndroidMain` → `BUILD SUCCESSFUL`.
  - Branch: gate (Gradle `:core:domain:jvmTest :composeApp:testAndroidHostTest :androidApp:assembleDebug`
    → `BUILD SUCCESSFUL`; APK resource check → found `files/themes/index.json`; `deno test supabase/functions/`
    → `ok | 6 passed | 0 failed`; `./supabase/tests/run.sh` → `database tests passed`).
- Follow-ups: maplibre-compose publishes iOS variants, so iOS could use the real map later (P6 stubs it for
  now). Revisit web if maplibre-compose ships wasmJs.

## P2 Domain on all targets
- Changes:
  - `core/domain/build.gradle.kts`: added `wasmJs { nodejs() }`, `iosArm64()` and `iosSimulatorArm64()` next to
    `jvm()`, and replaced the "JVM only for now" comment that no longer holds. The website (SPEC §1.2) and iOS
    (§1.3) reuse this module's logic: route, matching, palettes, captions, and P5's EXIF reader. They can only
    use it if it is built for their targets, and the task's check runs the tests on Node.
  - `.github/workflows/ci.yml`: added `:core:domain:wasmJsNodeTest` to the "Unit tests" step. TASKS.md counts a
    task as done when its check passes "locally or in CI", and CI is the one place it runs with the real Kotlin
    npm tooling. Without this, a JVM-only API slipping into `commonMain` would only fail on the web.
- Approach: added the targets and looked for JVM-only code to fix. A grep for `java.`, `String.format`,
  `System.`, `synchronized` and `Thread` in `core/domain/src` found nothing (the only `.format(` calls are
  kotlinx-datetime's, which is multiplatform). The whole module, tests included, then compiled for wasmJs and
  both iOS targets, and the 118 tests pass on Node, so nothing needed changing. I considered `wasmJs { browser() }`
  for the domain tests and rejected it: the task names `wasmJsNodeTest`, and Node tests need no browser.
- Verification:
  - Task check `./gradlew :core:domain:jvmTest :core:domain:wasmJsNodeTest` → `BUILD SUCCESSFUL`; the result
    XML sums to `118 tests, 0 failures, 0 errors, 0 skipped`. That equals the 118 `@Test`s in
    `core/domain/src/commonTest`.
  - Before Gradle could run the Node tests (tooling blocked), I ran the same wasm test binary under a stand-in
    runner: 118/118 passed. With a deliberately failing canary test added it reported `1 failed` and exited 1,
    then I removed the canary.
  - `./gradlew :core:domain:compileKotlinIosSimulatorArm64 :core:domain:compileKotlinIosArm64` → `BUILD
    SUCCESSFUL` on Linux. `iosSimulatorArm64Test` is disabled here ("cannot run on the current host").
  - Gate: Gradle unit tests + APK → `BUILD SUCCESSFUL`; APK resources found; deno `6 passed | 0 failed`;
    database `database tests passed`.
  - `:composeApp:compileKotlinWasmJs` (TASKS.md: "from P2 on") doesn't exist until P3 adds the target, so it
    enters the gate from P3.
- Follow-ups: **needs manual check:** the iOS simulator tests run only on macOS (P6's CI job). The wasm Node
  tests in CI depend on the Kotlin tooling downloading normally on GitHub's runners, so the first CI run of this
  branch should be watched.

## P3 composeApp on wasmJs
- Changes:
  - `composeApp/build.gradle.kts`: added `wasmJs { browser() }`. Moved `maplibre-compose` from `commonMain` to
    `androidMain`, because P1 found it has no wasmJs variant and a commonMain dependency must resolve for every
    target. Added `ktor-client-js` to `wasmJsMain`: supabase-kt and Coil need an HTTP engine on each platform, and
    SPEC §1.1 names Js for web.
  - `gradle/libs.versions.toml`: added `ktor-client-js` at the existing `ktor` version. It's the "matching
    per-platform Ktor engine" SPEC allows, not a new library.
  - `ui/MapScreen.kt`: calls `TripMap(...)` instead of the private MapLibre `RouteMap`, and drops the maplibre
    and GeoJSON imports and code. `dayNumber`, `dayColor` and `parseHex` became `internal` so both actuals colour
    days the same way as the summary list beside them.
  - `ui/TripMap.kt` (new, commonMain): `expect fun TripMap`, plus `RouteDrawing`, the tile-free Canvas route the
    task asks for ("TripMap's web actual … fallback per P1"), and its pure `routeProjection`. The drawing lives in
    commonMain so S6's route slide can reuse it (SPEC §8.3).
  - `androidMain/.../TripMap.android.kt` (new): the MapLibre map moved here unchanged. Diffed against the old
    `RouteMap` body, only the signature line differs.
  - `wasmJsMain/.../TripMap.wasmJs.kt` (new): the web actual, which calls `RouteDrawing`.
  - `RouteProjectionTest.kt` (new): `routeProjection` is new math that decides where every stop is drawn and
    tapped, so it gets tests: points stay inside the padded box, north is up and east is right, the wider span
    fills its side, and a single stop is centred.
  - `.github/workflows/ci.yml`: a "Compile the web build" step, since TASKS.md requires every task from here on
    to pass `:composeApp:compileKotlinWasmJs`.
- Approach: the compile errors from P1 showed the map was the only platform-only code in commonMain. Everything
  else (camera roll, export, video, share, QR) already goes through the interfaces `App()` takes, so only the map
  needed `expect`/`actual`. I considered two alternatives and rejected both:
  - An interface parameter on `App()` for the map, as with `Exporter`. That would push maplibre into
    `androidApp` and change `App()`'s signature for something with no runtime state; `expect`/`actual` is what
    the task names.
  - An empty web map. The task asks for the P1 fallback.
  Tap hit-testing uses the nearest stop within 24 dp, re-projected from the gesture's own size, so drawing and
  tapping can't disagree.
- Verification:
  - Task check `./gradlew :composeApp:compileKotlinWasmJs :composeApp:testAndroidHostTest` → `BUILD
    SUCCESSFUL`. RouteProjectionTest: `tests="4" skipped="0" failures="0" errors="0"`.
  - Gate (now with `:composeApp:compileKotlinWasmJs`): Gradle `BUILD SUCCESSFUL`; APK resources found; deno
    `6 passed | 0 failed`; `database tests passed`.
- Follow-ups: **needs manual check:** the map screen on the phone should look and work as before. The code moved
  unchanged, but it now lives in a different source set and nobody has run it on a device. The web drawing gets
  its first real render in P4's smoke test, behind sign-in. It has no zoom or pan: fine for a route overview, a
  limit for long trips with dense stops.

## P4 webApp module and smoke test
- Changes:
  - `settings.gradle.kts`: `include(":webApp")`. The task asks for a separate `webApp` executable, the
    counterpart of `androidApp`.
  - `webApp/build.gradle.kts` (new): a wasmJs `browser()` target with `binaries.executable()`, bundled as
    `postmarked.js`, depending on `composeApp`. `wasmJsBrowserDistribution`, the task's check, comes from this.
  - `webApp/src/wasmJsMain/resources/index.html` (new): the page that loads `postmarked.js`, full-height with the
    paper background so nothing flashes white before Compose draws.
  - `web/Main.kt` (new): `ComposeViewport { App(...) }`, the "runs `App()`" part, with
    `isA11YEnabled = true`. Compose web draws on a canvas, and with a11y off the page has no readable text at
    all. I rebuilt once with it off: Playwright's aria snapshot was empty and `getByRole('button', {name: 'Sign
    in'})` matched 0 elements. With it on, they match. So it is what lets the smoke test see the sign-in screen,
    and it also makes the site usable with a screen reader.
  - `web/Platform.kt` (new): the browser's versions of what `App()` asks for. Share uses Web Share where
    present, else the clipboard. Videos open in a new tab, since a `<video>` can't sit inside the canvas. QR
    scanning returns null (people join by link). The camera roll and exporter are `// shortcut:` stubs that
    P5 replaces (file picker, downloads), so the site browses but can't upload yet.
  - `web/smoke.mjs` (new): serves the dist folder on a local port with correct MIME types (`.wasm` must be
    `application/wasm`) and opens it in the preinstalled Chromium. It fails on any `console.error` or page error,
    or if the "Sign in" button doesn't appear within 60 s. The task asks for exactly that.
  - `kotlin-js-store/wasm/yarn.lock`: Kotlin's lock for wasm npm packages. `webApp` adds `ws` (via Ktor's Js
    engine). Without the update the build fails ("Lock file was changed"). The file itself first came in with
    P2's commit (`@js-joda/core` for kotlinx-datetime), which the P2 entry above doesn't mention.
- Approach:
  - Playwright is loaded through `createRequire`, so the globally installed copy (`NODE_PATH`) is found without a
    `package.json` or new dependency.
  - One console error is allowed by exact text: Skiko, Compose's renderer, logs "Accessing `memory` via
    `wasmExports` is deprecated". I traced it in the bundle to Skiko's own glue, and it isn't fixable here
    without changing Compose's version.
  - I considered and rejected two other ways to detect the sign-in screen:
    - Comparing screenshot pixels: fragile across fonts and GPUs.
    - A DOM marker set from the session state: it proves the state, not that the screen drew.
- Verification:
  - Task check `./gradlew :webApp:wasmJsBrowserDistribution && node web/smoke.mjs` → `BUILD SUCCESSFUL`, then
    `smoke test passed: sign-in screen rendered with no console errors`. A screenshot showed the postmark logo,
    "Sign in", the Email and Password fields, and "Create an account".
  - Negative checks: an injected `console.error("canary")` gave `console error: canary`, exit 1. With
    `postmarked.js` removed it reported the 404 plus `sign-in screen didn't render`, exit 1. Restored, it exits 0.
  - Gate: Gradle unit tests + APK + `compileKotlinWasmJs` → `BUILD SUCCESSFUL`; APK resources found; deno
    `6 passed | 0 failed`; `database tests passed`.
- Follow-ups:
  - The smoke test isn't in CI yet: GitHub's runner needs Playwright and Chromium installed first. P7's `web` job
    is where the site gets built in CI.
  - Sign-in against the real Supabase project from the website isn't tested here. It needs the site URL in
    Supabase Auth's redirect list (P7) and a real account. **Needs manual check.**

## P5 Web upload
- Changes:
  - `core/domain/.../media/ExifReader.kt` (new): `readExif(bytes)` walks the JPEG segments to APP1 "Exif",
    reads the TIFF IFDs in either byte order, and returns DateTimeOriginal, OffsetTimeOriginal and the GPS
    position. Also `exifDateTime()`. The task asks for a pure-Kotlin EXIF reader in `core/domain/media` because
    the browser has no EXIF library and iOS will reuse it. It returns EXIF's own strings, so the existing
    `exifDate` and `exifOffsetMinutes` (already used on Android) interpret them identically, and GPS goes through
    the existing `gpsOrNull`. Every read is bounds-checked, so truncated or hostile files can't throw: these bytes
    come from arbitrary user files.
  - `ExifReaderTest.kt` (new): the task's check. The fixtures are 8 × 8 JPEGs written by Pillow, an independent
    EXIF writer, so the test doesn't just mirror my reader. There are two with GPS (Intel and Motorola byte
    orders) and one without, with a JFIF APP0 before APP1 so the segment walk is exercised. The truncated-file
    case tries every possible cut of the file and asserts each field is either correct or null, never wrong and
    never an exception. Also covered: non-JPEG input including a HEIC header, and `exifDateTime`.
  - `composeApp/.../data/PickedFiles.kt` (new) + `PickedFilesTest.kt`: the web rules as pure, tested functions.
    `pickedKind`, and `pickedFileProblem` (HEIC by MIME type or by `.heic`/`.heif` name, since some browsers leave
    the type empty; anything over 50 MB refused with the phone's existing words, `TOO_BIG`). `pickedMedia` turns
    EXIF + file date into the `DeviceMedia` the import screen already sorts: an exact instant with the offset,
    the local zone without one, and the file date with no EXIF. The test covers each branch, including a late
    evening photo landing on the right day.
  - `data/Media.kt`: `CameraRoll.preview(item)`, defaulting to `item.uri`. The import grid showed thumbnails with
    `AsyncImage(model = item.uri)`, and Coil can't load a browser File or `blob:` URL. The web returns small JPEG
    bytes (Coil 3 has a ByteArray fetcher), and Android keeps working unchanged through the default.
  - `ui/ImportScreen.kt`: passes `cameraRoll::preview` down to `Cell` for that.
  - `webApp/.../Browser.kt` (new): the JavaScript the web needs: file picker, SHA-256 (Web Crypto), an upright
    picture or video frame (`createImageBitmap` with `imageOrientation: 'from-image'`), scaling on an
    OffscreenCanvas, JPEG blobs, ARGB pixels, upload via XMLHttpRequest (the only browser API that reports
    upload progress, which "in-tab progress" needs), downloads and Web Share with files.
  - `webApp/.../WebCameraRoll.kt` (new): replaces P4's stub. It follows the phone's `UploadWorker`:
    - The same files per item (original, display, thumb; or video, poster, thumb), sizes and JPEG qualities.
    - Skip-if-same-SHA-256, three parallel lanes, and resume per file.
    - dhash, sharpness and palette measured the same way, and the same `Media.add`, so web uploads look the same
      in the gallery.
    - Files stay JavaScript `File` handles and go straight to Storage's REST endpoint with the user's token, so
      a 50 MB video is never copied into wasm memory.
    - HEIC and oversize files are queued with their reason, so the trip page's existing "N couldn't be uploaded:
      …" line shows it.
    - The tab asks before closing while uploads run.
  - `web/Platform.kt`: `WebExporter` now encodes each picture to JPEG (Skia, quality 92 like Android) and
    downloads it, or uses Web Share with files where the browser supports it. The caption is copied for pasting.
  - `webApp/build.gradle.kts`: opts the module into `ExperimentalWasmJsInterop`. Every `js()` call warns without
    it, and this module is the interop layer by design.
- Approach:
  - I picked the photos through the existing `scan()` → `sortForTrip` → import screen flow instead of a new web
    screen. It keeps one import UI, and the "taken on this trip / no location / elsewhere" sorting works on web
    for free.
  - For uploads I considered three routes:
    - supabase-kt's `upload(ByteArray)`: rejected, because it means copying every file byte by byte into wasm.
    - `fetch`: rejected, because it has no upload progress.
    - Signed upload URLs: rejected, an extra round trip per file for no gain.
  - Videos are uploaded as they are, per SPEC (no transcoding on web).
- Verification:
  - Task check `./gradlew :core:domain:jvmTest --tests '*ExifReaderTest*'` → `tests="6" … failures="0"` (and 6/6
    on `wasmJsNodeTest`). Then `node web/smoke.mjs` → `smoke test passed`.
  - `PickedFilesTest` → 6/6.
  - Browser glue, run in Chromium on fixtures by a scratch script that extracts the exact JS bodies from
    `Browser.kt`:
    - A 3000 × 2000 JPEG with EXIF orientation 6 came back upright at 2000 × 3000, giving display 1365 × 2048,
      thumb 267 × 400 and a 9 × 8 hash canvas. An 8 × 8 image was not enlarged.
    - ARGB pixels read `255,30,90,200` for the RGB(30, 90, 200) fill, and the JPEG blob starts `FF D8`.
    - The 640 × 360 test video gave a 640 × 360 frame.
    - The upload reached a local server with `Bearer TOKEN`, `apikey`, `x-upsert: true` and `image/jpeg`, the
      body byte-identical to the file, and progress ending at 1. A 413 came back as 413, an unreachable host
      as 0.
    - Web Crypto's SHA-256 equalled Node's.
  - Gate: Gradle unit tests + APK + `compileKotlinWasmJs` → `BUILD SUCCESSFUL`; APK resources found; deno
    `6 passed | 0 failed`; `database tests passed`.
- Follow-ups:
  - **Needs manual check:** a real upload from the website to the Supabase project (sign in, add photos and a
    video, see them in the gallery from the phone). This sandbox has no account, so the Storage REST call is
    checked only against a local server, built from Supabase's documented API (POST `/storage/v1/object/{bucket}/{path}`
    with `x-upsert`).
  - The picker opens from the import screen's first composition, soon after the user's tap. Chrome and Firefox
    allow that, but a browser with stricter user-activation rules could ignore the click (then "Try again" opens
    it).
  - Videos keep the browser's MIME type (e.g. `video/quicktime`) under the name `video.mp4`, and some browsers
    can't play such files.
  - Video dates come from the file date only (no MP4 metadata parsing), the same limit the phone has for video
    clock time.
  - Uploads are in memory: reloading the tab drops the queue. That's expected per SPEC ("Uploads run while the
    tab is open"), and the browser warns before closing.

## Found
- (nothing yet)
