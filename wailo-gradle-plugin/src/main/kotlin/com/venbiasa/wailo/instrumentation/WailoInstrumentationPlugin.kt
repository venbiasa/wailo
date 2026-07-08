package com.venbiasa.wailo.instrumentation

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.AppPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Applied by a host app to auto-capture OkHttp traffic — including third-party libraries — without
 * manual `Wailo.interceptor()` wiring. Registers [WailoAsmClassVisitorFactory] over
 * [InstrumentationScope.ALL] so both app code and dependency jars are rewritten.
 *
 * Scoped to the Android *application* plugin: `ALL`-scope instrumentation of dependencies is only
 * available to apps, and the host app is where auto-capture belongs. Library-scope support is a later
 * addition if a use case appears.
 */
class WailoInstrumentationPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        // withType keeps this order-independent: works whether AGP is applied before or after us.
        project.plugins.withType(AppPlugin::class.java) {
            val android = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
            android.onVariants { variant ->
                variant.instrumentation.transformClassesWith(
                    WailoAsmClassVisitorFactory::class.java,
                    InstrumentationScope.ALL,
                ) { params ->
                    params.excludedPackagePrefixes.set(DEFAULT_EXCLUDED_PACKAGE_PREFIXES)
                }
                // The woven call is stack-neutral (pop Builder, push Builder), so frames don't move.
                variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COPY_FRAMES)
            }
        }
    }

    private companion object {
        val DEFAULT_EXCLUDED_PACKAGE_PREFIXES = listOf(
            "okhttp3.",
            "okio.",
            "com.venbiasa.wailo.sdk.",
            "kotlin.",
            "kotlinx.",
        )
    }
}
