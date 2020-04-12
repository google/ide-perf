package com.android.tools.idea.diagnostics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// A peculiar omission from the Kotlin standard library.
internal inline fun <T> Iterable<T>.sumByLong(selector: (T) -> Long): Long {
    var sum = 0L
    for (element in this) {
        sum += selector(element)
    }
    return sum
}

/**
 * Queues and executes coroutines atomically in FIFO order.
 *
 * This is different from [newFixedThreadPoolContext], which
 * can run coroutines concurrently if some are suspended.
 *
 * It's also different from `launch { mutex.withLock { ... } }`,
 * which does not guarantee FIFO ordering.
 */
class SequentialExecutor(scope: CoroutineScope) {
    private val channel = Channel<suspend () -> Any>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (action in channel) {
                action()
            }
        }
    }

    fun executeLater(action: suspend () -> Unit) {
        check(channel.offer(action))
    }
}

class Atomic<T>(private var data: T) {
    private val lock = Mutex()

    suspend fun setAtomically(newData: T) {
        lock.withLock {
            data = newData
        }
    }

    suspend fun <R> doAtomically(action: (T) -> R): R {
        return lock.withLock {
            action(data)
        }
    }
}
