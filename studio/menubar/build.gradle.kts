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
    mainClass.set("com.venbiasa.wailo.menubar.MainKt")
    applicationName = "wailo-menubar"
}

// The item's glyph is derived from the app mark at runtime, and the mark stays where it belongs — with the
// app whose icon it is. Copying it in at build time keeps one asset on disk, so the two cannot drift.
tasks.processResources {
    from(project(":desktopApp").file("src/main/resources/icons/wailo.png")) { into("icons") }
}

dependencies {
    // AWT only, deliberately: this process exists whenever a daemon does, so it must cost a fraction of
    // what a Compose frontend costs — the daemon's own footprint is what made its invisibility
    // indefensible in the first place (ADR-0062/0065).
    implementation(projects.daemon)

    testImplementation(kotlin("test"))
}
