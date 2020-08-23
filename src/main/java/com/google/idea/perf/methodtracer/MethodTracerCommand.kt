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

/** A tracer CLI command */
sealed class MethodTracerCommand {
    /** Unrecognized command. */
    object Unknown: MethodTracerCommand()

    /** Zero out all tracepoint data, but keep call tree. */
    object Clear: MethodTracerCommand()

    /** Zero out all tracepoint data and reset the call tree. */
    object Reset: MethodTracerCommand()

    /** Trace or untrace a set of methods. */
    data class Trace(
        val enable: Boolean,
        val traceOption: TraceOption?,
        val target: TraceTarget?
    ): MethodTracerCommand()

    /**
     * Checks for syntax errors. If no error exists, then all fields within this structure are
     * non-null values.
     */
    val errors: List<String>
        get() = when (this) {
            Unknown -> listOf("Unknown command")
            is Trace -> when {
                traceOption == null -> listOf("Expected a trace option")
                target == null -> listOf("Expected a trace target")
                else -> target.errors
            }
            else -> emptyList()
        }
}

/** Represents what to trace */
enum class TraceOption {
    /** Option to trace all aspects of a method's execution. */
    ALL,
    /** Option to trace the number of calls to a method. */
    CALL_COUNT,
    /** Option to trace the execution time of a method. */
    WALL_TIME;

    val tracepointFlag: Int
        get() = when (this) {
            ALL -> TracepointFlags.TRACE_ALL
            CALL_COUNT -> TracepointFlags.TRACE_CALL_COUNT
            WALL_TIME -> TracepointFlags.TRACE_WALL_TIME
        }
}

/** A set of methods that the tracer will trace. */
sealed class TraceTarget {
    /** Trace the tracer's own internal methods. */
    object Tracer: TraceTarget()

    /** Trace some important methods of the PSI. */
    object PsiFinders: TraceTarget()

    /** Trace everything. */
    object All: TraceTarget()

    /** Trace all methods of classes that match a wildcard. */
    data class ClassPattern(val classPattern: String): TraceTarget()

    /** Trace methods that match a wildcard. */
    data class MethodPattern(
        val className: String,
        val methodPattern: String
    ): TraceTarget()

    /** Trace a specific method. */
    data class Method(
        val className: String,
        val methodName: String?,
        val parameterIndexes: List<Int>?
    ): TraceTarget()

    val errors: List<String>
        get() = when (this) {
            is Method -> when {
                methodName == null -> listOf("Expected a method name")
                parameterIndexes == null -> listOf("Invalid parameter index syntax")
                else -> emptyList()
            }
            else -> emptyList()
        }
}

fun parseMethodTracerCommand(text: String): MethodTracerCommand {
    val tokens = tokenize(text)
    if (tokens.isEmpty()) {
        return MethodTracerCommand.Unknown
    }

    return when (tokens.first()) {
        ClearKeyword -> MethodTracerCommand.Clear
        ResetKeyword -> MethodTracerCommand.Reset
        TraceKeyword -> parseTraceCommand(tokens.advance(), true)
        UntraceKeyword -> parseTraceCommand(tokens.advance(), false)
        else -> MethodTracerCommand.Unknown
    }
}

private fun parseTraceCommand(tokens: List<Token>, enable: Boolean): MethodTracerCommand {
    return when (val option = parseTraceOption(tokens)) {
        null -> MethodTracerCommand.Trace(enable, TraceOption.ALL, parseTraceTarget(tokens))
        else -> MethodTracerCommand.Trace(enable, option, parseTraceTarget(tokens.advance()))
    }
}

private fun parseTraceOption(tokens: List<Token>): TraceOption? {
    return when (tokens.first()) {
        AllKeyword -> TraceOption.ALL
        CountKeyword -> TraceOption.CALL_COUNT
        WallTimeKeyword -> TraceOption.WALL_TIME
        else -> null
    }
}

private fun parseTraceTarget(tokens: List<Token>): TraceTarget? {
    val first = tokens.firstOrNull()
    val second = tokens.getOrNull(1)
    val third = tokens.getOrNull(2)
    val fourth = tokens.getOrNull(3)

    return when {
        first is PsiFindersKeyword -> TraceTarget.PsiFinders
        first is TracerKeyword -> TraceTarget.Tracer
        first is Pattern && first.text == "*" -> TraceTarget.All
        first is Pattern -> TraceTarget.ClassPattern(first.textString)
        first is Identifier && second is HashSymbol && third is Pattern ->
            TraceTarget.MethodPattern(first.textString, third.textString)
        first is Identifier && second is HashSymbol && third is Identifier && fourth is OpenBracketSymbol ->
            TraceTarget.Method(first.textString, third.textString, parseParameterList(tokens.advance(4)))
        first is Identifier && second is HashSymbol && third is Identifier ->
            TraceTarget.Method(first.textString, third.textString, emptyList())
        first is Identifier && second is HashSymbol ->
            TraceTarget.Method(first.textString, "", null)
        first is Identifier ->
            TraceTarget.Method(first.textString, null, null)
        else -> null
    }
}

private fun parseParameterList(tokens: List<Token>): List<Int>? {
    val parameters = mutableListOf<Int>()

    when (val token = tokens.firstOrNull()) {
        is IntLiteral -> parameters.add(token.value)
        else -> return null
    }

    var nextToken = tokens.advance()
    while (nextToken.firstOrNull() is CommaSymbol) {
        nextToken = nextToken.advance()
        when (val token = nextToken.firstOrNull()) {
            is IntLiteral -> parameters.add(token.value)
            else -> return null
        }
        nextToken = nextToken.advance()
    }

    return when (nextToken.firstOrNull()) {
        is CloseBracketSymbol -> return parameters
        else -> null
    }
}

private fun <E> List<E>.advance(numTokens: Int = 1): List<E> {
    return this.subList(numTokens, this.size)
}

private sealed class Token
private object UnrecognizedToken: Token()
private data class Identifier(val text: CharSequence): Token() {
    val textString: String get() = text.toString()
}
private data class Pattern(val text: CharSequence): Token() {
    val textString: String get() = text.toString()
}
private data class IntLiteral(val value: Int): Token()
private object EndOfLine: Token()
private object ClearKeyword: Token()
private object ResetKeyword: Token()
private object TraceKeyword: Token()
private object UntraceKeyword: Token()
private object AllKeyword: Token()
private object CountKeyword: Token()
private object WallTimeKeyword: Token()
private object PsiFindersKeyword: Token()
private object TracerKeyword: Token()
private object HashSymbol: Token()
private object CommaSymbol: Token()
private object OpenBracketSymbol: Token()
private object CloseBracketSymbol: Token()

private fun tokenize(text: CharSequence): List<Token> {
    fun Char.isIdentifierChar() =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '.' || this == '-' ||
                this == '_' || this == '$' || this == '*' || this == '<' || this == '>'

    val tokens = mutableListOf<Token>()
    var offset = 0

    while (true) {
        while (offset < text.length && text[offset].isWhitespace()) {
            offset++
        }

        if (offset >= text.length) {
            break
        }

        when (text[offset]) {
            in 'A'..'Z', in 'a'..'z', '.', '-', '_', '$', '*', '<', '>' -> {
                val startOffset = offset
                while (offset < text.length && text[offset].isIdentifierChar()) {
                    offset++
                }

                when (val identifierText = text.subSequence(startOffset, offset)) {
                    "clear" -> tokens.add(ClearKeyword)
                    "reset" -> tokens.add(ResetKeyword)
                    "trace" -> tokens.add(TraceKeyword)
                    "untrace" -> tokens.add(UntraceKeyword)
                    "all" -> tokens.add(AllKeyword)
                    "count" -> tokens.add(CountKeyword)
                    "wall-time" -> tokens.add(WallTimeKeyword)
                    "psi-finders" -> tokens.add(PsiFindersKeyword)
                    "tracer" -> tokens.add(TracerKeyword)
                    else -> {
                        if (identifierText.contains('*')) {
                            tokens.add(Pattern(identifierText))
                        }
                        else {
                            tokens.add(Identifier(identifierText))
                        }
                    }
                }
            }
            in '0'..'9' -> {
                var value = 0
                while (offset < text.length && text[offset] in '0'..'9') {
                    value = (value * 10) + (text[offset].toInt() - '0'.toInt())
                    offset++
                }
                tokens.add(IntLiteral(value))
            }
            '#' -> { tokens.add(HashSymbol); offset++ }
            ',' -> { tokens.add(CommaSymbol); offset++ }
            '[' -> { tokens.add(OpenBracketSymbol); offset++ }
            ']' -> { tokens.add(CloseBracketSymbol); offset++ }
            else -> { tokens.add(UnrecognizedToken); offset++ }
        }
    }

    tokens.add(EndOfLine)
    return tokens
}
