// SPDX-License-Identifier: AGPL-3.0-or-later
buildscript {
    dependencies {
        // AGP 9 compiles Kotlin itself; pin the Kotlin version it uses so it
        // matches the Compose compiler plugin below.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
