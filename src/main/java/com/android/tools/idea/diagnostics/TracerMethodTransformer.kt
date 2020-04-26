/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.idea.diagnostics

import com.android.tools.idea.diagnostics.agent.Trampoline
import com.intellij.openapi.diagnostic.Logger
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassReader.SKIP_FRAMES
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ClassWriter.COMPUTE_FRAMES
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.ASM8
import org.objectweb.asm.Type
import org.objectweb.asm.commons.AdviceAdapter
import org.objectweb.asm.commons.Method
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain
import kotlin.reflect.jvm.javaMethod

// Things to improve:
// - Should be possible to do without COMPUTE_FRAMES (but then also remove SKIP_FRAMES.)
// - Stress-test this transformer by running it on a lot of classes.

/** Inserts calls (within JVM byte code) to the trampoline. */
class TracerMethodTransformer : ClassFileTransformer {

    companion object {
        private val LOG = Logger.getInstance(TracerMethodTransformer::class.java)
        private const val ASM_API = ASM8

        private val TRAMPOLINE_CLASS_NAME = Type.getType(Trampoline::class.java).internalName
        private val TRAMPOLINE_ENTER_METHOD = Method.getMethod(Trampoline::enter.javaMethod)
        private val TRAMPOLINE_LEAVE_METHOD = Method.getMethod(Trampoline::leave.javaMethod)
    }

    override fun transform(
        loader: ClassLoader?,
        className: String,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classfileBuffer: ByteArray
    ): ByteArray? {
        return try {
            if (!TracerConfig.shouldInstrumentClass(className)) return null
            tryTransform(className, classfileBuffer)
        } catch (e: Throwable) {
            LOG.error("Failed to instrument class $className", e)
            throw e
        }
    }

    private fun tryTransform(
        className: String,
        classBytes: ByteArray
    ): ByteArray? {
        val reader = ClassReader(classBytes)
        val writer = ClassWriter(reader, COMPUTE_FRAMES)

        val classVisitor = object : ClassVisitor(ASM_API, writer) {

            override fun visitMethod(
                access: Int,
                method: String,
                desc: String,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor? {
                val methodWriter = cv.visitMethod(access, method, desc, signature, exceptions)
                val id = TracerConfig.getMethodId(className, method, desc) ?: return methodWriter

                return object : AdviceAdapter(ASM_API, methodWriter, access, method, desc) {
                    val methodStart = Label()
                    val methodEnd = Label()

                    override fun onMethodEnter() {
                        invokeHook(TRAMPOLINE_ENTER_METHOD)
                        mv.visitLabel(methodStart)
                    }

                    override fun onMethodExit(opcode: Int) {
                        if (opcode != ATHROW) {
                            invokeHook(TRAMPOLINE_LEAVE_METHOD)
                        }
                    }

                    override fun visitMaxs(maxStack: Int, maxLocals: Int) {
                        // visitMaxs is the first method called after visiting all instructions.
                        mv.visitLabel(methodEnd)

                        // We wrap the method in a try-catch block in order to
                        // invoke the exit hook even when an exception is thrown.
                        //
                        // The ASM library claims to require that visitTryCatchBlock be called
                        // *before* its arguments are visited, but doing that would place the
                        // handler at the beginning of the exception table, thus incorrectly
                        // taking priority over user catch blocks. Fortunately, violating the
                        // ASM requirement seems to work, despite breaking the ASM verifier...
                        mv.visitTryCatchBlock(methodStart, methodEnd, methodEnd, null)
                        invokeHook(TRAMPOLINE_LEAVE_METHOD)
                        mv.visitInsn(ATHROW) // Rethrow.

                        mv.visitMaxs(maxStack, maxLocals)
                    }

                    fun invokeHook(hook: Method) {
                        mv.visitLdcInsn(id)
                        mv.visitMethodInsn(
                            INVOKESTATIC,
                            TRAMPOLINE_CLASS_NAME,
                            hook.name,
                            hook.descriptor,
                            false
                        )
                    }
                }
            }
        }

        reader.accept(classVisitor, SKIP_FRAMES)
        return writer.toByteArray()
    }
}
