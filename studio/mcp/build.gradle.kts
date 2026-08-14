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
    mainClass.set("com.venbiasa.wailo.mcp.MainKt")
    applicationName = "wailo-mcp"
}

dependencies {
    implementation(projects.daemon)
    implementation(libs.mcp.sdk)
    // Tree parsing only (no @Serializable codegen, so no serialization plugin): redaction walks captured
    // JSON bodies structurally, which a regex cannot do for nested or escaped values.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}
