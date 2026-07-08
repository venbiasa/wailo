import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

kotlin {
    androidLibrary {
        namespace = "com.venbiasa.wailo.sample.kmp"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
        }
    }

    // A static framework the iOS app links; Swift calls SampleApi from it.
    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            // Ktor's OkHttp engine builds an OkHttpClient; the ASM plugin rewrites that build(), so
            // this shared traffic is auto-captured on Android with no Wailo code here.
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            // Ktor's Darwin engine sits on URLSession, captured by sdk-ios's URLProtocol swizzle.
            implementation(libs.ktor.client.darwin)
        }
    }
}
