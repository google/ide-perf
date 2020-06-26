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

package com.google.idea.perf.methodtracer

import com.google.idea.perf.agent.Argument
import com.google.idea.perf.agent.Trampoline
import com.intellij.openapi.diagnostic.Logger
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassReader.SKIP_FRAMES
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ClassWriter.COMPUTE_FRAMES
import org.objectweb.asm.ClassWriter.COMPUTE_MAXS
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.Opcodes.ASM8
import org.objectweb.asm.Type
import org.objectweb.asm.commons.AdviceAdapter
import org.objectweb.asm.commons.Method
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain
import kotlin.reflect.jvm.javaConstructor
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

        private val ARGUMENT_NAME = Type.getType(Argument::class.java).internalName
        private val ARGUMENT_CONSTRUCTOR = Method.getMethod(Argument::class.constructors.first().javaConstructor)
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
        val writer = ClassWriter(reader, COMPUTE_MAXS or COMPUTE_FRAMES)

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
                val tracepoint = TracerConfig.getTracepoint(id)
                val parameters = tracepoint.parameters.get()
                val parameterTypes = Type.getArgumentTypes(desc)
                val parameterBaseIndex = if (access and ACC_STATIC == 0) 1 else 0

                if (!tracepoint.isEnabled) {
                    return methodWriter
                }

                return object : AdviceAdapter(ASM_API, methodWriter, access, method, desc) {
                    val methodStart = Label()
                    val methodEnd = Label()

                    override fun onMethodEnter() {
                        invokeEnter()
                        mv.visitLabel(methodStart)
                    }

                    override fun onMethodExit(opcode: Int) {
                        if (opcode != ATHROW) {
                            invokeLeave()
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
                        invokeLeave()
                        mv.visitInsn(ATHROW) // Rethrow.

                        mv.visitMaxs(maxStack, maxLocals)
                    }

                    private fun boxPrimitive(index: Int, opcode: Int, owner: String, descriptor: String) {
                        mv.visitVarInsn(opcode, index)
                        mv.visitMethodInsn(INVOKESTATIC, "java/lang/$owner", "valueOf", descriptor, false)
                    }

                    private fun loadArg(index: Int, parameterType: Type) {
                        when (parameterType) {
                            Type.BYTE_TYPE -> boxPrimitive(index, ILOAD, "Byte", "(B)Ljava/lang/Byte;")
                            Type.CHAR_TYPE -> boxPrimitive(index, ILOAD, "Character", "(C)Ljava/lang/Character")
                            Type.DOUBLE_TYPE -> boxPrimitive(index, DLOAD, "Double", "(D)Ljava/lang/Double")
                            Type.FLOAT_TYPE -> boxPrimitive(index, FLOAD, "Float", "(F)Ljava/lang/Double")
                            Type.INT_TYPE -> boxPrimitive(index, ILOAD, "Integer", "(I)Ljava/lang/Integer")
                            Type.LONG_TYPE -> boxPrimitive(index, LLOAD, "Long", "(J)Ljava/lang/Long")
                            Type.SHORT_TYPE -> boxPrimitive(index, ILOAD, "Short", "(S)Ljava/lang/Short")
                            Type.BOOLEAN_TYPE -> boxPrimitive(index, ILOAD, "Boolean", "(Z)Ljava/lang/Boolean")
                            else -> mv.visitVarInsn(ALOAD, index)
                        }
                    }

                    private fun loadArgSet() {
                        if (parameters == 0) {
                            mv.visitInsn(ACONST_NULL)
                            return
                        }

                        val arraySize = Integer.bitCount(parameters)

                        // Create new Argument[]
                        mv.visitLdcInsn(arraySize)
                        mv.visitTypeInsn(ANEWARRAY, Type.getInternalName(Argument::class.java))

                        var storeIndex = arraySize - 1
                        for ((parameterIndex, parameterType) in parameterTypes.withIndex()) {
                            if (parameters and (1 shl parameterIndex) != 0) {
                                // Pseudocode for:
                                // boxedArg = loadArg(args[parameterIndex])
                                // args[storeIndex] = Argument(boxedArg, parameterIndex)

                                mv.visitInsn(DUP)
                                mv.visitLdcInsn(storeIndex)

                                mv.visitTypeInsn(NEW, ARGUMENT_NAME)
                                mv.visitInsn(DUP)
                                loadArg(parameterBaseIndex + parameterIndex, parameterType)
                                mv.visitLdcInsn(parameterIndex)
                                mv.visitMethodInsn(
                                    INVOKESPECIAL,
                                    ARGUMENT_NAME,
                                    ARGUMENT_CONSTRUCTOR.name,
                                    ARGUMENT_CONSTRUCTOR.descriptor,
                                    false
                                )

                                mv.visitInsn(AASTORE)
                                storeIndex--
                            }
                        }
                    }

                    private fun invokeEnter() {
                        mv.visitLdcInsn(id)
                        loadArgSet()

                        mv.visitMethodInsn(
                            INVOKESTATIC,
                            TRAMPOLINE_CLASS_NAME,
                            TRAMPOLINE_ENTER_METHOD.name,
                            TRAMPOLINE_ENTER_METHOD.descriptor,
                            false
                        )
                    }

                    private fun invokeLeave() {
                        mv.visitLdcInsn(id)
                        mv.visitMethodInsn(
                            INVOKESTATIC,
                            TRAMPOLINE_CLASS_NAME,
                            TRAMPOLINE_LEAVE_METHOD.name,
                            TRAMPOLINE_LEAVE_METHOD.descriptor,
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
