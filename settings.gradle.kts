pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Postmarked"
// Lets build scripts write `projects.core.domain` instead of the string ":core:domain".
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":core:domain") // pure Kotlin logic: geo math now; route, matching and palette later
include(":composeApp")  // shared Compose Multiplatform UI
include(":androidApp")  // Android entry point: Activity, manifest, platform APIs
