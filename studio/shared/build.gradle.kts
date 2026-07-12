import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    // JVM-only: the desktop viewer runs on the JVM, so there is no Android target here (dropping it
    // keeps AGP out of the studio build entirely — ADR-0015). The expect/actual for the resize cursor
    // now resolves against jvmMain alone.
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
        }
    }

    sourceSets {
        commonMain.dependencies {
            // HttpExchange is part of this module's public API (FlowEntry), so protocol is `api`.
            // Consumed as the published wailo-protocol binary (ADR-0015), not a project dependency.
            api(libs.wailo.protocol)
            // The `compose.*` accessors are used (rather than catalog GAVs) because the Compose
            // plugin resolves the correct multiplatform artifact/version for each; material3 and
            // components-resources have no plain `org.jetbrains.compose.*:*:<version>` coordinate.
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

compose.resources {
    packageOfResClass = "com.venbiasa.wailo.shared.resources"
}
