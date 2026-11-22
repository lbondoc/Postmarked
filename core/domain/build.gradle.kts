plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    // JVM only for now: Android consumes the JVM build, and tests run in seconds.
    // The iOS targets get added here when there is a Mac to build them on.
    jvm()

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
