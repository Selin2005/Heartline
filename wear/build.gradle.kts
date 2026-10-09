// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.paparazzi)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.heartline.wear"
    compileSdk = 37

    defaultConfig {
        // Same applicationId as :phone — required by the Wearable Data Layer.
        applicationId = "io.github.selin2005.heartline"
        minSdk = 30
        targetSdk = 37
        // Set by the Build workflow: -Pheartline.versionName=1.2.0 -Pheartline.versionCode=<minutes since 2026>.
        versionCode = (findProperty("heartline.versionCode") ?: "1").toString().toInt()
        versionName = (findProperty("heartline.versionName") ?: "0.1.0").toString()
        // `./gradlew -Pheartline.fakeSensors=true :wear:assembleDebug` builds a watch app with
        // synthetic sensors for demos without Developer mode.
        buildConfigField("boolean", "USE_FAKE_SENSORS", (findProperty("heartline.fakeSensors") ?: "false").toString())
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // The release key comes from the environment (the Build workflow's secrets). Without it,
        // every build falls back to the shared test key (local builds, pull requests).
        System.getenv("HEARTLINE_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let { keystore ->
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("HEARTLINE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HEARTLINE_KEY_ALIAS")
                keyPassword = System.getenv("HEARTLINE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // Local builds and tests. The Build workflow makes every channel (dev, beta and stable) from
        // the release build type below, so all three are the same app and install over each other.
        debug {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        release {
            // R8 shrinks the libraries only (proguard-rules.pro keeps Heartline's own code whole and
            // unrenamed). Resources are all kept: some are read only by the system, such as the Wear OS
            // capability the phone and watch find each other by. Signed with the release key when the
            // environment provides one.
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        // Google Play: the same release build, named like the phone's so both are bundled together.
        create("play") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
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

    // Exported Room schemas, readable by MigrationTestHelper in Robolectric tests.
    sourceSets.getByName("debug").assets.directories.add("$projectDir/schemas")

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Paparazzi (layoutlib) and Robolectric cannot share a JVM: one test class per fork.
            it.forkEvery = 1
            it.maxParallelForks = 2
            // Robolectric (SDK 36 sandbox) on JDK 21 needs these internals opened.
            it.jvmArgs(
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED"
            )
        }
    }
}

// Unit and screenshot tests run on debug; the play build type only differs in packaging.
tasks.matching { it.name == "testPlayUnitTest" }.configureEach { enabled = false }

dependencies {
    implementation(project(":shared"))
    implementation(project(":datalayer"))
    implementation(project(":bubbles"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime.ktx)
    implementation(libs.health.services.client)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // Samsung Health Sensor SDK v1.4.1 (see docs/SAMSUNG_HEALTH_SENSOR_SDK.md).
    implementation(files("libs/samsung-health-sensor-api.aar"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.wear.compose.navigation)
    // Wear widgets (Wear OS 7 tile stack; shown as full-screen tiles on older watches).
    implementation(libs.glance.wear)
    implementation(libs.glance.wear.core)
    implementation(libs.remote.creation.compose)
    implementation(libs.remote.core)
    implementation(libs.wear.remote.material3)
    testImplementation(libs.remote.tooling.preview)
    testImplementation("androidx.glance.wear:wear-tooling-preview:1.0.0-alpha19")
    implementation(libs.wear.complications.data.source.ktx)
    // Navigation stays on 2.9.x; compileSdk 37 now allows newer versions.
    implementation(libs.androidx.navigation.compose)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.wear.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.work.testing)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
