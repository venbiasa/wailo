import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 provides built-in Kotlin support; the kotlin-android plugin is no longer applied.
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.venbiasa.waylay.sdk.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

dependencies {
    implementation(projects.protocol)
    // api: WaylayClient/CaptureSink are part of the SDK's public surface.
    api(projects.core)

    // Host app owns OkHttp; compileOnly avoids forcing a version on it (invariant #3).
    compileOnly(libs.okhttp)
}
