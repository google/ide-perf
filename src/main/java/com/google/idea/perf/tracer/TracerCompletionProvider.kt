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

import com.google.idea.perf.tracer.TracerCompletionUtil.LenientPrefixMatcher
import com.intellij.codeInsight.completion.AddSpaceInsertHandler
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.CharFilter
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.util.textCompletion.TextCompletionProvider

// Things to improve:
// * Consider configuring typo detection, case sensitivity, etc.
// * Consider showing completion immediately after the user types "trace" (if performance is ok).
// * Consider showing documentation while completing the primary commands (reset, trace, etc.)
// * The default prefix matcher seems to treat '*' as a pattern for completion, which is strange.
// * When auto-completing an untrace command, only show methods which were traced.

/**
 * Adds auto-completion for tracing commands.
 *
 * Some examples:
 *   "t" => "trace"
 *   "trace Object" => "trace java.lang.Object"
 *   "trace java.lang.Object#toStr" => "trace java.lang.Object#toString"
 */
class TracerCompletionProvider : TextCompletionProvider, DumbAware {

    override fun fillCompletionVariants(
        parameters: CompletionParameters,
        prefix: String,
        initialResult: CompletionResultSet
    ) {
        var result = initialResult
        val textBeforeCaret = parameters.editor.document.text.substring(0, parameters.offset)
        val command = parseMethodTracerCommand(textBeforeCaret)

        val words = textBeforeCaret.split(' ', '\t').filter(String::isNotBlank)
        val normalizedText = words.joinToString(" ")

        fun isTokenSeparator(c: Char): Boolean = c.isWhitespace() || c == '#'
        var tokenIndex = normalizedText.count(::isTokenSeparator)
        if (textBeforeCaret.isNotBlank() && isTokenSeparator(textBeforeCaret.last())) {
            ++tokenIndex
        }

        when (tokenIndex) {
            0 -> {
                // We want all commands to be shown regardless of the prefix (for discoverability).
                val allCommands = setOf("clear", "reset", "trace", "scan", "untrace")
                val prefixMatcher = LenientPrefixMatcher(result.prefixMatcher, allCommands)
                result = result.withPrefixMatcher(prefixMatcher)

                result.addElement(LookupElementBuilder.create("clear"))
                result.addElement(LookupElementBuilder.create("reset"))
                result.addElement(
                    LookupElementBuilder.create("trace")
                        .withTailText(" <method>")
                        .withInsertHandler(AddSpaceInsertHandler.INSTANCE_WITH_AUTO_POPUP)
                )
                result.addElement(LookupElementBuilder.create("scan"))
                result.addElement(
                    LookupElementBuilder.create("untrace")
                        .withTailText(" <method>")
                        .withInsertHandler(AddSpaceInsertHandler.INSTANCE_WITH_AUTO_POPUP)
                )
            }
            1 -> {
                if (command is TracerCommand.Trace) {
                    if (command.enable) {
                        TracerCompletionUtil.addLookupElementsForLoadedClasses(result)
                    } else {
                        val wildcard = TracerCompletionUtil.WildcardLookupElement.withPriority(1.0)
                        result.addElement(wildcard)

                        val traceRequests = TracerConfig.getAllRequests()
                        val affectedClasses = ClassRegistry.affectedClasses(traceRequests)
                        for (clazz in affectedClasses) {
                            ProgressManager.checkCanceled()
                            val lookup = TracerCompletionUtil.createClassLookupElement(clazz)
                            result.addElement(lookup)

                        }
                    }
                }
            }
            2, 3 -> {
                if (command is TracerCommand.Trace) {
                    val target = command.target
                    if (target is TraceTarget.Method && target.methodName != null) {
                        TracerCompletionUtil.addLookupElementsForMethods(target.className, result)
                        val wildcard = TracerCompletionUtil.WildcardLookupElement.withPriority(1.0)
                        result.addElement(wildcard)
                    } else {
                        TracerCompletionUtil.addLookupElementsForLoadedClasses(result)
                    }
                }
            }
        }

        result.stopHere()
    }

    private fun LookupElement.withPriority(priority: Double): LookupElement {
        return PrioritizedLookupElement.withPriority(this, priority)
    }

    override fun getPrefix(text: String, offset: Int): String {
        val separators = charArrayOf(' ', '\t', '#')
        val lastSeparatorPos = text.lastIndexOfAny(separators, offset - 1)
        return text.substring(lastSeparatorPos + 1, offset)
    }

    override fun applyPrefixMatcher(
        result: CompletionResultSet, prefix: String
    ): CompletionResultSet {
        return result.withPrefixMatcher(prefix)
    }

    override fun acceptChar(c: Char): CharFilter.Result {
        return when (c) {
            ' ', '\t', '#' -> CharFilter.Result.HIDE_LOOKUP
            else -> CharFilter.Result.ADD_TO_PREFIX
        }
    }

    override fun getAdvertisement(): String? = null
}
