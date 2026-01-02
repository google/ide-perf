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

import com.google.idea.perf.agent.TracerTrampoline
import com.intellij.openapi.diagnostic.Logger
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassReader.EXPAND_FRAMES
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.ClassWriter.COMPUTE_MAXS
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.Opcodes.ASM9
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.commons.AdviceAdapter
import org.jetbrains.org.objectweb.asm.commons.Method
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain
import kotlin.reflect.jvm.javaMethod

private const val ASM_API = ASM9

/**
 * [TracerClassFileTransformer] inserts calls (in JVM byte code) to the tracer hooks.
 * It consults [TracerConfig] to figure out which classes to transform.
 */
class TracerClassFileTransformer : ClassFileTransformer {

    override fun transform(
        loader: ClassLoader?,
        classJvmName: String,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classfileBuffer: ByteArray,
    ): ByteArray? {
        try {
            val className = classJvmName.replace('/', '.')
            if (TracerConfig.shouldInstrumentClass(className)) {
                return tryTransform(className, classfileBuffer)
            } else {
                return null
            }
        }
        catch (e: Throwable) {
            Logger.getInstance(javaClass).error("Failed to instrument class $classJvmName", e)
            return null
        }
    }

    // Debugging tip: the JVM verifier will only give you good error messages if the VerifyError
    // happens during the *initial* class load (not during class retransformations).
    private fun tryTransform(
        clazz: String,
        classBytes: ByteArray
    ): ByteArray? {
        // Note: we avoid using COMPUTE_FRAMES because that causes ClassWriter to use
        // reflection. Reflection triggers class loading (undesirable) and can also fail outright
        // if it is using the wrong classloader. See ClassWriter.getCommonSuperClass().
        val reader = ClassReader(classBytes)
        val writer = ClassWriter(reader, COMPUTE_MAXS)
        val classVisitor = TracerClassVisitor(clazz, writer)
        reader.accept(classVisitor, EXPAND_FRAMES) // EXPAND_FRAMES is required by AdviceAdapter.
        return when {
            classVisitor.transformedSomeMethods -> writer.toByteArray()
            else -> null
        }
    }
}

class TracerClassVisitor(
    private val clazz: String,
    writer: ClassVisitor,
) : ClassVisitor(ASM_API, writer) {
    var transformedSomeMethods = false

    override fun visitMethod(
        access: Int, method: String, desc: String, signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor? {
        val methodWriter = super.visitMethod(access, method, desc, signature, exceptions)
        val methodFqName = MethodFqName(clazz, method, desc)
        val traceData = TracerConfig.getMethodTraceData(methodFqName)
        if (traceData != null && traceData.config.enabled) {
            transformedSomeMethods = true
            return TracerMethodVisitor(methodWriter, traceData, clazz, method, desc, access)
        } else {
            return methodWriter
        }
    }
}

class TracerMethodVisitor(
    methodWriter: MethodVisitor,
    private val traceData: MethodTraceData,
    private val clazz: String,
    private val method: String,
    private val desc: String,
    access: Int,
) : AdviceAdapter(ASM_API, methodWriter, access, method, desc) {

    companion object {
        private val LOG = Logger.getInstance(TracerMethodVisitor::class.java)
        private val OBJECT_TYPE = Type.getType(Any::class.java)
        private val THROWABLE_TYPE = Type.getType(Throwable::class.java)
        private val TRAMPOLINE_TYPE = Type.getType(TracerTrampoline::class.java)
        private val TRAMPOLINE_ENTER_METHOD = Method.getMethod(TracerTrampoline::enter.javaMethod)
        private val TRAMPOLINE_LEAVE_METHOD = Method.getMethod(TracerTrampoline::leave.javaMethod)
    }

    private val methodStart = newLabel()

    // For constructors with control flow prior to the super class constructor call,
    // AdviceAdapter will sometimes fail to call onMethodEnter(). In those cases it
    // is better to bail out completely than have incorrect tracer hooks.
    private var methodEntered = false

    override fun onMethodEnter() {
        // Note: AdviceAdapter has a comment, "For constructors... onMethodEnter() is
        // called after each super class constructor call, because the object
        // cannot be used before it is properly initialized." In particular, it
        // seems we cannot wrap the super class constructor call inside our
        // try-catch block because then the JVM complains about seeing
        // "uninitialized this" in the stack map. So, we have to live with a
        // compromise: traced constructors will exclude the time spent in their
        // super class constructor calls. This seems acceptable for now.
        push(traceData.methodId)
        loadTracedArgs()
        invokeStatic(TRAMPOLINE_TYPE, TRAMPOLINE_ENTER_METHOD)
        methodEntered = true
        mark(methodStart)
    }

    override fun onMethodExit(opcode: Int) {
        if (methodEntered && opcode != ATHROW) {
            invokeStatic(TRAMPOLINE_TYPE, TRAMPOLINE_LEAVE_METHOD)
        }
    }

    override fun visitMaxs(maxStack: Int, maxLocals: Int) {
        // Note: visitMaxs() is the first method called after visiting all instructions.
        if (methodEntered) {
            buildCatchBlock()
        } else {
            val fqName = "$clazz.$method$desc"
            LOG.warn("Unable to instrument $fqName because ASM failed to call onMethodEnter")
        }
        super.visitMaxs(maxStack, maxLocals)
    }

    // Surrounds the entire method body with a try-catch block to ensure that the
    // exit hook is called even if exceptions are thrown.
    private fun buildCatchBlock() {
        // Technically, ASM requires that visitTryCatchBlock() is called *before* its start
        // label is visited. However, we do not want to call visitTryCatchBlock() at the start
        // of the method because that would place the handler at the beginning of the exception
        // table, thus incorrectly taking priority over preexisting catch blocks.
        // For more details see https://gitlab.ow2.org/asm/asm/-/issues/317617.
        //
        // There are a few ways to work around this:
        //   1. Use the ASM tree API, which can directly mutate the exception table.
        //   2. Replace the method body with a single try-catch block surrounding
        //      a call to a synthetic method containing the original body.
        //   3. Ignore what ASM says and just visit the labels out of order.
        //
        // Option 1 seems more complex than we want, and option 2 seems invasive. So we
        // choose option 3 for now, which---despite breaking the ASM verifier---seems to
        // work fine. In fact, option 3 is what everyone else seems to do, including the author
        // of AdviceAdapter (see the 2007 paper "Using the ASM framework to implement common
        // Java bytecode transformation patterns" from Eugene Kuleshov).
        catchException(methodStart, mark(), THROWABLE_TYPE)
        visitFrame(Opcodes.F_NEW, 0, emptyArray(), 1, arrayOf(THROWABLE_TYPE.internalName))
        invokeStatic(TRAMPOLINE_TYPE, TRAMPOLINE_LEAVE_METHOD)
        throwException() // Rethrow.
    }

    private fun loadTracedArgs() {
        val rawTracedParams = traceData.config.tracedParams
        val tracedParams = rawTracedParams.filter(argumentTypes.indices::contains)
        if (tracedParams.size < rawTracedParams.size) {
            val fqName = "$clazz.$method$desc"
            LOG.warn("Some arg indices are out of bounds for method $fqName: $rawTracedParams")
        }

        if (tracedParams.isEmpty()) {
            visitInsn(ACONST_NULL)
            return
        }

        // See the similar code in GeneratorAdapter.loadArgArray().
        push(tracedParams.size)
        newArray(OBJECT_TYPE)
        for ((storeIndex, paramIndex) in tracedParams.withIndex()) {
            dup()
            push(storeIndex)
            loadArg(paramIndex)
            box(argumentTypes[paramIndex])
            arrayStore(OBJECT_TYPE)
        }
    }
}
