rootProject.name = "wailo"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    // Build-time bytecode instrumentation plugin, kept as an isolated build so its
    // ASM/AGP-API deps never leak onto any runtime classpath.
    includeBuild("wailo-gradle-plugin")
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":protocol")
include(":sdk-android")
include(":engine")
include(":shared")
include(":desktopApp")
include(":sample-android")
// KMP consumer sample: shared Kotlin (Ktor) + Android app. Its iOS app is an Xcode project
// (sample-kmp/iosApp), integrated at the Swift shell, so it is not a Gradle module.
include(":sample-kmp:shared")
include(":sample-kmp:androidApp")
