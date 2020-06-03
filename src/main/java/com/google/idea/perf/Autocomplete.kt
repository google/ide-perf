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

data class Suggestion(val name: String, val formattedName: String? = null)

class Autocomplete(classes: Collection<Class<*>>) {
    private val classNames: List<String> = classes.mapNotNull { it.canonicalName }

    fun predict(input: String, index: Int): List<Suggestion> {
        val tokens = input.trimStart().split(' ')
        val normalizedInput = tokens.joinToString(" ")
        val command = parseTracerCommand(normalizedInput)
        val tokenIndex = getTokenIndex(normalizedInput, index)
        val token = tokens.getOrElse(tokenIndex) { "" }

        return when (tokenIndex) {
            0 -> predictToken(
                listOf("clear", "reset", "trace", "untrace"),
                token
            )
            1 -> when (command) {
                is TracerCommand.Trace -> predictToken(
                    listOf("all", "count", "wall-time"),
                    token
                )
                else -> emptyList()
            }
            2 -> when (command) {
                is TracerCommand.Trace -> predictToken(classNames, token)
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    private fun predictToken(choices: Collection<String>, token: String): List<Suggestion> {
        return fuzzyMatchMany(choices, token).filter { it.score > 0 }.map { Suggestion(it.source) }
    }

    private fun getTokenIndex(input: String, index: Int): Int {
        var tokenIndex = 0

        for (i in 0..index) {
            if (input[i].isWhitespace()) {
                tokenIndex++
            }
        }

        return tokenIndex
    }
}
