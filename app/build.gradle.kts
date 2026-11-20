// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val commit = providers.exec {
    // The short commit, marked when the files differ from it. (No tag ever matches, so it is never a tag's name.)
    commandLine("git", "describe", "--always", "--dirty", "--abbrev=7", "--match=no-tag-is-named-this")
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
        versionCode = 4
        versionName = "0.2.2"
        // The commit, so that a report says exactly which build wrote it.
        buildConfigField("String", "GIT_COMMIT", "\"" + (commit.orNull?.trim()?.ifEmpty { null } ?: "unknown") + "\"")
    }

    androidResources {
        // The app's own text is English only for now. Without this the libraries' translations
        // are packed in, and the phone is told the app speaks eighty languages.
        localeFilters += "en"
    }

    // The release key lives outside the repository, and is named to the build only through the
    // environment. Without it a release build is still made, unsigned, so that it can be checked;
    // it just cannot be installed.
    val releaseKeystore = providers.environmentVariable("PAPRELAY_KEYSTORE")
    signingConfigs {
        if (releaseKeystore.isPresent) {
            val password = providers.environmentVariable("PAPRELAY_KEYSTORE_PASSWORD").orNull
                ?: throw GradleException("PAPRELAY_KEYSTORE is set, so PAPRELAY_KEYSTORE_PASSWORD must be set too")
            create("release") {
                // A relative path is taken from the project's root, not from this module.
                storeFile = rootProject.file(releaseKeystore.get())
                storeType = "PKCS12"
                storePassword = password
                keyAlias = providers.environmentVariable("PAPRELAY_KEY_ALIAS").getOrElse("paprelay")
                keyPassword = password
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore.isPresent) signingConfig = signingConfigs.getByName("release")
            buildConfigField("boolean", "SYNC_ENABLED", "true")
        }
        debug {
            // Health Connect files records under the app that wrote them, and this build is a
            // different app to it than the released one. It checks the connections and writes nothing.
            buildConfigField("boolean", "SYNC_ENABLED", "false")
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
    implementation(libs.work.runtime)
    implementation(libs.serialization.json)

    // Android's local unit tests run on JUnit 4.
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.coroutines.test)
    testImplementation(testFixtures(project(":as11")))
}
