package com.jeangbeat.core.model

/** Simple snapshot-based undo/redo for immutable state. */
class UndoHistory<T>(initial: T, private val limit: Int = 100) {
    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()

    var present: T = initial
        private set

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** Records [state] as the new present. No-op if it equals the current state. */
    fun push(state: T) {
        if (state == present) return
        past.addLast(present)
        if (past.size > limit) past.removeFirst()
        future.clear()
        present = state
    }

    /** Replaces the present without creating an undo step (e.g. while dragging). */
    fun replace(state: T) {
        present = state
    }

    fun undo(): T {
        if (past.isEmpty()) return present
        future.addLast(present)
        present = past.removeLast()
        return present
    }

    fun redo(): T {
        if (future.isEmpty()) return present
        past.addLast(present)
        present = future.removeLast()
        return present
    }

    /** Starts a new history from [state] (e.g. after opening another project). */
    fun reset(state: T) {
        past.clear()
        future.clear()
        present = state
    }
}
