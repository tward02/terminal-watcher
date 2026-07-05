package com.tward.watcher.desktop.session

import com.tward.watcher.core.hooks.Action
import com.tward.watcher.core.hooks.ActionExecutor
import com.tward.watcher.core.hooks.Hook
import com.tward.watcher.core.hooks.HookEngine
import com.tward.watcher.core.hooks.Notify
import com.tward.watcher.core.hooks.OutputMatches
import com.tward.watcher.core.model.TerminalLine
import com.tward.watcher.core.protocol.OutputBatch
import com.tward.watcher.core.protocol.ProcessStatus
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class WatcherSessionTest {

    private fun newSession(clock: () -> Long = { 1000 }) = WatcherSession(
        sessionId = "s",
        description = "test",
        startedAt = 1L,
        historySize = 3,
        clock = clock,
    )

    @Test
    fun assignsIncreasingSequenceNumbers() = runBlocking {
        val session = newSession()
        session.emitLine(TerminalLine.Stream.STDOUT, "a")
        session.emitLine(TerminalLine.Stream.STDERR, "b")
        val history = session.history()
        assertEquals(listOf(1L, 2L), history.map { it.seq })
        assertEquals(listOf("a", "b"), history.map { it.text })
    }

    @Test
    fun historyIsBoundedByCapacity() = runBlocking {
        val session = newSession()
        repeat(5) { session.emitLine(TerminalLine.Stream.STDOUT, "line$it") }
        assertEquals(listOf("line2", "line3", "line4"), session.history().map { it.text })
    }

    @Test
    fun broadcastsEmittedLinesToSubscribers() = runBlocking {
        val session = newSession()
        // UNDISPATCHED makes the collector subscribe before emitLine runs.
        val firstBatch = async(start = CoroutineStart.UNDISPATCHED) {
            session.broadcast.filterIsInstance<OutputBatch>().first()
        }

        session.emitLine(TerminalLine.Stream.STDOUT, "hello")

        val batch = withTimeout(10_000.milliseconds) { firstBatch.await() }
        assertEquals("hello", batch.lines.single().text)
    }

    @Test
    fun processExitUpdatesStatusAndEmitsSystemLine() = runBlocking {
        val session = newSession()
        session.processExited(7)
        assertEquals(ProcessStatus(running = false, exitCode = 7, timestamp = 1000), session.lastStatus)
        val system = session.history().single()
        assertEquals(TerminalLine.Stream.SYSTEM, system.stream)
        assertTrue("7" in system.text)
    }

    @Test
    fun linesAreFedThroughTheHookEngine() = runBlocking {
        val session = newSession()
        val firedActions = mutableListOf<Action>()
        val executor = ActionExecutor { _, action, _ -> firedActions += action }
        session.hookEngine = HookEngine(
            listOf(Hook("h", OutputMatches("deploy"), listOf(Notify("t", "b")))),
            executor,
        )

        session.emitLine(TerminalLine.Stream.STDOUT, "starting deploy now")
        session.emitLine(TerminalLine.Stream.STDOUT, "unrelated")

        assertEquals(listOf<Action>(Notify("t", "b")), firedActions)
    }
}
