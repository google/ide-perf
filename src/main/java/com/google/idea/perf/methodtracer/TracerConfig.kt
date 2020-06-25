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

import com.google.idea.perf.agent.MethodListener
import com.google.idea.perf.util.ConcurrentAppendOnlyList
import org.objectweb.asm.Type
import java.lang.reflect.Method
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// Things to improve:
// - Somehow gc or recycle Tracepoints that are no longer used.
// - Pretty-print method descriptors for better UX.

/** Keeps track of which methods should be traced via bytecode instrumentation. */
object TracerConfig {
    private val tracepoints = ConcurrentAppendOnlyList<Tracepoint>()
    private val lock = ReentrantLock() // Protects the data structures below.
    private val classConfigs = mutableMapOf<String, ClassConfig>() // Keyed by 'JVM' class name.

    private class TracepointProperties(
        val flags: Int = 0,
        val parameters: Int = 0
    ) {
        companion object {
            val DEFAULT = TracepointProperties()
        }
    }

    /** Specifies which methods to instrument for a given class. */
    private class ClassConfig {
        /** Set of simple method names to instrumental and their associated properties. */
        val methodNames = mutableMapOf<String, TracepointProperties>()

        /** Map from method signature to method ID. */
        val methodIds = mutableMapOf<String, Int>()
    }

    fun traceMethods(
        classJvmName: String,
        methodName: String,
        flags: Int,
        parameters: Collection<Int>
    ) {
        var parameterBits = 0
        for (index in parameters) {
            parameterBits = parameterBits or (1 shl index)
        }

        lock.withLock {
            val classConfig = classConfigs.getOrPut(classJvmName) { ClassConfig() }
            classConfig.methodNames.put(methodName, TracepointProperties(flags, parameterBits))

            // If the tracepoint already exists, set tracepoint properties.
            for ((signature, methodId) in classConfig.methodIds) {
                if (signature.substringBefore('(') == methodName) {
                    val tracepoint = getTracepoint(methodId)
                    tracepoint.setFlags(TracepointFlags.TRACE_ALL)
                    tracepoint.parameters.set(parameterBits)
                }
            }
        }
    }

    fun traceMethod(method: Method) {
        lock.withLock {
            val classJvmName = method.declaringClass.name.replace('.', '/')
            val methodDesc = Type.getMethodDescriptor(method)
            traceMethod(classJvmName, method.name, methodDesc)
        }
    }

    private fun traceMethod(classJvmName: String, methodName: String, methodDesc: String) {
        lock.withLock {
            val classConfig = classConfigs.getOrPut(classJvmName) { ClassConfig() }
            val methodSignature = "$methodName$methodDesc"
            val tracepoint = createTracepoint(
                classJvmName, methodName, methodDesc, TracepointProperties.DEFAULT
            )
            classConfig.methodIds.getOrPut(methodSignature) { tracepoints.append(tracepoint) }
        }
    }

    fun untraceMethods(classJvmName: String, methodName: String) {
        lock.withLock {
            val classConfig = classConfigs[classJvmName] ?: return

            for ((signature, _) in classConfig.methodIds) {
                if (signature.substringBefore('(') == methodName) {
                    val methodDesc = signature.substring(methodName.length)
                    val methodId = getMethodId(classJvmName, methodName, methodDesc)
                    if (methodId != null) {
                        val tracepoint = getTracepoint(methodId)
                        tracepoint.unsetFlags(TracepointFlags.TRACE_ALL)
                        tracepoint.parameters.set(0)
                    }
                }
            }
        }
    }

    fun untraceMethod(method: Method) {
        lock.withLock {
            val classJvmName = method.declaringClass.name.replace('.', '/')
            val methodDesc = Type.getMethodDescriptor(method)
            untraceMethod(classJvmName, method.name, methodDesc)
        }
    }

    private fun untraceMethod(classJvmName: String, methodName: String, methodDesc: String) {
        lock.withLock {
            val methodId = getMethodId(classJvmName, methodName, methodDesc)
            if (methodId != null) {
                val tracepoint = getTracepoint(methodId)
                tracepoint.unsetFlags(TracepointFlags.TRACE_ALL)
                tracepoint.parameters.set(0)
            }
        }
    }

    /** Remove all tracing and return the affected class names. */
    fun removeAllTracing(): List<String> {
        lock.withLock {
            val classNames = classConfigs.keys.map { it.replace('/', '.') }
            classConfigs.clear()
            return classNames
        }
    }

    fun shouldInstrumentClass(classJvmName: String): Boolean {
        lock.withLock {
            val classConfig = classConfigs[classJvmName] ?: return false
            return classConfig.methodNames.isNotEmpty() || classConfig.methodIds.isNotEmpty()
        }
    }

    /**
     * Returns the method ID to be used for [MethodListener] events,
     * or null if the given method should not be instrumented.
     */
    fun getMethodId(classJvmName: String, methodName: String, methodDesc: String): Int? {
        lock.withLock {
            val classConfig = classConfigs[classJvmName] ?: return null
            val methodSignature = "$methodName$methodDesc"

            val existingId = classConfig.methodIds[methodSignature]
            if (existingId != null) {
                return existingId
            }

            val properties = classConfig.methodNames[methodName]
            if (properties != null) {
                val tracepoint = createTracepoint(classJvmName, methodName, methodDesc, properties)
                val newId = tracepoints.append(tracepoint)
                classConfig.methodIds[methodSignature] = newId
                return newId
            }

            return null
        }
    }

    fun getTracepoint(methodId: Int): Tracepoint = tracepoints.get(methodId)

    private fun createTracepoint(
        classJvmName: String,
        methodName: String,
        methodDesc: String,
        properties: TracepointProperties
    ): Tracepoint {
        val classShortName = classJvmName.substringAfterLast('/')
        val className = classJvmName.replace('/', '.')
        return Tracepoint(
            displayName = "$classShortName.$methodName()",
            description = "$className#$methodName$methodDesc",
            flags = properties.flags,
            parameters = properties.parameters
        )
    }
}
