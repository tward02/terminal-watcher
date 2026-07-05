package com.tward.watcher.core.hooks

import com.tward.watcher.core.model.TerminalLine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HookEngineTest {

    private class RecordingExecutor : ActionExecutor {
        val executed = mutableListOf<Triple<String, Action, Map<String, String>>>()

        override suspend fun execute(hook: Hook, action: Action, placeholders: Map<String, String>) {
            executed += Triple(hook.name, action, placeholders)
        }
    }

    private fun line(
        text: String,
        stream: TerminalLine.Stream = TerminalLine.Stream.STDOUT,
        timestamp: Long = 1000,
    ) = WatcherEvent.Line(TerminalLine(seq = 1, timestamp = timestamp, stream = stream, text = text))

    private fun notifyHook(
        name: String,
        trigger: Trigger,
        once: Boolean = false,
        enabled: Boolean = true,
    ) = Hook(
        name = name,
        trigger = trigger,
        actions = listOf(Notify(title = "t", body = "b")),
        enabled = enabled,
        once = once,
    )

    @Test
    fun outputMatchFiresWithPlaceholders() = runTest {
        val executor = RecordingExecutor()
        val engine = HookEngine(
            listOf(notifyHook("err", OutputMatches("""ERROR: (\w+)"""))),
            executor,
        )

        val fired = engine.handle(line("ERROR: disk"))

        assertEquals(1, fired.size)
        val placeholders = fired.single().placeholders
        assertEquals("err", placeholders["hook"])
        assertEquals("ERROR: disk", placeholders["line"])
        assertEquals("ERROR: disk", placeholders["match"])
        assertEquals("disk", placeholders["group1"])
        assertEquals("STDOUT", placeholders["stream"])
        assertEquals(1, executor.executed.size)
    }

    @Test
    fun nonMatchingLineDoesNotFire() = runTest {
        val executor = RecordingExecutor()
        val engine = HookEngine(listOf(notifyHook("err", OutputMatches("ERROR"))), executor)

        assertTrue(engine.handle(line("all good")).isEmpty())
        assertTrue(executor.executed.isEmpty())
    }

    @Test
    fun streamFilterIsRespected() = runTest {
        val executor = RecordingExecutor()
        val engine = HookEngine(
            listOf(notifyHook("stderr-only", OutputMatches("boom", stream = TerminalLine.Stream.STDERR))),
            executor,
        )

        assertTrue(engine.handle(line("boom", stream = TerminalLine.Stream.STDOUT)).isEmpty())
        assertEquals(1, engine.handle(line("boom", stream = TerminalLine.Stream.STDERR)).size)
    }

    @Test
    fun onceHookFiresOnlyOnce() = runTest {
        val executor = RecordingExecutor()
        val engine = HookEngine(listOf(notifyHook("once", OutputMatches("x"), once = true)), executor)

        assertEquals(1, engine.handle(line("x1")).size)
        assertTrue(engine.handle(line("x2")).isEmpty())
        assertEquals(1, executor.executed.size)
    }

    @Test
    fun repeatingHookFiresEveryMatch() = runTest {
        val executor = RecordingExecutor()
        val engine = HookEngine(listOf(notifyHook("repeat", OutputMatches("x"))), executor)

        engine.handle(line("x1"))
        engine.handle(line("x2"))
        assertEquals(2, executor.executed.size)
    }

    @Test
    fun disabledHookNeverFires() = runTest {
        val executor = RecordingExecutor()
        val engine = HookEngine(
            listOf(notifyHook("off", OutputMatches("x"), enabled = false)),
            executor,
        )

        assertTrue(engine.handle(line("x")).isEmpty())
    }

    @Test
    fun allActionsOfAFiredHookExecute() = runTest {
        val executor = RecordingExecutor()
        val hook = Hook(
            name = "multi",
            trigger = OutputMatches("go"),
            actions = listOf(Notify("t"), Webhook("https://x.test"), RunCommand("echo hi")),
        )
        HookEngine(listOf(hook), executor).handle(line("go"))

        assertEquals(3, executor.executed.size)
        assertEquals(
            listOf(Notify("t"), Webhook("https://x.test"), RunCommand("echo hi")),
            executor.executed.map { it.second },
        )
    }

    @Test
    fun processExitMatchesConfiguredCodes() = runTest {
        val executor = RecordingExecutor()
        val engine = HookEngine(
            listOf(
                notifyHook("fail", ProcessExits(codes = listOf(1, 2))),
                notifyHook("any", ProcessExits(codes = null)),
            ),
            executor,
        )

        val fired = engine.handle(WatcherEvent.ProcessExited(exitCode = 2, timestamp = 5))

        assertEquals(listOf("fail", "any"), fired.map { it.hook.name })
        assertEquals("2", fired[0].placeholders["exitCode"])
    }

    @Test
    fun processExitWithUnlistedCodeOnlyFiresWildcard() = runTest {
        val executor = RecordingExecutor()
        val engine = HookEngine(
            listOf(
                notifyHook("fail", ProcessExits(codes = listOf(1))),
                notifyHook("any", ProcessExits(codes = null)),
            ),
            executor,
        )

        val fired = engine.handle(WatcherEvent.ProcessExited(exitCode = 0, timestamp = 5))
        assertEquals(listOf("any"), fired.map { it.hook.name })
    }

    @Test
    fun inactivityFiresAfterThresholdAndRearmsOnNewOutput() = runTest {
        val executor = RecordingExecutor()
        val engine = HookEngine(listOf(notifyHook("idle", Inactivity(seconds = 10))), executor)

        engine.handle(WatcherEvent.ProcessStarted(timestamp = 0))
        assertTrue(engine.handle(WatcherEvent.Tick(timestamp = 9_000)).isEmpty())

        val fired = engine.handle(WatcherEvent.Tick(timestamp = 10_000))
        assertEquals(1, fired.size)
        assertEquals("10", fired.single().placeholders["idleSeconds"])

        // Does not refire while still idle.
        assertTrue(engine.handle(WatcherEvent.Tick(timestamp = 20_000)).isEmpty())

        // New output re-arms the trigger.
        engine.handle(line("output", timestamp = 21_000))
        assertTrue(engine.handle(WatcherEvent.Tick(timestamp = 25_000)).isEmpty())
        assertEquals(1, engine.handle(WatcherEvent.Tick(timestamp = 31_000)).size)
    }

    @Test
    fun inactivityDoesNotFireBeforeAnyActivity() = runTest {
        val executor = RecordingExecutor()
        val engine = HookEngine(listOf(notifyHook("idle", Inactivity(seconds = 1))), executor)

        assertTrue(engine.handle(WatcherEvent.Tick(timestamp = 1_000_000)).isEmpty())
    }

    @Test
    fun invalidRegexFailsAtConstruction() {
        val executor = RecordingExecutor()
        val exception = assertFailsWith<IllegalArgumentException> {
            HookEngine(listOf(notifyHook("bad", OutputMatches("("))), executor)
        }
        assertTrue("bad" in (exception.message ?: ""))
    }
}
