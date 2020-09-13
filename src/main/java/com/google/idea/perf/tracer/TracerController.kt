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
import com.google.idea.perf.tracer.ui.TracerPanel
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import kotlin.reflect.jvm.javaMethod
import kotlin.system.measureNanoTime

// Things to improve:
// - Audit overall overhead and memory usage.
// - Make sure CPU overhead is minimal when the tracer window is not showing.
// - Add some logging.
// - Detect repeated instrumentation requests with the same spec.
// - Add a proper progress indicator (subclass of ProgressIndicator) which displays text status.
// - Consider moving callTree to CallTreeManager so that it persists after the Tracer window closes.
// - Add visual indicator in UI if agent is not available.
// - Make sure we're handling inner classes correctly (and lambdas, etc.)
// - Try to reduce the overhead of transforming classes by batching or parallelizing.
// - Support line-number-based tracepoints.
// - Corner case: classes may being loaded *during* a request to instrument a method.
// - Show all instrumented methods in the method list view, even if there are no calls yet(?)
// - Cancelable progress indicator for class retransformations.

class TracerController(
    private val view: TracerPanel, // Access from EDT only.
    parentDisposable: Disposable
) : Disposable {
    companion object {
        private val LOG = Logger.getInstance(TracerController::class.java)
        private const val REFRESH_DELAY_MS = 30L
        private var instrumentationInitialized = false
    }

    // For simplicity we run all tasks in a single-thread executor.
    // Most methods are assumed to run only on this executor.
    private val executor = ExecutorWithExceptionLogging("Tracer", 1)
    private val dataRefreshLoopStarted = AtomicBoolean()

    init {
        Disposer.register(parentDisposable, this)
    }

    fun startDataRefreshLoop() {
        check(dataRefreshLoopStarted.compareAndSet(false, true))
        val refreshLoop = { updateRefreshTimeUi(measureNanoTime(::refreshCallTreeData)) }
        executor.scheduleWithFixedDelay(refreshLoop, 0, REFRESH_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    override fun dispose() {
        executor.shutdownNow()
    }

    private fun refreshCallTreeData() {
        val treeSnapshot = CallTreeManager.getGlobalTreeSnapshot()
        val allStats = TreeAlgorithms.computeFlatTracepointStats(treeSnapshot)
        val visibleStats = allStats.filter { it.tracepoint != Tracepoint.ROOT }

        // We use invokeAndWait to ensure proper backpressure for the data refresh loop.
        invokeAndWaitIfNeeded {
            view.refreshCallTreeData(treeSnapshot, visibleStats)
        }
    }

    private fun updateRefreshTimeUi(refreshTime: Long) {
        invokeAndWaitIfNeeded {
            view.setRefreshTime(refreshTime)
        }
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

    private fun handleCommand(cmd: String) {
        val command = parseMethodTracerCommand(cmd)
        val errors = command.errors

        if (errors.isNotEmpty()) {
            displayWarning(errors.joinToString("\n"))
            return
        }

        when (command) {
            is TracerCommand.Clear -> {
                CallTreeManager.clearAllTrees()
                refreshCallTreeData()
            }
            is TracerCommand.Reset -> {
                // TODO: Change meaning of 'reset' to be 'untrace *' plus 'clear'.
                CallTreeManager.clearAllTrees()
                refreshCallTreeData()
            }
            is TracerCommand.Trace -> {
                val flags = command.traceOption!!.tracepointFlag

                when (command.target) {
                    is TraceTarget.PsiFinders -> {
                        val defaultProject = ProjectManager.getInstance().defaultProject
                        val psiFinders = PsiElementFinder.EP.getExtensions(defaultProject)
                        val baseMethod = PsiElementFinder::findClass.javaMethod!!
                        val methods = psiFinders.map {
                            it.javaClass.getMethod(baseMethod.name, *baseMethod.parameterTypes)
                        }
                        traceAndRetransform(command.enable, flags, *methods.toTypedArray())
                    }
                    is TraceTarget.Tracer -> {
                        traceAndRetransform(
                            command.enable,
                            flags,
                            this::refreshCallTreeData.javaMethod!!,
                            this::handleCommand.javaMethod!!
                        )
                    }
                    is TraceTarget.All -> {
                        if (command.enable) {
                            displayWarning("Tracing all methods is not supported")
                        }
                        else {
                            val classNames = TracerConfig.untraceAll()
                            retransformClasses(classNames.toSet())
                        }
                    }
                    is TraceTarget.ClassPattern -> {
                        val classPattern = command.target.classPattern
                        val classes = if (command.enable) {
                            TracerConfig.trace(
                                TracePattern.ByClassPattern(classPattern),
                                flags
                            )
                        }
                        else {
                            TracerConfig.untrace(TracePattern.ByClassPattern(classPattern))
                        }
                        retransformClasses(classes.toSet())
                    }
                    is TraceTarget.MethodPattern -> {
                        val className = command.target.className
                        val methodPattern = command.target.methodPattern
                        if (command.enable) {
                            TracerConfig.trace(
                                TracePattern.ByMethodPattern(className, methodPattern),
                                flags
                            )
                        }
                        else {
                            TracerConfig.untrace(TracePattern.ByMethodPattern(className, methodPattern))
                        }
                        retransformClasses(setOf(className))
                    }
                    is TraceTarget.Method -> {
                        val className = command.target.className
                        val methodName = command.target.methodName!!
                        val parameters = command.target.parameterIndexes!!
                        if (command.enable) {
                            TracerConfig.trace(
                                TracePattern.ByMethodName(className, methodName),
                                flags,
                                parameters
                            )
                        }
                        else {
                            TracerConfig.untrace(TracePattern.ByMethodName(className, methodName))
                        }
                        retransformClasses(setOf(className))
                    }
                }
            }
            else -> {
                displayWarning("Command not implemented")
            }
        }
    }

    private fun traceAndRetransform(enable: Boolean, flags: Int, vararg methods: Method) {
        if (methods.isEmpty()) return
        if (enable) {
            methods.forEach { TracerConfig.trace(TracePattern.Exact(it), flags, emptyList()) }
        }
        else {
            methods.forEach { TracerConfig.untrace(TracePattern.Exact(it)) }
        }
        val classes = methods.map { it.declaringClass }.toSet()
        retransformClasses(classes)
    }

    private fun retransformClasses(classNames: Set<String>) {
        if (classNames.isEmpty()) return
        val instrumentation = AgentLoader.instrumentation ?: return
        val classes = instrumentation.allLoadedClasses.filter { classNames.contains(it.name) }
        retransformClasses(classes)
    }

    // This method can be slow.
    private fun retransformClasses(classes: Collection<Class<*>>) {
        if (classes.isEmpty()) return
        val instrumentation = AgentLoader.instrumentation ?: return
        if (!instrumentationInitialized) {
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
        invokeLater {
            view.showCommandLinePopup(warning, MessageType.WARNING)
            LOG.warn(warning, e)
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
