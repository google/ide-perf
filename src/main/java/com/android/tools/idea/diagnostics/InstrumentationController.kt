package com.android.tools.idea.diagnostics

import com.android.tools.idea.diagnostics.agent.AgentMain
import com.android.tools.idea.diagnostics.agent.MethodListener
import com.android.tools.idea.diagnostics.agent.Trampoline
import org.objectweb.asm.Type
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

// Things to improve:
// - Fail gracefully if agent not available.
// - Allow removing instrumentation from a given method.
// - Make sure we're handling inner classes correctly (and lambdas, etc.)
// - Try to avoid querying 'instrumentation.allLoadedClasses' for every call to instrumentMethod().
// - Add some logging.
// - Catch some more exceptions thrown by Instrumentation.
// - Support line-number-based tracepoints.
// - What happens if a class is being loaded *during* the call to instrumentMethod()?

/**
 * Instruments Java methods with tracing hooks and delegates tracing events to [CallTreeManager].
 * This is the only class that should interact with the agent directly.
 */
object InstrumentationController {
    private val instrumentation = AgentMain.savedInstrumentationInstance

    // Used by instrumented bytecode as a map from method id to the corresponding Tracepoint instance.
    private val tracepoints = ConcurrentAppendOnlyList<Tracepoint>()

    /** Specifies which methods to instrument for a specific class. */
    private class ClassInfo {
        // The set of simple method names to instrument.
        val methodNames: MutableSet<String> = ConcurrentHashMap<String, Unit>().keySet(Unit)
        // A map from method signature to method id, for methods which should be instrumented.
        val methodIds: ConcurrentMap<String, Int> = ConcurrentHashMap()
    }

    // A map from JVM class names to the instrumentation settings for that class.
    private val classInfoMap = ConcurrentHashMap<String, ClassInfo>()

    init {
        Trampoline.methodListener = MyMethodListener()
        instrumentation.addTransformer(MethodTracingTransformer(MyMethodFilter()), true)
    }

    /** Dispatches method entry/exit events to the [CallTreeManager]. */
    private class MyMethodListener : MethodListener {
        override fun enter(methodId: Int): Unit = CallTreeManager.enter(tracepoints.get(methodId))
        override fun leave(methodId: Int): Unit = CallTreeManager.leave(tracepoints.get(methodId))
    }

    /** Called by [MethodTracingTransformer] to decide which classes and methods to instrument. */
    private class MyMethodFilter : MethodFilter {

        override fun shouldInstrumentClass(classJvmName: String): Boolean {
            return classInfoMap.containsKey(classJvmName)
        }

        override fun getMethodId(classJvmName: String, methodName: String, methodDesc: String): Int? {
            val classInfo = classInfoMap[classJvmName] ?: return null
            val methodSignature = "$methodName$methodDesc"

            // Check for an existing method id.
            val methodId = classInfo.methodIds[methodSignature]
            if (methodId != null) {
                return methodId
            }

            // Otherwise, create a new method id.
            if (classInfo.methodNames.contains(methodName)) {
                val tracepoint = createTracepoint(classJvmName, methodName, methodDesc)
                return classInfo.methodIds.computeIfAbsent(methodSignature) { tracepoints.append(tracepoint) }
            }

            return null
        }
    }

    // This method can be slow! Call it in a background thread with a progress indicator.
    fun instrumentMethod(className: String, methodName: String, methodDesc: String? = null) {
        val classJvmName = className.replace('.', '/')
        val classInfo = classInfoMap.getOrPut(classJvmName) { ClassInfo() }

        if (methodDesc != null) {
            val methodSignature = "$methodName$methodDesc"
            val tracepoint = createTracepoint(classJvmName, methodName, methodDesc)
            classInfo.methodIds.computeIfAbsent(methodSignature) { tracepoints.append(tracepoint) }
        } else {
            classInfo.methodNames.add(methodName)
        }

        instrumentation.allLoadedClasses.asSequence()
            .filter { it.name == className }
            .forEach { instrumentation.retransformClasses(it) }
    }

    // This method can be slow! Call it in a background thread with a progress indicator.
    fun instrumentMethod(method: Method) {
        instrumentMethod(method.declaringClass.name, method.name, Type.getMethodDescriptor(method))
    }

    private fun createTracepoint(classJvmName: String, methodName: String, methodDesc: String): Tracepoint {
        val classShortName = classJvmName.substringAfterLast('/')
        val className = classJvmName.replace('/', '.')
        return Tracepoint(
            displayName = "$classShortName.$methodName()",
            description = "$className#$methodName$methodDesc"
        )
    }
}
