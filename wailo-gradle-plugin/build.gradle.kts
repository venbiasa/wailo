import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlinJvm)
    `maven-publish`
}

// Standalone included build: it does not inherit the root gradle.properties, so read that same file
// for the coordinates. Keeps the plugin's group/version from drifting away from the SDK's.
val rootProperties = Properties().apply {
    file("../gradle.properties").inputStream().use { load(it) }
}
group = rootProperties.getProperty("group")
version = rootProperties.getProperty("version")

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
    // AGP provides the ASM Instrumentation API at build time; never bundled into the plugin jar.
    compileOnly(libs.agp.api)
    implementation(gradleKotlinDsl())
    implementation(libs.asm)

    testImplementation(libs.junit)
    testImplementation(libs.asm)
    testImplementation(libs.asm.util)
    // The visitor rewrites okhttp3 call sites; the test transforms and runs a real OkHttp caller.
    testImplementation(libs.okhttp)
}

gradlePlugin {
    plugins {
        create("wailoInstrumentation") {
            // Bare product id (like Square's own `com.squareup.wire`): Wailo ships a single Gradle
            // plugin, so `id("com.venbiasa.wailo")` reads as "apply Wailo". The implementation class
            // stays in the .instrumentation package — only the public plugin id is shortened.
            id = "com.venbiasa.wailo"
            implementationClass = "com.venbiasa.wailo.instrumentation.WailoInstrumentationPlugin"
        }
    }
}
