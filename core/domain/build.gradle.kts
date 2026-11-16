plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    // JVM only for now: Android consumes the JVM build, and tests run in seconds.
    // The iOS targets get added here when there is a Mac to build them on.
    jvm()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
