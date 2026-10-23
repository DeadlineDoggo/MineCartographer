package minecartographer.core.edit

/** Bounded undo/redo stacks. Entries describe a change; the caller applies them. */
class UndoHistory<T>(private val limit: Int = 200) {
    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()
    val nextUndo: T? get() = undoStack.lastOrNull()
    val nextRedo: T? get() = redoStack.lastOrNull()

    fun push(entry: T) {
        undoStack.addLast(entry)
        if (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }

    /** Drops entries that can no longer be applied (e.g. for a world that was closed). */
    fun removeIf(predicate: (T) -> Boolean) {
        undoStack.removeAll(predicate)
        redoStack.removeAll(predicate)
    }

    /** Returns the entry to revert, or null. */
    fun undo(): T? = undoStack.removeLastOrNull()?.also { redoStack.addLast(it) }

    /** Returns the entry to apply again, or null. */
    fun redo(): T? = redoStack.removeLastOrNull()?.also { undoStack.addLast(it) }
}
