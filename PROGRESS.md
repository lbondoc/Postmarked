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

## Found
- (nothing yet)
