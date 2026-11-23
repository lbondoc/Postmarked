plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig { outputFileName = "postmarked.js" }
        }
        binaries.executable()
    }

    sourceSets {
        // This module is the browser glue, so its JavaScript interop is the point, not an accident.
        all { languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop") }
        wasmJsMain.dependencies {
            implementation(projects.composeApp)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
        }
    }
}
