package com.android.tools.idea.diagnostics

/**
 * A list supporting only two operations: [append] and [get].
 *
 * For the sake of memory consistency, the index passed to [get] must come from a prior call to [append].
 *
 * Calls to [get] are O(1) and lock-free.
 * Calls to [append] are amortized O(1) but require locking.
 */
class ConcurrentAppendOnlyList<T : Any>(initialCapacity: Int = 0) {

    @Volatile
    private var data = arrayOfNulls<Any?>(initialCapacity)

    private var tail = 0

    /**
     * Retrieves the element at the specified index.
     * For the sake of memory consistency, requires that [index] comes from a prior call to [append].
     */
    fun get(index: Int): T {
        @Suppress("UNCHECKED_CAST")
        return data[index] as T
    }

    /** Appends [element] and returns its index. */
    @Synchronized
    fun append(element: T): Int {
        val index = tail++

        if (index >= data.size) {
            val newCapacity = if (data.isEmpty()) 1 else 2 * data.size
            val newData = arrayOfNulls<Any?>(newCapacity)
            System.arraycopy(data, 0, newData, 0, data.size)
            data = newData // Must come last to ensure readers don't see uninitialized array elements.
        }

        data[index] = element
        return index
    }
}
