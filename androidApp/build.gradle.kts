plugins {
    // No Kotlin plugin here: Android Gradle Plugin 9 compiles Kotlin itself.
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization) // the upload queue is saved as JSON
}

android {
    namespace = "io.github.lbondoc.postmarked"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // The applicationId is permanent once the app is on Google Play.
        applicationId = "io.github.lbondoc.postmarked"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 3
        versionName = "0.2.0"
    }
}

dependencies {
    implementation(projects.composeApp)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.play.services.code.scanner)
    // The scanner pulls in Fragment 1.0, whose old result handling breaks permission requests; this replaces it.
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.work)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.common)
    implementation(libs.coil.video) // video thumbnails in the import grid
}
