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

## Found
- `composeApp/.../ui/SummaryCanvas.kt:168`: compiler warning "Unnecessary safe call on a non-null receiver of type
  `(Set<Int>) -> Unit`". It predates this round and is harmless; I left it alone (unrelated to P6).
