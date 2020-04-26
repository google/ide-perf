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

package com.android.tools.idea.diagnostics

import com.android.tools.idea.diagnostics.agent.AgentMain
import com.android.tools.idea.diagnostics.agent.MethodListener
import com.android.tools.idea.diagnostics.agent.Trampoline
import com.intellij.execution.process.OSProcessUtil
import com.intellij.ide.plugins.PluginManager
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.sun.tools.attach.VirtualMachine
import java.io.File
import java.lang.instrument.Instrumentation

// Things to improve:
// - Fail gracefully upon SOE caused by instrumenting code used by TracerMethodListener.

/** Loads and initializes the instrumentation agent. */
object AgentLoader {
    private val LOG = Logger.getInstance(AgentLoader::class.java)

    val instrumentation: Instrumentation? by lazy { tryLoadAgent() }

    private fun tryLoadAgent(): Instrumentation? {
        val agentLoadedAtStartup = try {
            // Until the agent is loaded, we cannot trigger symbol resolution for its
            // classes---otherwise NoClassDefFoundError is imminent. So we use reflection.
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
                val msg = """
                    [Tracer] Failed to attach the instrumentation agent after startup.
                    On JDK 9+, make sure jdk.attach.allowAttachSelf is set to true.
                    Alternatively, you can attach the agent at startup via the -javaagent flag.
                    """.trimIndent()
                Notification("Tracer", "", msg, NotificationType.ERROR).notify(null)
                LOG.warn(e)
                instrumentation = null
            }
        }

        // Disable tracing entirely if class retransformation is not supported.
        if (instrumentation != null && !instrumentation.isRetransformClassesSupported) {
            val msg = "[Tracer] The current JVM configuration does not allow class retransformation"
            Notification("Tracer", "", msg, NotificationType.ERROR).notify(null)
            LOG.warn(msg)
            instrumentation = null
        }

        // Install our tracing hooks and transformers.
        if (instrumentation != null) {
            Trampoline.methodListener = TracerMethodListener()
            instrumentation.addTransformer(TracerMethodTransformer(), true)
        }

        return instrumentation
    }

    // This method can throw a variety of exceptions.
    private fun tryLoadAgentAfterStartup() {
        val pluginId = PluginManager.getPluginByClassName(AgentLoader::class.java.name)!!
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
    private class TracerMethodListener : MethodListener {

        override fun enter(methodId: Int) {
            val tracepoint = TracerConfig.getTracepoint(methodId)
            CallTreeManager.enter(tracepoint)
        }

        override fun leave(methodId: Int) {
            val tracepoint = TracerConfig.getTracepoint(methodId)
            CallTreeManager.leave(tracepoint)
        }

        companion object {
            init {
                // Trigger class loading for CallTreeManager early so that it doesn't happen
                // during tracing. This reduces the chance of invoking an instrumented method
                // from a tracing hook (causing infinite recursion).
                CallTreeManager.enter(Tracepoint.ROOT)
                CallTreeManager.leave(Tracepoint.ROOT)
                CallTreeManager.collectAndReset()
            }
        }
    }
}
