import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    application
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

application {
    mainClass.set("com.venbiasa.wailo.cli.MainKt")
    applicationName = "wailo-cli"
}

dependencies {
    // Thin frontend over host (ADR-0055). Never depends on shared/Compose.
    implementation(projects.host)

    testImplementation(kotlin("test"))
}
