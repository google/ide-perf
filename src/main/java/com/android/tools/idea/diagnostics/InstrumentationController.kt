package com.android.tools.idea.diagnostics

import com.android.tools.idea.diagnostics.agent.AgentMain
import com.android.tools.idea.diagnostics.agent.MethodListener
import com.android.tools.idea.diagnostics.agent.Trampoline
import com.intellij.execution.process.OSProcessUtil
import com.intellij.ide.plugins.PluginManager
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.Logger
import com.sun.tools.attach.VirtualMachine
import org.objectweb.asm.Type
import java.io.File
import java.lang.instrument.Instrumentation
import java.lang.instrument.UnmodifiableClassException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

// Things to improve:
// - Add visual indicator in UI if agent is not available.
// - Allow removing instrumentation from a given method.
// - Make sure we're handling inner classes correctly (and lambdas, etc.)
// - Try to avoid querying 'instrumentation.allLoadedClasses' for every call to instrumentMethod().
// - Add some logging.
// - Support line-number-based tracepoints.
// - What happens if a class is being loaded *during* the call to instrumentMethod()?
// - Pretty-print method descriptors for better UX.
// - Fail gracefully upon StackOverflowError caused by instrumenting code used by MyMethodListener.
// - Consider extracting the agent loading code into another class.

/**
 * Instruments Java methods with tracing hooks and delegates tracing events to [CallTreeManager].
 * This is the only class that should interact with the agent directly.
 */
object InstrumentationController {
    private val LOG = Logger.getInstance(InstrumentationController::class.java)
    private val instrumentation: Instrumentation?

    // Used by instrumented bytecode as a map from method id to the corresponding Tracepoint instance.
    private val tracepoints = ConcurrentAppendOnlyList<Tracepoint>()

    // A map from JVM class names to the instrumentation settings for that class.
    private val classInfoMap = ConcurrentHashMap<String, ClassInfo>()

    init {
        // Trigger classloading for CallTreeManager now so that it doesn't happen during tracing. This reduces
        // the chance of accidentally instrumenting a callee of CallTreeManager (causing infinite recursion).
        CallTreeManager.enter(Tracepoint.ROOT)
        CallTreeManager.leave(Tracepoint.ROOT)
        CallTreeManager.collectAndReset()

        val agentLoadedAtStartup = try {
            // Note: until the agent is loaded, we have to be careful not to trigger symbol resolution
            // for its classes; otherwise NoClassDefFoundError is imminent. So we use reflection.
            Class.forName("com.android.tools.idea.diagnostics.agent.AgentMain", false, null)
            true
        } catch (e: ClassNotFoundException) {
            false
        }

        var instrumentation: Instrumentation?
        if (agentLoadedAtStartup) {
            instrumentation = checkNotNull(AgentMain.savedInstrumentationInstance)
            LOG.info("Agent was loaded at startup")
        } else {
            try {
                tryLoadAgentAfterStartup()
                instrumentation = checkNotNull(AgentMain.savedInstrumentationInstance)
                LOG.info("Agent was loaded on demand")
            } catch (e: Throwable) {
                LOG.error(
                    """
                    Failed to attach the agent after startup.
                    On JDK 9+, make sure jdk.attach.allowAttachSelf is set to true.
                    Alternatively, you can attach the agent at startup via the -javaagent flag.
                    """.trimIndent(), e
                )
                instrumentation = null
            }
        }

        // Disable tracing entirely if class retransformation is not supported.
        if (instrumentation != null && !instrumentation.isRetransformClassesSupported) {
            LOG.error("The current JVM configuration does not support class retransformation")
            instrumentation = null
        }

        // Install our tracing hooks and transformers.
        if (instrumentation != null) {
            Trampoline.methodListener = MyMethodListener()
            val transformer = MethodTracingTransformer(MyMethodFilter())
            instrumentation.addTransformer(transformer, true)
        }

        this.instrumentation = instrumentation
    }

    // This method can throw a variety of exceptions.
    private fun tryLoadAgentAfterStartup() {
        // TODO: Try to silence the ServiceConfigurationErrors printed to stderr from
        //  AttachProvider.providers(). Maybe we need to run this code from a different class loader?
        val pluginId = PluginManager.getPluginByClassName(InstrumentationController::class.java.name)!!
        val plugin = PluginManagerCore.getPlugin(pluginId)!!
        val agentJar = File(plugin.path!!, "agent.jar")
        val vm = VirtualMachine.attach(OSProcessUtil.getApplicationPid())
        try {
            vm.loadAgent(agentJar.absolutePath)
        } finally {
            vm.detach()
        }
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

    /** Specifies which methods to instrument for a specific class. */
    private class ClassInfo {
        // The set of simple method names to instrument.
        val methodNames: MutableSet<String> = ConcurrentHashMap<String, Unit>().keySet(Unit)
        // A map from method signature to method id, for methods which should be instrumented.
        val methodIds: ConcurrentMap<String, Int> = ConcurrentHashMap()
    }

    // This method can be slow! Call it in a background thread with a progress indicator.
    fun instrumentMethod(className: String, methodName: String, methodDesc: String? = null) {
        if (instrumentation == null) return

        val classJvmName = className.replace('.', '/')
        val classInfo = classInfoMap.getOrPut(classJvmName) { ClassInfo() }

        if (methodDesc != null) {
            val methodSignature = "$methodName$methodDesc"
            val tracepoint = createTracepoint(classJvmName, methodName, methodDesc)
            classInfo.methodIds.computeIfAbsent(methodSignature) { tracepoints.append(tracepoint) }
        } else {
            classInfo.methodNames.add(methodName)
        }

        // Retransform loaded classes.
        val classes = instrumentation.allLoadedClasses.asSequence().filter { it.name == className }
        for (clazz in classes) {
            try {
                instrumentation.retransformClasses(clazz)
            } catch (e: UnmodifiableClassException) {
                LOG.warn("Cannot instrument non-modifiable class: ${clazz.name}")
            } catch (e: Throwable) {
                LOG.error("Failed to retransform class: ${clazz.name}", e)
            }
        }
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
