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

import com.google.idea.perf.CommandPredictor
import com.google.idea.perf.util.fuzzySearch
import com.intellij.openapi.progress.ProgressManager

class MethodTracerCommandPredictor: CommandPredictor {
    @Volatile
    private var classNames = emptyList<String>()

    fun setClasses(classes: Collection<Class<*>>) {
        classNames = classes.mapNotNull { it.canonicalName }
    }

    override fun predict(text: String, offset: Int): List<String> {
        val tokens = text.trimStart().split(' ', '\t')
        val normalizedText = tokens.joinToString(" ")
        val command = parseMethodTracerCommand(normalizedText)
        val tokenIndex = getTokenIndex(normalizedText, offset)
        val token = tokens.getOrElse(tokenIndex) { "" }

        return when (tokenIndex) {
            0 -> predictToken(
                listOf("clear", "reset", "trace", "untrace"), token
            )
            1 -> when (command) {
                is MethodTracerCommand.Trace -> {
                    val options = predictToken(listOf("all", "count", "wall-time"), token)
                    val classes = predictToken(classNames, token)
                    return options + classes
                }
                else -> emptyList()
            }
            2, 3 -> when (command) {
                is MethodTracerCommand.Trace -> predictClassOrMethod(command, token)
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    private fun predictClassOrMethod(
        command: MethodTracerCommand.Trace, token: String
    ): List<String> {
        val target = command.target
        return if (target is TraceTarget.Method && target.methodName != null) {
            predictMethodToken(target.className, token)
        }
        else {
            predictToken(classNames, token)
        }
    }

    private fun predictToken(choices: Collection<String>, token: String): List<String> {
        return fuzzySearch(choices, token, -1) { ProgressManager.checkCanceled() }
            .map { it.source }
    }

    private fun predictMethodToken(className: String, token: String): List<String> {
        val clazz = AgentLoader.instrumentation?.allLoadedClasses?.firstOrNull {
            it.canonicalName == className
        }
        val methodNames = clazz?.methods?.map { it.name.substringAfter('$') }
        if (methodNames != null) {
            return predictToken(methodNames, token)
        }
        return emptyList()
    }

    private fun getTokenIndex(input: String, index: Int): Int {
        return input.subSequence(0, index).count { it.isWhitespace() || it == '#' }
    }
}
