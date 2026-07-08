import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 provides built-in Kotlin support; the kotlin-android plugin is no longer applied.
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    // Auto-instruments OkHttp call sites so clients built without Wailo.interceptor() are still
    // captured (POC). Resolved from the wailo-gradle-plugin included build.
    id("com.venbiasa.wailo.instrumentation")
}

android {
    namespace = "com.venbiasa.wailo.sample"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.venbiasa.wailo.sample"
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
    implementation(projects.sdkAndroid)

    // Required at runtime: the SDK declares OkHttp compileOnly.
    implementation(libs.okhttp)

    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material)
    implementation(libs.compose.ui)
}
