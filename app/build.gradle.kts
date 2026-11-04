plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.localai.controller"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.localai.linuxagent.desktop"
        manifestPlaceholders["appLabel"] = "Local AI Linux"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "0.13.0-auto-update"
        ndk { abiFilters += "arm64-v8a" }
    }

    packaging { jniLibs.useLegacyPackaging = true }

    // Use an owner-controlled keystore when configured in GitHub Actions secrets.
    // Never commit private signing keys or passwords to the public repository.
    signingConfigs {
        val keyFile = System.getenv("AI_RELEASE_KEYSTORE")
        if (!keyFile.isNullOrBlank()) {
            create("persistent") {
                storeFile = file(keyFile)
                storePassword = System.getenv("AI_RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("AI_RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("AI_RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            // Independent TEST installation: never overwrite a differently signed
            // existing user app and never require deleting Linux/model data.
            applicationIdSuffix = ".test.v012"
            versionNameSuffix = "-test-v012"
            manifestPlaceholders["appLabel"] = "Local AI Linux TEST"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("persistent")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
}

dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("dev.ffmpegkit-maintained:llama-android:0.1.1")
    implementation("org.apache.commons:commons-compress:1.27.1")
}