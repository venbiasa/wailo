import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidLibrary)
    // AGP 8 has no built-in Kotlin support, so apply kotlin-android to compile the SDK's Kotlin.
    alias(libs.plugins.kotlinAndroid)
    `maven-publish`
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

    // Expose the release AAR (plus sources) as a publishable component. This creates the
    // `release` software component consumed by the publication below.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

publishing {
    publications {
        // The `release` component only exists once AGP has configured the variant.
        register<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
            // Published under the product name; the internal Gradle module stays :sdk-android.
            // "sdk-android" is ambiguous as a bare coordinate/filename (which SDK?).
            artifactId = "wailo-android"
        }
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
    // OkHttp is compileOnly for the SDK (the host provides it); the interceptor tests need it on the
    // test classpath to build Request/Response and a fake Chain.
    testImplementation(libs.okhttp)
}
