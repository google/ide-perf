package com.android.tools.idea.diagnostics.instrumentation

import com.android.tools.idea.diagnostics.agent.MethodListener

interface ClassFilter {
    /**
     * Returns a method filter if the given class should be instrumented, otherwise null.
     * Class names are in internal JVM form (e.g., java/lang/Object).
     * Implementations must be thread-safe.
     */
    fun getMethodFilter(className: String): MethodFilter?
}

interface MethodFilter {
    /**
     * Returns an integer id if the given method should be instrumented, otherwise null.
     * The returned integer id will later be passed to [MethodListener] events.
     * Implementations must be thread-safe if shared between invocations of [ClassFilter.getMethodFilter].
     */
    fun getMethodId(methodName: String): Int?
}
