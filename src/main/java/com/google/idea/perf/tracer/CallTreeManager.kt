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

// Things to improve:
// - GC the state for dead threads.

/** Builds and manages the call trees for active threads. */
object CallTreeManager {

    // Synchronized by monitor lock.
    private class ThreadState {
        var busy = false // Whether this thread is currently inside enter() or leave().
        var callTreeBuilder: CallTreeBuilder = CallTreeBuilder()
    }

    private val threadState: ThreadLocal<ThreadState> = ThreadLocal.withInitial {
        ThreadState().also {
            synchronized(allThreadState) {
                allThreadState.add(it)
            }
        }
    }

    // Synchronized by monitor lock.
    private val allThreadState = mutableListOf<ThreadState>()

    fun enter(tracepoint: Tracepoint) {
        val state = threadState.get()
        synchronized(state) {
            doPreventingRecursion(state) {
                state.callTreeBuilder.push(tracepoint)
            }
        }
    }

    fun leave() {
        val state = threadState.get()
        synchronized(state) {
            doPreventingRecursion(state) {
                state.callTreeBuilder.pop()
            }
        }
    }

    /**
     * Runs [action] unless doing so would cause infinite recursion. This helps prevent a
     * StackOverflowError when the user has instrumented a callee of [enter] or [leave].
     */
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

    /** Collect and reset the call trees from all threads. */
    fun collectAndReset(): List<CallTree> {
        val allState = synchronized(allThreadState) { ArrayList(allThreadState) }
        val oldTrees = ArrayList<CallTree>(allState.size)
        for (state in allState) {
            val tree = synchronized(state) { state.callTreeBuilder.buildAndReset() }
            oldTrees.add(tree)
        }
        return oldTrees
    }
}
