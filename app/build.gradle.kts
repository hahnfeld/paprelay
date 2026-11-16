// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val commit = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText

android {
    namespace = "com.everysoft.paprelay"
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }

    defaultConfig {
        applicationId = "com.everysoft.paprelay"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            // The commit, so that a report says which build wrote it.
            versionNameSuffix = "-debug+" + (commit.orNull?.trim()?.ifEmpty { null } ?: "unknown")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    bundle {
        // The diagnostic report is always written in English, whatever the phone's
        // language, so every language must stay in the base package of an app bundle.
        language {
            enableSplit = false
        }
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        // The app compiles against Android 17 but still targets Android 16: its
        // behaviour changes for Bluetooth and background work have not been
        // tested on a device yet. Raise targetSdk only together with that test.
        disable += "OldTargetApi"
    }

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/*.kotlin_module", "DebugProbesKt.bin")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":as11"))
    implementation(libs.coroutines.android)
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.health.connect)
    implementation(libs.serialization.json)

    // Android's local unit tests run on JUnit 4.
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.coroutines.test)
}
