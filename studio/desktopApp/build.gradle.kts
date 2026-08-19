import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

compose.desktop {
    application {
        mainClass = "com.venbiasa.wailo.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Wailo"
            packageVersion = "1.0.0"

            // Per-platform launcher icons for the packaged app. The runtime window/Dock icon is set
            // separately in Main.kt (dev runs don't go through packaging); these files share the same
            // Wailo mark. Linux reuses the runtime PNG so there's a single source of truth for it.
            macOS { iconFile.set(project.file("icons/wailo.icns")) }
            windows { iconFile.set(project.file("icons/wailo.ico")) }
            linux { iconFile.set(project.file("src/main/resources/icons/wailo.png")) }
        }
    }
}

dependencies {
    // currentOs bundles the Compose desktop runtime + window toolkit for the host platform.
    implementation(compose.desktop.currentOs)
    // daemon is the shared capture/control owner; shared is the Compose viewer. Studio is a client and
    // therefore never binds the capture port or owns cable transports itself.
    implementation(projects.daemon)
    implementation(projects.shared)
    // The menu bar item is its own process now (ADR-0065), spawned by class name. runtimeOnly keeps that a
    // packaging fact rather than a compile dependency: Studio must not be able to call into it.
    runtimeOnly(projects.menubar)
    implementation(libs.zxing.core)

    testImplementation(kotlin("test"))
}
