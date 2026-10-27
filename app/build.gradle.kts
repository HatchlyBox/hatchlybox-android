import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Add when needed: alias(libs.plugins.ksp) for Room, alias(libs.plugins.kotlin.serialization) for JSON.
}

android {
    namespace = "com.hatchlybox.app"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.hatchlybox.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "6.4.1"
        ndk { abiFilters += "arm64-v8a" }
    }

    // proot and its loader ship as lib*.so so Android unpacks them where apps may execute files.
    packaging { jniLibs { useLegacyPackaging = true } }

    // Official builds are signed with the Hatchlybox release key, kept outside the repository (its signing.properties
    // names it: storeFile, storePassword, keyAlias, keyPassword). Anyone else's build falls back to the debug key, so it
    // still installs, but can't update an official copy. Play bundles are signed with the app's upload key by
    // `hatchlybox play prepare`, which also sets the version.
    val signing = file(System.getenv("HATCHLYBOX_SIGNING") ?: "${System.getProperty("user.home")}/.config/hatchlybox/release-key/signing.properties")
    val official = signing.isFile
    if (official) signingConfigs {
        create("official") {
            val p = Properties().apply { signing.inputStream().use { load(it) } }
            storeFile = file(p.getProperty("storeFile")); storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias"); keyPassword = p.getProperty("keyPassword")
            storeType = "PKCS12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (official) "official" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    // More, tested with this toolchain: see gradle/libs.versions.toml and the skills in .claude/skills.
}
