// A web app as an Android app (hatchlybox wrap): its files ship inside the APK and run offline in a WebView.
// No Compose and no libraries: it builds fast and stays small.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "__PKG__"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "__PKG__"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so it installs without a keystore. Play bundles are signed
            // with the app's upload key by `hatchlybox play prepare`, which also sets the version.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // The web app's files are served as they are (no compression games with .wasm, fonts or media).
    androidResources { noCompress += listOf("wasm", "pck", "mp3", "ogg", "mp4", "webm", "woff2") }
}
