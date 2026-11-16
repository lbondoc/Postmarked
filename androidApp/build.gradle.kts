plugins {
    // No Kotlin plugin here: Android Gradle Plugin 9 compiles Kotlin itself.
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "io.github.lbondoc.postmarked"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // The applicationId is permanent once the app is on Google Play.
        applicationId = "io.github.lbondoc.postmarked"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 2
        versionName = "0.1.0"
    }
}

dependencies {
    implementation(projects.composeApp)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.play.services.code.scanner)
}
