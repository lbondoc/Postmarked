plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    android {
        namespace = "io.github.lbondoc.postmarked.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {} // runs commonTest on the computer, no phone needed
    }

    sourceSets {
        commonMain.dependencies {
            // `api` re-exports these to the app modules: they use the domain types,
            // and the Android uploader talks to Supabase Storage directly.
            api(projects.core.domain)
            api(libs.supabase.auth)
            api(libs.supabase.postgrest)
            api(libs.supabase.storage)
            implementation(libs.supabase.functions)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.components.resources) // decodes downloaded JPEGs into pixels for palettes
            implementation(libs.navigation.compose)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.qrose)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
            implementation(libs.maplibre.compose)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            // The HTTP engine is per platform; iOS will get its own (Darwin) later.
            implementation(libs.ktor.client.okhttp)
        }
    }
}

// The theme test reads the theme files directly, so a change to them must re-run the tests.
tasks.withType<Test>().configureEach { inputs.dir("src/commonMain/composeResources/files") }

compose.resources {
    // Generated accessors for fonts and theme files, e.g. Res.font.inter_400regular.
    packageOfResClass = "io.github.lbondoc.postmarked.resources"
}
