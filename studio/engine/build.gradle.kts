import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
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

dependencies {
    // Exposed on the query API (CapturedExchange, StateFlow), so consumers see them.
    // protocol crosses the SDK/studio build boundary as a published binary (ADR-0015), so it is the
    // one dependency referenced by Maven coordinate rather than a type-safe project accessor.
    api(libs.wailo.protocol)
    api(libs.kotlinx.coroutines.core)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)

    // Test-only: a raw WebSocket client drives the server over a real socket (loopback), replacing
    // the desktopApp test that used the former :core WailoClient.
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)
}
