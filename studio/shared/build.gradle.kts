import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    // Navigation 3 destination keys are @Serializable so the panel's back stack persists portably
    // across platforms (not just via JVM/Android reflection) — ADR-0022.
    alias(libs.plugins.kotlinSerialization)
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
            // Navigation 3 (JetBrains multiplatform) owns the Map Local panel's page stack; kept in
            // commonMain so the nav layer ports beyond JVM desktop (ADR-0022).
            implementation(libs.navigation3.ui)
            // The body editor drives debounced validation off snapshotFlow and saves/loads on a
            // background dispatcher; depend on coroutines directly rather than via Compose transitively.
            implementation(libs.kotlinx.coroutines.core)
            // The rule archive (export/import) is a named-key JSON document, not the positional codec
            // the prefs use, so a file a user keeps survives a field being added — see RuleArchive.kt.
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.resources {
    packageOfResClass = "com.venbiasa.wailo.shared.resources"
}
