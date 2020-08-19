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

package com.google.idea.perf.methodtracer

import com.google.idea.perf.AgentLoader
import com.google.idea.perf.CommandCompletionProvider
import com.google.idea.perf.TracerController
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager.getApplication
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.psi.PsiElementFinder
import com.intellij.util.ui.UIUtil
import org.jetbrains.annotations.TestOnly
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.lang.instrument.UnmodifiableClassException
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit.SECONDS
import javax.imageio.ImageIO
import kotlin.reflect.jvm.javaMethod

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

class MethodTracerController(
    private val view: MethodTracerView, // Access from EDT only.
    parentDisposable: Disposable
): TracerController("Method Tracer", view), Disposable {
    companion object {
        const val AUTOCOMPLETE_RELOAD_INTERVAL = 120L
        private var instrumentationInitialized = false
    }

    private var callTree = MutableCallTree(MethodCall.ROOT)
    private val predictor = MethodTracerCommandPredictor()
    val autocomplete = CommandCompletionProvider(predictor)

    init {
        CallTreeManager.collectAndReset() // Clear trees accumulated while the tracer was closed.
        Disposer.register(parentDisposable, this)
    }

    override fun onControllerInitialize() {
        executor.scheduleWithFixedDelay(
            this::reloadAutocompleteClasses, 0L, AUTOCOMPLETE_RELOAD_INTERVAL, SECONDS
        )
    }

    override fun updateModel(): Boolean {
        val treeDeltas = CallTreeManager.collectAndReset()
        if (treeDeltas.isNotEmpty()) {
            treeDeltas.forEach(callTree::accumulate)
            return true
        }
        return false
    }

    /** Refreshes the UI with the current state of [callTree]. */
    override fun updateUi() {
        val allStats = TreeAlgorithms.computeFlatTracepointStats(callTree)
        val visibleStats = allStats.filter { it.tracepoint != Tracepoint.ROOT }

        val visibleArgStats = ArgStatMap.fromCallTree(callTree, false)

        // We use invokeAndWait to ensure proper backpressure for the data refresh loop.
        getApplication().invokeAndWait {
            view.listView.setTracepointStats(visibleStats)
            view.updateTabs(visibleArgStats)
        }
    }

    override fun handleRawCommandFromEdt(text: String) {
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
            errors.forEach { LOG.warn(it) }
            view.showCommandBalloon(errors.joinToString("\n"), MessageType.ERROR)
            return
        }

        when (command) {
            is MethodTracerCommand.Clear -> {
                callTree.clear()
                updateUi()
            }
            is MethodTracerCommand.Reset -> {
                callTree = MutableCallTree(MethodCall.ROOT)
                updateUi()
            }
            is MethodTracerCommand.Trace -> {
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
                            this::dataRefreshLoop.javaMethod!!,
                            this::updateUi.javaMethod!!,
                            this::handleCommand.javaMethod!!
                        )
                    }
                    is TraceTarget.All -> {
                        if (command.enable) {
                            LOG.warn("Tracing all methods is prohibited.")
                            view.showCommandBalloon(
                                "Tracing all methods is prohibited", MessageType.ERROR
                            )
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
                LOG.warn("Command not implemented.")
                view.showCommandBalloon("Command not implemented", MessageType.ERROR)
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
            MethodTracerTrampoline.installHook(TracerMethodListener())
            instrumentation.addTransformer(TracerMethodTransformer(), true)
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
                    LOG.warn("Cannot instrument non-modifiable class: ${clazz.name}")
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
            LOG.warn("Destination file must be a .png file; instead got $path")
            return
        }
        val file = File(path)
        if (!file.isAbsolute) {
            LOG.warn("Destination file must be specified with an absolute path; instead got $path")
            return
        }
        val img = UIUtil.createImage(view, view.width, view.height, BufferedImage.TYPE_INT_RGB)
        view.paintAll(img.createGraphics())
        getApplication().executeOnPooledThread {
            try {
                ImageIO.write(img, "png", file)
            }
            catch (e: IOException) {
                LOG.warn("Failed to write png to $path", e)
            }
        }
    }

    private fun reloadAutocompleteClasses() {
        val instrumentation = AgentLoader.instrumentation

        if (instrumentation != null) {
            predictor.setClasses(instrumentation.allLoadedClasses.filter {
                it.canonicalName != null
            }.sortedBy { it.canonicalName })
        }
        else {
            LOG.warn("Cannot reload classes.")
        }
    }

    @TestOnly
    fun handleCommandFromTest(cmd: String) {
        check(!getApplication().isDispatchThread) { "Do not run on EDT; deadlock imminent" }
        invokeAndWaitIfNeeded { handleRawCommandFromEdt(cmd) }
        executor.submit(EmptyRunnable.INSTANCE).get() // Await quiescence.
    }

    @TestOnly
    fun getCallTreeSnapshot(): CallTree {
        check(!getApplication().isDispatchThread) { "Do not run on EDT; deadlock imminent" }
        val getTree = { updateModel(); callTree.copy() }
        return executor.submit(getTree).get()
    }
}
