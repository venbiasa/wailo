import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
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
    mainClass.set("com.venbiasa.wailo.daemon.MainKt")
    applicationName = "wailo-daemon"
}

dependencies {
    api(projects.host)
    // The daemon is the only thing that adapts the relay to the engine (ADR-0070); no frontend sees it.
    implementation(projects.proxy)
    implementation(libs.kotlinx.serialization.json)
    // X.509 authoring for the local root and its leaves (ADR-0073). Deliberately not in `proxy`, which
    // only ever receives an SSLContext and so stays on the JDK alone.
    implementation(libs.bouncycastle.pkix)
    implementation(libs.bouncycastle.prov)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)
}
