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

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionUtilCore
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.CharFilter
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.progress.ProgressManager
import com.intellij.util.textCompletion.DefaultTextCompletionValueDescriptor
import com.intellij.util.textCompletion.TextCompletionProvider

private class AutocompleteMatcher(val pattern: String): PrefixMatcher(pattern) {
    override fun prefixMatches(name: String): Boolean {
        return fuzzyMatch(name, pattern) != null
    }

    override fun cloneWithPrefix(prefix: String): PrefixMatcher {
        return AutocompleteMatcher(pattern)
    }
}

class AutocompleteCompletionProvider: TextCompletionProvider {
    @Volatile
    private var classNames = emptyList<String>()

    fun setClasses(classes: Collection<Class<*>>) {
        classNames = classes.mapNotNull { it.canonicalName }
    }

    override fun applyPrefixMatcher(
        result: CompletionResultSet,
        prefix: String
    ): CompletionResultSet {
        return result.withPrefixMatcher(AutocompleteMatcher(prefix))
    }

    override fun getAdvertisement(): String? = ""

    override fun getPrefix(text: String, offset: Int): String? {
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
        val suggestions = predict(text, offset)

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

    private fun predict(text: String, offset: Int): List<String> {
        val tokens = text.trimStart().split(' ', '\t')
        val normalizedText = tokens.joinToString(" ")
        val command = parseTracerCommand(normalizedText)
        val tokenIndex = getTokenIndex(normalizedText, offset)
        val token = tokens.getOrElse(tokenIndex) { "" }

        return when (tokenIndex) {
            0 -> predictToken(
                listOf("clear", "reset", "trace", "untrace"), token
            )
            1 -> when (command) {
                is TracerCommand.Trace -> {
                    val options = predictToken(listOf("all", "count", "wall-time"), token)
                    val classes = predictToken(classNames, token)
                    return options + classes
                }
                else -> emptyList()
            }
            2 -> when {
                command is TracerCommand.Trace && command.target is TraceTarget.Method ->
                    predictMethodToken(command.target.className, token)
                command is TracerCommand.Trace -> predictToken(classNames, token)
                else -> emptyList()
            }
            3 -> when {
                command is TracerCommand.Trace && command.target is TraceTarget.Method ->
                    predictMethodToken(command.target.className, token)
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    private fun predictToken(choices: Collection<String>, token: String): List<String> {
        return fuzzySearch(choices, token, -1) { ProgressManager.checkCanceled() }
            .map { it.source }
    }

    private fun predictMethodToken(className: String, token: String): List<String> {
        val clazz = Class.forName(className)
        val methodNames = clazz.methods.map { it.name.substringAfter('$') }
        return predictToken(methodNames, token)
    }

    private fun getTokenIndex(input: String, index: Int): Int {
        return input.subSequence(0, index).count { it.isWhitespace() || it == '#' }
    }
}
