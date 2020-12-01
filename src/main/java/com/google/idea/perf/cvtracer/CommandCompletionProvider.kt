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
import com.intellij.codeInsight.lookup.CharFilter
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.util.textCompletion.TextCompletionProvider

class CommandCompletionProvider : TextCompletionProvider {
    override fun applyPrefixMatcher(
        result: CompletionResultSet,
        prefix: String
    ): CompletionResultSet {
        return result.withPrefixMatcher(prefix)
    }

    override fun getAdvertisement(): String? = null

    override fun getPrefix(text: String, offset: Int): String {
        val separators = charArrayOf(' ', '\t')
        val lastSeparatorPos = text.lastIndexOfAny(separators, offset - 1)
        return text.substring(lastSeparatorPos + 1, offset)
    }

    override fun fillCompletionVariants(
        parameters: CompletionParameters,
        prefix: String,
        result: CompletionResultSet
    ) {
        val textBeforeCaret = parameters.editor.document.text.substring(0, parameters.offset)
        val words = textBeforeCaret.split(' ', '\t').filter(String::isNotBlank)
        val normalizedText = words.joinToString(" ")
        val command = parseCachedValueTracerCommand(normalizedText)

        var tokenIndex = normalizedText.count(Char::isWhitespace)
        if (textBeforeCaret.isNotBlank() && textBeforeCaret.last().isWhitespace()) {
            ++tokenIndex
        }

        val elements = when (tokenIndex) {
            0 -> listOf("clear", "reset", "filter", "clear-filters", "group-by")
            1 -> when (command) {
                is CachedValueTracerCommand.GroupBy -> listOf("class", "stack-trace")
                else -> emptyList()
            }
            else -> emptyList()
        }

        result.addAllElements(elements.map(LookupElementBuilder::create))
        result.stopHere()
    }

    override fun acceptChar(c: Char): CharFilter.Result {
        return when (c) {
            ' ', '\t' -> CharFilter.Result.HIDE_LOOKUP
            else -> CharFilter.Result.ADD_TO_PREFIX
        }
    }
}
