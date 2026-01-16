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

package com.google.idea.perf.mcp

import com.google.idea.perf.AgentLoader
import com.google.idea.perf.tracer.*
import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.McpToolSchema
import com.intellij.mcpserver.McpToolsProvider
import com.intellij.openapi.application.runReadAction
import kotlinx.serialization.json.*
import java.lang.instrument.UnmodifiableClassException

class IdePerfMcpToolsProvider : McpToolsProvider {
    override fun getTools(): List<McpTool> = listOf(
        TraceMethodsTool(),
        UntraceAllTool(),
        GetFlatStatsTool(),
        GetCallTreeTool(),
        ClearTraceDataTool(),
    )
}

private class TraceMethodsTool : McpTool {
    override val descriptor: McpToolDescriptor
        get() = McpToolDescriptor(
            name = "trace_methods",
            description = """
                 Start tracing methods matching a pattern. The pattern format is:
                 - "com.example.MyClass#myMethod" - trace specific method
                 - "com.example.MyClass#*" - trace all methods in a class
                 - "com.example.*#*" - trace all methods in all classes in a package
                 Returns success message with number of classes instrumented.
            """.trimIndent(),
            inputSchema = McpToolSchema(
                propertiesSchema = buildJsonObject {
                    put("pattern", buildJsonObject {
                        put("type", "string")
                        put("description", "Method pattern to trace (e.g., 'com.example.MyClass#myMethod')")
                    })
                },
                requiredProperties = setOf("pattern"),
                definitions = emptyMap()
            )
        )

    override suspend fun call(args: JsonObject): McpToolCallResult {
        val pattern = args["pattern"]?.jsonPrimitive?.content
            ?: return McpToolCallResult.error("'pattern' argument is required")

        if (!AgentLoader.ensureTracerHooksInstalled) {
            return McpToolCallResult.error("Failed to install instrumentation agent. Check idea.log for details.")
        }

        // Parse the pattern: "com.example.Class#method" or "com.example.*#*"
        val parts = pattern.split("#", limit = 2)
        val className = parts[0]
        val methodName = if (parts.size > 1) parts[1] else "*"

        if (className.isBlank()) {
            return McpToolCallResult.error("Invalid pattern. Use format: 'com.example.Class#method' or 'com.example.*#*'")
        }

        val methodPattern = MethodFqName(className, methodName, "*")
        val config = MethodConfig(enabled = true)
        val request = TracerConfigUtil.appendTraceRequest(methodPattern, config)

        // Find and retransform affected classes
        val affectedClasses = runReadAction {
            TracerConfigUtil.getAffectedClasses(listOf(request))
        }

        val instrumentation = AgentLoader.instrumentation
            ?: return McpToolCallResult.error("Instrumentation not available")

        var transformedCount = 0
        var errorCount = 0
        for (clazz in affectedClasses) {
            try {
                instrumentation.retransformClasses(clazz)
                transformedCount++
            } catch (e: UnmodifiableClassException) {
                errorCount++
            } catch (e: Throwable) {
                errorCount++
            }
        }

        CallTreeManager.clearCallTrees()

        return McpToolCallResult.text(
            buildString {
                append("Tracing enabled for pattern '$pattern'. ")
                append("Instrumented $transformedCount classes.")
                if (errorCount > 0) {
                    append(" ($errorCount classes could not be instrumented)")
                }
            }
        )
    }
}

private class UntraceAllTool : McpTool {
    override val descriptor: McpToolDescriptor
        get() = McpToolDescriptor(
            name = "untrace_all",
            description = """
                Stop all tracing and reset instrumentation.
                Removes all tracepoints and clears collected data
            """.trimIndent(),
            inputSchema = McpToolSchema(
                propertiesSchema = buildJsonObject {},
                requiredProperties = emptySet(),
                definitions = emptyMap()
            )
        )

    override suspend fun call(args: JsonObject): McpToolCallResult {
        if (!AgentLoader.ensureTracerHooksInstalled) {
            return McpToolCallResult.error("Instrumentation agent not available")
        }

        val oldRequests = TracerConfig.clearAllRequests()
        if (oldRequests.isEmpty()) {
            return McpToolCallResult.error("No active tracing. Nothing to reset.")
        }

        val affectedClasses = runReadAction {
            TracerConfigUtil.getAffectedClasses(oldRequests)
        }

        val instrumentation = AgentLoader.instrumentation
            ?: return McpToolCallResult.error("Instrumentation not available")

        var transformedCount = 0
        for (clazz in affectedClasses) {
            try {
                instrumentation.retransformClasses(clazz)
                transformedCount++
            } catch (e: Throwable) {
                // Ignore errors during cleanup
            }
        }

        CallTreeManager.clearCallTrees()

        return McpToolCallResult.text("All tracing disabled. Reset $transformedCount classes.")
    }
}

private class GetFlatStatsTool : McpTool {
    override val descriptor: McpToolDescriptor
        get() = McpToolDescriptor(
            name = "get_flat_stats",
            description = """
                Get aggregated tracing statistics sorted by wall time (descending).
                Returns a list of methods with call counts and timing information.
                Optional 'limit' argument (default: 20) controls max number of results.
            """.trimIndent(),
            inputSchema = McpToolSchema(
                propertiesSchema = buildJsonObject {
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum number of results to return (default: 20)")
                    })
                },
                requiredProperties = emptySet(),
                definitions = emptyMap()
            )
        )

    override suspend fun call(args: JsonObject): McpToolCallResult {
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 20

        val callTree = CallTreeManager.getCallTreeSnapshotAllThreadsMerged()
        val stats = CallTreeUtil.computeFlatTracepointStats(callTree)

        if (stats.isEmpty()) {
            return McpToolCallResult.error("No tracing data collected yet. Use trace_methods first, then trigger the code you want to measure.")
        }

        val sortedStats = stats.sortedByDescending { it.wallTime }
        val overhead = CallTreeUtil.estimateTracingOverhead(callTree)

        return McpToolCallResult.text(
            buildString {
                appendLine("=== Flat Tracing Statistics (top $limit by wall time) ===")
                appendLine()
                sortedStats.take(limit).forEachIndexed { index, stat ->
                    appendLine("${index + 1}. ${stat.tracepoint.displayName}")
                    appendLine("   calls=${stat.callCount}, wallTime=${formatTime(stat.wallTime)}, maxWallTime=${formatTime(stat.maxWallTime)}")
                }
                appendLine()
                appendLine("Estimated tracing overhead: ${formatTime(overhead)}")
                appendLine("Total methods tracked: ${stats.size}")
            }
        )
    }
}

private class GetCallTreeTool : McpTool {
    override val descriptor: McpToolDescriptor
        get() = McpToolDescriptor(
            name = "get_call_tree",
            description = """
                Get the hierarchical call tree showing method invocation structure.
                Shows parent-child relationships between traced methods with timing.
                Optional 'max_depth' argument (default: 10) limits tree depth.
                """.trimIndent(),
            inputSchema = McpToolSchema(
                propertiesSchema = buildJsonObject {
                    put("max_depth", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum tree depth to display (default: 10)")
                    })
                },
                requiredProperties = emptySet(),
                definitions = emptyMap()
            )
        )

    override suspend fun call(args: JsonObject): McpToolCallResult {
        val maxDepth = args["max_depth"]?.jsonPrimitive?.intOrNull ?: 10

        val callTree = CallTreeManager.getCallTreeSnapshotAllThreadsMerged()

        if (callTree.children.isEmpty()) {
            return McpToolCallResult.error("No call tree data collected yet. Use trace_methods first, then trigger the code you want to measure.")
        }

        return McpToolCallResult.text(
            buildString {
                appendLine("=== Call Tree (max depth: $maxDepth) ===")
                appendLine()
                appendCallTree(callTree, 0, maxDepth)
            }
        )
    }

    private fun StringBuilder.appendCallTree(node: CallTree, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) {
            appendLine("${"  ".repeat(depth)}... (truncated)")
            return
        }

        val indent = "  ".repeat(depth)
        val name = node.tracepoint.displayName
        val calls = node.callCount
        val time = formatTime(node.wallTime)
        val maxTime = formatTime(node.maxWallTime)

        if (depth == 0) {
            appendLine(name)
        } else {
            appendLine("$indent$name [calls=$calls, time=$time, max=$maxTime]")
        }

        // Sort children by wall time descending for easier analysis
        val sortedChildren = node.children.values.sortedByDescending { it.wallTime }
        for (child in sortedChildren) {
            appendCallTree(child, depth + 1, maxDepth)
        }
    }
}

private class ClearTraceDataTool : McpTool {
    override val descriptor: McpToolDescriptor
        get() = McpToolDescriptor(
            name = "clear_trace_data",
            description = """
                Clear collected tracing data without removing tracepoints.
                Use this to reset measurements between test runs while keeping the same methods traced.
                """.trimIndent(),
            inputSchema = McpToolSchema(
                propertiesSchema = buildJsonObject {},
                requiredProperties = emptySet(),
                definitions = emptyMap()
            )
        )

    override suspend fun call(args: JsonObject): McpToolCallResult {
        CallTreeManager.clearCallTrees()
        return McpToolCallResult.text("Tracing data cleared. Tracepoints are still active.")
    }
}

private fun formatTime(nanos: Long): String {
    return when {
        nanos >= 1_000_000_000 -> String.format("%.2fs", nanos / 1_000_000_000.0)
        nanos >= 1_000_000 -> String.format("%.2fms", nanos / 1_000_000.0)
        nanos >= 1_000 -> String.format("%.2fus", nanos / 1_000.0)
        else -> "${nanos}ns"
    }
}
