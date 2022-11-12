/*
 * Copyright 2022 Google LLC
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

package com.google.idea.perf.tracer.ui

import com.google.idea.perf.sample.Sample
import com.google.idea.perf.tracer.TracerController
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import junit.framework.TestCase
import org.junit.Test
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.javaMethod

internal class TracerConfigTabTest : BasePlatformTestCase() {

    private lateinit var tracer: TracerController
    private lateinit var tracerPanel: TracerPanel

    override fun setUp() {
        super.setUp()
        tracerPanel = invokeAndWaitIfNeeded { TracerPanel(project, testRootDisposable) }
        tracer = tracerPanel.controller
    }

    override fun tearDown() {
        try {
            tracer.handleCommandFromTest("reset")
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    // Avoid deadlock when waiting on the tracer.
    override fun runInDispatchThread(): Boolean = false

    @Test
    fun testSimpleTracesConfig() {
        for (method in listOf(Sample::a, Sample::c, Sample::b, Sample::e)) {
            tracer.handleCommand("trace ${format(method)}")
        }
        tracerPanel.updateCallTree()
        TestCase.assertEquals(
            """
            trace com.google.idea.perf.sample.Sample::a
            trace com.google.idea.perf.sample.Sample::c
            trace com.google.idea.perf.sample.Sample::b
            trace com.google.idea.perf.sample.Sample::e
            """.trimIndent(),
            tracerPanel.configView.text
        )
        for (method in listOf(Sample::b, Sample::c, Sample::d)) {
            tracer.handleCommand("untrace ${format(method)}")
        }
        tracerPanel.updateCallTree()
        TestCase.assertEquals(
            """
            trace com.google.idea.perf.sample.Sample::a
            trace com.google.idea.perf.sample.Sample::e
            untrace com.google.idea.perf.sample.Sample::d
            """.trimIndent(),
            tracerPanel.configView.text
        )

        tracer.handleCommand("reset")
        tracerPanel.updateCallTree()
        TestCase.assertEquals(
            """
            """.trimIndent(),
            tracerPanel.configView.text
        )

    }

    private fun format(method: KFunction<*>): String {
        val className = method.javaMethod!!.declaringClass.name
        val methodName = method.name
        return "$className#$methodName"
    }

}
