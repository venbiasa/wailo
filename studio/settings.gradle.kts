rootProject.name = "wailo-studio"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
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
        // wailo-protocol is consumed as a published binary from Maven Local (produced by the root SDK
        // build). This is the only seam between the two builds — see DECISIONS.md ADR-0015.
        mavenLocal()
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

include(":engine")
include(":host")
include(":daemon")
include(":shared")
include(":desktopApp")
include(":cli")
include(":mcp")
include(":menubar")
