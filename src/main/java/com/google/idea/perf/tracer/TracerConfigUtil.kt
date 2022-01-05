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

import com.google.idea.perf.AgentLoader
import com.google.idea.perf.util.GlobMatcher
import com.intellij.openapi.progress.ProgressManager
import org.objectweb.asm.Type

class TraceRequest(
    val matcher: MethodFqMatcher,
    val config: MethodConfig,
)

data class MethodFqName(
    val clazz: String, // Fully qualified class name or glob pattern.
    val method: String,
    val desc: String, // See Type.getMethodDescriptor in the ASM library.
)

class MethodConfig(
    val enabled: Boolean = true, // Whether the method should be traced.
    val countOnly: Boolean = false, // Whether to skip measuring wall time.
    val tracedParams: List<Int> = emptyList(), // Support for parameter tracing.
)

class MethodTraceData(
    val methodId: Int,
    val config: MethodConfig,
)

class MethodFqMatcher(methodPattern: MethodFqName) {
    private val classMatcher = GlobMatcher.create(methodPattern.clazz)
    private val methodMatcher = GlobMatcher.create(methodPattern.method)
    private val descMatcher = GlobMatcher.create(methodPattern.desc)
    private val pattern = methodPattern

    fun matches(m: MethodFqName): Boolean {
        return classMatcher.matches(m.clazz) &&
                methodMatcher.matches(m.method) &&
                descMatcher.matches(m.desc)
    }

    fun matchClassExact(className: String): Boolean {
        return pattern.clazz == className
    }

    fun mightMatchMethodInClass(className: String): Boolean {
        return classMatcher.matches(className)
    }

    fun matchesMethodInClass(clazz: Class<*>): Boolean {
        try {
            if (!classMatcher.matches(clazz.name)) return false

            // getDeclaredMethods() is quite slow, but it seems to be the only option.
            for (m in clazz.declaredMethods) {
                if (methodMatcher.matches(m.name) &&
                    descMatcher.matches(Type.getMethodDescriptor(m))
                ) {
                    return true
                }
            }

            if (methodMatcher.matches("<init>")) {
                for (c in clazz.declaredConstructors) {
                    if (descMatcher.matches(Type.getConstructorDescriptor(c))) {
                        return true
                    }
                }
            }

            return false
        }
        catch (ignored: Throwable) {
            // We are interacting with arbitrary user classes, so exceptions like
            // NoClassDefFoundError may be thrown in certain corner cases.
            return false
        }
    }
}

object TracerConfigUtil {

    fun appendTraceRequest(methodPattern: MethodFqName, methodConfig: MethodConfig): List<TraceRequest> {
        val matcher = MethodFqMatcher(methodPattern)
        val traceRequests = mutableListOf(TraceRequest(matcher, methodConfig))

        //trace all overriding methods if class name match exactly
        val baseClass = AgentLoader.instrumentation?.allLoadedClasses?.firstOrNull() {
            matcher.matchClassExact(it.name)
        }
        if (baseClass != null) {
            AgentLoader.instrumentation?.allLoadedClasses
                ?.filter { baseClass.isAssignableFrom(it) }
                ?.forEach {
                    val childPattern =
                        MethodFqName(it.name, methodPattern.method, methodPattern.desc)
                    val childRequest = TraceRequest(MethodFqMatcher(childPattern), methodConfig)
                    traceRequests += childRequest
                }
        }
        traceRequests.forEach(TracerConfig::appendTraceRequest)
        return traceRequests
    }

    // This may be slow if there are many trace requests or if they use broad glob patterns.
    fun getAffectedClasses(traceRequests: Collection<TraceRequest>): List<Class<*>> {
        if (traceRequests.isEmpty()) return emptyList()
        val instrumentation = AgentLoader.instrumentation ?: return emptyList()

        fun classMightBeAffected(clazz: Class<*>): Boolean {
            ProgressManager.checkCanceled()
            return traceRequests.any { it.matcher.matchesMethodInClass(clazz) }
        }

        return instrumentation.allLoadedClasses.filter(::classMightBeAffected)
    }
}
