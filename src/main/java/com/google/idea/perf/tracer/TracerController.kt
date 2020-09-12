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

import com.google.idea.perf.AgentLoader
import com.google.idea.perf.tracer.TracepointFlags.TRACE_CALL_COUNT
import com.google.idea.perf.tracer.ui.TracerPanel
import com.google.idea.perf.tracer.ui.TracerTable
import com.google.idea.perf.tracer.ui.TracerTree
import com.google.idea.perf.util.ExecutorWithExceptionLogging
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager.getApplication
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiElementFinder
import com.intellij.util.ui.UIUtil
import org.jetbrains.annotations.TestOnly
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.lang.instrument.UnmodifiableClassException
import java.lang.reflect.Method
import javax.imageio.ImageIO
import kotlin.reflect.jvm.javaMethod

// Things to improve:
// - Audit overall overhead and memory usage.
// - Make sure CPU overhead is minimal when the tracer window is not showing.
// - Add some logging.
// - Detect repeated instrumentation requests with the same spec.
// - Add a proper progress indicator (subclass of ProgressIndicator) which displays text status.
// - Add visual indicator in UI if agent is not available.
// - Make sure we're handling inner classes correctly (and lambdas, etc.)
// - Try to reduce the overhead of transforming classes by batching or parallelizing.
// - Support line-number-based tracepoints.
// - Corner case: classes may being loaded *during* a request to instrument a method.
// - Cancelable progress indicator for class retransformations.

class TracerController(
    private val view: TracerPanel, // Access from EDT only.
    parentDisposable: Disposable
) : Disposable {
    companion object {
        private val LOG = Logger.getInstance(TracerController::class.java)
        private var instrumentationInitialized = false
    }

    // For simplicity we run all tasks in a single-thread executor.
    // Most methods are assumed to run only on this executor.
    private val executor = ExecutorWithExceptionLogging("Tracer", 1)

    init {
        Disposer.register(parentDisposable, this)
    }

    override fun dispose() {
        // TODO: Should we wait for tasks to finish (under a modal progress dialog)?
        executor.shutdownNow()
    }

    fun handleRawCommandFromEdt(text: String) {
        getApplication().assertIsDispatchThread()
        if (text.isBlank()) return
        val cmd = text.trim()
        if (cmd.startsWith("save")) {
            // Special case: handle this command while we're still on the EDT.
            val path = cmd.substringAfter("save").trim()
            savePngFromEdt(path)
        }
        else {
            executor.execute { handleCommand(cmd) }
        }
    }

    private fun handleCommand(commandString: String) {
        val command = parseMethodTracerCommand(commandString)
        val errors = command.errors

        if (errors.isNotEmpty()) {
            displayWarning(errors.joinToString("\n"))
            return
        }

        handleCommand(command)
    }

    private fun handleCommand(command: TracerCommand) {
        when (command) {
            is TracerCommand.Clear -> {
                CallTreeManager.clearCallTrees()
            }
            is TracerCommand.Reset -> {
                val oldRequests = TracerConfig.clearAllRequests()
                val affectedClasses = TracerConfigUtil.getAffectedClasses(oldRequests)
                retransformClasses(affectedClasses)
                CallTreeManager.clearCallTrees()
            }
            is TracerCommand.Trace -> {
                val countOnly = command.traceOption!!.tracepointFlag == TRACE_CALL_COUNT

                when (command.target) {
                    is TraceTarget.PsiFinders -> {
                        val defaultProject = ProjectManager.getInstance().defaultProject
                        val psiFinders = PsiElementFinder.EP.getExtensions(defaultProject)
                        val baseMethod = PsiElementFinder::findClass.javaMethod!!
                        val methods = psiFinders.map {
                            it.javaClass.getMethod(baseMethod.name, *baseMethod.parameterTypes)
                        }
                        val config = MethodConfig(enabled = command.enable, countOnly = countOnly)
                        traceAndRetransform(config, *methods.toTypedArray())
                    }
                    is TraceTarget.Tracer -> {
                        val config = MethodConfig(enabled = command.enable, countOnly = countOnly)
                        traceAndRetransform(
                            config,
                            CallTreeManager::getCallTreeSnapshotAllThreadsMerged.javaMethod!!,
                            CallTreeUtil::computeFlatTracepointStats.javaMethod!!,
                            TracerTable::setTracepointStats.javaMethod!!,
                            TracerTree::setCallTree.javaMethod!!,
                        )
                    }
                    is TraceTarget.All -> {
                        when {
                            command.enable -> displayWarning("Cannot trace all classes")
                            else -> handleCommand(TracerCommand.Reset)
                        }
                    }
                    is TraceTarget.ClassPattern -> {
                        // TODO: Simplify (join with other branches).
                        val methodPattern = MethodFqName(command.target.classPattern, "*", "*")
                        val config = MethodConfig(enabled = command.enable, countOnly = countOnly)
                        val request = TracerConfigUtil.appendTraceRequest(methodPattern, config)
                        val affectedClasses = TracerConfigUtil.getAffectedClasses(listOf(request))
                        retransformClasses(affectedClasses)
                    }
                    is TraceTarget.MethodPattern -> {
                        // TODO: Simplify (join with other branches).
                        val clazz = command.target.className
                        val method = command.target.methodPattern
                        val methodPattern = MethodFqName(clazz, method, "*")
                        val config = MethodConfig(enabled = command.enable, countOnly = countOnly)
                        val request = TracerConfigUtil.appendTraceRequest(methodPattern, config)
                        val affectedClasses = TracerConfigUtil.getAffectedClasses(listOf(request))
                        retransformClasses(affectedClasses)
                    }
                    is TraceTarget.Method -> {
                        // TODO: Simplify (join with other branches).
                        val clazz = command.target.className
                        val method = command.target.methodName!!
                        val methodPattern = MethodFqName(clazz, method, "*")
                        val config = MethodConfig(
                            enabled = command.enable,
                            countOnly = countOnly,
                            tracedParams = command.target.parameterIndexes!!
                        )
                        val request = TracerConfigUtil.appendTraceRequest(methodPattern, config)
                        val affectedClasses = TracerConfigUtil.getAffectedClasses(listOf(request))
                        retransformClasses(affectedClasses)
                    }
                }
            }
            else -> {
                displayWarning("Command not implemented")
            }
        }
    }

    private fun traceAndRetransform(config: MethodConfig, vararg methods: Method) {
        if (methods.isEmpty()) return
        val traceRequests = mutableListOf<TraceRequest>()
        for (method in methods) {
            val methodFqName = TracerConfigUtil.createMethodFqName(method)
            val request = TracerConfigUtil.appendTraceRequest(methodFqName, config)
            traceRequests.add(request)
        }
        val affectedClasses = TracerConfigUtil.getAffectedClasses(traceRequests)
        retransformClasses(affectedClasses)
    }

    // This method can be slow.
    private fun retransformClasses(classes: Collection<Class<*>>) {
        if (classes.isEmpty()) return
        val instrumentation = AgentLoader.instrumentation ?: return
        if (!instrumentationInitialized) {
            // TODO: The hook should be installed even if we're not retransforming classes!!
            TracerTrampoline.installHook(TracerHookImpl())
            instrumentation.addTransformer(TracerClassFileTransformer(), true)
            instrumentationInitialized = true
        }

        LOG.info("Retransforming ${classes.size} classes")
        runWithProgress { progress ->
            progress.isIndeterminate = classes.size <= 5
            var count = 0.0
            for (clazz in classes) {
                // Retransforming classes tends to lock up all threads, so to keep
                // the UI responsive it helps to flush the EDT queue in between.
                invokeAndWaitIfNeeded {}
                progress.checkCanceled()
                try {
                    instrumentation.retransformClasses(clazz)
                }
                catch (e: UnmodifiableClassException) {
                    LOG.info("Cannot instrument non-modifiable class: ${clazz.name}")
                }
                catch (e: Throwable) {
                    LOG.error("Failed to retransform class: ${clazz.name}", e)
                }
                if (!progress.isIndeterminate) {
                    progress.fraction = ++count / classes.size
                }
            }
        }
    }

    /** Saves a png of the current view. */
    private fun savePngFromEdt(path: String) {
        if (!path.endsWith(".png")) {
            displayWarning("Destination file must be a .png file; instead got $path")
            return
        }
        val file = File(path)
        if (!file.isAbsolute) {
            displayWarning("Must specify destination file with an absolute path; instead got $path")
            return
        }
        val img = UIUtil.createImage(view, view.width, view.height, BufferedImage.TYPE_INT_RGB)
        view.paintAll(img.createGraphics())
        getApplication().executeOnPooledThread {
            try {
                ImageIO.write(img, "png", file)
            }
            catch (e: IOException) {
                displayWarning("Failed to write png to $path", e)
            }
        }
    }

    private fun displayWarning(warning: String, e: Throwable? = null) {
        LOG.warn(warning, e)
        invokeLater {
            view.showCommandLinePopup(warning, MessageType.WARNING)
        }
    }

    private fun <T> runWithProgress(action: (ProgressIndicator) -> T): T {
        val indicator = view.createProgressIndicator()
        val computable = Computable { action(indicator) }
        return ProgressManager.getInstance().runProcess(computable, indicator)
    }

    @TestOnly
    fun handleCommandFromTest(cmd: String) {
        check(!getApplication().isDispatchThread) { "Do not run on EDT; deadlock imminent" }
        invokeAndWaitIfNeeded { handleRawCommandFromEdt(cmd) }
        executor.submit {}.get() // Await quiescence.
    }
}
