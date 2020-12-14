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

import com.intellij.util.ui.EDT
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.system.measureNanoTime

// Things to improve:
// * GC the state for dead threads.

/** Builds and manages the call trees for active threads. */
object CallTreeManager {

    private class ThreadState(val isEdt: Boolean) {
        var busy = false // See doPreventingRecursion().
        val lock = ReentrantLock() // Guards the thread-local CallTreeBuilder (low contention).
        var callTreeBuilder = CallTreeBuilder()
    }

    private val allThreadState = CopyOnWriteArrayList<ThreadState>()

    private val threadState: ThreadLocal<ThreadState> =
        ThreadLocal.withInitial {
            val state = ThreadState(EDT.isCurrentThreadEdt())
            allThreadState.add(state)
            state
        }

    fun enter(tracepoint: Tracepoint) {
        val state = threadState.get()
        doPreventingRecursion(state) {
            doWithLockAndAdjustOverhead(state) {
                state.callTreeBuilder.push(tracepoint)
            }
        }
    }

    fun leave() {
        val state = threadState.get()
        doPreventingRecursion(state) {
            doWithLockAndAdjustOverhead(state) {
                state.callTreeBuilder.pop()
            }
        }
    }

    fun getCallTreeSnapshotAllThreadsMerged(): CallTree {
        val mergedTree = MutableCallTree(Tracepoint.ROOT)
        for (threadState in allThreadState) {
            threadState.lock.withLock {
                val localTree = threadState.callTreeBuilder.borrowUpToDateTree()
                mergedTree.accumulate(localTree)
            }
        }
        return mergedTree
    }

    fun getCallTreeSnapshotEdtOnly(): CallTree {
        val edtState = allThreadState.firstOrNull { it.isEdt }
        if (edtState == null) {
            return MutableCallTree(Tracepoint.ROOT)
        }
        edtState.lock.withLock {
            val tree = edtState.callTreeBuilder.borrowUpToDateTree()
            return tree.copy()
        }
    }

    fun clearCallTrees() {
        for (threadState in allThreadState) {
            threadState.lock.withLock {
                threadState.callTreeBuilder.clear()
            }
        }
    }

    // Subtracts lock contention overhead if needed.
    // This should only be called by the thread that owns the ThreadState.
    private inline fun doWithLockAndAdjustOverhead(state: ThreadState, action: () -> Unit) {
        if (!state.lock.tryLock()) {
            val overhead = measureNanoTime { state.lock.lock() }
            state.callTreeBuilder.subtractOverhead(overhead)
        }
        try {
            action()
        } finally {
            state.lock.unlock()
        }
    }

    // Helps prevent StackOverflowError if the user has instrumented a callee of enter() or leave().
    private inline fun doPreventingRecursion(state: ThreadState, action: () -> Unit) {
        if (!state.busy) {
            state.busy = true
            try {
                action()
            }
            finally {
                state.busy = false
            }
        }
    }
}
