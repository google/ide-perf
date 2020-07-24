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

import com.google.idea.perf.AgentLoader
import com.google.idea.perf.util.ConcurrentAppendOnlyList
import com.intellij.util.PatternUtil
import org.objectweb.asm.Type
import java.lang.reflect.Method
import java.util.concurrent.locks.ReentrantLock
import java.util.regex.Pattern
import kotlin.concurrent.withLock

// Things to improve:
// - Somehow gc or recycle Tracepoints that are no longer used.
// - Pretty-print method descriptors for better UX.

sealed class TracePattern {
    data class Exact(val method: Method): TracePattern()
    data class ByMethodName(val className: String, val methodName: String): TracePattern()
    data class ByMethodPattern(val className: String, val methodPattern: String): TracePattern()
    data class ByClassPattern(val classPattern: String): TracePattern()
}

/** Keeps track of which methods should be traced via bytecode instrumentation. */
object TracerConfig {
    private val tracepoints = ConcurrentAppendOnlyList<Tracepoint>()
    private val lock = ReentrantLock() // Protects the data structures below.
    private val classConfigs = mutableMapOf<String, ClassConfig>() // Keyed by 'JVM' class name.

    private class TracepointProperties(
        val flags: Int = TracepointFlags.TRACE_ALL,
        val parameters: Int = 0
    ) {
        companion object {
            val DEFAULT = TracepointProperties()
        }
    }

    /** Specifies which methods to instrument for a given class. */
    private class ClassConfig {
        /**
         * List of trace commands to execute before the class config is fully loaded with method IDs.
         * If this field is null, then method IDs within the class config have been fully loaded.
         */
        var commands: MutableList<Pair<Pattern, TracepointProperties>>? = mutableListOf()

        /**
         * Map from method signature to method ID. A null value indicates that the method is not
         * associated with an initialized tracepoint.
         */
        val methodIds = mutableMapOf<String, Int?>()
    }

    fun trace(
        pattern: TracePattern,
        flags: Int = TracepointFlags.TRACE_ALL,
        parameters: Collection<Int> = emptyList()
    ): List<String> {
        return when (pattern) {
            is TracePattern.Exact -> setTrace(true, pattern.method, flags, parameters)
            is TracePattern.ByMethodName -> setTrace(true, pattern.className, pattern.methodName, flags, parameters)
            is TracePattern.ByMethodPattern -> setTrace(true, pattern.className, pattern.methodPattern, flags, parameters)
            is TracePattern.ByClassPattern -> setTrace(true, pattern.classPattern, flags, parameters)
        }
    }

    fun untrace(pattern: TracePattern): List<String> {
        return when(pattern) {
            is TracePattern.Exact -> setTrace(false, pattern.method)
            is TracePattern.ByMethodName -> setTrace(false, pattern.className, pattern.methodName)
            is TracePattern.ByMethodPattern -> setTrace(false, pattern.className, pattern.methodPattern)
            is TracePattern.ByClassPattern -> setTrace(false, pattern.classPattern)
        }
    }

    private fun setTrace(
        enable: Boolean,
        method: Method,
        flags: Int = 0,
        parameters: Collection<Int> = emptyList()
    ): List<String> {
        val className = method.declaringClass.name
        val classJvmName = className.replace('.', '/')
        var parameterBits = 0
        for (index in parameters) {
            parameterBits = parameterBits or (1 shl index)
        }

        val methodDesc = Type.getMethodDescriptor(method)
        val methodSignature = "${method.name}$methodDesc"

        lock.withLock {
            val methodId = if (flags and TracepointFlags.TRACE_ALL == 0) {
                getMethodId(classJvmName, method.name, methodDesc)
            }
            else {
                val classConfig = classConfigs.getOrPut(classJvmName) { ClassConfig() }
                classConfig.methodIds.getOrPut(methodSignature) {
                    val tracepoint = createTracepoint(
                        classJvmName, method.name, methodDesc, TracepointProperties.DEFAULT
                    )
                    tracepoints.append(tracepoint)
                }
            }

            if (methodId != null) {
                val tracepoint = getTracepoint(methodId)
                tracepoint.parameters.set(parameterBits)

                if (enable) {
                    tracepoint.setFlags(flags)
                }
                else {
                    tracepoint.unsetFlags(flags)
                }
            }

            return listOf(className)
        }
    }

    private fun setTrace(
        enable: Boolean,
        className: String,
        methodPattern: String,
        flags: Int = 0,
        parameters: Collection<Int> = emptyList()
    ): List<String> {
        val classJvmName = className.replace('.', '/')
        val methodRegex = Pattern.compile(PatternUtil.convertToRegex(methodPattern))
        var parameterBits = 0
        for (index in parameters) {
            parameterBits = parameterBits or (1 shl index)
        }

        lock.withLock {
            val classConfig = classConfigs.getOrPut(classJvmName) { ClassConfig() }

            // If the class config isn't loaded, enqueue a command.
            classConfig.commands?.add(Pair(methodRegex, TracepointProperties(flags, parameterBits)))

            // If the class config is loaded, set tracepoint properties.
            for (entry in classConfig.methodIds) {
                var (signature, methodId) = entry
                val index = signature.indexOf('(')
                val methodName = signature.substring(0, index)
                val methodDesc = signature.substring(index)

                if (methodId == null) {
                    val tracepoint = createTracepoint(
                        classJvmName, methodName, methodDesc,
                        TracepointProperties(flags, parameterBits)
                    )
                    methodId = tracepoints.append(tracepoint)
                    entry.setValue(methodId)
                }

                if (methodRegex.matcher(methodName).matches()) {
                    val tracepoint = getTracepoint(methodId)
                    tracepoint.parameters.set(parameterBits)
                    if (enable) {
                        tracepoint.setFlags(flags)
                    }
                    else {
                        tracepoint.unsetFlags(TracepointFlags.TRACE_ALL)
                    }
                }
            }

            return listOf(className)
        }
    }

    private fun setTrace(
        enable: Boolean,
        classPattern: String,
        flags: Int = 0,
        parameters: Collection<Int> = emptyList()
    ): List<String> {
        val regex = Pattern.compile(PatternUtil.convertToRegex(classPattern))
        val matcher = regex.matcher("")

        if (enable) {
            val classes = AgentLoader.instrumentation?.allLoadedClasses ?: return emptyList()

            val matchingClasses = mutableListOf<String>()
            for (clazz in classes) {
                val className = clazz.name
                matcher.reset(className)
                if (matcher.matches()) {
                    matchingClasses.add(className)
                }
            }

            lock.withLock {
                for (className in matchingClasses) {
                    setTrace(enable, className, "*", flags, parameters)
                }
            }

            return matchingClasses
        }
        else {
            val matchingClasses = mutableListOf<String>()

            lock.withLock {
                for ((classJvmName, _) in classConfigs) {
                    val className = classJvmName.replace('/', '.')
                    matcher.reset(className)

                    if (matcher.matches()) {
                        matchingClasses.add(className)
                    }
                }

                for (className in matchingClasses) {
                    setTrace(enable, className, "*", flags, parameters)
                }
            }

            return matchingClasses
        }
    }

    /** Remove all tracing and return the affected class names. */
    fun untraceAll(): List<String> {
        lock.withLock {
            val classNames = classConfigs.keys.map { it.replace('/', '.') }
            classConfigs.clear()
            return classNames
        }
    }

    fun shouldInstrumentClass(classJvmName: String): Boolean {
        lock.withLock {
            val classConfig = classConfigs[classJvmName] ?: return false
            return (classConfig.commands?.isNotEmpty() ?: true) || classConfig.methodIds.isNotEmpty()
        }
    }

    fun applyCommands(classJvmName: String, methodSignatures: Collection<String>) {
        lock.withLock {
            val classConfig = classConfigs[classJvmName] ?: return
            val commands = classConfig.commands

            if (commands != null) {
                for (signature in methodSignatures) {
                    classConfig.methodIds.putIfAbsent(signature, null)
                }

                for ((pattern, properties) in commands) {
                    for (signature in methodSignatures) {
                        val index = signature.indexOf('(')
                        val methodName = signature.substring(0, index)
                        val methodDesc = signature.substring(index)
                        if (pattern.matcher(methodName).matches()) {
                            putMethodId(classJvmName, methodName, methodDesc, properties)
                        }
                    }
                }

                classConfig.commands = null
            }
        }
    }

    /**
     * Returns the method ID to be used for [MethodTracerHook] events,
     * or null if the given method should not be instrumented.
     */
    fun getMethodId(classJvmName: String, methodName: String, methodDesc: String): Int? {
        lock.withLock {
            val classConfig = classConfigs[classJvmName] ?: return null
            val methodSignature = "$methodName$methodDesc"
            return classConfig.methodIds[methodSignature]
        }
    }

    private fun putMethodId(
        classJvmName: String, methodName: String, methodDesc: String,
        properties: TracepointProperties
    ): Int? {
        lock.withLock {
            val classConfig = classConfigs[classJvmName] ?: return null
            val methodSignature = "$methodName$methodDesc"
            val existingId = classConfig.methodIds[methodSignature]
            if (existingId != null) {
                getTracepoint(existingId).flags.set(properties.flags)
                getTracepoint(existingId).parameters.set(properties.parameters)
                return existingId
            }

            val tracepoint = createTracepoint(classJvmName, methodName, methodDesc, properties)
            val newId = tracepoints.append(tracepoint)
            classConfig.methodIds[methodSignature] = newId
            return newId
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

    fun getTracedClassNames(): List<String> {
        val classNames = lock.withLock { classConfigs.keys.toList() }
        return classNames.map { it.replace('/', '.') }
    }
}
