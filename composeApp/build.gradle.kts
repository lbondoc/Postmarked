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
            // `api` re-exports these to the app modules: they use the domain types,
            // and the Android uploader talks to Supabase Storage directly.
            api(projects.core.domain)
            api(libs.supabase.auth)
            api(libs.supabase.postgrest)
            api(libs.supabase.storage)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.navigation.compose)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.qrose)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
        }
        androidMain.dependencies {
            // The HTTP engine is per platform; iOS will get its own (Darwin) later.
            implementation(libs.ktor.client.okhttp)
        }
    }
}
