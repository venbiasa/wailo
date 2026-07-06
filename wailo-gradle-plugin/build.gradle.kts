import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-gradle-plugin`
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
            id = "com.venbiasa.wailo.instrumentation"
            implementationClass = "com.venbiasa.wailo.instrumentation.WailoInstrumentationPlugin"
        }
    }
}
