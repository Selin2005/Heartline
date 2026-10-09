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
    namespace = "com.heartline.phone"
    compileSdk = 37

    defaultConfig {
        // The in-app GitHub updater; off in the "play" build type.
        buildConfigField("boolean", "UPDATER", "true")
        // Same applicationId as :wear — required by the Wearable Data Layer.
        applicationId = "io.github.selin2005.heartline"
        minSdk = 26
        targetSdk = 37
        // Set by the Build workflow: -Pheartline.versionName=1.2.0 -Pheartline.versionCode=<minutes since 2026>.
        versionCode = (findProperty("heartline.versionCode") ?: "1").toString().toInt()
        versionName = (findProperty("heartline.versionName") ?: "0.1.0").toString()
        // `-Pheartline.demoData=true` seeds sample records on first launch (UI exploration without a watch).
        buildConfigField("boolean", "DEMO_DATA", (findProperty("heartline.demoData") ?: "false").toString())
    }

    // ECGFounder is opened with AssetManager.openFd (size check), which needs the file stored uncompressed.
    androidResources {
        noCompress += "onnx"
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
            // Phones paired with a Galaxy Watch are ARM; ONNX Runtime's x86 libraries (≈ 46 MB) are for emulators only.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
        // Google Play: the same release build, without the GitHub updater (Play updates the app).
        create("play") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            buildConfigField("boolean", "UPDATER", "false")
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

// legal/*.md and CHANGELOG.md go into the APK as assets/docs/, so the app shows the same text as the repository.
val bundledDocs = layout.buildDirectory.dir("generated/bundled-docs")
val bundleDocs = tasks.register<Sync>("bundleDocs") {
    from(rootProject.fileTree("legal") { include("*.md") })
    from(rootProject.file("CHANGELOG.md"))
    into(bundledDocs.map { it.dir("docs") })
}
android.sourceSets.getByName("main").assets.directories.add(bundledDocs.get().asFile.path)
tasks.named("preBuild") { dependsOn(bundleDocs) }

// Unit and screenshot tests run on debug; the play build type only differs in packaging.
tasks.matching { it.name == "testPlayUnitTest" }.configureEach { enabled = false }

dependencies {
    implementation(project(":shared"))
    // On-device PPG encoder (PaPaGei) for the personal blood-pressure model.
    implementation(libs.onnxruntime.android)
    implementation(project(":datalayer"))
    implementation(project(":bubbles"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.koin.android)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime.ktx)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.koin.androidx.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.work.testing)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.glance.appwidget.testing)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
