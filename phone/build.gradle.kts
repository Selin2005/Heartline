plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.paparazzi)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.heartline.phone"
    compileSdk = 36

    defaultConfig {
        // Same applicationId as :wear — required by the Wearable Data Layer.
        applicationId = "com.heartline.app"
        minSdk = 26
        targetSdk = 36
        // Set by the Build workflow form: -Pheartline.versionName=1.2.0 -Pheartline.versionCode=<run number>.
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
    }

    buildTypes {
        release {
            // R8 with the shared test keystore until a release key exists (P11).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
            // Phones paired with a Galaxy Watch are ARM; ONNX Runtime's x86 libraries (≈ 46 MB) are for emulators only.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
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

dependencies {
    implementation(project(":shared"))
    // On-device PPG encoder (PaPaGei) for the personal blood-pressure model.
    implementation(libs.onnxruntime.android)
    implementation(project(":datalayer"))
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
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.glance.appwidget.testing)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
