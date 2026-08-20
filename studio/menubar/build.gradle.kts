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

// Windows and Linux tray areas show the app icon in its own colours, so the item is the mark itself there
// (macOS draws the two template assets in this module's resources instead). The mark stays where it
// belongs — with the app whose icon it is — and is copied in rather than duplicated.
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
