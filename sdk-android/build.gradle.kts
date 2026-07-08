import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 provides built-in Kotlin support; the kotlin-android plugin is no longer applied.
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.venbiasa.wailo.sdk.android"
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
    // api: HttpExchange (protocol) is exposed through the public CaptureSink surface.
    api(projects.protocol)

    // Transport + sink, merged in from the former :core module. Kept as implementation:
    // WailoClient is public but its Ktor internals are not part of the SDK's API.
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)

    // Host app owns OkHttp; compileOnly avoids forcing a version on it (invariant #3).
    compileOnly(libs.okhttp)

    // Test-only: run the real WailoClient against a throwaway WebSocket server (loopback). Uses
    // ktor-server as a test fixture only — the SDK never depends on engine (invariant #3).
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.server.websockets)
}
