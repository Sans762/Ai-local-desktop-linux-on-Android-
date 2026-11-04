plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.localai.controller"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.localai.controller"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0-native-linux-ai"
        ndk { abiFilters += "arm64-v8a" }
    }

    packaging { jniLibs.useLegacyPackaging = true }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("dev.ffmpegkit-maintained:llama-android:0.1.1")
    implementation("org.apache.commons:commons-compress:1.27.1")
}