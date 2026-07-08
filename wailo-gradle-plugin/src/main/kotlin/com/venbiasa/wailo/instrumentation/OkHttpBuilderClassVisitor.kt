package com.venbiasa.wailo.instrumentation

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Rewrites every `OkHttpClient.Builder.build()` call site to `WailoRuntime.hook(builder).build()`.
 *
 * Hooking the *call site* (not OkHttp's own classes) is what lets a single visitor reach clients
 * built by third-party libraries: wherever anyone calls `build()`, the woven `hook` gets a chance to
 * add the interceptor. The inserted call is stack-neutral — it consumes the `Builder` on top of the
 * stack and returns the same `Builder` — so no frames or locals change.
 *
 * [hookOwner] is injected so tests can retarget the woven call to a stand-in; production always uses
 * the real [WAILO_RUNTIME].
 */
internal class OkHttpBuilderClassVisitor(
    apiVersion: Int,
    next: ClassVisitor,
    private val hookOwner: String = WAILO_RUNTIME,
) : ClassVisitor(apiVersion, next) {

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor {
        val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
        return BuildCallRewriter(api, delegate, hookOwner)
    }

    private class BuildCallRewriter(
        api: Int,
        next: MethodVisitor,
        private val hookOwner: String,
    ) : MethodVisitor(api, next) {

        override fun visitMethodInsn(
            opcode: Int,
            owner: String?,
            name: String?,
            descriptor: String?,
            isInterface: Boolean,
        ) {
            if (opcode == Opcodes.INVOKEVIRTUAL &&
                owner == OKHTTP_BUILDER &&
                name == BUILD &&
                descriptor == BUILD_DESCRIPTOR
            ) {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, HOOK, HOOK_DESCRIPTOR, false)
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        }
    }

    companion object {
        const val WAILO_RUNTIME = "com/venbiasa/wailo/sdk/android/WailoRuntime"
        const val HOOK = "hook"

        private const val OKHTTP_BUILDER = "okhttp3/OkHttpClient\$Builder"
        private const val OKHTTP_CLIENT = "okhttp3/OkHttpClient"
        private const val BUILD = "build"
        private const val BUILD_DESCRIPTOR = "()L$OKHTTP_CLIENT;"
        private const val HOOK_DESCRIPTOR = "(L$OKHTTP_BUILDER;)L$OKHTTP_BUILDER;"
    }
}
