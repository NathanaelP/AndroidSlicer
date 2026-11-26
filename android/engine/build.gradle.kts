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
    // Android's org.json is only a stub in local unit tests; use the real library there.
    testImplementation("org.json:json:20250517")
}

// Desktop unit tests use the host build of the native simplifier when there is
// one (engine/build-simplifier.sh host); tests that need it are skipped otherwise.
tasks.withType<Test>().configureEach {
    val work = System.getenv("WORK") ?: "${System.getProperty("user.home")}/mobileslicer-build"
    val lib = File(work, "host/bin/libmeshsimplify.so")
    if (lib.exists()) systemProperty("mobileslicer.simplifier", lib.path)
}
