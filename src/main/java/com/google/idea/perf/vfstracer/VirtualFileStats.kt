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

package com.google.idea.perf.vfstracer

import java.util.*

/** A file containing VirtualFile statistics. */
data class VirtualFileStats(
    val fileName: String,
    val stubIndexAccesses: Int,
    val psiElementWraps: Int
)

/** Extracts all files from a [VirtualFileTree] and creates flattened list of [VirtualFileStats]. */
fun VirtualFileTree.flattenedList(): List<VirtualFileStats> {
    fun flattenImpl(
        tree: VirtualFileTree,
        pathBuilder: Stack<String>,
        list: MutableList<VirtualFileStats>
    ) {
        pathBuilder.push(tree.name)

        if (tree.isDirectory) {
            for (child in tree.children.values) {
                flattenImpl(child, pathBuilder, list)
            }
        }
        else {
            list.add(VirtualFileStats(
                pathBuilder.toTypedArray().joinToString("/"),
                tree.stubIndexAccesses,
                tree.psiElementWraps
            ))
        }

        pathBuilder.pop()
    }

    val pathBuilder = Stack<String>()
    val list = mutableListOf<VirtualFileStats>()
    for (child in children.values) {
        flattenImpl(child, pathBuilder, list)
    }
    return list
}
