package com.android.tools.idea.diagnostics

/** A call tree, represented recursively. */
interface CallTree {
    val tracepoint: Tracepoint
    val callCount: Long
    val wallTime: Long
    val children: Map<Tracepoint, CallTree>

    val selfWallTime: Long
        get() = wallTime - children.values.sumByLong(CallTree::wallTime)

    /** Returns an immutable deep copy of this tree. */
    fun snapshot(): CallTree {
        return ImmutableCallTree(
            tracepoint = tracepoint,
            callCount = callCount,
            wallTime = wallTime,
            children = children.mapValues { (_, child) -> child.snapshot() }
        )
    }
}

/** An immutable call tree implementation. */
class ImmutableCallTree(
    override val tracepoint: Tracepoint,
    override val callCount: Long,
    override val wallTime: Long,
    override val children: Map<Tracepoint, CallTree>
) : CallTree

/** A mutable call tree implementation. */
class MutableCallTree(
    override val tracepoint: Tracepoint
) : CallTree {
    override var callCount: Long = 0
    override var wallTime: Long = 0
    override val children: MutableMap<Tracepoint, MutableCallTree> = LinkedHashMap()

    /** Accumulates the data from another call tree into this one. */
    fun accumulate(other: CallTree) {
        require(other.tracepoint == tracepoint) {
            "Doesn't make sense to sum call tree nodes representing different tracepoints"
        }

        callCount += other.callCount
        wallTime += other.wallTime

        for ((childTracepoint, otherChild) in other.children) {
            val child = children.getOrPut(childTracepoint) { MutableCallTree(childTracepoint) }
            child.accumulate(otherChild)
        }
    }

    fun clear() {
        callCount = 0
        wallTime = 0
        children.values.forEach(MutableCallTree::clear)
    }
}

object TreeAlgorithms {
    /**
     * Returns all nodes in the tree, *except* those sharing a tracepoint with an ancestor.
     * Use this to avoid double-counting the time spent in recursive method calls.
     */
    fun allNonRecursiveNodes(root: CallTree): Sequence<CallTree> {
        val seen = HashSet<Tracepoint>()
        return sequence { yieldNonRecursiveNodes(root, seen) }
    }

    private suspend fun SequenceScope<CallTree>.yieldNonRecursiveNodes(root: CallTree, seen: MutableSet<Tracepoint>) {
        val nonRecursive = !seen.contains(root.tracepoint)
        if (nonRecursive) {
            yield(root)
            seen.add(root.tracepoint)
        }
        for (child in root.children.values) {
            yieldNonRecursiveNodes(child, seen)
        }
        if (nonRecursive) {
            seen.remove(root.tracepoint)
        }
    }

    /**
     * Merge several call trees into one.
     * The roots must all have the same tracepoint.
     */
    fun mergeNodes(nodes: Collection<CallTree>): CallTree {
        require(nodes.isNotEmpty())

        if (nodes.size == 1) {
            return nodes.single()
        }

        val tracepoint = nodes.first().tracepoint
        val allHaveSameTracepoint = nodes.all { it.tracepoint == tracepoint }
        require(allHaveSameTracepoint)

        val mergedChildren = nodes.asSequence()
            .flatMap { it.children.values.asSequence() }
            .groupBy { it.tracepoint }
            .mapValues { (_, childrenToMerge) -> mergeNodes(childrenToMerge) }

        return ImmutableCallTree(
            tracepoint = tracepoint,
            callCount = nodes.sumByLong(CallTree::callCount),
            wallTime = nodes.sumByLong(CallTree::wallTime),
            children = mergedChildren
        )
    }
}
