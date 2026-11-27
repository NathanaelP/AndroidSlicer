// SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.mobileslicer.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.mobileslicer.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 10
        versionName = "0.5.3-m5"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            // Shrink unused code: Compose is large unshrunk.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Sideload-only for now: sign with the debug key so it installs directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures { compose = true }

    packaging {
        // Extract native libraries at install time: CuraEngine is an executable
        // packaged as libcuraengine.so and can only be run from the library folder.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}

// Desktop unit tests use the host build of the native simplifier when there is
// one (engine/build-simplifier.sh host); tests that need it are skipped otherwise.
tasks.withType<Test>().configureEach {
    val work = System.getenv("WORK") ?: "${System.getProperty("user.home")}/mobileslicer-build"
    val lib = File(work, "host/bin/libmeshsimplify.so")
    if (lib.exists()) systemProperty("mobileslicer.simplifier", lib.path)
}
