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

import com.google.idea.perf.util.GlobMatcher

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

    fun matches(m: MethodFqName): Boolean {
        return classMatcher.matches(m.clazz) &&
                methodMatcher.matches(m.method) &&
                descMatcher.matches(m.desc)
    }

    fun mightMatchMethodInClass(className: String): Boolean {
        return classMatcher.matches(className)
    }

    fun matchesMethodInClass(clazz: ClassMethods): Boolean {
        try {
            if (!classMatcher.matches(clazz.name)) return false

            // getDeclaredMethods() is quite slow, but it seems to be the only option.
            for (method in clazz.declaredMethods) {
                if (methodMatcher.matches(method.name) &&
                    descMatcher.matches(method.descriptor)
                ) {
                    return true
                }
            }

            if (methodMatcher.matches("<init>")) {
                for (c in clazz.declaredConstructors) {
                    if (descMatcher.matches(c)) {
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

    fun appendTraceRequest(methodPattern: MethodFqName, methodConfig: MethodConfig): TraceRequest {
        val matcher = MethodFqMatcher(methodPattern)
        val request = TraceRequest(matcher, methodConfig)
        TracerConfig.appendTraceRequest(request)
        return request
    }

}
