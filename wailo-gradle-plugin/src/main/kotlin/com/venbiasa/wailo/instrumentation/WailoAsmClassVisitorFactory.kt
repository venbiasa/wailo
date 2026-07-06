package com.venbiasa.wailo.instrumentation

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.objectweb.asm.ClassVisitor

/**
 * AGP entry point: builds an [OkHttpBuilderClassVisitor] for each instrumentable class.
 *
 * [isInstrumentable] is deliberately broad (scope is ALL, i.e. app + dependency jars, so third-party
 * clients are reached) but skips packages that can't contain host call sites we care about — OkHttp
 * itself, and Wailo's own runtime — via a configurable prefix list so shaded/relocated OkHttp can be
 * tuned later without touching code.
 */
abstract class WailoAsmClassVisitorFactory :
    AsmClassVisitorFactory<WailoAsmClassVisitorFactory.Params> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor,
    ): ClassVisitor = OkHttpBuilderClassVisitor(
        apiVersion = instrumentationContext.apiVersion.get(),
        next = nextClassVisitor,
    )

    override fun isInstrumentable(classData: ClassData): Boolean {
        val excluded = parameters.get().excludedPackagePrefixes.get()
        return excluded.none(classData.className::startsWith)
    }

    interface Params : InstrumentationParameters {
        /** Dotted package prefixes skipped during instrumentation (e.g. `okhttp3.`). */
        @get:Input
        val excludedPackagePrefixes: ListProperty<String>
    }
}
