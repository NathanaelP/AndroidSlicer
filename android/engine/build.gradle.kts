// SPDX-License-Identifier: AGPL-3.0-or-later
// The app's core: imports meshes and runs CuraEngine as a separate process.
plugins {
    id("com.android.library")
}

android {
    namespace = "org.mobileslicer.engine"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
        ndk { abiFilters += "arm64-v8a" }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
