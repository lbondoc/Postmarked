# Progress

Work log for TASKS.md, one entry per task, in order.

**How tasks are verified.** The goal as first written gated every task on `npm test`, `npm run lint` and
`npx tsc --noEmit`, but this repo is Kotlin/Gradle with no `package.json`: those commands exit 254, 254 and 1
before any task code runs. The owner chose (2026-11-23) to gate each task on its own **Check** line from TASKS.md
plus the CI commands TASKS.md lists (Gradle unit tests + debug APK + the APK resource check, `deno test
supabase/functions/`, `./supabase/tests/run.sh`, and `:composeApp:compileKotlinWasmJs` once that task exists).
Steps that need a phone, a Mac, or a CI run on `main` are written up as **needs manual check** rather than
BLOCKED, and nothing gets deployed from this sandbox.

**From S4 on (2026-11-25): `npm test`.** The owner's new goal gates every task on `npm test` exiting 0. A root
`package.json` now exists for exactly that. Its `test` script is the gate above: Gradle unit tests, the debug APK,
`compileKotlinWasmJs`, `deno test supabase/functions/` and `./supabase/tests/run.sh`. It is not a Node project.
Each task still runs its own **Check** line too. Locally the database tests need `PGHOST=localhost PGUSER=postgres
PGPASSWORD=postgres` for the sandbox's Postgres.

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

## P6 iOS compiles in CI
- Changes:
  - `composeApp/build.gradle.kts`: added `iosArm64()` and `iosSimulatorArm64()`, plus `ktor-client-darwin` in
    `iosMain`. The task asks for these, and SPEC §1.1 names Darwin as the iOS engine that supabase-kt and Coil
    need at runtime.
  - `gradle/libs.versions.toml`: `ktor-client-darwin` at the existing `ktor` version (the matching per-platform
    engine SPEC allows).
  - `iosMain/.../ui/TripMap.ios.kt` (new): the `actual` the new targets require for `TripMap`. It uses the
    tile-free `RouteDrawing` and is marked `// shortcut: stub, implement when a Mac is available`, as
    SPEC §1.3 asks.
  - `iosMain/.../MainViewController.kt` (new): `ComposeUIViewController { App(...) }` with stub camera roll,
    exporter, video, share and QR, each marked with the same shortcut comment. SPEC §1.3 says iOS's
    native-API pieces are stubs that say "Not available on iOS yet". Giving `App()` its iOS entry point means the
    CI compile covers the whole app wiring, not just the library, and a future Xcode project only has to call it.
    The camera roll denies access, so the import screen shows its existing "access off" state rather than
    crashing.
  - `.github/workflows/ci.yml`: a `workflow_dispatch` trigger, and an `ios` job on `macos-latest` that runs
    exactly the task's command. Its `if:` limits it to pushes to `main` and manual runs, because SPEC says macOS
    minutes are scarce on the free plan.
- Approach:
  - Stubs went in the `iosMain` source set of composeApp rather than in a separate `iosApp` module. There's no
    Xcode project to host one this round (no Mac, out of scope), and the task asks only for composeApp to compile.
  - I considered using maplibre-compose's iOS build for the map now and rejected it: nobody can run it this
    round, so the stub is the honest choice, and it's flagged.
- Verification:
  - Locally (Linux can build iOS klibs, not run them): `./gradlew :core:domain:compileKotlinIosSimulatorArm64
    :composeApp:compileKotlinIosSimulatorArm64 :composeApp:compileKotlinIosArm64` → `BUILD SUCCESSFUL`.
  - The workflow YAML parses: triggers `push, pull_request, workflow_dispatch`, jobs
    `database, functions, build, ios`.
  - Gate: Gradle unit tests + APK + `compileKotlinWasmJs` → `BUILD SUCCESSFUL`; APK resources found; deno
    `6 passed | 0 failed`; `database tests passed`; web build + `node web/smoke.mjs` → passed.
  - For the record: GitHub Actions ran this branch's pushes for P2 (run 22) and P4 (run 23) with the real Kotlin
    npm tooling, and both concluded `success`. So the `wasmJsNodeTest` and "Compile the web build" CI steps work
    there.
- Follow-ups: **needs manual check:** the `ios` job itself has not run. It needs a push to `main` or a manual run
  from the Actions tab, and both are outside this session. `:core:domain:iosSimulatorArm64Test` can only run on
  macOS. The first macOS run may also need Xcode's simulator runtime, which `macos-latest` normally includes.

## P7 Deploy the website
- Changes (commit c6911a3; this entry and the checkbox follow in their own commit, once CI had run):
  - `.github/workflows/ci.yml`: a `web` job that builds `:webApp:wasmJsBrowserDistribution` and runs
    `node web/smoke.mjs`, with Playwright 1.63.0 (the version used locally) installed without a `package.json`.
    On `main` it also uploads the site as the Pages artifact. And a `pages` job, `main` only, `needs: web`, that
    publishes it with `actions/deploy-pages`. The repo is public (GitHub search: `"visibility":"public"`), so
    SPEC picks GitHub Pages over Cloudflare. I split building from publishing because the `github-pages`
    environment only accepts `main` by default: one combined job would fail on every branch push. This way the
    task's "CI job `web` is green" is checkable from any branch.
  - `README.md`: a "Website" section with the URL `https://lbondoc.github.io/Postmarked/` and how to build and
    smoke-test it. Two setup steps added: Supabase Auth → Redirect URLs (the task asks for the site URL there),
    and GitHub Settings → Pages → Source "GitHub Actions" (`has_pages` is false today, and the workflow's token
    can't enable it).
- Approach: GitHub Pages serves the site under `/Postmarked/`, so I served the production build under that
  sub-path locally. The sign-in screen rendered with no 404s or console errors: every asset path is relative,
  so no base-path setting is needed. I considered Cloudflare Pages and rejected it: SPEC reserves it for a
  private repo, and it needs an API token secret.
- Verification:
  - GitHub Actions run 26 on this branch (commit c6911a3): `web` → success. Its steps: "Build the website"
    success, "Smoke test" success, the Pages upload skipped (not `main`). `build`, `database` and `functions`
    succeeded; `pages` and `ios` skipped as designed.
  - Local gate: Gradle unit tests + APK + `compileKotlinWasmJs` → `BUILD SUCCESSFUL`; APK resources found; deno
    `6 passed | 0 failed`; `database tests passed`; web build + smoke → passed.
- Follow-ups: **needs manual check (owner):**
  1. Settings → Pages → Source: GitHub Actions.
  2. Merge to `main`, so `pages` publishes.
  3. `curl -sf https://lbondoc.github.io/Postmarked/ | grep -q '<canvas\|composeApp'`. The page's own HTML has
     neither string until the script runs, so this grep would fail on any build of the site. `grep -q
     postmarked.js` is the equivalent check that works.
  4. Add the URL to Supabase Auth's Redirect URLs in the dashboard. It isn't in the repo: there's no
     `supabase/config.toml`.

  Nothing was deployed from this session.

## F1 Trip hues replace the muddy palette
- Changes:
  - `core/domain/.../color/Color.kt`: `tripHues(palettes)`, following SPEC §2 step by step:
    1. Each photo's weights are normalised to 1.
    2. Only swatches with $C \ge$ `NEUTRAL_CHROMA` and $0.3 \le L \le 0.92$ compete.
    3. They're grouped into 12 hue sectors of $30^\circ$, scored $S_k = \sum w_i C_i$.
    4. Up to 6 sectors with $S_k \ge 0.02 \max S$ are returned, best first, each as its real
       $\arg\max w_i C_i$ swatch.
    5. Then at most one neutral (White, Grey or Black by `colourName`, whichever covers most), never
       $L < 0.2$.

    Each swatch's weight is its sector's share of the trip's photos, so `Swatch` keeps meaning "share of the
    pictures".
  - `ui/TripScreen.kt`: `tripPalette` calls `tripHues` instead of `mergePalettes`. That one function feeds the
    gallery colour filter, the summary styles and the theme, so the fix lands everywhere SPEC names.
  - `ui/AlbumTheming.kt`: the accent chips skip the neutral. SPEC says "the theme accent uses the trip hues
    only", and the picker listed every palette swatch. The automatic accent (`resolveColours`) already filtered
    to colourful swatches.
  - `TripHuesTest.kt` (new): the task's check with its own scenario. Eight dark road photos (asphalt, black
    shadow, a navy night sky at L 0.25 that is colourful but dark, dark dirt), one sunset and one sky. It
    asserts orange and blue are present, no swatch has $L < 0.3$, and there's at most one neutral. It also
    checks:
    - every hue is a real swatch from a photo;
    - the strongest sector comes first and the neutral last;
    - each photo counts equally even with unnormalised weights;
    - the cap of six, and the 2 % cut-off;
    - an empty input.
- Approach: implemented SPEC's algorithm literally. I didn't try to tune k-means (weights, more clusters):
  averaging is the cause of the muddiness, and SPEC asks for real swatches, not centres. A scratch run of
  the same trip through the old `mergePalettes` gave `Black L=0.25, Grey L=0.4, Black L=0.19, Blue, Orange`,
  three dark entries ahead of any colour. `tripHues` gives `Blue L=0.64, Orange L=0.72, Purple L=0.46, Grey
  L=0.39`.
  - **`mergePalettes` was not deleted.** The task says to delete it "if unused". Production no longer calls it,
    but the existing test `ColorTest.tripPaletteWeighsEachPhotoEqually` does, and deleting the function means
    deleting that test, which this round's rules forbid. It stays, and its KDoc is unchanged.
- Verification:
  - Task check `./gradlew :core:domain:jvmTest --tests '*TripHuesTest*'` → `tests="6" skipped="0"
    failures="0" errors="0"`.
  - Gate: Gradle unit tests (incl. the existing `ColorTest`, `SummaryBuilderTest`, `AlbumThemeTest`) + APK +
    `compileKotlinWasmJs` → `BUILD SUCCESSFUL`; APK resources found; deno `6 passed | 0 failed`; `database
    tests passed`; web build + `node web/smoke.mjs` → passed.
- Follow-ups:
  - The neutral may be as dark as L 0.2 per SPEC. A trip whose largest neutral is near-black could show one swatch
    between L 0.2 and 0.3. The task's check (no $L < 0.3$) holds for its scenario because asphalt grey
    outweighs the near-blacks.
  - **Needs manual check:** how the new palette looks on a real trip.
  - Decide whether to delete `mergePalettes` together with its test.

## F2 "More colours" wheel
- Changes:
  - `core/domain/.../color/Color.kt`: `COLOUR_WHEEL`, the 12 named colours SPEC §2 lists (red, orange, yellow,
    lime, green, teal, cyan, blue, purple, pink, white, black). The values were computed from OKLab at chosen
    hues, checked to be inside sRGB gamut, and vivid ($C \ge 0.1$) so the existing `share()` rule ("at least
    half as vivid") matches only real colour. It lives in the domain because both the gallery and summaries
    use it.
  - `ui/GalleryFilters.kt`: a "More colours" row after the trip's hues. Each swatch is counted by the existing
    `countEach` (the same `share()` match the filter uses) and greyed out at 0. A selected swatch stays tappable
    so it can be turned off. Active-filter chips show the wheel's own name: `colourName` would call Lime "Green"
    and Cyan "Teal".
  - `ui/SummaryScreens.kt` `ColourPanel` (the one-colour "chosen by hand" picker, used by the editor and the
    guided first summary): the same row, counting matching photos with the panel's existing `share() ≥
    MONOCHROME_SHARE` rule. It's greyed out at 0 because a one-colour summary from no photos is empty.
  - `GalleryTest.kt`: the new case the task's check names. "Blue" filters to exactly the two bluish photos and
    counts 2. Yellow and White each find exactly their photo. Purple and Black count 0. All 12 get a count.
- Approach: the filter already stores colours as hex and matches them by family, so wheel colours reuse
  `filterMedia` and `countEach` unchanged. Nothing new decides what "blue" means. I considered generating the
  wheel from `colourName`'s hue bands at runtime and rejected it: fixed, reviewed values are easier to reason
  about and can't drift out of gamut.
- Verification:
  - Task check `./gradlew :composeApp:testAndroidHostTest --tests '*GalleryTest*'` → `tests="8" skipped="0"
    failures="0" errors="0"`, 7 existing tests plus the new one.
  - Gate: Gradle unit tests + APK + `compileKotlinWasmJs` → `BUILD SUCCESSFUL`; APK resources found; deno
    `6 passed | 0 failed`; `database tests passed`; web build + smoke → passed.
- Follow-ups: **needs manual check:** how the second row of stamps looks in the filter sheet and the summary
  colour panel on a phone. There are no UI tests in this repo. A wheel colour picked in a summary is stored by
  hex like any other, so saved summaries need no change.

## F3 Viewer zoom
- Changes:
  - `ui/Zoom.kt` (new): `ZoomState(scale, pan)` with `transformed` (a pinch about the fingers' centroid),
    `doubleTapped` ($1\times \leftrightarrow 2.5\times$ around the tap) and `swipe` (pans when zoomed; returns
    null at 1×, meaning the pager takes the swipe). Plus `clampPan`, implementing SPEC §3.1's
    $|t_x| \le \frac{(s-1)W}{2}$, $|t_y| \le \frac{(s-1)H}{2}$. Scale is clamped to $[1, 5]$. The task asks
    for the clamp math as a pure commonMain function, so all the decisions live here and the gesture code only
    forwards events. The constants are prefixed `VIEWER_`: `MAX_ZOOM` already exists in `SummaryCanvas.kt`
    for the crop editor (4×), and the two limits are deliberately different.
  - `ui/Viewer.kt`:
    - The zoom state is held per current page (`remember(pager.currentPage)`), so it resets on every photo
      change, as SPEC asks.
    - `HorizontalPager(userScrollEnabled = !zoom.zoomed)`, so horizontal swipes pan instead of paging while
      zoomed.
    - A `zoomable` modifier on photos only (videos play in the platform player): a pinch always zooms, a
      one-finger drag goes through `swipe()`, and a double-tap goes through `doubleTapped()`.
    - The `graphicsLayer` sits inside the pointer handlers, so they read screen coordinates, which the math
      assumes.
  - `ZoomTest.kt` (new): the task's check.
    - Scale clamped at 5 and at 1.
    - Pan clamped to $(s-1)W/2$ and $(s-1)H/2$, including a huge pinch-pan, with nothing movable at 1×.
    - Zooming back to 1× recentres.
    - A pinch keeps the content point under the fingers.
    - Swipe returns null at 1× and pans (clamped) when zoomed.
    - Double-tap zooms to 2.5× keeping the tapped point and toggles back.
- Approach: I wrote the gesture loop by hand (`awaitEachGesture`) instead of `detectTransformGestures`, which
  consumes every one-finger drag. That would have stopped the pager from swiping at 1×, breaking the
  existing swipe-between-photos. I considered a zoom library and rejected it: it would be a new dependency, and
  SPEC rules out unnecessary ones.
- Verification:
  - Task check `./gradlew :composeApp:testAndroidHostTest --tests '*ZoomTest*'` → `tests="6" skipped="0"
    failures="0" errors="0"`.
  - The rebuilt APK contains `ZoomState` (11 hits in its dex files).
  - Gate: Gradle unit tests + APK + `compileKotlinWasmJs` → `BUILD SUCCESSFUL`; APK resources found; deno
    `6 passed | 0 failed`; `database tests passed`; web build + smoke → passed.
- Follow-ups: **needs manual check** on a phone:
  - Pinch, double-tap and panning feel right.
  - Swiping still changes photos at 1×.
  - Zoom resets on the next photo.

  The clamp keeps the photo's box on screen. Because the picture is letterboxed (`ContentScale.Fit`), a
  zoomed portrait photo can still pan a little into its black bars. That's within SPEC's formula, which uses
  the page size.

## F4 Progressive load and prefetch
- Changes:
  - `data/ImageKeys.kt` (new): `storageKey(url)` turns a signed media-bucket link into its Storage path, the same
    string as `Media.path`, else null. SPEC §4: Coil's caches must key on the path, not the signed URL, whose
    token changes every time.
  - `ui/Images.kt` (new): `StoragePathKeys`, a Coil interceptor that applies that key (memory and disk) to every
    request whose data is a signed link, unless the request already set one. Plus `imageLoader()`, Coil's
    default builder with it added. Before this, only the grid thumbnail and the viewer set path keys by hand;
    summaries, the map sheet and exports loaded raw signed URLs, so their caches missed on every new link.
  - `ui/App.kt`: `setSingletonImageLoaderFactory(::imageLoader)`, so all `AsyncImage`s (Android, web, iOS)
    share it.
  - `ui/Viewer.kt`:
    - The viewer creates signed links for the current photo and its two neighbours, and prefetches the
      neighbours' display images into the cache. That's the task's "prefetch the neighbours".
    - Each photo page draws the thumbnail at once with the display image over it, so the display image
      replaces the thumbnail as soon as it loads ("show the thumb first, swap in the display image"). Before,
      it relied on `placeholderMemoryCacheKey`, which shows nothing unless the thumbnail is already in memory,
      as it isn't when the viewer is opened from the map's stop sheet.
    - Pages no longer each request their own link: the one map serves both display and prefetch.
  - `ImageKeyTest.kt` (new): the task's check. Two signed URLs for the same path with different tokens give the
    same key, and it equals `Media.path`. Different files keep different keys, and non-Storage links (Spotify
    artwork) are left alone.
- Approach: one interceptor in the shared loader rather than adding `.diskCacheKey()` at every call site.
  That would be easy to forget in new screens (the summary editor had already missed it). I considered a Coil
  `Keyer`, but it only sets the memory key, while the disk cache is what matters offline.
- Verification:
  - Task check `./gradlew :composeApp:testAndroidHostTest --tests '*ImageKeyTest*'` → `tests="3" skipped="0"
    failures="0" errors="0"`.
  - Gate: Gradle unit tests + APK + `compileKotlinWasmJs` → `BUILD SUCCESSFUL`; APK resources found; deno
    `6 passed | 0 failed`; `database tests passed`; web build + smoke → passed. The smoke test shows the
    custom image loader doesn't break the web app's start.
- Follow-ups: **needs manual check** on a phone:
  - Swiping to the next photo shows it sharp at once.
  - Opening the viewer from the map shows the thumbnail before the full picture.
  - Cached images still show after the links expire (an hour).

  `storageKey` assumes Supabase's signed-URL form `…/object/sign/media/<path>?token=…`. If the form ever
  changes, it returns null and images fall back to Coil's default keys, not to broken images.

## F5 Info panel
- Changes:
  - `ui/MediaInfo.kt` (new): `infoLines(item, tripStart, stop, place, pixels, bytes, seconds)`. It gives
    "Taken by …", the local time (the viewer's existing `MOMENT` format, in the zone where it was taken),
    "Day N", the stop title, "Near <place>", then "W × H px · size" for photos or "m:ss · size" for videos.
    Missing fields are dropped, including "Day N" for items taken before the trip started. The task names
    `infoLines()` and its behaviour.
  - `data/Media.kt`: `Media.fileSize()`, the original photo's or video's size from Storage's file info
    (`bucket.info`). SPEC lists file size, and the database has no size column.
  - `ui/Viewer.kt`: an "ⓘ Info" / "Hide info" toggle beside Comments. When on, the bottom bar shows the info
    lines in place of the usual "name · time" line. The place name comes from the existing cached
    `placeName()` reverse lookup, and the file size from Storage. Each fills in when its lookup finishes.
    New parameters `tripStart` and `stopTitles`.
  - `ui/TripScreen.kt`: passes the trip's start date, and each item's stop title computed from the same
    `planRoute` the Stops view uses, so numbers match. Names appear where they've been looked up.
  - `MediaInfoTest.kt` (new): the task's check.
    - All six lines in order, for a photo whose UTC time falls on the next day (local time and Day N follow
      the taken-there zone).
    - Missing person, stop and place are left out.
    - No "Day 0".
    - A video shows its length instead of pixels.
- Approach: a pure line builder plus a thin composable, the same split the codebase uses for gallery
  sections. I chose a toggle over SPEC's "or swiping up": the bottom bar already swipes horizontally with the
  pager and vertically with the comments sheet, and a second vertical gesture there would fight the sheet.
- Verification:
  - Task check `./gradlew :composeApp:testAndroidHostTest --tests '*MediaInfoTest*'` → `tests="3" skipped="0"
    failures="0" errors="0"`.
  - Gate: Gradle unit tests + APK + `compileKotlinWasmJs` → `BUILD SUCCESSFUL`; APK resources found; deno
    `6 passed | 0 failed`; `database tests passed`; web build + smoke → passed.
- Follow-ups:
  - Pixel size and video length are supported by `infoLines` but not yet shown. Neither is stored (no
    columns), and reading them means downloading the original. A follow-up could save width, height and
    duration at upload in a migration.
  - **Needs manual check:** the panel on a phone. The file size depends on Storage's `info` endpoint
    answering for members.
  - Stop names only exist for stops someone has looked up (in the Stops view or on the map). Other stops show
    "Stop N".

## F6 Share one photo, and save to phone
- Changes:
  - `data/PhotoSaver.kt` (new):
    - The `PhotoSaver` interface the task names, with `save` and `share` of a `SavedFile` (a signed link,
      name and kind). The platform only needs a link, and streaming one keeps a 50 MB video out of memory.
    - `saveEach`, which saves several one after another, reporting progress, counting failures without
      stopping, and stopping before the next item when cancelled. That's "saving several shows progress and
      can be cancelled".
    - `extensionFor(mime)`, because originals can be JPEG or HEIC and videos MP4 or MOV.
  - `data/Media.kt`: `savedFile()`, a 1-hour signed link to the original photo or `video.mp4`, named
    `postmarked-<local yyyyMMdd-HHmmss>-<id>`. SPEC says "the original photo or `video.mp4`".
  - `ui/Saving.kt` (new): `SaveJob` (start, cancel, progress) and `SavingBar` ("Saving 2 of 5" with Cancel and a
    progress bar, then "Saved N" or "N couldn't be saved"). F7's multi-select reuses them for several items.
  - `ui/Viewer.kt`: Share and Save buttons in the top bar, and the saving bar above the reactions.
  - `ui/App.kt`, `ui/TripScreen.kt`: pass a `photoSaver` from the platform to the viewer, the same way
    `exporter` and `videoPlayer` already flow.
  - `androidApp/.../AndroidPhotoSaver.kt` (new) + `MainActivity.kt`:
    - Saving streams the link into MediaStore under `Pictures/Postmarked` (Images or Video collection,
      `IS_PENDING` until complete, deleted if it fails or is cancelled), as `AndroidExporter` already does for
      summaries.
    - Sharing downloads into `cache/exports/shared` (inside the existing FileProvider path) and opens the
      share sheet.
    - The copy loop checks for cancellation between 64 KB chunks.
  - `webApp/.../WebPhotoSaver.kt` (new) + `Browser.kt` + `Main.kt`: fetch the file as a Blob. Save downloads it.
    Share uses Web Share with the file where `navigator.canShare({files})` allows, else downloads.
  - `iosMain/.../MainViewController.kt`: `IosPhotoSaver` stub, marked `// shortcut:` and saying "Not available
    on iOS yet", as SPEC §1.3 asks.
  - `SaveEachTest.kt` (new):
    - Progress after each item, and a failure counted without stopping the rest.
    - Cancelling while item 2 is saving means item 3 never starts.
    - File extensions from MIME types.

    It uses plain coroutine starts, not `kotlinx-coroutines-test`, to avoid adding a test dependency.
- Approach: links rather than bytes across the platform boundary. I considered downloading in common code with
  `downloadAuthenticated` (a ByteArray) and handing bytes to the platform, and rejected it: it holds whole
  videos in memory, and on the web it means copying them into wasm.
- Verification:
  - Task check `./gradlew :androidApp:assembleDebug :composeApp:compileKotlinWasmJs` → `BUILD SUCCESSFUL`, then
    `node web/smoke.mjs` → passed. iOS also compiles (`:composeApp:compileKotlinIosSimulatorArm64` →
    `BUILD SUCCESSFUL`).
  - `SaveEachTest` → `tests="3" … failures="0"`.
  - Web glue in Chromium, with the exact JS bodies from `Browser.kt`:
    - `fetchBlob` returned the 253 920-byte JPEG with type `image/jpeg`, and a 404 rejected with
      "Download failed (404)".
    - `shareBlob` returned false in desktop headless Chromium, so the code falls back to a download.
    - `downloadBlob` produced a download named `postmarked-20261003-182200-abcd1234.jpg` whose bytes match the
      file.
  - Gate: Gradle unit tests + APK + `compileKotlinWasmJs` → `BUILD SUCCESSFUL`; APK resources found; deno
    `6 passed | 0 failed`; `database tests passed`.
- Follow-ups: **needs manual check (the task's own):**
  - On the phone, a saved photo appears in Pictures/Postmarked.
  - Share opens the share sheet with the file.
  - On the web, Save downloads and Share uses Web Share on a phone browser.

  Downloads depend on Supabase Storage allowing the site's origin for `fetch` (CORS). Storage allows all
  origins by default.

## F7 Multi-select
- Changes:
  - `ui/DragSelect.kt` (new): the import screen's long-press-and-drag selection (run select or deselect, plus
    edge auto-scroll) moved out of `ImportScreen` into `Modifier.dragSelect`. The task says to reuse it, and a
    copy would drift.
  - `ui/ImportScreen.kt`:
    - Uses `dragSelect` (about 50 lines removed), with unchanged behaviour: the default `selectHeld = false`
      keeps "tick only once the finger moves".
    - `setRange` uses the new pure `selectRange`.
    - Imports no longer used were removed.
  - `ui/Selection.kt` (new): pure `dragRange`, `selectRange`, `toggled`, `ownItems` (yours vs others) and
    `skippedNote` ("N not yours, skipped"). These are what the task's check tests.
  - `ui/TripScreen.kt`:
    - `TripViewModel.selected`, kept in the view model so it survives rotation, like the filter.
    - The grid gets `dragSelect` (`selectHeld = true`: a long press starts selecting).
    - A `SelectionBar` (N selected · Save · Share · Delete · Done) with "Delete: N not yours, skipped" when
      relevant. Save goes through F6's `SaveJob`/`SavingBar`, so saving several shows progress and can be
      cancelled.
    - The delete dialog deletes only your own items (`ownItems`) and says how many are skipped, or explains
      that none are yours.
    - `deleteSelected` and `share` added to the view model.
  - `ui/GalleryFilters.kt` `gallery()`: while selecting, a tap toggles a tile instead of opening it, and
    selected tiles get a border and ✓.
  - `PhotoSaver.share` now takes a list: "selected items can be … shared". Android uses `ACTION_SEND_MULTIPLE`
    with a common MIME type and ClipData permissions (as `AndroidExporter` does). The web shares all files with
    Web Share, or downloads each. The iOS stub and the viewer (a list of one) follow.
  - `SelectionTest.kt` (new):
    - The drag range in both directions.
    - Range select keeps tiles outside the run, range deselect works, and shrinking a drag recomputes from
      the drag's start.
    - Tap toggle.
    - `ownItems` returns 2 mine and 2 skipped, with "2 not yours, skipped", and nothing is yours when signed
      out.
- Approach: selection is by tile (keyed by cover id), and actions apply to the tiles as shown. Shots folded
  behind a burst tile aren't included, so "Delete 3" never silently removes 9. I considered selecting every
  shot of a tile and rejected it as surprising for delete. Burst shots can still be handled one by one in the
  viewer.
- Verification:
  - Task check `./gradlew :composeApp:testAndroidHostTest --tests '*SelectionTest*'` → `tests="4" skipped="0"
    failures="0" errors="0"`.
  - The existing tests still pass after the import-screen refactor (whole `testAndroidHostTest` in the gate).
  - iOS compile → `BUILD SUCCESSFUL`.
  - Gate: Gradle unit tests + APK + `compileKotlinWasmJs` → `BUILD SUCCESSFUL`; APK resources found; deno
    `6 passed | 0 failed`; `database tests passed`; web build + smoke → passed.
- Follow-ups: **needs manual check** on a phone:
  - Long-press then drag in the gallery.
  - The import screen's drag-select still works exactly as before.
  - Edge scrolling, and the selection bar's actions.

## F8 Trip cover
- Changes:
  - `supabase/migrations/20261129000000_trip_cover.sql` (new): `trips.cover_media_id` with a composite foreign
    key `(cover_media_id, id) → media (id, trip_id)` and `on delete set null (cover_media_id)`, plus
    `grant update (cover_media_id)`. This covers all three SPEC §3.2 rules in the database:
    - "Same trip": the key includes the trip id, which needs the new `unique (id, trip_id)` on media.
    - "Cleared when that media is deleted": the column-list form nulls only the cover, not the trip's id. It
      needs Postgres 15+; CI runs 16 and Supabase 15+.
    - "Only editors": the existing "owner and editors edit the trip" RLS policy, which the grant extends to
      the new column.
  - `supabase/tests/cover_test.sql` (new), the task's check:
    - An editor sets the cover and a viewer sees it.
    - A viewer's update touches 0 rows and the cover is unchanged.
    - Media from another trip, and a made-up id, are refused even for the owner of both trips.
    - Deleting the photo clears the cover, and only the cover.
  - `data/Trips.kt`: `Trip.coverMediaId`, selected with every trip, and `Trips.setCover`.
  - `data/Media.kt`: `coverUrls(trips)`, every cover's thumbnail link for the trip list in one signed-URL request.
  - `ui/Viewer.kt` + `ui/TripScreen.kt`: "Set as trip cover" for owners and editors (the viewer receives a null
    callback for viewers), and "Trip cover" on the current one. After setting it, the trip reloads.
  - `ui/TripListScreen.kt`: the card shows the cover as a 56 dp rounded thumbnail.
- Approach: enforced in the database, not the app. I considered a trigger and rejected it: a composite foreign
  key is declarative and can't be bypassed through PostgREST. RLS already says who may update a trip.
- Verification:
  - Task check `./supabase/tests/run.sh` → `== tests/cover_test.sql` … `database tests passed`.
  - Mutation check: with the composite foreign key removed, the run fails with `ERROR: media from another trip
    is refused`. Restored, it passes.
  - Gate: Gradle unit tests + APK + `compileKotlinWasmJs` → `BUILD SUCCESSFUL`; APK resources found; deno
    `6 passed | 0 failed`; iOS compile → `BUILD SUCCESSFUL`; web build + smoke → passed.
- Follow-ups:
  - **Needs manual step (owner):** run the new migration in the Supabase SQL editor, as README's setup says
    for each file in `supabase/migrations`.
  - **Needs manual check:** "Set as trip cover" on a phone, and the trip list card.

## F9 Cancel uploads
- Changes:
  - `data/Media.kt`:
    - `cancelUploads(queue, ids)`, pure. Waiting, uploading and failed items leave; finished ones stay, as
      SPEC §3.3 says ("Items that already finished stay").
    - `CameraRoll.cancel(mediaIds)`.
    - `Media.discardUpload()`, which deletes the item's row if it got that far and every file its kind can
      have. That's "its partial files are deleted": deleting files that were never sent is harmless, which
      also covers a file finishing just as the cancel lands.
  - `androidApp/.../UploadQueue.kt`: `cancel()` removes the items from the saved queue, clears their progress,
    deletes their scratch copies and calls `discardUpload`.
  - `androidApp/.../UploadWorker.kt`: each item's upload races a watcher on the queue (`select` over
    `work.onAwait` and "the item left the queue"). Cancelling therefore aborts the request in flight: the upload
    coroutine is cancelled, and Ktor cancels the HTTP call. The worker then cleans up once more for the race
    above. Other lanes and the rest of the queue carry on.
  - `androidApp/.../AndroidCameraRoll.kt`: `cancel` delegates to `UploadQueue`.
  - `webApp/.../WebCameraRoll.kt` + `Browser.kt`: one job per item, so cancel can stop it, plus an
    `AbortController` passed to the XHR. Cancelling a coroutine that awaits a Promise doesn't stop the browser's
    request by itself, so `abortNow` stops it. Then `discardUpload`. SPEC: "Cancel applies to the web upload
    too."
  - `iosMain/.../MainViewController.kt`: stub `cancel`.
  - `ui/TripScreen.kt`: the queue list on the trip page ("N in the queue · Cancel all", then one row per item
    with kind, day, "uploading 40%" and Cancel), under the existing progress bar.
  - `BatchProgressTest.kt`: three new cases, the task's check.
    - Cancelling waiting items shrinks the batch (1 of 2, 0.5).
    - Cancelling an item mid-upload drops its partial progress from the bar.
    - Cancel-all keeps the finished item and removes waiting and failed ones, so the bar disappears.

    The four existing tests are untouched.
- Approach: the queue is the source of truth. Removing an item is the cancel, and the uploaders react to it,
  so no new channel between UI and worker was needed. On Android that also works across the process boundary
  WorkManager may use, since both read `UploadQueue`. I considered cancelling the whole WorkManager job and
  re-queueing the rest, and rejected it: it restarts every lane and loses in-flight progress of items nobody
  cancelled.
- Verification:
  - Task check `./gradlew :composeApp:testAndroidHostTest --tests '*BatchProgressTest*'` → `tests="7"
    skipped="0" failures="0" errors="0"` (4 existing + 3 new).
  - Web abort in Chromium, using the exact JS from `Browser.kt`: an 8 MB upload to a server that never
    answers, aborted after 200 ms, resolved with status 0 at 205 ms, and the server saw the connection close.
    The scratch harness that extracts the JS needed a fix: it mis-parsed parameter lists containing
    `(Double) -> Unit`. It was a test-tool bug, not an app bug.
  - All three targets compile (`:androidApp:assembleDebug :webApp:compileKotlinWasmJs
    :composeApp:compileKotlinIosSimulatorArm64` → `BUILD SUCCESSFUL`).
  - Gate: Gradle unit tests + APK + `compileKotlinWasmJs` → `BUILD SUCCESSFUL`; APK resources found; deno
    `6 passed | 0 failed`; `database tests passed`; web build + smoke → passed.
- Follow-ups:
  - **Needs manual check** on a phone: cancel an upload mid-video, and confirm it stops and its files are gone
    in Storage.
  - If the phone is offline when cancelling, the Storage cleanup fails quietly (`// shortcut:` in
    `UploadQueue.cancel`), and those partial files stay. They're never shown, because no media row points at
    them.

## O1 Trip cache
- Changes:
  - `data/TripCache.kt` (new):
    - The `TripCacheStore` interface the task names (read, write, clear).
    - `TripCache`: kotlinx-serialization JSON for the trip list, the list's cover links, and a per-trip
      `TripSnapshot` (the trip with its members, media rows and thumbnail links). It saves after each load,
      and any missing or unreadable copy reads as nothing.
    - `loadOrCached`: on a network failure (`isOffline`: supabase-kt's `HttpRequestException`) it returns the
      saved copy marked offline. Any other failure, or no saved copy, is thrown as before, so a server refusal
      is never hidden behind stale data.
  - `androidApp/.../AndroidTripCacheStore.kt` (new): JSON files under app `files/offline/`, written with
    `AtomicFile` like the upload queue ("files on Android").
  - `webApp/.../LocalStorageTripCache.kt` (new): `localStorage` with a `postmarked:` prefix. Every access is
    wrapped, because storage can be full or blocked (private browsing), and then the web just has no offline
    copy ("localStorage on web").
  - `PostmarkedApp.kt`, `web/Main.kt`: set the store at start, and clear it on sign-out. The saved trips
    belong to that account, as the upload queue already does on Android. `NoTripCache` is the default (iOS
    and tests).
  - `ui/TripListScreen.kt` and `ui/TripScreen.kt`: loads go through `loadOrCached`, and each screen shows
    `OfflineBanner` ("Offline, showing what you last saw", SPEC's words) when it fell back. Offline, the trip
    page shows no songs instead of failing, and skips the place shapes and the palette backfill (both need the
    network).
  - `ui/App.kt`: the `OfflineBanner` composable.
  - `TripCacheTest.kt` (new), the task's check:
    - Save then load round-trips the list and a snapshot with members, cover, coordinates and thumbnail links.
    - Corrupt JSON and a wrong shape load as empty or null without throwing.
    - A failed network load returns the cached copy marked offline.
    - Offline with nothing saved still throws, and a non-network error isn't masked by a saved copy.
- Approach: route and day notes aren't cached yet. SPEC lists them, but day notes don't exist until J1, and the
  route comes from a server function plus the media. The task's check and title cover the trip list and
  per-trip JSON. I considered caching raw PostgREST responses and rejected it: typed snapshots keep the cache
  readable and let `ignoreUnknownKeys` survive model changes.
- Verification:
  - Task check `./gradlew :composeApp:testAndroidHostTest --tests '*TripCacheTest*'` → `tests="4" skipped="0"
    failures="0" errors="0"`.
  - All targets compile; the smoke test shows the web store is set up at start without errors.
  - Gate: Gradle unit tests + APK + `compileKotlinWasmJs` → `BUILD SUCCESSFUL`; APK resources found; deno
    `6 passed | 0 failed`; `database tests passed`; iOS compile → `BUILD SUCCESSFUL`; web build + smoke →
    passed.
- Follow-ups:
  - Cache the route (the map screen) and, after J1, the day notes.
  - **Needs manual check** on a phone: open a trip, turn on airplane mode, reopen the app. The list and trip
    should show with the banner, and thumbnails should come from Coil's path-keyed disk cache.
  - Offline, a session that can't be refreshed may sign the user out; that's the existing `// shortcut:` in
    `App.kt`.

## O2 Outbox for comments, reactions and day notes
- Changes:
  - `data/Outbox.kt` (new):
    - `OutboxEntry` (Comment, Reaction, DayNote), serializable with a type tag.
    - Pure `enqueue`: comments append. A reaction replaces any queued one for the same photo and emoji, so a
      duplicate becomes one, the last. A note replaces a queued note for the same day only if newer (last
      write wins by `updatedAtMs`).
    - Pure `sendInOrder`: an offline failure stops the run and keeps that entry and everything after it, so
      comments never arrive out of order. A permanent refusal is dropped so it can't block the queue forever.
    - The `Outbox` singleton persists to the same `TripCacheStore` as O1 (files on Android, `localStorage` on
      web), so it survives restarts, and is forgotten on sign-out.
    - Reactions are idempotent: removal is a delete, and adding treats Postgres 23505 on the
      `(media_id, user_id, emoji)` key as success.
    - Notes upsert `day_notes`, which J1 creates.

    SPEC §4 names all of these rules.
  - `ui/Viewer.kt`: comments and reactions go through the outbox. Queued comments show under the list as
    "You · Waiting to send" and count in "Comments (N)". Reaction chips show the queued state and an adjusted
    count. Offline after queueing, the reload's network error isn't shown, since the write is visibly waiting.
  - `ui/App.kt`, `ui/TripScreen.kt`: send triggers. SPEC says sending happens "on the next app start or
    successful request", so the outbox flushes once signed in and after each successful media load.
  - `androidApp/.../OutboxWorker.kt` (new) + `PostmarkedApp.kt`: a WorkManager job with a CONNECTED
    constraint, enqueued (KEEP) on every add, so a write made in airplane mode is sent when the network returns
    even with the app closed. `Outbox.forget()` runs on sign-out.
  - `web/Main.kt`: forgets the outbox on sign-out.
  - `OutboxTest.kt` (new), the task's check:
    - Order is kept.
    - A failed (offline) send stays queued with everything after it, and a permanent refusal is dropped.
    - A duplicate reaction becomes one, and on then off leaves only "off".
    - A newer note beats an older one, a late stale edit doesn't replace it, and other days are separate.
- Approach: pure queue rules plus a thin sender, so the rules SPEC lists are testable without a server.
  I considered a database-backed queue and rejected it: SPEC rules out Room and SQLDelight. A JSON list in
  the existing store is enough for a handful of writes.
- Verification:
  - Task check `./gradlew :composeApp:testAndroidHostTest --tests '*OutboxTest*'` → `tests="4" skipped="0"
    failures="0" errors="0"`.
  - All targets compile (Android APK, web, iOS).
  - Gate: Gradle unit tests + APK + `compileKotlinWasmJs` → `BUILD SUCCESSFUL`; APK resources found; deno
    `6 passed | 0 failed`; `database tests passed`; web build + smoke → passed.
- Follow-ups:
  - Day notes are queued and sent by this outbox, but nothing writes them until J2. Last write wins is enforced
    here (client side). J1's migration has to make the server keep the newest `updated_at` too.
  - **Needs manual check** on a phone: react and comment in airplane mode, kill the app, turn the network
    back on, and both should appear for others.
  - A comment whose response was lost after the server saved it can be sent twice: comment ids are
    server-generated.

## O3 Offline imports
- Changes:
  - `data/Trips.kt`: `Trips.bounds()` goes through `loadOrCached` and saves the trip's dates and region shapes
    under `bounds-<trip>`. This is the fix the task asks for ("fix whatever doesn't"). `ImportViewModel.load()`
    called `Trips.bounds()` on the server before it could sort or queue anything, so in airplane mode the
    import screen stopped at "Can't reach the server", and the photos never reached the queue that would have
    waited for the network.
  - `ui/TripScreen.kt`: the trip page saves the bounds whenever it loads online, so they're already there
    before the user goes offline.
  - `data/TripCache.kt`: public `put` and `get` for typed copies other than the trip list and snapshots.
  - `TripCacheTest.kt`: a new case, a keyed copy round-trips and an unknown key reads as null. The 4 existing
    cases are untouched.
- Approach: I confirmed the rest of the path by reading it rather than changing it.
  - The queue is a JSON `AtomicFile` loaded in `Application.onCreate` (`UploadQueue.init`), before any worker
    runs, so it survives a reboot.
  - Uploads are WorkManager work with a network constraint (UNMETERED, or CONNECTED with "Use mobile data").
    That's the "wait for the network" part.
  - WorkManager reschedules after a reboot. The merged debug manifest contains `RECEIVE_BOOT_COMPLETED`,
    `ACCESS_NETWORK_STATE`, `SystemJobService`, and `RescheduleReceiver` on `BOOT_COMPLETED`.
  - The worker loads the saved sign-in (`awaitInitialization`) and retries while it can't refresh.
  - The camera-roll scan itself is local.

  I considered adding WorkManager's test library to simulate a reboot and rejected it: a new dependency only
  for a check the task says to do on the phone.
- Verification:
  - Task check `./gradlew :androidApp:assembleDebug` → `BUILD SUCCESSFUL`.
  - `TripCacheTest` → `tests="5" … failures="0"`.
  - Merged manifest grep as above.
  - Gate: Gradle unit tests + APK + `compileKotlinWasmJs` → `BUILD SUCCESSFUL`; APK resources found; deno
    `6 passed | 0 failed`; `database tests passed`; iOS compile → `BUILD SUCCESSFUL`; web build + smoke →
    passed.
- Follow-ups: **needs manual check (the task's own):** open the trip once online, then:
  1. Turn on airplane mode and queue 3 photos.
  2. Reboot.
  3. Turn on Wi-Fi.

  All 3 should appear in the gallery. A trip never opened online on this phone still can't be imported into
  offline, because its area is unknown.

## L1 Live candidates
- Changes:
  - `core/domain/.../trip/LiveCandidates.kt` (new): `LiveItem(uri, addedMs, takenOn, location)` and
    `liveCandidates(items, sinceMs, queuedUris, start, end, area)`. It keeps items added after the last run
    that the existing `matchTrip` marks `Both` (in the dates and the area), and skips those already queued and
    those with no location. The task asks for a pure function over "new MediaStore items since the last run"
    that "the trip matcher marks as taken on this trip". It lives in `core/domain` with the matcher, so L2's
    worker only maps MediaStore rows to `LiveItem`s.
  - `LiveCandidatesTest.kt` (new), the task's check:
    - Keeps a new in-trip item.
    - Skips items before or after the dates, and elsewhere.
    - Skips already-queued items, and items with no location.
    - Only looks at items added after the last run (the `>` boundary).
- Approach: "Taken on this trip" uses the same rule as the import screen's pre-ticked section (`TripMatch.Both`)
  so live mode never adds something the import screen wouldn't have ticked. I considered including date-only
  matches (no GPS) and rejected it: the task's check says to skip them, and adding photos automatically
  should err on the side of leaving things out.
- Verification:
  - Task check `./gradlew :core:domain:jvmTest --tests '*LiveCandidatesTest*'` → `tests="5" skipped="0"
    failures="0" errors="0"`. Also passes on `wasmJsNodeTest`.
  - Gate: Gradle unit tests + APK + `compileKotlinWasmJs` → `BUILD SUCCESSFUL`; APK resources found; deno
    `6 passed | 0 failed`; `database tests passed`; web build + smoke → passed.
- Follow-ups: none for L1. The worker, the switch and the notification are L2.

## L2 Live mode worker and switch
- Changes:
  - `core/domain/.../trip/LiveCandidates.kt`: `liveModeOn(choice, canEdit, today, start, end)`, SPEC §5's
    default ("on by default for editors while today is within the trip's dates"), with the person's own choice
    taking precedence. `LiveCandidatesTest` gets a case for it covering editor during the trip, viewer, after
    the trip, off by hand and on by hand.
  - `data/Media.kt`: a `LiveMode` interface (choices, watch, set) and `CameraRoll.live`, null by default. The
    web and iOS have no live mode (SPEC: "No live mode … on web"), so the switch simply doesn't appear there.
  - `data/Backend.kt` + `ui/App.kt`: `pendingTrip`, like `pendingInvite`. The signed-in app navigates to that
    trip when it's set, which is how the notification "opens the queue": the queue list (F9) is on the trip page.
  - `ui/TripScreen.kt`: `LiveSwitch` ("Live: add new photos automatically", the whole row toggles), shown to
    owners and editors where live mode exists. It also registers the trip with the background job.
  - `androidApp/.../LiveMode.kt` (new):
    - `LiveTrips`: watched trips in SharedPreferences with name, dates, role, choice and a "since" mark.
      When a trip is first seen or switched on, the mark is now, so the whole camera roll is never imported.
    - `AndroidLiveMode`.
    - `LiveWork.schedule`: a WorkManager job with content-URI triggers on the Images and Video collections,
      a 5 s update delay and a 30 s max delay ("within a minute"). It's re-armed after each run, because a
      content trigger fires only once. It isn't scheduled when no trip is live.
    - `LiveWorker`: for each live trip, it queries MediaStore for items added since the mark, reads their
      time and place with the existing `readDeviceMedia`, and asks L1's `liveCandidates` which are on the
      trip. Those are queued with the existing `UploadQueue.add`, which respects the Wi-Fi-only setting.
      It posts "Added N photos to <trip> · Review" and moves the mark forward.
    - The bounds come from `Trips.bounds`, which is cached (O3), so matching works without signal.
  - `AndroidCameraRoll.kt`: `live = AndroidLiveMode(...)`.
  - `MainActivity.kt`: reads the notification's `open_trip` extra into `pendingTrip`.
  - `PostmarkedApp.kt`: arms the job at start, so it continues after a restart, and clears live trips on
    sign-out.
- Approach:
  - Live mode is opt-out via the switch, and only `TripMatch.Both` items are added (L1). I considered a
    periodic job (every 15 min) instead of content triggers and rejected it: it misses the "within a minute"
    check and wakes the phone for nothing.
  - The "since" mark uses MediaStore's DATE_ADDED, not DATE_TAKEN. A photo shared into the camera roll later,
    with an old capture date, is then still "new" for the mark, and the date filter in `liveCandidates`
    decides.
- Verification:
  - Task check `./gradlew :androidApp:assembleDebug` → `BUILD SUCCESSFUL`.
  - `LiveCandidatesTest` → `tests="6" … failures="0"`.
  - All targets compile; the web and iOS have no switch (`live == null`).
  - Gate: Gradle unit tests + APK + `compileKotlinWasmJs` → `BUILD SUCCESSFUL`; APK resources found; deno
    `6 passed | 0 failed`; `database tests passed`; web build + smoke → passed.
- Follow-ups:
  - **Needs manual check (the task's own):** on the phone, with live on, take a photo inside the trip's area
    and dates. It should be in the queue within a minute and show the notification, and tapping "Review"
    should open the trip page.
  - Photo access must already have been granted (through the import screen). Live mode doesn't ask by itself.
  - Content-URI triggers are best effort under Doze, so on a phone left idle a while, new photos may wait until
    it wakes.

## L3 "N new from friends"
- Changes:
  - `data/NewSince.kt` (new):
    - `newFromFriends(media, lastOpenedMs, me)` counts items whose `created_at` (upload time, not capture
      time) is after the last open and whose contributor isn't you. A trip never opened on this device counts
      none, because there's no baseline.
    - `LastOpened`, per-trip times in the device store from O1 ("stored on the device", forgotten on
      sign-out).
  - `data/Media.kt`: `TripMedia.createdAt` (selected with the media rows, so offline snapshots have it too), and
    `newFromFriendsCount(trip, since, me)` for the trip list. It fetches only the ids of the new rows, so the
    list doesn't download every trip's media.
  - `ui/TripScreen.kt`: the view model reads the previous open time once, then marks the trip opened now. The
    page shows "N new from friends since you last looked", and the next visit starts from zero, which is the
    task's "resets on opening the trip".
  - `ui/TripListScreen.kt`: "N new" as each card's trailing text, online only.
  - `NewSinceTest.kt` (new), the task's check:
    - Your own uploads don't count (2 for "me", 3 for someone else).
    - Opening the trip resets the count to 0. Postgres's `+00:00` microsecond timestamps parse.
    - A never-opened trip counts nothing.
- Approach: device-local times, as SPEC says. I considered a `last_seen` table on the server and rejected
  it: SPEC says the time is stored on the device, and the server would need another migration and RLS for no
  gain this round.
- Verification:
  - Task check `./gradlew :composeApp:testAndroidHostTest --tests '*NewSinceTest*'` → `tests="3" skipped="0"
    failures="0" errors="0"`.
  - All targets compile.
  - Gate: Gradle unit tests + APK + `compileKotlinWasmJs` → `BUILD SUCCESSFUL`; APK resources found; deno
    `6 passed | 0 failed`; `database tests passed`; web build + smoke → passed.
- Follow-ups:
  - The trip list makes one small request per opened trip. That's fine for a handful of trips; a single
    grouped database function would scale better.
  - **Needs manual check:** with two accounts, add a photo from one and see "1 new" on the other's trip list.

## J1 day_notes table
- Changes:
  - `supabase/migrations/20261130000000_day_notes.sql` (new): `day_notes(trip_id, day, body, updated_by,
    updated_at)` with primary key `(trip_id, day)`, as SPEC §6 defines it. RLS: members read; owner and editors
    insert, update and delete ("members read, editors write"). Column grants match the app's upsert:
    `insert (trip_id, day, body, updated_at)`, `update (body, updated_at)`. A `before insert or update` trigger
    (security definer, execute revoked from clients):
    - refuses a `day` outside the trip's dates (both ends inclusive), which the task requires and a check
      constraint can't express because the dates live on `trips`;
    - skips an update whose `updated_at` is older than the saved one. That's O2's "last write wins by
      `updated_at`" on the server, so a stale offline edit arriving late can't overwrite a newer note;
    - always sets `updated_by` to the caller.
  - `supabase/tests/day_notes_test.sql` (new), the task's check:
    - An editor writes and is recorded as the writer, and a viewer reads it.
    - A viewer can't add, edit or delete; a stranger sees nothing and can't add.
    - The last day is accepted; days before and after the trip are refused.
    - A newer upsert replaces; an older one is skipped.
    - An editor deletes, and notes go with the trip.
- Approach: the trigger returns `NULL` for stale updates. With `insert … on conflict do update` (PostgREST's
  upsert), that silently keeps the newer row instead of raising an error. That matters because the outbox
  (O2) would otherwise retry a stale edit forever. I considered `WHERE excluded.updated_at > day_notes.updated_at`
  on the conflict clause and rejected it: PostgREST can't send that, so the rule must live in the database.
- Verification:
  - Task check `./supabase/tests/run.sh` → `== tests/day_notes_test.sql` … `database tests passed`.
  - Mutation checks: without the stale-edit rule the run fails with `ERROR: the older edit does not win`, and
    without the date rule with `ERROR: a day before the trip is refused`. Restored, it passes.
  - Gate: Gradle unit tests + APK + `compileKotlinWasmJs` → `BUILD SUCCESSFUL`; APK resources found; deno
    `6 passed | 0 failed`; web build + smoke → passed.
- Follow-ups: **needs manual step (owner):** run the migration in the Supabase SQL editor. If a trip's dates
  are later shortened, notes on days now outside them stay until edited; the trigger checks only on writes.

## J2 Notes in the By day view
- Changes:
  - `data/DayNotes.kt` (new): `DayNote` (day, body, updated_at), `DayNotes.ofTrip`, and pure
    `notesToShow(saved, outbox, trip)`. It lays queued outbox edits over the saved notes (a blank edit hides
    the note), so an offline edit shows at once, which is "going through the outbox when offline".
  - `ui/Gallery.kt`: `Section` gains `day` and `note`. `gallerySections(..., notes)` attaches each day's note to
    its By-day group. That's the task's check ("a by-day group carries its note").
  - `ui/GalleryFilters.kt` `gallery()`: By-day headers show the note, "Waiting to send" while an edit is
    queued, and "Add a note" or "Edit note" for owners and editors (SPEC: "editors can edit in place").
  - `ui/TripScreen.kt`: the view model loads notes with the media (offline from the snapshot), and `saveNote`
    queues an `OutboxEntry.DayNote` with the current time. There's an edit dialog, and the outbox list is
    collected and passed to the gallery so waiting notes appear and update.
  - `data/TripCache.kt`: `TripSnapshot.notes`, the O1 follow-up "and day notes".
  - `data/Outbox.kt`: a blank note is sent as a delete rather than an empty row.
  - `GalleryTest.kt`: two new cases.
    - A By-day group carries its note and its day, and the All view has none.
    - Queued edits show over saved ones: the replaced text, a cleared day, and another trip's edit ignored.
- Approach: notes travel with the gallery's sections rather than as a separate UI lookup, so the pure
  `gallerySections` (already tested) decides where they appear. I considered a separate list of notes above
  the gallery and rejected it: SPEC puts them "at the top of each day".
- Verification:
  - Task check `./gradlew :composeApp:testAndroidHostTest --tests '*GalleryTest*'` → `tests="10" skipped="0"
    failures="0" errors="0"` (8 earlier + 2 new).
  - All targets compile.
  - Gate (rerun after the blank-note change): Gradle unit tests + APK + `compileKotlinWasmJs` →
    `BUILD SUCCESSFUL`; APK resources found; deno `6 passed | 0 failed`; `database tests passed`; web build +
    smoke → passed.
- Follow-ups:
  - **Needs manual check:** write a note in airplane mode, see "Waiting to send", go online, and the note
    appears for others.
  - Deleting a note doesn't use last-write-wins: a delete always removes, even if a newer edit happened
    elsewhere.

## J3 Notes in summaries and captions
- Changes:
  - `core/domain/.../summary/Caption.kt`:
    - `caption(..., note = null)` quotes a day note under the stats line. It's a new optional last
      parameter, so the existing call and the existing `aFullCaption` test are unchanged.
    - `shortQuote(text, max)` wraps the note in curly quotes and cuts it at a word with "…" so it fits:
      150 characters in captions, 80 in tiles.
    - `noteToQuote(days, notes)` picks the single note among the days the summary's photos come from, and
      none when there are several or none.

    That's the task's "the caption generator can quote it".
  - `CaptionTest.kt`: three new cases.
    - A caption with a note has the quoted line in place (and only there).
    - Long notes are cut at a word within the limit, and short ones are trimmed.
    - The note rule: one day with a note, a day without one ignored, two notes give none, no note gives
      none.
  - `ui/SummaryScreens.kt`: `SummaryMaterial.notes` is loaded with the material. Offline there are no notes,
    and edits still waiting in the outbox count (`notesToShow`). The tile sheet gets a "Day notes" row of chips
    ("Day 2" …) that put the shortened note into the text tile; that's the "Day note" text tile. The 80-character
    tile limit became the named `TILE_TEXT_MAX`, shared with the free-text field.
  - `ui/Export.kt`: the generated caption passes `noteToQuote(days of the summary's photos, notes)`.
- Approach: a day note becomes an ordinary text tile (its text, shortened), rather than a new tile type
  linked to the note. That needs no change to saved summaries or the drawing code, and the tile keeps
  showing even if the note is edited later. I considered a live link to the note and rejected it: a summary
  is a finished picture people post, and it shouldn't change under them.
- Verification:
  - Task check `./gradlew :core:domain:jvmTest --tests '*CaptionTest*'` → `tests="8" skipped="0" failures="0"
    errors="0"` (5 earlier + 3 new).
  - All targets compile.
  - Gate: Gradle unit tests + APK + `compileKotlinWasmJs` → `BUILD SUCCESSFUL`; APK resources found; deno
    `6 passed | 0 failed`; `database tests passed`; web build + smoke → passed.
- Follow-ups: **needs manual check:** in the summary editor, a text tile's "Day notes" chips, and the export
  caption for a one-day summary showing the quote.

## S1 Five new filters
- Changes:
  - `core/domain/.../color/Looks.kt`: `Filter.Mono`, `Moody`, `Vivid`, `Desert` and `NightDrive` ("Night Drive"),
    each a chain of the existing CSS-style steps (`saturation`, `contrast`, `brightness`, `sepia`, and the
    channel `diagonal` that the Warmth slider already uses). SPEC §8.1 names these five. They're added after
    the existing seven, and saved looks store filters by name (`LookData`), so existing summaries are
    unaffected and the picker (`Filter.entries`) shows them automatically.
    - Mono: greyscale, slightly matte.
    - Moody: less colour and light, a bit more contrast.
    - Vivid: saturation ×1.45 with a touch of contrast.
    - Desert: sepia plus reds up and blues down.
    - Night Drive: blue up, red down, darker.
  - `LooksTest.kt`: five new cases, the task's check, each over five photo colours (plus mid-grey where it
    applies).
    - Mono gives $R = G = B$ exactly.
    - Vivid raises OKLab chroma.
    - Moody lowers OKLab lightness.
    - Desert raises red relative to blue and moves OKLab $b$ toward yellow.
    - Night Drive raises blue relative to red and lowers lightness.

    The seven existing tests are untouched.
- Approach: the first version of the Night Drive test compared the blue−red difference, and it failed for
  sky blue (217−74 → 189−54). The filter was fine. Darkening scales all channels, which shrinks the gap even
  as the colour cools. I changed my new test to the ratio $\frac{b+1}{r+1}$, which measures cooling
  independent of brightness: 2.9 → 3.5 for that sky. Filters as `Grade` chains keep them GPU-applied and
  non-destructive like the others. A lookup-table approach was considered and rejected: it needs new
  drawing code.
- Verification:
  - Task check `./gradlew :core:domain:jvmTest --tests '*LooksTest*'` → `tests="12" skipped="0" failures="0"
    errors="0"`.
  - Gate: Gradle unit tests + APK + `compileKotlinWasmJs` → `BUILD SUCCESSFUL`; APK resources found; deno
    `6 passed | 0 failed`; `database tests passed`; web build + smoke → passed.
- Follow-ups: **needs manual check:** how the five look on real photos in the per-photo editor.

## S2 New layouts: 2-up, 4-up, panorama split
- Changes:
  - `core/domain/.../summary/Layouts.kt`:
    - `Layout.slides`, defaulting to 1.
    - `TwoUp`: two photos stacked on 4:5.
    - `FourUp`: a 2 × 2 grid on a square.
    - `Panorama2` and `Panorama3`: one full-canvas slot on a canvas exactly 2 or 3 slides of 4:5 wide.

    SPEC §8.2 asks for these, with the panorama cut "across 2–3 carousel slides". Saved summaries store the
    layout by name, so existing ones are unaffected, and the layout picker lists `Layout.entries`.
  - `core/domain/.../summary/ExportGeometry.kt`: pure `panoramaSlices(slides, width)`, equal strips where each
    one starts exactly where the last ended.
  - `ui/Export.kt`: for panorama layouts, Carousel draws the whole summary once at `slides ×` slide width, then
    cuts that one image into the slides, so the seams match by construction. The text explains this, and
    Carousel is the default for panoramas. Other layouts export as before.
  - `LayoutsTest.kt`: two new cases. The new layouts have 2, 4 and 1 photos and 2 or 3 slides. Panorama slices
    start at 0, end at the full width, meet edge to edge, are each exactly one 1080 px slide, and the canvas is
    exactly `slides × 4/5` so no slide gets a sliver of border. The existing "every slot is on the canvas" and
    "straight layouts never overlap" tests run over `Layout.entries`, so they now cover the new layouts too.
- Approach: the panorama is a layout like any other (one wide slot) plus a cut at export time. I considered
  modelling slides as separate layouts with matched crops and rejected it: separate crops can drift apart,
  while slicing one rendered image can't.
- Verification:
  - Task check `./gradlew :core:domain:jvmTest --tests '*LayoutsTest*'` → `tests="7" skipped="0" failures="0"
    errors="0"`.
  - All targets compile.
  - Gate: Gradle unit tests + APK + `compileKotlinWasmJs` → `BUILD SUCCESSFUL`; APK resources found; deno
    `6 passed | 0 failed`; `database tests passed`; web build + smoke → passed.
- Follow-ups:
  - **Needs manual check:** post a panorama carousel and swipe it on Instagram.
  - With the Tall or Square export size, a 4:5-slide panorama gets paper above and below; the slices still
    line up.
  - The generated panorama fills its one slot with the first matching photo. Picking the widest photo needs
    photo aspect ratios, which come with S3/S4.

## S3 Justified rows and reflow by photo ratio
- Changes:
  - `core/domain/.../summary/Justify.kt` (new): pure `justify(aspects, width, gap, targetHeight)`, the task's
    function and SPEC §8.4's formula $h = \frac{W - g(n-1)}{\sum r_i}$. Rows break greedily where $h$ comes
    closest to the target height, and a photo that alone would be no taller than the target (very wide) gets
    a row to itself.
  - `core/domain/.../summary/Layouts.kt`:
    - `Layout.adaptive`, set on Filmstrip, Polaroids and Magazine (SPEC's adaptive list) and on the new
      `JustifiedRows` (6 photos, target row 0.36 of the width).
    - `Arrangement(aspect, slots)` and `Layout.arrange(aspects)`. Strict layouts (3 × 3, 2 × 3, 2-up, 4-up,
      panoramas) are unchanged, so the profile grid still lines up. Justified rows reflow completely,
      including the canvas height. The other adaptive layouts fit each photo's own shape inside its slot,
      centred. An unknown shape (still loading, or a text tile) leaves the slot as designed.
    - `JustifiedRows`' static slots are its arrangement for six 4:3 photos, so the existing layout tests check
      it too.
  - `ui/SummaryCanvas.kt`:
    - The editor canvas draws from `layout.arrange(...)`, including its shape, the slot positions and
      tap/drag hit-testing.
    - `rememberPictureAspects` measures each tile's picture through Coil with the same request size the tile
      draws at, so it's a cache hit, not a second download. The layout reflows as the shapes arrive.
  - `ui/Export.kt`: `whole()` arranges with the exact shapes of the downloaded pictures, so exports match the
    editor.
  - `JustifyTest.kt` (new), the task's check:
    - Every row exactly fills $W$ (to $10^{-9}$), every photo appears once in order, and non-final rows are
      near the target.
    - A portrait stays portrait ($w/h = 0.66$ exactly).
    - A 6:1 photo gets `JustifiedRow(3, 4, W/6)` to itself.
    - Plus: the `JustifiedRows` layout reflows (portraits make a taller canvas, each slot keeps its photo's
      shape, slots stay on the canvas), Filmstrip fits a portrait, and the 3 × 3 grid ignores shapes.
- Approach: the photo shapes come from the pictures already being loaded, not from stored dimensions.
  Media rows don't record width or height, and adding them would need a migration plus a backfill of every
  existing photo. I considered a separate justified-rows renderer and rejected it: a layout that returns
  slots fits the existing canvas, export and drag-to-swap code unchanged.
- Verification:
  - Task check `./gradlew :core:domain:jvmTest --tests '*JustifyTest*'` → `tests="5" skipped="0" failures="0"
    errors="0"`.
  - `LayoutsTest` → 7/7, including JustifiedRows on the canvas without overlap.
  - Domain tests pass on wasm. All targets compile.
  - Gate: Gradle unit tests + APK + `compileKotlinWasmJs` → `BUILD SUCCESSFUL`; APK resources found; deno
    `6 passed | 0 failed`; `database tests passed`; web build + smoke → passed.
- Follow-ups:
  - **Needs manual check:** the editor reflowing as pictures load. There may be a brief jump from 4:3
    placeholders to real shapes.
  - Magazine and Polaroid fit photos inside their slots, which can leave paper around a photo whose shape is
    far from the slot's. A deeper reflow of those two is possible later.
  - S4's crop ratio picker builds on `arrange` (a chosen ratio replaces the photo's own).

## Change: stop uploading photo originals (owner's request, after this round's tasks)
- Changes:
  - `androidApp/.../UploadWorker.kt` and `webApp/.../WebCameraRoll.kt`: photos upload only `display.jpg` (2048 px)
    and `thumb.jpg`. The original was most of each photo's 3–6 MB, and the free plan's 1 GB is shared by every
    trip. Originals stay on the phone that took them.
  - `data/Media.kt`: Save/Share (F6) and the info panel's file size (F5) use the display copy. `MediaKind.files`
    keeps `original`, so deleting a photo uploaded earlier still removes its original.
  - `README.md`: the "What is stored" table and a note on why.
- Verification: Android, web and iOS compile; the gate passes (unit tests, APK, deno, database, web smoke).
- Follow-ups: originals already uploaded stay in Storage until their photos are deleted. Date, time zone and GPS
  are unaffected: they are read at upload and kept in the database row.

## Found
- `composeApp/.../ui/SummaryCanvas.kt:168`: compiler warning "Unnecessary safe call on a non-null receiver of type
  `(Set<Int>) -> Unit`". It predates this round and is harmless; I left it alone (unrelated to P6).

## S4 Crop ratio picker
- Changes:
  - `data/Summaries.kt`: `Crop` gains `aspect` (width / height; null = Original) and `free`. `tileAspect(tile,
    photoAspect)` gives the shape a tile's photo is laid out at: the chosen shape, else the photo's own. Old saved
    crops have neither field and load as Original.
  - `ui/SummaryCanvas.kt` and `ui/Export.kt`: adaptive layouts are arranged with `tileAspect` instead of the
    photo's shape, so choosing 4:5 reflows the layout and the slot, and therefore the exported crop, is 4:5.
  - `ui/PhotoLooks.kt`: the Crop tab gets Original, 1:1, 4:5, 3:4, 16:9 and Free (a slider from 1:2 to 2:1,
    on a log scale so both ends are as far from square). The preview frame takes the chosen shape. In strict
    grids (3 × 3, 2 × 3, profile grid) there is no picker, only "Shape 1:1: this layout's tiles have fixed
    shapes", and `arrange` ignores any saved shape there.
  - `ui/SummaryScreens.kt`: passes `adaptive` to the editor.
- Approach: the ratio lives on `Crop` because it is saved with the tile and travels with it when tiles are swapped.
  Saved looks (`LookData`) are colour only and stay that way. The task's "saved looks keep the ratio" means the
  saved summary keeps it, which `SavedLooksTest` checks. Strict layouts needed no new code: `arrange` already
  ignores shapes for them.
- Verification:
  - Check `SavedLooksTest` → `tests="4" failures="0"` (new case: a shape survives saving; a crop saved before S4
    loads as Original; `tileAspect` picks the chosen shape over the photo's).
  - Check `ExportGeometryTest` → `tests="8" failures="0"` (new case: in every adaptive layout a photo cropped to
    4:5 gets a 4:5 slot and a 4:5 export crop; the 3 × 3 grid stays square).
  - `npm test` → exit 0.
- Follow-ups: **needs manual check** on the phone that dragging the Free slider reflows smoothly.

## S5 Style presets
- Changes:
  - `composeResources/files/presets/`: six presets and `index.json`, in the album-themes pattern. They are
    Scrapbook, Minimal White, Film Strip, Postcard, Night Drive and Sun-faded. Each names a layout, an automatic
    style (`SummaryMode`), a filter with its strength, and a Match (harmonize) amount.
  - `data/StylePresets.kt`: `StylePreset` and the loader, read once like `AlbumThemes`.
  - `ui/SummaryScreens.kt`: a Presets row at the top of the editor's Style tab. One tap applies the preset:
    layout and style (the tiles refill for them), then its filter and Match amount. `ui/Guided.kt`: the
    first-summary walkthrough doesn't show the row, to keep its steps simple.
  - `.github/workflows/ci.yml`: the APK resource check also greps for `files/presets/index.json`.
- Approach: SPEC §8.3 also lists paper/border and text style per preset. Summaries don't have those settings:
  paper, border, edge and text colours come from the trip's album theme (M6). Presets therefore set what a
  summary does have, and adding per-summary paper settings was left out. A preset is applied, not remembered,
  so changing anything afterwards just edits the summary.
- Verification:
  - Check `PresetsTest` → `tests="2" failures="0"`: every file parses, every file is listed in `index.json` and
    every listed id has a file, ids are unique, there are 5–8 presets, and each names an existing layout, an
    automatic style and a filter, with strength and Match in $[0, 1]$. An Original preset adds no look.
  - The APK contains `assets/composeResources/io.github.lbondoc.postmarked.resources/files/presets/index.json`.
  - `npm test` → exit 0.
- Follow-ups: **needs manual check** of how each preset looks on a real trip. The values are first guesses.

## S6 Carousel presets
- Changes:
  - `core/domain/.../summary/CarouselPlan.kt` (new): `carouselPlan(photos, perSlide, hasRoute)` returns a cover,
    the photo slides, a route slide when there is a route, and a closing slide, never more than `MAX_SLIDES` =
    20. Photos go in time order. A new slide starts at each day when that fits; otherwise days share slides;
    if even that is too many, photos are picked evenly across the trip.
  - `ui/Export.kt`: a **Trip carousel** export kind. The cover is the earliest photo above the trip's name and
    dates. Each photo gets its own slide, drawn with the summary's look, crop and harmonizing. The route slide
    is the road line and stops on paper, coloured by day, under "N MILES", with no map tiles; it uses the
    same projection as the web `RouteDrawing`. The closing slide is the caption's first paragraph, without
    hashtags. All slides use the trip's album theme paper and ink. Posting works like the existing carousel.
  - `ui/SummaryScreens.kt`: the editor keeps the built `TripRoute` (it already built it for the miles) and
    passes it to the export sheet.
- Approach: one photo per slide (`perSlide = 1`), as people mostly post carousels that way; the planner takes any
  number per slide. The photos are the summary's, so what the person picked and cropped is what gets posted.
- Verification:
  - Check `CarouselPlanTest` → `tests="5" failures="0"`. It checks that photos stay in time order even when given
    out of order, and that day boundaries split slides when they fit. Days share slides when splitting would pass
    the limit. A sweep over 0–500 photos, with and without a route, never gives more than 20 slides, and every
    photo is shown once in order when they fit. With no route there is no route slide.
  - `npm test` → exit 0.
- Follow-ups: **needs manual check** of the exported slides on a phone, especially text size on the cover and
  closing slides at 3:4.

## W1 trip_shares table
- Changes:
  - `supabase/migrations/20261201000000_trip_shares.sql` (new): `trip_shares(token, trip_id, created_by,
    created_at, revoked_at)`. The token is 128 random bits from `gen_random_bytes(16)` in base64url, 22
    characters with no padding, and a check constraint holds it to that shape. A partial unique index allows at
    most one live link per trip.
  - RLS: only the person who made a link can read it (`created_by = auth.uid()`). There are no insert or update
    grants. Links are made and revoked through `create_share(trip)` (owner and editors; it revokes any live link
    first, so a new link always gets a new token) and `revoke_share(trip)` (any owner or editor, whoever made the
    link). `share_is_on(trip)` tells members whether sharing is on without showing the token. All three refuse
    signed-out callers.
  - `create extension if not exists pgcrypto with schema extensions`: Supabase has it already, and the local
    test database needs it.
- Approach: functions rather than direct writes, because a direct `update ... set revoked_at` would need
  editors to read the row, and only the creator may read tokens. With the functions, any editor can still turn a
  link off.
- Verification:
  - Check `./supabase/tests/run.sh` → `database tests passed`. The new `shares_test.sql` checks the token's shape.
    It checks that the creator reads their link and the owner, viewers and strangers read nothing. Members see
    `share_is_on` and strangers don't. Signed-out callers get no access. Viewers can't create or revoke, and nobody
    inserts or updates directly. A new link revokes the old token, leaving one live link. Any editor can revoke,
    and deleting the trip deletes its links.
  - `npm test` → exit 0.

## W2 `share` Edge Function
- Changes:
  - `supabase/functions/share/index.ts` (new): `POST {"token"}` with no sign-in. A malformed, unknown or revoked
    token gets 404 before anything else is read or signed. For a live token it returns the trip's name, dates,
    start and end labels and theme, its day notes, and its media rows with only the taker's display name (no
    contributor id, no emails). Each item comes with signed links, valid 1 hour, to `thumb.jpg` plus `display.jpg`
    (photos) or `poster.jpg` (videos), all from one batch signing call. It never signs `original` or `video.mp4`.
    It answers the browser's CORS preflight, since the website is on another origin.
  - It reads with `SUPABASE_SERVICE_ROLE_KEY`, which Supabase gives every Edge Function, through the REST and
    Storage HTTP APIs. Like `route`, it takes its dependencies as `Deps`, so tests run offline.
- Verification:
  - Check `deno test supabase/functions/` → `11 passed | 0 failed`. The new `share/index_test.ts` has 5 tests: a
    live token gets the trip, notes and media with signed links, and a revoked, unknown or malformed token gets
    404 with nothing signed. The response contains no `original` and no `@`, and only thumb, display and poster
    are signed. One signing call asks for `expiresIn: 3600`. Only POST is answered, and the preflight gets CORS
    headers.
  - `npm test` → exit 0.
- Follow-ups: **needs deploying** with `supabase functions deploy share` and the migration applied. Nothing is
  deployed from this sandbox.
