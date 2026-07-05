package com.tward.watcher.desktop

import com.tward.watcher.core.model.TerminalLine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Runs real child processes; commands are chosen per platform. */
class ProcessWatcherTest {

    private val isWindows = System.getProperty("os.name").startsWith("Windows")

    private fun shellCommand(script: String): List<String> =
        if (isWindows) listOf("cmd.exe", "/c", script) else listOf("/bin/sh", "-c", script)

    private fun capture(script: String): Pair<Int, List<Pair<TerminalLine.Stream, String>>> =
        runBlocking {
            val lines = mutableListOf<Pair<TerminalLine.Stream, String>>()
            val exit = withTimeout(30_000.milliseconds) {
                ProcessWatcher(shellCommand(script)).run { stream, text ->
                    synchronized(lines) { lines += stream to text }
                }
            }
            exit to lines
        }

    @Test
    fun capturesStdoutLines() {
        val (exit, lines) = capture("echo first&& echo second")
        assertEquals(0, exit)
        val stdout = lines.filter { it.first == TerminalLine.Stream.STDOUT }.map { it.second.trim() }
        assertEquals(listOf("first", "second"), stdout)
    }

    @Test
    fun capturesStderrSeparately() {
        val (exit, lines) = capture(if (isWindows) "echo oops 1>&2" else "echo oops 1>&2")
        assertEquals(0, exit)
        val stderr = lines.filter { it.first == TerminalLine.Stream.STDERR }.map { it.second.trim() }
        assertEquals(listOf("oops"), stderr)
    }

    @Test
    fun reportsExitCode() {
        val (exit, _) = capture("exit 3")
        assertEquals(3, exit)
    }

    @Test
    fun resolvesShellCommandsLikeATerminal() {
        // On Windows 'echo' is a cmd builtin, not an executable - exactly like
        // gradle.bat or npm.cmd it cannot be started by ProcessBuilder directly,
        // so this exercises the shell fallback. On Unix it resolves via PATH.
        val (exit, lines) = runBlocking {
            val captured = mutableListOf<Pair<TerminalLine.Stream, String>>()
            val code = withTimeout(30_000.milliseconds) {
                ProcessWatcher(listOf("echo", "hi")).run { stream, text ->
                    synchronized(captured) { captured += stream to text }
                }
            }
            code to captured
        }
        assertEquals(0, exit)
        assertEquals(listOf("hi"), lines.map { it.second.trim() })
    }

    @Test
    fun missingCommandFailsWithHelpfulErrorInsteadOfStackTrace() {
        val name = "definitely-not-a-real-command-12345"
        if (isWindows) {
            // The shell fallback starts cmd, which reports the unknown command on
            // stderr and exits nonzero - the same behaviour as typing it in a terminal.
            val (exit, lines) = runBlocking {
                val captured = mutableListOf<Pair<TerminalLine.Stream, String>>()
                val code = withTimeout(30_000.milliseconds) {
                    ProcessWatcher(listOf(name)).run { stream, text ->
                        synchronized(captured) { captured += stream to text }
                    }
                }
                code to captured
            }
            assertTrue(exit != 0, "expected nonzero exit, got $exit")
            assertTrue(
                lines.any { it.first == TerminalLine.Stream.STDERR && name in it.second },
                "expected cmd's 'not recognized' stderr line, got $lines",
            )
        } else {
            val exception = kotlin.test.assertFailsWith<ProcessWatcher.StartException> {
                runBlocking {
                    ProcessWatcher(listOf(name)).run { _, _ -> }
                }
            }
            assertTrue(name in (exception.message ?: ""))
        }
    }

    @Test
    fun capturesInterleavedOutputCompletely() {
        val script = if (isWindows) {
            (1..20).joinToString("&& ") { "echo line$it" }
        } else {
            (1..20).joinToString("; ") { "echo line$it" }
        }
        val (exit, lines) = capture(script)
        assertEquals(0, exit)
        assertEquals(20, lines.count { it.first == TerminalLine.Stream.STDOUT })
        assertTrue(lines.any { it.second.trim() == "line20" })
    }
}
