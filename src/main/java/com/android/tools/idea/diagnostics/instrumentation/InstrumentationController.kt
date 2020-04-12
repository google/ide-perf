package com.android.tools.idea.diagnostics.instrumentation

import com.android.tools.idea.diagnostics.CallTreeManager
import com.android.tools.idea.diagnostics.ConcurrentAppendOnlyList
import com.android.tools.idea.diagnostics.Tracepoint
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

/** Handles requests to instrument Java methods. */
object InstrumentationController {
    private val log = Logger.getInstance(InstrumentationController::class.java)
    private val instrumentation = AgentMain.savedInstrumentationInstance
    private val instrumentedTracepoints = ConcurrentAppendOnlyList<Tracepoint>()

    /**
     * Map: className -> methodName -> id
     * Class names are in internal JVM form (e.g., java/lang/Object).
     */
    private val methodsToInstrument = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()

    init {
        Trampoline.methodListener = MyMethodListener()
        instrumentation.addTransformer(MethodTracingTransformer(MyClassFilter()), true)
    }

    /** Dispatches method entry/exit events to the CallTreeManager. */
    private class MyMethodListener : MethodListener {
        override fun enter(id: Int): Unit = CallTreeManager.enter(instrumentedTracepoints.get(id))
        override fun leave(id: Int): Unit = CallTreeManager.leave(instrumentedTracepoints.get(id))
    }

    /** Filters class loading events based on [methodsToInstrument]. */
    private class MyClassFilter : ClassFilter {

        // Called by EntryExitTransformer during class loading.
        override fun getMethodFilter(className: String): MethodFilter? {
            val methodMap = methodsToInstrument[className] ?: return null
            return MyMethodFilter(methodMap)
        }

        private class MyMethodFilter(private val methodMap: Map<String, Int>) : MethodFilter {
            override fun getMethodId(methodName: String): Int? = methodMap[methodName]
        }
    }

    fun instrumentMethod(className: String, methodName: String, tracepoint: Tracepoint) {
        log.info("Attempting to instrument $className#$methodName")
        val internalClassName = className.replace('.', '/')
        val methodMap = methodsToInstrument.getOrPut(internalClassName) { ConcurrentHashMap() }
        methodMap[methodName] = instrumentedTracepoints.append(tracepoint)
        instrumentation.allLoadedClasses.asSequence()
            .filter { it.name == className }
            .forEach { instrumentation.retransformClasses(it) }
    }
}
