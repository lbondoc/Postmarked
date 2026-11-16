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
    }

    sourceSets {
        commonMain.dependencies {
            // `api` re-exports the domain types (LatLng etc.) to modules that depend on this one.
            api(projects.core.domain)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.navigation.compose)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.supabase.auth)
            implementation(libs.supabase.postgrest)
            implementation(libs.kotlinx.datetime)
            implementation(libs.qrose)
        }
        androidMain.dependencies {
            // The HTTP engine is per platform; iOS will get its own (Darwin) later.
            implementation(libs.ktor.client.okhttp)
        }
    }
}
