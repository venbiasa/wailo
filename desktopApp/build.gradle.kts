import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

compose.desktop {
    application {
        mainClass = "com.venbiasa.waylay.desktop.MainKt"
    }
}

dependencies {
    // currentOs bundles runtime/foundation/ui/material (2) for the host platform.
    implementation(compose.desktop.currentOs)
    implementation(projects.engine)

    // Test-only: exercise the real device client against the real server (loopback).
    testImplementation(kotlin("test"))
    testImplementation(projects.core)
}
