import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    // AGP 8 has no built-in Kotlin support, so apply kotlin-android to compile the app's Kotlin.
    alias(libs.plugins.kotlinAndroid)
    // Rewrites OkHttp build() call sites across app code *and dependencies* (incl. Ktor's OkHttp
    // engine), so the shared module's traffic is captured with no Wailo code in commonMain.
    id("com.venbiasa.wailo")
}

android {
    namespace = "com.venbiasa.wailo.sample.kmp.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.venbiasa.wailo.sample.kmp.android"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
    implementation(projects.sampleKmp.shared)
    implementation(projects.sdkAndroid)

    implementation(libs.kotlinx.coroutines.core)
    // sdk-android's interceptor and Ktor's OkHttp engine both need OkHttp at runtime.
    runtimeOnly(libs.okhttp)
}
