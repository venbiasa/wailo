import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.wire)
}

kotlin {
    jvm()

    androidLibrary {
        namespace = "com.venbiasa.wailo.protocol"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Generated messages are part of this module's API.
            api(libs.wire.runtime)
        }
    }
}

wire {
    kotlin {}
    sourcePath {
        srcDir("src/commonMain/proto")
    }
}

// The Wire Gradle plugin emits only Kotlin/Java, so the iOS SDK's Swift types are produced by
// running the Wire compiler CLI against the *same* schema. This keeps `protocol` the single source
// of truth (invariant #1): the Swift DTOs are generated, never hand-written.
val wireCompiler: Configuration by configurations.creating

dependencies {
    wireCompiler(libs.wire.compiler)
}

tasks.register<JavaExec>("generateSwiftProto") {
    group = "wire"
    description = "Generates Swift protobuf types for sdk-ios from protocol/src/commonMain/proto."
    classpath = wireCompiler
    mainClass.set("com.squareup.wire.WireCompiler")
    val protoDir = layout.projectDirectory.dir("src/commonMain/proto")
    // Emitted into the sibling SwiftPM package; committed so sdk-ios builds without Gradle.
    val outDir = layout.projectDirectory.dir("../sdk-ios/Sources/WailoProtocol/generated")
    inputs.dir(protoDir)
    outputs.dir(outDir)
    doFirst { outDir.asFile.mkdirs() }
    args(
        "--proto_path=${protoDir.asFile.absolutePath}",
        "--swift_out=${outDir.asFile.absolutePath}",
    )
}
