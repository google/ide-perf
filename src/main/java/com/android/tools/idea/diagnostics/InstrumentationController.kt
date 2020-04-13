package com.android.tools.idea.diagnostics

import com.android.tools.idea.diagnostics.agent.AgentMain
import com.android.tools.idea.diagnostics.agent.MethodListener
import com.android.tools.idea.diagnostics.agent.Trampoline
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.ConcurrentHashMap

// Things to improve:
// - Fail gracefully if agent not available.
// - Try to avoid querying 'instrumentation.allLoadedClasses' for every call to instrumentMethod().
// - Account for full method signature, not just name.
// - Add some logging.
// - Catch some more exceptions thrown by Instrumentation.
// - Support line-number-based tracepoints.
// - What happens if a class is being loaded *during* the call to instrumentMethod()?
// - Consider adding support for filtering by method signature, not just name.

/**
 * Instruments Java methods with tracing hooks and delegates tracing events to [CallTreeManager].
 * This is the only class that should interact with the agent directly.
 */
object InstrumentationController {
    private val LOG = Logger.getInstance(InstrumentationController::class.java)
    private val instrumentation = AgentMain.savedInstrumentationInstance
    private val instrumentedTracepoints = ConcurrentAppendOnlyList<Tracepoint>()

    /**
     * Map: className -> methodName -> id
     * Class names are in internal JVM form (e.g., java/lang/Object).
     */
    private val methodsToInstrument = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()

    init {
        Trampoline.methodListener = MyMethodListener()
        instrumentation.addTransformer(MethodTracingTransformer(MyMethodFilter()), true)
    }

    /** Dispatches method entry/exit events to the [CallTreeManager]. */
    private class MyMethodListener : MethodListener {
        override fun enter(methodId: Int): Unit = CallTreeManager.enter(instrumentedTracepoints.get(methodId))
        override fun leave(methodId: Int): Unit = CallTreeManager.leave(instrumentedTracepoints.get(methodId))
    }

    /** Called by [MethodTracingTransformer] to decide which classes and methods to instrument. */
    private class MyMethodFilter : MethodFilter {

        override fun shouldInstrumentClass(className: String): Boolean {
            return methodsToInstrument.containsKey(className)
        }

        override fun getMethodId(className: String, methodName: String): Int? {
            return methodsToInstrument[className]?.get(methodName)
        }
    }

    fun instrumentMethod(className: String, methodName: String, tracepoint: Tracepoint) {
        LOG.info("Instrumenting $className#$methodName")
        val internalClassName = className.replace('.', '/')
        val methodMap = methodsToInstrument.getOrPut(internalClassName) { ConcurrentHashMap() }
        methodMap[methodName] = instrumentedTracepoints.append(tracepoint)
        instrumentation.allLoadedClasses.asSequence()
            .filter { it.name == className }
            .forEach { instrumentation.retransformClasses(it) }
    }
}
