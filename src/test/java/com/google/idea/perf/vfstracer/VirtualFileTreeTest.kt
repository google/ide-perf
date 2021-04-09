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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun assertPath(
    expectedStubIndexAccesses: Int,
    expectedPsiElementWraps: Int,
    tree: VirtualFileTree,
    vararg path: String
) {
    var targetNode: VirtualFileTree? = tree
    for (part in path) {
        if (targetNode != null) {
            targetNode = targetNode.children[part]
        }
        else {
            fail("Node ${path.joinToString("/")} does not exist.")
        }
    }
    assertEquals(expectedStubIndexAccesses, targetNode!!.stubIndexAccesses)
    assertEquals(expectedPsiElementWraps, targetNode.psiElementWraps)
}

private fun MutableVirtualFileTree.addChild(
    name: String,
    stubIndexAccesses: Int,
    psiElementWraps: Int,
    applyFunc: MutableVirtualFileTree.() -> Unit = {}
) {
    val child = MutableVirtualFileTree(name)
    child.stubIndexAccesses = stubIndexAccesses
    child.psiElementWraps = psiElementWraps
    applyFunc(child)
    children[name] = child
}

private class ChangeLog: TreePatchEventListener {
    companion object {
        fun generate(diff: VirtualFileTreeDiff): String {
            val generator = ChangeLog()
            diff.applyPatch(generator)
            return generator.logger.toString()
        }
    }

    private val logger = StringBuilder()

    override fun onTreeInsert(
        path: VirtualFileTreePath,
        parent: MutableVirtualFileTree,
        child: MutableVirtualFileTree
    ) {
        val pathString = path.parts.joinToString("/") + "/${child.name}"
        val stubIndexAccesses = child.stubIndexAccesses
        val psiElementWraps = child.psiElementWraps
        logger.appendLine("inserted $pathString $stubIndexAccesses $psiElementWraps")
    }

    override fun onTreeModify(
        path: VirtualFileTreePath,
        parent: MutableVirtualFileTree,
        child: MutableVirtualFileTree,
        newChild: VirtualFileTree
    ) {
        val pathString = path.parts.joinToString("/") + "/${child.name}"
        val stubIndexAccesses = newChild.stubIndexAccesses
        val psiElementWraps = newChild.psiElementWraps
        logger.appendLine("modified $pathString $stubIndexAccesses $psiElementWraps")
    }

    override fun onTreeRemove(
        path: VirtualFileTreePath,
        parent: MutableVirtualFileTree,
        child: MutableVirtualFileTree
    ) {
        val pathString = path.parts.joinToString("/") + "/${child.name}"
        logger.appendLine("removed $pathString")
    }
}

class VirtualFileTreeTest {
    init {
        // Enforce LF line endings for developers on Windows.
        System.setProperty("line.separator", "\n")
    }

    @Test
    fun testPathAccumulator() {
        val tree = MutableVirtualFileTree.createRoot()
        tree.accumulate("/com/example/Main.java", psiElementWraps = 1)
        tree.accumulate("/com/example/util/A.java", stubIndexAccesses = 2, psiElementWraps = 1)
        tree.accumulate("/com/example/util/B.java",  stubIndexAccesses = 2, psiElementWraps = 1)
        tree.accumulate("/java/lang/String.class", stubIndexAccesses = 100)
        tree.accumulate("/java/lang/System.class", stubIndexAccesses = 200)

        assertPath(304, 3, tree)
        assertPath(4, 3, tree, "com")
        assertPath(4, 3, tree, "com", "example")
        assertPath(0, 1, tree, "com", "example", "Main.java")
        assertPath(4, 2, tree, "com", "example", "util")
        assertPath(2, 1, tree, "com", "example", "util", "A.java")
        assertPath(2, 1, tree, "com", "example", "util", "B.java")
        assertPath(300, 0, tree, "java")
        assertPath(300, 0, tree, "java", "lang")
        assertPath(100, 0, tree, "java", "lang", "String.class")
        assertPath(200, 0, tree, "java", "lang", "System.class")
    }

    @Test
    fun testTreeAccumulator() {
        val accumulatedTree = MutableVirtualFileTree.createRoot()
        val emptyTree = MutableVirtualFileTree.createRoot()
        val tree1 = MutableVirtualFileTree.createRoot().apply {
            stubIndexAccesses = 100
            psiElementWraps = 1
            addChild("com", 0, 1) {
                addChild("example", 0, 1) {
                    addChild("Main.java", 0, 1)
                }
            }
            addChild("java", 100, 0) {
                addChild("lang", 100, 0) {
                    addChild("String.class", 100, 0)
                }
            }
        }
        val tree2 = MutableVirtualFileTree.createRoot().apply {
            stubIndexAccesses = 204
            psiElementWraps = 2
            addChild("com", 4, 2) {
                addChild("example", 4, 2) {
                    addChild("util", 4, 2) {
                        addChild("A.java", 2, 1)
                        addChild("B.java", 2, 1)
                    }
                }
            }
            addChild("java", 200, 0) {
                addChild("lang", 200, 0) {
                    addChild("System.class", 200, 0)
                }
            }
        }

        accumulatedTree.accumulate(emptyTree)
        assertPath(0, 0, accumulatedTree)
        assertTrue(accumulatedTree.children.isEmpty())

        accumulatedTree.accumulate(tree1)
        assertPath(100, 1, accumulatedTree)
        assertPath(0, 1, accumulatedTree, "com")
        assertPath(0, 1, accumulatedTree, "com", "example")
        assertPath(0, 1, accumulatedTree, "com", "example", "Main.java")
        assertPath(100, 0, accumulatedTree, "java")
        assertPath(100, 0, accumulatedTree, "java", "lang")
        assertPath(100, 0, accumulatedTree, "java", "lang", "String.class")

        accumulatedTree.accumulate(tree2)
        assertPath(304, 3, accumulatedTree)
        assertPath(4, 3, accumulatedTree, "com")
        assertPath(4, 3, accumulatedTree, "com", "example")
        assertPath(0, 1, accumulatedTree, "com", "example", "Main.java")
        assertPath(4, 2, accumulatedTree, "com", "example", "util")
        assertPath(2, 1, accumulatedTree, "com", "example", "util", "A.java")
        assertPath(2, 1, accumulatedTree, "com", "example", "util", "B.java")
        assertPath(300, 0, accumulatedTree, "java")
        assertPath(300, 0, accumulatedTree, "java", "lang")
        assertPath(100, 0, accumulatedTree, "java", "lang", "String.class")
        assertPath(200, 0, accumulatedTree, "java", "lang", "System.class")

        accumulatedTree.accumulate(emptyTree)
        assertPath(304, 3, accumulatedTree)
        assertPath(4, 3, accumulatedTree, "com")
        assertPath(4, 3, accumulatedTree, "com", "example")
        assertPath(0, 1, accumulatedTree, "com", "example", "Main.java")
        assertPath(4, 2, accumulatedTree, "com", "example", "util")
        assertPath(2, 1, accumulatedTree, "com", "example", "util", "A.java")
        assertPath(2, 1, accumulatedTree, "com", "example", "util", "B.java")
        assertPath(300, 0, accumulatedTree, "java")
        assertPath(300, 0, accumulatedTree, "java", "lang")
        assertPath(100, 0, accumulatedTree, "java", "lang", "String.class")
        assertPath(200, 0, accumulatedTree, "java", "lang", "System.class")
    }

    @Test
    fun testTreeDiff() {
        val emptyTree = MutableVirtualFileTree.createRoot()
        val tree1 = MutableVirtualFileTree.createRoot().apply {
            stubIndexAccesses = 100
            psiElementWraps = 1
            addChild("com", 0, 1) {
                addChild("example", 0, 1) {
                    addChild("Main.java", 0, 1)
                }
            }
            addChild("java", 100, 0) {
                addChild("lang", 100, 0) {
                    addChild("String.class", 100, 0)
                    addChild("System.class", 100, 0)
                }
            }
        }
        val tree2 = MutableVirtualFileTree.createRoot().apply {
            stubIndexAccesses = 304
            psiElementWraps = 3
            addChild("com", 4, 3) {
                addChild("example", 4, 3) {
                    addChild("Main.java", 0, 1)
                    addChild("util", 4, 2) {
                        addChild("A.java", 2, 1)
                        addChild("B.java", 2, 1)
                    }
                }
            }
            addChild("java", 300, 0) {
                addChild("lang", 300, 0) {
                    addChild("String.class", 100, 0)
                    addChild("System.class", 200, 0)
                }
            }
        }

        var diff = VirtualFileTreeDiff.create(emptyTree, emptyTree)
        var changeLog = ChangeLog.generate(diff)
        assertEquals("", changeLog)

        diff = VirtualFileTreeDiff.create(emptyTree, tree1)
        changeLog = ChangeLog.generate(diff)
        assertEquals("""
            inserted [root]/com 0 1
            inserted [root]/com/example 0 1
            inserted [root]/com/example/Main.java 0 1
            inserted [root]/java 100 0
            inserted [root]/java/lang 100 0
            inserted [root]/java/lang/String.class 100 0
            inserted [root]/java/lang/System.class 100 0
            
        """.trimIndent(), changeLog)

        diff = VirtualFileTreeDiff.create(tree1, emptyTree)
        changeLog = ChangeLog.generate(diff)
        assertEquals("""
            removed [root]/com
            removed [root]/java
            
        """.trimIndent(), changeLog)

        diff = VirtualFileTreeDiff.create(tree1, tree2)
        changeLog = ChangeLog.generate(diff)
        assertEquals("""
            modified [root]/com 4 3
            modified [root]/com/example 4 3
            inserted [root]/com/example/util 4 2
            inserted [root]/com/example/util/A.java 2 1
            inserted [root]/com/example/util/B.java 2 1
            modified [root]/java 300 0
            modified [root]/java/lang 300 0
            modified [root]/java/lang/System.class 200 0
            
        """.trimIndent(), changeLog)

        diff = VirtualFileTreeDiff.create(tree2, tree1)
        changeLog = ChangeLog.generate(diff)
        assertEquals("""
            modified [root]/com 0 1
            modified [root]/com/example 0 1
            removed [root]/com/example/util
            modified [root]/java 100 0
            modified [root]/java/lang 100 0
            modified [root]/java/lang/System.class 100 0
            
        """.trimIndent(), changeLog)
    }

    @Test
    fun testTreeFlattener() {
        val emptyTree = MutableVirtualFileTree.createRoot()
        val tree = MutableVirtualFileTree.createRoot().apply {
            stubIndexAccesses = 304
            psiElementWraps = 3
            addChild("com", 4, 3) {
                addChild("example", 4, 3) {
                    addChild("Main.java", 0, 1)
                    addChild("util", 4, 2) {
                        addChild("A.java", 2, 1)
                        addChild("B.java", 2, 1)
                    }
                }
            }
            addChild("java", 300, 0) {
                addChild("lang", 300, 0) {
                    addChild("String.class", 100, 0)
                    addChild("System.class", 200, 0)
                }
            }
        }

        assertEquals(emptyList<VirtualFileStats>(), emptyTree.flattenedList())
        assertEquals(listOf(
            VirtualFileStats("com/example/Main.java", 0, 1),
            VirtualFileStats("com/example/util/A.java", 2, 1),
            VirtualFileStats("com/example/util/B.java", 2, 1),
            VirtualFileStats("java/lang/String.class", 100, 0),
            VirtualFileStats("java/lang/System.class", 200, 0)
        ), tree.flattenedList())
    }
}
