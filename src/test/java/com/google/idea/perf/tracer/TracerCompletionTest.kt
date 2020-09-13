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

import com.google.common.truth.Truth.assertThat
import com.intellij.openapi.application.runWriteAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.textCompletion.TextCompletionUtil
import org.junit.Test

/** Tests [TracerCompletionProvider]. */
@Suppress("SpellCheckingInspection")
class TracerCompletionTest : BasePlatformTestCase() {

    // Test data.
    @Suppress("unused")
    private class UniqueClass1729 {
        fun foo() {}
        fun bar() {}
    }

    override fun setUp() {
        super.setUp()
        UniqueClass1729() // Triggers class loading.
        val psiFile = myFixture.configureByText("test.txt", "")
        TextCompletionUtil.installProvider(psiFile, TracerCompletionProvider(), true)
    }

    @Test
    fun testCommandCompletion() {
        checkInsert("t", "trace ")
        checkInsert("u", "untrace ")
        checkInsert("c", "clear")
        checkInsert(" r", " reset")
        checkInsert("r ", null)
        checkInsert("reset MyClass", null)
        assertThat(complete("")).containsExactly("trace", "untrace", "clear", "reset")
    }

    @Test
    fun testClassCompletion() {
        val fqName = UniqueClass1729::class.java.name
        checkInsert("trace UniqueClass1729", "trace $fqName#")
        checkInsert("trace Unique1729", "trace $fqName#")
        checkInsert("trace uniqueclass1729", "trace $fqName#")
        checkInsert("trace google.UniqueClass", "trace $fqName#")
        checkInsert("trace $fqName", "trace $fqName#")
        checkInsert("trace NonExistentClass1024", null)
    }

    @Test
    fun testMethodCompletion() {
        val fqName = UniqueClass1729::class.java.name
        checkInsert("trace $fqName#b", "trace $fqName#bar")
        checkInsert("trace $fqName#foo", "trace $fqName#foo")
        checkInsert("trace $fqName#<", "trace $fqName#<init>")
        checkInsert("trace $fqName#all", "trace $fqName#*")
        checkInsert("trace $fqName#nonExistentMethod", null)
        checkInsert("trace $fqName#toString", null)
        assertThat(complete("trace $fqName#")).containsExactly("all", "foo", "bar", "<init>")
    }

    @Test
    fun testPackageCompletion() {
        val packageName = UniqueClass1729::class.java.packageName
        checkInsert("trace $packageName", "trace $packageName.*")
    }

    @Test
    fun testUntraceCompletion() {
        val fqName = UniqueClass1729::class.java.name
        TracerConfig.trace(TracePattern.ByMethodName(fqName, "foo"))
        TracerConfig.trace(TracePattern.ByMethodName(fqName, "bar"))
        try {
            checkInsert("untrace UniqueClass1729", "untrace $fqName#")
            assertThat(complete("untrace ")).containsExactly("UniqueClass1729")
        }
        finally {
            TracerConfig.untraceAll()
        }
    }

    /** Checks that [text] auto-completes to [after]. */
    private fun checkInsert(text: String, after: String?) {
        val lookupStrings = complete(text)
        if (after == null) {
            assertThat(lookupStrings).isEmpty()
        } else {
            assertThat(lookupStrings).isNotEmpty()
            assertThat(after).isEqualTo(myFixture.editor.document.text)
        }
    }

    /**
     * Runs completion and inserts the first option (if any).
     * Returns the list of all options that were available.
     */
    private fun complete(text: String): List<String> {
        val editor = myFixture.editor
        val document = editor.document

        runWriteAction {
            document.setText(text)
            editor.caretModel.moveToOffset(text.length)
        }

        myFixture.completeBasic()

        val lookupStrings = myFixture.lookupElementStrings
        if (lookupStrings == null) {
            // There was exactly one completion item and it is already inserted.
            return listOf(document.text)
        }

        if (lookupStrings.isNotEmpty()) {
            // Select the first completion item.
            myFixture.finishLookup('\t')
        }

        return lookupStrings
    }
}
