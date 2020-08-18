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

import com.google.idea.perf.sample.Sample
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.javaMethod

/**
 * This is an integration test for the method tracer.
 *
 * It creates a real instance of [MethodTracerController], issues tracing commands,
 * and validates the state of the call tree. It does not test the UI.
 */
class TracerIntegrationTest : BasePlatformTestCase() {
    private lateinit var tracer: MethodTracerController

    override fun setUp() {
        super.setUp()
        val tracerView = invokeAndWaitIfNeeded { MethodTracerView(testRootDisposable) }
        tracer = tracerView.controller
    }

    override fun tearDown() {
        try {
            tracer.handleCommandFromTest("untrace *")
        }
        catch (e: Throwable) {
            addSuppressedException(e)
        }
        finally {
            super.tearDown()
        }
    }

    // Avoid deadlock when waiting on the tracer.
    override fun runInDispatchThread(): Boolean = false

    @Test
    fun testSimpleTracing() {
        // Trace all methods.
        for (method in listOf(Sample::a, Sample::b, Sample::c, Sample::d, Sample::e)) {
            tracer.handleCommandFromTest("trace ${format(method)}")
        }
        Sample.a()
        assertCallTreeStructure(
            """
            [root] [0]
              Sample.a [1]
                Sample.b [1]
                  Sample.c [2]
                    Sample.d [2]
                      Sample.d [2]
                        Sample.e [2]
                Sample.d [1]
                  Sample.e [1]
            """.trimIndent()
        )

        // Reset.
        tracer.handleCommandFromTest("reset")
        assertCallTreeStructure("[root] [0]")

        // Untrace a few methods.
        for (method in listOf(Sample::b, Sample::c, Sample::d)) {
            tracer.handleCommandFromTest("untrace ${format(method)}")
        }
        Sample.a()
        assertCallTreeStructure(
            """
            [root] [0]
              Sample.a [1]
                Sample.e [3]
            """.trimIndent()
        )
    }

    @Test
    fun testWildcardTracing() {
        // Trace all methods via a class wildcard.
        tracer.handleCommandFromTest("trace ${Sample::class.java.name}#*")
        Sample.a()
        assertCallTreeStructure(
            """
            [root] [0]
              Sample.a [1]
                Sample.b [1]
                  Sample.c [2]
                    Sample.d [2]
                      Sample.d [2]
                        Sample.e [2]
                Sample.d [1]
                  Sample.e [1]
            """.trimIndent()
        )

        // Reset.
        tracer.handleCommandFromTest("reset")
        assertCallTreeStructure("[root] [0]")

        // Untrace all methods via a wildcard.
        tracer.handleCommandFromTest("untrace *")
        Sample.a()
        assertCallTreeStructure("[root] [0]")

        // Trace all methods via a package wildcard.
        val packageName = Sample::class.java.name.substringBeforeLast('.')
        tracer.handleCommandFromTest("trace $packageName.*")
        Sample.a()
        assertCallTreeStructure(
            """
            [root] [0]
              Sample.a [1]
                Sample.b [1]
                  Sample.c [2]
                    Sample.d [2]
                      Sample.d [2]
                        Sample.e [2]
                Sample.d [1]
                  Sample.e [1]
            """.trimIndent()
        )
    }

    @Test
    fun testParameterTracing() {
        val paramMethods1 = listOf(
            Sample::paramString, Sample::paramBool, Sample::paramByte, Sample::paramChar,
            Sample::paramShort, Sample::paramInt, Sample::paramLong
        )
        val paramMethods2 = listOf(Sample::paramFloat, Sample::paramDouble)
        for (method in paramMethods1) tracer.handleCommandFromTest("trace ${format(method)}[0]")
        for (method in paramMethods2) tracer.handleCommandFromTest("trace ${format(method)}[0,1]")

        Sample.paramString("hello")
        assertCallTreeStructure(
            """
            [root] [0]
              Sample.paramString(hello) [1]
                Sample.paramBool(true) [1]
                  Sample.paramByte(1) [1]
                    Sample.paramChar(2) [1]
                      Sample.paramShort(3) [2]
                  Sample.paramInt(4) [1]
                    Sample.paramLong(5) [1]
                      Sample.paramFloat(0.0, 6.0) [1]
                        Sample.paramDouble(0.0, 7.0) [1]
                          Sample.paramDouble(0.0, 8.0) [1]
                        Sample.paramDouble(0.0, 8.0) [1]
            """.trimIndent()
        )
    }

    private fun format(method: KFunction<*>): String {
        val className = method.javaMethod!!.declaringClass.name
        val methodName = method.name
        return "$className#$methodName"
    }

    private fun assertCallTreeStructure(expected: String) {
        val callTree = tracer.getCallTreeSnapshot()
        val callTreeStr = renderCallTree(callTree)
        assertEquals(expected, callTreeStr)
    }

    private fun renderCallTree(callTree: CallTree): String {
        val sb = StringBuilder()
        renderCallTree(callTree, sb, 0)
        return sb.toString()
    }

    private fun renderCallTree(callTree: CallTree, sb: StringBuilder, indentWidth: Int) {
        if (indentWidth > 0) sb.appendln()

        val indent = " ".repeat(indentWidth)
        val name = callTree.methodCall.tracepoint.displayName
        val abbreviatedName = name.substringAfter('\$').substringBefore('(')
        val args = callTree.methodCall.arguments?.let { "($it)" } ?: ""
        val count = callTree.callCount
        sb.append("$indent$abbreviatedName$args [$count]")

        val children = callTree.children.values.sortedBy { it.methodCall.tracepoint.displayName }
        for (child in children) {
            renderCallTree(child, sb, indentWidth + 2)
        }
    }
}
