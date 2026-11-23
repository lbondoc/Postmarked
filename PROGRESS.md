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

## Found
- (nothing yet)
