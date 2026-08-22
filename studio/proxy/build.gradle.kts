import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
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

dependencies {
    // The capture sink hands over protobuf messages, so consumers see them. Deliberately the only
    // dependency besides the JDK: the relay must not be able to reach engine or host state (ADR-0070).
    api(libs.wailo.protocol)

    testImplementation(kotlin("test"))
}
