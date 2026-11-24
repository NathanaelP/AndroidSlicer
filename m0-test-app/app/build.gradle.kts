// SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    id("com.android.application")
}

android {
    namespace = "org.mobileslicer.enginetest"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.mobileslicer.enginetest"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "m0-1"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideload-only test build: sign with the debug key so it installs directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        // Extract the native libraries to disk at install time. CuraEngine is an
        // executable packaged as libcuraengine.so; it can only be run from there.
        jniLibs.useLegacyPackaging = true
    }
}
