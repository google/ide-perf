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

package com.google.idea.perf.tracer

import com.intellij.openapi.diagnostic.Logger
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassReader.EXPAND_FRAMES
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ClassWriter.COMPUTE_MAXS
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Opcodes.ASM8
import org.objectweb.asm.Type
import org.objectweb.asm.commons.AdviceAdapter
import org.objectweb.asm.commons.Method
import org.objectweb.asm.util.CheckMethodAdapter
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain
import kotlin.reflect.jvm.javaMethod

/** Inserts calls (within JVM byte code) to the trampoline. */
class TracerClassFileTransformer: ClassFileTransformer {

    companion object {
        private val LOG = Logger.getInstance(TracerClassFileTransformer::class.java)
        private const val ASM_API = ASM8

        private val THROWABLE_TYPE = Type.getType(Throwable::class.java).internalName
        private val TRAMPOLINE_TYPE = Type.getType(TracerTrampoline::class.java).internalName
        private val TRAMPOLINE_ENTER_METHOD = Method.getMethod(TracerTrampoline::enter.javaMethod)
        private val TRAMPOLINE_LEAVE_METHOD = Method.getMethod(TracerTrampoline::leave.javaMethod)

        // Set to true to enable bytecode verification.
        private const val DEBUG = false
    }

    override fun transform(
        loader: ClassLoader?,
        classJvmName: String,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classfileBuffer: ByteArray
    ): ByteArray? {
        return try {
            val className = classJvmName.replace('/', '.')
            if (!TracerConfig.shouldInstrumentClass(className)) return null
            tryTransform(className, classfileBuffer)
        }
        catch (e: Throwable) {
            LOG.error("Failed to instrument class $classJvmName", e)
            throw e
        }
    }

    private fun tryTransform(
        className: String,
        classBytes: ByteArray
    ): ByteArray? {
        val reader = ClassReader(classBytes)

        // Note: we avoid using COMPUTE_FRAMES because that would force ClassWriter to use
        // reflection. Reflection triggers class loading (undesirable) and can also fail outright
        // if it is using the wrong classloader. See ClassWriter.getCommonSuperClass().
        val writer = ClassWriter(reader, COMPUTE_MAXS)

        val classVisitor = object: ClassVisitor(ASM_API, writer) {
            override fun visitMethod(
                access: Int,
                method: String,
                desc: String,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor? {
                var methodWriter = cv.visitMethod(access, method, desc, signature, exceptions)
                val methodFqName = MethodFqName(className, method, desc)
                val traceData = TracerConfig.getMethodTraceData(methodFqName) ?: return methodWriter
                if (!traceData.config.enabled) {
                    return methodWriter
                }

                val parameterTypes = Type.getArgumentTypes(desc)
                val tracedParams = traceData.config.tracedParams.filter {
                    it >= 0 && it < parameterTypes.size
                }

                @Suppress("ConstantConditionIf")
                if (DEBUG) {
                    methodWriter = CheckMethodAdapter(methodWriter)
                }

                return object: AdviceAdapter(ASM_API, methodWriter, access, method, desc) {
                    val methodStart = Label()
                    val methodEnd = Label()

                    override fun onMethodEnter() {
                        // Note: AdviceAdapter has a comment, "For constructors... onMethodEnter()
                        // is called after each super class constructor call, because the object
                        // cannot be used before it is properly initialized." In particular, it
                        // seems we cannot wrap the super class constructor call inside our
                        // try-finally block because then the JVM complains about seeing
                        // "uninitialized this" in the stack map. So, we have to live with a
                        // compromise: traced constructors will exclude the time spent in their
                        // super class constructor calls. This seems acceptable for now.
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
                        // For more details see https://gitlab.ow2.org/asm/asm/-/issues/317617.
                        // The comments there suggest another workaround is to use the tree API,
                        // so we can explore that route too if needed.
                        mv.visitTryCatchBlock(methodStart, methodEnd, methodEnd, THROWABLE_TYPE)
                        mv.visitFrame(Opcodes.F_NEW, 0, emptyArray(), 1, arrayOf(THROWABLE_TYPE))
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
                            Type.CHAR_TYPE -> boxPrimitive(index, ILOAD, "Character", "(C)Ljava/lang/Character;")
                            Type.DOUBLE_TYPE -> boxPrimitive(index, DLOAD, "Double", "(D)Ljava/lang/Double;")
                            Type.FLOAT_TYPE -> boxPrimitive(index, FLOAD, "Float", "(F)Ljava/lang/Float;")
                            Type.INT_TYPE -> boxPrimitive(index, ILOAD, "Integer", "(I)Ljava/lang/Integer;")
                            Type.LONG_TYPE -> boxPrimitive(index, LLOAD, "Long", "(J)Ljava/lang/Long;")
                            Type.SHORT_TYPE -> boxPrimitive(index, ILOAD, "Short", "(S)Ljava/lang/Short;")
                            Type.BOOLEAN_TYPE -> boxPrimitive(index, ILOAD, "Boolean", "(Z)Ljava/lang/Boolean;")
                            else -> mv.visitVarInsn(ALOAD, index)
                        }
                    }

                    private fun loadArgSet() {
                        val arraySize = tracedParams.size

                        if (arraySize == 0) {
                            mv.visitInsn(ACONST_NULL)
                            return
                        }

                        // Create new Object[] for the arguments.
                        mv.visitLdcInsn(arraySize)
                        mv.visitTypeInsn(ANEWARRAY, Type.getInternalName(Any::class.java))

                        var parameterBaseIndex = if (access and Opcodes.ACC_STATIC == 0) 1 else 0
                        var storeIndex = 0
                        for ((parameterIndex, parameterType) in parameterTypes.withIndex()) {
                            if (tracedParams.contains(parameterIndex)) {
                                // args[storeIndex] = loadArg(parameterIndex)
                                mv.visitInsn(DUP)
                                mv.visitLdcInsn(storeIndex)
                                loadArg(parameterBaseIndex + parameterIndex, parameterType)
                                mv.visitInsn(AASTORE)
                                ++storeIndex
                            }
                            if (parameterType == Type.LONG_TYPE || parameterType == Type.DOUBLE_TYPE) {
                                // These parameter types take up an extra stack slot.
                                ++parameterBaseIndex
                            }
                        }
                    }

                    private fun invokeEnter() {
                        mv.visitLdcInsn(traceData.methodId)
                        loadArgSet()

                        mv.visitMethodInsn(
                            INVOKESTATIC,
                            TRAMPOLINE_TYPE,
                            TRAMPOLINE_ENTER_METHOD.name,
                            TRAMPOLINE_ENTER_METHOD.descriptor,
                            false
                        )
                    }

                    private fun invokeLeave() {
                        mv.visitMethodInsn(
                            INVOKESTATIC,
                            TRAMPOLINE_TYPE,
                            TRAMPOLINE_LEAVE_METHOD.name,
                            TRAMPOLINE_LEAVE_METHOD.descriptor,
                            false
                        )
                    }
                }
            }
        }

        // We have to use EXPAND_FRAMES because AdviceAdapter requires it.
        // If performance is insufficient we can look into changing this.
        reader.accept(classVisitor, EXPAND_FRAMES)

        return writer.toByteArray()
    }
}
