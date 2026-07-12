import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

kotlin {
    androidLibrary {
        namespace = "com.venbiasa.wailo.sample.kmp"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
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

// AGP 8's KMP library plugin has no `androidLibrary { compilerOptions }` (that's AGP 9); pin the JVM
// bytecode target for the android compilation at the task level to keep it on 21.
tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}
