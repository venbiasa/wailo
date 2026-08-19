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
    // Thin frontend over the shared daemon client. Never depends on shared/Compose.
    implementation(projects.daemon)
    // Never called from here — the launcher spawns it by class name (ADR-0065). It is on the runtime
    // classpath so that the daemon this frontend starts comes with something that shows it exists.
    runtimeOnly(projects.menubar)

    testImplementation(kotlin("test"))
}
