import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.wire)
    // Publish so :sdk-android's `api(projects.protocol)` resolves as a real Maven coordinate for
    // external consumers. The KMP plugin registers the root + per-target publications itself.
    `maven-publish`
}

kotlin {
    jvm()

    androidLibrary {
        namespace = "com.venbiasa.wailo.protocol"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    sourceSets {
        commonMain.dependencies {
            // Generated messages are part of this module's API.
            api(libs.wire.runtime)
        }
    }
}

// AGP 8's KMP library plugin has no `androidLibrary { compilerOptions }` (that's AGP 9); pin the JVM
// bytecode target for the jvm + android compilations at the task level to keep everything on 21.
tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

wire {
    kotlin {}
    sourcePath {
        srcDir("src/commonMain/proto")
    }
}

// Publish under the product name while the internal Gradle module stays :protocol. The KMP plugin
// creates one publication per target, so rename the whole family: protocol -> wailo-protocol,
// protocol-android -> wailo-protocol-android, protocol-jvm -> wailo-protocol-jvm. configureEach is
// lazy, so it also catches the target publications AGP/KMP register during afterEvaluate.
publishing {
    publications.withType<MavenPublication>().configureEach {
        artifactId = artifactId.replaceFirst(project.name, "wailo-protocol")
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
