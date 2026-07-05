import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 provides built-in Kotlin support; the kotlin-android plugin is no longer applied.
    alias(libs.plugins.androidApplication)
}

android {
    namespace = "com.venbiasa.waylay.sample"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.venbiasa.waylay.sample"
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
}
