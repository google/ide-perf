package com.android.tools.idea.diagnostics.instrumentation

import com.android.tools.idea.diagnostics.agent.MethodListener

/**
 * Used by [MethodTracingTransformer] to decide which classes and methods to instrument.
 * Implementations must be thread-safe because class loading happens in parallel.
 */
interface MethodFilter {
    /**
     * Returns true if the given class should be instrumented.
     * Class names are in internal JVM form (e.g., java/lang/Object).
     */
    fun shouldInstrumentClass(className: String): Boolean

    /**
     * Returns a method id if the given method should be instrumented, otherwise returns null.
     * The integer id will later be passed to [MethodListener] events.
     */
    fun getMethodId(className: String, methodName: String): Int?
}
