// SPDX-License-Identifier: GPL-3.0-or-later
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "paprelay"

include(":core", ":as11")

// The Android module needs the Android SDK. JVM-only checkouts (protocol work,
// CI unit-test jobs) can skip it with -Ppaprelay.skipAndroid=true.
if (providers.gradleProperty("paprelay.skipAndroid").orNull != "true") {
    include(":app")
}
