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

package com.google.idea.perf.util

private class Node<K:Any, V: Any>(var key: K, var value: V) {
    var next: Node<K, V>? = null
    var previous: Node<K, V>? = null
}

class LruCache<K: Any, V: Any>(private val maxCapacity: Int = 0) {
    private val nodes = HashMap<K, Node<K, V>>(maxCapacity)
    private var head: Node<K, V>? = null
    private var tail: Node<K, V>? = null

    init {
        check(maxCapacity > 0) { "maxCapacity must be a value greater than zero." }
    }

    operator fun get(key: K): V? {
        val node = nodes[key] ?: return null
        val value = node.value

        if (node != head) {
            if (node.next != null && node.previous != null) {
                val next = node.next
                val previous = node.previous
                next!!.previous = previous
                previous!!.next = next
            }
            else if (node.previous != null) {
                val previous = node.previous
                previous!!.next = null
                tail = previous
            }

            node.next = head
            node.previous = null
            head!!.previous = node
            head = node
        }

        return value
    }

    operator fun set(key: K, value: V) {
        if (nodes.size >= maxCapacity) {
            if (nodes.size != 1) {
                nodes.remove(tail!!.key)
                tail = tail!!.previous
                tail!!.next = null
            }
            else {
                nodes.clear()
                head = null
                tail = null
            }
        }

        var node = nodes[key]

        if (node != null) {
            node.value = value
            get(key)
        }
        else {
            node = Node(key, value)
            node.next = head
            nodes[key] = node

            if (head != null) {
                head!!.previous = node
                head = node
            }
            else {
                head = node
                tail = node
            }
        }
    }

    fun put(key: K, value: V) = set(key, value)

    fun computeIfAbsent(key: K, mappingFunction: (K) -> V) {
        if (!nodes.containsKey(key)) {
            put(key, mappingFunction(key))
        }
    }
}