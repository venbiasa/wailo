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
    }
}

dependencies {
    // currentOs bundles the Compose desktop runtime + window toolkit for the host platform.
    implementation(compose.desktop.currentOs)
    // engine = the headless query surface; shared = the Compose viewer (invariant #2: UI is a
    // frontend over engine, and the two never talk to the transport directly).
    implementation(projects.engine)
    implementation(projects.shared)
}
