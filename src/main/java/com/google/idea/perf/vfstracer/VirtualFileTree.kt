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

import java.io.File
import java.util.*

/** A tree containing VirtualFile statistics. */
interface VirtualFileTree {
    companion object {
        val EMPTY: VirtualFileTree = MutableVirtualFileTree.createRoot()
    }

    val name: String
    val stubIndexAccesses: Int
    val psiElementWraps: Int
    val children: Map<String, VirtualFileTree>

    val isDirectory: Boolean get() = children.isNotEmpty()
    val isFile: Boolean get() = children.isEmpty()

    fun statEquals(other: VirtualFileTree): Boolean =
        stubIndexAccesses == other.stubIndexAccesses && psiElementWraps == other.psiElementWraps
}

class MutableVirtualFileTree(
    override val name: String
): VirtualFileTree {
    companion object {
        fun createRoot() = MutableVirtualFileTree("[root]")
    }

    override var stubIndexAccesses: Int = 0
    override var psiElementWraps: Int = 0
    override val children: MutableMap<String, MutableVirtualFileTree> = TreeMap()

    fun clear() {
        fun clearImpl(tree: MutableVirtualFileTree) {
            tree.stubIndexAccesses = 0
            tree.psiElementWraps = 0
            for (child in tree.children.values) {
                clearImpl(child)
            }
        }
        clearImpl(this)
    }

    fun accumulate(tree: VirtualFileTree) {
        for ((childName, child) in tree.children) {
            val thisChild = children.getOrPut(childName) { MutableVirtualFileTree(childName) }
            thisChild.accumulate(child)
        }

        stubIndexAccesses += tree.stubIndexAccesses
        psiElementWraps += tree.psiElementWraps
    }

    fun accumulate(
        path: String,
        stubIndexAccesses: Int = 0,
        psiElementWraps: Int = 0
    ) {
        val parts = getParts(path)
        var tree = this
        tree.stubIndexAccesses += stubIndexAccesses
        tree.psiElementWraps += psiElementWraps

        for (part in parts) {
            val child = tree.children.getOrPut(part) { MutableVirtualFileTree(part) }
            child.stubIndexAccesses += stubIndexAccesses
            child.psiElementWraps += psiElementWraps
            tree = child
        }
    }

    private fun getParts(path: String): List<String> {
        var file = File(path)
        val parts = mutableListOf<String>()
        do {
            val name = file.name
            parts.add(name)
            file = file.parentFile
        }
        while (file.parentFile != null)

        return parts.reversed()
    }

    override fun toString(): String = name
}

/** A VirtualFileTree path where each part contains a reference to each node. */
class VirtualFileTreePath(val parts: Array<VirtualFileTree>)

interface TreePatchEventListener {
    /**
     * Called when the diff contains a new pending tree node.
     * @param path A path of tree nodes that leads up to {@code parent}
     * @param parent The parent of the new tree node
     * @param child The new tree node
     */
    fun onTreeInsert(
        path: VirtualFileTreePath,
        parent: MutableVirtualFileTree,
        child: MutableVirtualFileTree
    )

    /**
     * Called when the diff contains a modified tree node.
     * @param path A path of tree nodes that leads up to {@code parent}
     * @param parent The parent of the modified tree node
     * @param child The existing tree node pending for modification
     * @param newChild A new tree node containing the modified values
     */
    fun onTreeChange(
        path: VirtualFileTreePath,
        parent: MutableVirtualFileTree,
        child: MutableVirtualFileTree,
        newChild: VirtualFileTree
    )

    /**
     * Called when the diff contains a removed tree node.
     * @param path A path of tree nodes that leads up to {@code parent}
     * @param parent The parent of the removed tree node
     * @param child The existing tree node pending for removal
     */
    fun onTreeRemove(
        path: VirtualFileTreePath,
        parent: MutableVirtualFileTree,
        child: MutableVirtualFileTree
    )
}

/** A recursive diff between two VirtualFileTree instances. */
class VirtualFileTreeDiff private constructor(
    private val underlyingTree: MutableVirtualFileTree,
    private val newTree: VirtualFileTree,
    private val insertedChildren: Map<String, VirtualFileTreeDiff>,
    private val modifiedChildren: Map<String, VirtualFileTreeDiff>,
    private val removedChildren: Map<String, MutableVirtualFileTree>
): VirtualFileTree {
    override val name get() = underlyingTree.name
    override val stubIndexAccesses: Int get() = underlyingTree.stubIndexAccesses
    override val psiElementWraps: Int get() = underlyingTree.psiElementWraps
    override val children: Map<String, VirtualFileTree> get() = underlyingTree.children

    /** Recursively applies a patch function to the @{code underlyingTree} based on the diff. */
    fun applyPatch(listener: TreePatchEventListener) {
        fun patchImpl(
            pathBuilder: Stack<MutableVirtualFileTree>,
            treeDiff: VirtualFileTreeDiff
        ) {
            val underlyingTree = treeDiff.underlyingTree
            pathBuilder.push(underlyingTree)

            val path = VirtualFileTreePath(pathBuilder.toTypedArray())

            for ((childName, newChild) in treeDiff.insertedChildren) {
                val child = underlyingTree.children[childName]
                check(child == null)
                listener.onTreeInsert(path, underlyingTree, newChild.underlyingTree)
                patchImpl(pathBuilder, newChild)
            }

            for ((childName, newChild) in treeDiff.modifiedChildren) {
                val child = underlyingTree.children[childName]
                check(child === newChild.underlyingTree)
                listener.onTreeChange(
                    path,
                    underlyingTree,
                    newChild.underlyingTree,
                    newChild.newTree
                )
                patchImpl(pathBuilder, newChild)
            }

            for (oldChild in treeDiff.removedChildren.values) {
                listener.onTreeRemove(path, underlyingTree, oldChild)
            }

            pathBuilder.pop()
        }

        val pathBuilder = Stack<MutableVirtualFileTree>()
        patchImpl(pathBuilder, this)
    }

    companion object {
        /** Creates a diff based on the changes from {@code oldTree} to {@param newTree}. */
        fun create(
            oldTree: MutableVirtualFileTree?,
            newTree: VirtualFileTree
        ): VirtualFileTreeDiff {
            val insertedChildren = LinkedHashMap<String, VirtualFileTreeDiff>()
            val modifiedChildren = LinkedHashMap<String, VirtualFileTreeDiff>()
            val removedChildren = LinkedHashMap<String, MutableVirtualFileTree>()

            if (oldTree != null) {
                for ((childName, newChild) in newTree.children) {
                    val oldChild = oldTree.children[childName]
                    val childDiff = create(oldChild, newChild)
                    if (oldChild == null) {
                        insertedChildren[childDiff.name] = childDiff
                    }
                    else if (!oldChild.statEquals(newChild)) {
                        modifiedChildren[childDiff.name] = childDiff
                    }
                }

                for ((childName, oldChild) in oldTree.children) {
                    val newChild = newTree.children[childName]
                    if (newChild == null) {
                        removedChildren[childName] = oldChild
                    }
                }

                return VirtualFileTreeDiff(
                    oldTree, newTree, insertedChildren, modifiedChildren, removedChildren
                )
            }
            else {
                for ((childName, child) in newTree.children) {
                    insertedChildren[childName] = create(null, child)
                }

                return VirtualFileTreeDiff(
                    MutableVirtualFileTree(newTree.name).apply {
                        stubIndexAccesses = newTree.stubIndexAccesses
                        psiElementWraps = newTree.psiElementWraps
                    },
                    newTree,
                    insertedChildren,
                    modifiedChildren,
                    removedChildren
                )
            }
        }
    }
}
