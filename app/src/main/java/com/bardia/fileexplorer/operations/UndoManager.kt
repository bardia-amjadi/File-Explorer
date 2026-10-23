package com.bardia.fileexplorer.operations

import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps a short history of reversible operations. Each entry knows how to reverse itself (by queueing another
 * operation) and is only registered when reversal is really possible. Irreversible actions (permanent delete)
 * never register an entry.
 */
class UndoManager {
    class Entry(val id: Long, val label: String, val action: () -> Unit)

    private val ids = AtomicLong(1)
    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun register(label: String, action: () -> Unit): Long {
        val id = ids.getAndIncrement()
        entries.addLast(Entry(id, label, action))
        while (entries.size > MAX) entries.removeFirst()
        return id
    }

    /** Runs and removes entry [id]; returns false if it is no longer available. */
    fun run(id: Long): Boolean {
        val entry = synchronized(this) {
            val e = entries.firstOrNull { it.id == id } ?: return false
            entries.remove(e)
            e
        }
        entry.action()
        return true
    }

    companion object {
        private const val MAX = 20
    }
}
