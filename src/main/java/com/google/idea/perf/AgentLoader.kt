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

package com.google.idea.perf

import com.google.idea.perf.AgentLoader.ensureTracerHooksInstalled
import com.google.idea.perf.AgentLoader.instrumentation
import com.google.idea.perf.agent.AgentMain
import com.google.idea.perf.tracer.TracerClassFileTransformer
import com.google.idea.perf.tracer.TracerHookImpl
import com.google.idea.perf.tracer.TracerTrampoline
import com.intellij.execution.process.OSProcessUtil
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.io.isFile
import com.sun.tools.attach.VirtualMachine
import java.lang.instrument.Instrumentation
import kotlin.system.measureTimeMillis

// Things to improve:
// - Fail gracefully upon SOE caused by instrumenting code used by TracerMethodListener.

/**
 * Loads and initializes the instrumentation agent with [VirtualMachine.loadAgent].
 *
 * See [instrumentation] for the global [java.lang.instrument.Instrumentation] instance.
 * See [ensureTracerHooksInstalled] for installing tracer hooks.
 *
 * Note: the agent must be loaded here before any of its classes are referenced, otherwise
 * [NoClassDefFoundError] is thrown. So, for safety, all direct references to agent
 * classes should ideally be encapsulated in [AgentLoader].
 */
object AgentLoader {
    private val LOG = Logger.getInstance(AgentLoader::class.java)

    val instrumentation: Instrumentation?
        get() = if (ensureJavaAgentLoaded) AgentMain.savedInstrumentationInstance else null

    val ensureJavaAgentLoaded: Boolean by lazy { doLoadJavaAgent() }

    val ensureNativeAgentLoaded: Boolean by lazy { doLoadNativeAgent() }

    val ensureTracerHooksInstalled: Boolean by lazy { doInstallTracerHooks() }

    private fun doLoadJavaAgent(): Boolean {
        val agentLoadedAtStartup = try {
            // Until the agent is loaded, we cannot trigger symbol resolution for its
            // classes---otherwise NoClassDefFoundError is imminent. So we use reflection.
            Class.forName("com.google.idea.perf.agent.AgentMain", false, null)
            true
        }
        catch (e: ClassNotFoundException) {
            false
        }

        if (agentLoadedAtStartup) {
            LOG.info("Java agent was loaded at startup")
        }
        else {
            try {
                val overhead = measureTimeMillis {
                    tryLoadAgent("agent.jar", native = false)
                }
                LOG.info("Java agent was loaded on demand in $overhead ms")
            }
            catch (e: Throwable) {
                var msg = "Failed to load the Java agent"
                if (System.getProperty("jdk.attach.allowAttachSelf") == null) {
                    msg += ". Please set the VM option jdk.attach.allowAttachSelf to true."
                }
                LOG.warn(msg, e)
                return false
            }
        }

        // Disable tracing entirely if class retransformation is not supported.
        val instrumentation = checkNotNull(AgentMain.savedInstrumentationInstance)
        if (!instrumentation.isRetransformClassesSupported) {
            LOG.warn("This JVM does not support class retransformations")
            return false
        }

        return true
    }

    private fun doLoadNativeAgent(): Boolean {
        val agentLoadedAtStartup = try {
            AllocationSampling.countAllocationsForCurrentThread()
            true
        }
        catch (e: LinkageError) {
            false
        }

        if (agentLoadedAtStartup) {
            LOG.info("Native agent was loaded at startup")
        }
        else {
            try {
                val binary = when {
                    SystemInfo.isMac -> "libagent.dylib"
                    SystemInfo.isWindows -> "agent.dll"
                    else -> "libagent.so"
                }
                val overhead = measureTimeMillis {
                    tryLoadAgent(binary, native = true)
                }
                LOG.info("Native agent was loaded on demand in $overhead ms")
            }
            catch (e: Throwable) {
                LOG.warn("Failed to load the native agent", e)
                return false
            }
        }

        return true
    }

    // Throws exceptions on failure.
    private fun tryLoadAgent(fileName: String, native: Boolean) {
        val plugin = PluginManagerCore.getPlugin(PluginId.getId("com.google.ide-perf"))
            ?: error("Failed to find our own plugin")

        val path = plugin.pluginPath.resolve("agent").resolve(fileName)
        check(path.isFile()) { "Could not find agent at $path" }

        val absolutePath = path.toAbsolutePath().toString()
        val vm = VirtualMachine.attach(OSProcessUtil.getApplicationPid())
        try {
            when {
                native -> vm.loadAgentPath(absolutePath)
                else -> vm.loadAgent(absolutePath)
            }
        }
        finally {
            vm.detach()
        }
    }

    private fun doInstallTracerHooks(): Boolean {
        val instrumentation = instrumentation ?: return false
        TracerTrampoline.installHook(TracerHookImpl())
        instrumentation.addTransformer(TracerClassFileTransformer(), true)
        return true
    }
}
