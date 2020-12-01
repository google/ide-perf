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

package com.google.idea.perf.cvtracer

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionUtilCore
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.CharFilter
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.util.textCompletion.DefaultTextCompletionValueDescriptor
import com.intellij.util.textCompletion.TextCompletionProvider

interface CommandPredictor {
    fun predict(text: String, offset: Int): List<String>
}

class CommandCompletionProvider(
    private val predictor: CommandPredictor
): TextCompletionProvider {
    override fun applyPrefixMatcher(
        result: CompletionResultSet,
        prefix: String
    ): CompletionResultSet {
        return result.withPrefixMatcher(prefix)
    }

    override fun getAdvertisement(): String = ""

    override fun getPrefix(text: String, offset: Int): String {
        var start = Int.MIN_VALUE
        for (char in listOf(' ', '\t', '#')) {
            start = maxOf(start, text.lastIndexOf(char, offset - 1))
        }
        return text.substring(start + 1, offset)
    }

    override fun fillCompletionVariants(
        parameters: CompletionParameters,
        prefix: String,
        result: CompletionResultSet
    ) {
        val text = parameters.position.text.substringBeforeLast(CompletionUtilCore.DUMMY_IDENTIFIER)
        val offset = parameters.offset
        val suggestions = predictor.predict(text, offset)

        val descriptor = DefaultTextCompletionValueDescriptor.StringValueDescriptor()
        val elements = ArrayList<LookupElement>()

        for ((index, suggestion) in suggestions.withIndex()) {
            val builder = descriptor.createLookupBuilder(suggestion)
            val element = PrioritizedLookupElement.withPriority(builder, -index.toDouble())
            elements.add(element)
        }

        result.addAllElements(elements)
    }

    override fun acceptChar(c: Char): CharFilter.Result? {
        if (c == ' ' || c == '\t' || c == '#') {
            return CharFilter.Result.HIDE_LOOKUP
        }
        return CharFilter.Result.ADD_TO_PREFIX
    }
}
