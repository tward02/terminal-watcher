package com.tward.watcher.core.buffer

import com.tward.watcher.core.model.TerminalLine

/**
 * Fixed-capacity buffer of recent terminal lines. When full, the oldest line is
 * evicted. Not thread safe; callers serialise access (the desktop session uses a
 * mutex, the client mutates it from a single coroutine).
 */
class LineRingBuffer(private val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val lines = ArrayDeque<TerminalLine>()

    /** Number of lines evicted because the buffer was full. */
    var dropped: Long = 0
        private set

    val size: Int get() = lines.size

    fun add(line: TerminalLine) {
        if (lines.size == capacity) {
            lines.removeFirst()
            dropped++
        }
        lines.addLast(line)
    }

    /** Oldest-to-newest copy of the buffered lines. */
    fun snapshot(): List<TerminalLine> = lines.toList()

    fun clear() {
        lines.clear()
        dropped = 0
    }
}
