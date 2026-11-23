plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    // Android consumes the JVM build, and its tests run in seconds. The web and iOS apps share the same logic.
    jvm()
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { nodejs() }
    // Built and tested on CI's macOS runner (SPEC §1.3); Linux machines skip them with a warning.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // `api` because GeoJSON shapes (JsonObject) appear in this module's public types.
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime) // LocalDate in the trip matcher
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
