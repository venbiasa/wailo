package com.venbiasa.wailo.instrumentation

import fixtures.TestHook
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/**
 * Verifies the transform end-to-end without AGP or a device: it rewrites a compiled OkHttp caller,
 * loads the rewritten bytes, runs them, and asserts the woven hook fired and added the interceptor.
 * The hook is retargeted to [TestHook] since the real `WailoRuntime` isn't on this JVM classpath.
 */
class OkHttpBuilderClassVisitorTest {

    @Before
    fun resetHook() {
        TestHook.reset()
    }

    @Test
    fun `weaves hook into a plain build call`() {
        val client = transformAndRun("plainBuild")
        assertEquals("hook should fire once per build() call site", 1, TestHook.CALLS.get())
        assertEquals("interceptor should be auto-added", 1, client.interceptors.size)
    }

    @Test
    fun `weaves hook into a chained builder build call`() {
        val client = transformAndRun("chainedBuild")
        assertEquals(1, TestHook.CALLS.get())
        assertEquals(1, client.interceptors.size)
    }

    @Test
    fun `leaves methods without a build call untouched`() {
        val result = runTransformed("noBuild")
        assertEquals("no build() call site means no hook", 0, TestHook.CALLS.get())
        assertEquals(42, result)
    }

    private fun transformAndRun(method: String): OkHttpClient = runTransformed(method) as OkHttpClient

    private fun runTransformed(method: String): Any? {
        val transformed = transform(CALLER)
        val loader = SingleClassLoader(javaClass.classLoader, CALLER, transformed)
        val clazz = loader.loadClass(CALLER)
        return clazz.getMethod(method).invoke(null)
    }

    private fun transform(className: String): ByteArray {
        val original = javaClass.classLoader
            .getResourceAsStream(className.replace('.', '/') + ".class")
            ?: error("fixture not on classpath: $className")
        val reader = ClassReader(original.readBytes())
        val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
        // Retarget the woven call to the test hook; production weaves WailoRuntime.
        reader.accept(OkHttpBuilderClassVisitor(Opcodes.ASM9, writer, TEST_HOOK_OWNER), 0)
        return writer.toByteArray()
    }

    /** Defines exactly [className] from [bytes]; everything else (okhttp3, TestHook) comes from parent. */
    private class SingleClassLoader(
        parent: ClassLoader,
        private val className: String,
        private val bytes: ByteArray,
    ) : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name == className) {
                synchronized(getClassLoadingLock(name)) {
                    val existing = findLoadedClass(name)
                    if (existing != null) return existing
                    return defineClass(name, bytes, 0, bytes.size).also { if (resolve) resolveClass(it) }
                }
            }
            return super.loadClass(name, resolve)
        }
    }

    private companion object {
        const val CALLER = "fixtures.OkHttpCaller"
        const val TEST_HOOK_OWNER = "fixtures/TestHook"
    }
}
