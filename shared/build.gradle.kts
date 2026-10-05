// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Replays the cuff-checked blood-pressure sessions of an unpacked diagnostic export:
// ./gradlew :shared:bpEval -Pdir=<folder>
tasks.register<JavaExec>("bpEval") {
    group = "verification"
    description = "Blood-pressure error on the sessions of an unpacked export (-Pdir=<folder>)"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.heartline.shared.tools.BpEvalMainKt")
    args(project.findProperty("dir")?.toString() ?: "")
}
