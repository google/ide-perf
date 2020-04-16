package com.android.tools.idea.diagnostics

import com.android.tools.idea.diagnostics.agent.MethodListener

/**
 * Used by [MethodTracingTransformer] to decide which classes and methods to instrument.
 * Implementations must be thread-safe because class loading happens in parallel.
 */
interface MethodFilter {
    /** Returns true if the given class should be instrumented. */
    fun shouldInstrumentClass(classJvmName: String): Boolean

    /**
     * Returns a method id if the given method should be instrumented, otherwise returns null.
     * The integer id will later be passed to [MethodListener] events.
     */
    fun getMethodId(classJvmName: String, methodName: String, methodDesc: String): Int?
}
