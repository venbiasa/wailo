import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm()

    androidLibrary {
        namespace = "com.venbiasa.wailo.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
        }
        // The Noto Sans font ships as a Compose multiplatform resource; the AGP KMP library
        // plugin does not process resources unless opted in.
        androidResources.enable = true
    }

    sourceSets {
        commonMain.dependencies {
            // HttpExchange is part of this module's public API (FlowEntry), so protocol is `api`.
            api(projects.protocol)
            // The `compose.*` accessors are used (rather than catalog GAVs) because the Compose
            // plugin resolves the correct multiplatform artifact/version for each; material3 and
            // components-resources have no plain `org.jetbrains.compose.*:*:<version>` coordinate.
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
        }
        // Pure formatting logic lives in commonMain; tested from the JVM target so no Android
        // host-test wiring is needed for what is a desktop-only UI today.
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

compose.resources {
    packageOfResClass = "com.venbiasa.wailo.shared.resources"
}
