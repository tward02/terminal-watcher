package com.tward.watcher.core.buffer

import com.tward.watcher.core.model.TerminalLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LineRingBufferTest {

    private fun line(seq: Long) =
        TerminalLine(seq = seq, timestamp = seq, stream = TerminalLine.Stream.STDOUT, text = "line $seq")

    @Test
    fun keepsLinesInInsertionOrder() {
        val buffer = LineRingBuffer(10)
        (1L..3L).forEach { buffer.add(line(it)) }
        assertEquals(listOf(1L, 2L, 3L), buffer.snapshot().map { it.seq })
    }

    @Test
    fun evictsOldestWhenFull() {
        val buffer = LineRingBuffer(3)
        (1L..5L).forEach { buffer.add(line(it)) }
        assertEquals(listOf(3L, 4L, 5L), buffer.snapshot().map { it.seq })
        assertEquals(2, buffer.dropped)
        assertEquals(3, buffer.size)
    }

    @Test
    fun clearResetsContentAndDroppedCount() {
        val buffer = LineRingBuffer(2)
        (1L..4L).forEach { buffer.add(line(it)) }
        buffer.clear()
        assertEquals(emptyList(), buffer.snapshot())
        assertEquals(0, buffer.dropped)
        assertEquals(0, buffer.size)
    }

    @Test
    fun rejectsNonPositiveCapacity() {
        assertFailsWith<IllegalArgumentException> { LineRingBuffer(0) }
    }
}
