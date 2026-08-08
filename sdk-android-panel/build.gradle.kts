import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidLibrary)
    // AGP 8 has no built-in Kotlin support, so apply kotlin-android to compile the panel's Kotlin.
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    `maven-publish`
}

android {
    namespace = "com.venbiasa.wailo.sdk.android.panel"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

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
        register<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
            artifactId = "wailo-android-panel"
        }
    }
}

dependencies {
    // api: the panel is opened with types from the SDK (WailoStatus, WailoPairing) and a host that
    // wires its own trigger needs them on its compile classpath.
    api(projects.sdkAndroid)

    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.kotlinx.coroutines.core)

    // Camera + decoding for the pairing QR. Weighty, and deliberately quarantined here: :sdk-android
    // ships inside third-party apps and must stay dependency-light (invariant #3).
    implementation(libs.zxing.embedded)

    testImplementation(libs.junit)
}
