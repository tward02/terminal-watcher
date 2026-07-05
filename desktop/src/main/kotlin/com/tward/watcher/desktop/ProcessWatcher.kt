package com.tward.watcher.desktop

import com.tward.watcher.core.model.TerminalLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Runs a command and streams its stdout and stderr line by line.
 *
 * stdout and stderr are read on separate IO coroutines so a process that writes
 * heavily to one stream cannot stall the other.
 */
class ProcessWatcher(private val command: List<String>) {

    /** Thrown when the command cannot be started at all (not found or not executable). */
    class StartException(message: String, cause: Throwable) : Exception(message, cause)

    /** Starts the process, invokes [onLine] for every captured line, and returns the exit code. */
    suspend fun run(onLine: suspend (TerminalLine.Stream, String) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val process = startProcess()
            val readers = listOf(
                launch {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { onLine(TerminalLine.Stream.STDOUT, it) }
                    }
                },
                launch {
                    process.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { onLine(TerminalLine.Stream.STDERR, it) }
                    }
                },
            )
            try {
                val exitCode = process.onExit().await().exitValue()
                readers.joinAll()
                exitCode
            } finally {
                process.destroy()
            }
        }

    private fun startProcess(): Process {
        try {
            return ProcessBuilder(command).start()
        } catch (e: IOException) {
            // On Windows, commands like gradle, npm or mvn are .bat/.cmd scripts and
            // shell builtins like echo are not executables at all; ProcessBuilder can
            // start neither directly. Retry through the shell, as a terminal would.
            if (System.getProperty("os.name").startsWith("Windows")) {
                try {
                    return ProcessBuilder(listOf("cmd.exe", "/c") + command).start()
                } catch (e2: IOException) {
                    throw StartException(startFailureMessage(e2), e2)
                }
            }
            throw StartException(startFailureMessage(e), e)
        }
    }

    private fun startFailureMessage(cause: IOException): String =
        "Cannot start '${command.joinToString(" ")}': ${cause.message}. " +
            "Check that '${command.first()}' exists and is on your PATH."
}

/**
 * Streams lines from standard input, for pipe mode. Returns when stdin reaches
 * end of file, i.e. when the upstream command finishes.
 */
class StdinWatcher {
    suspend fun run(onLine: suspend (TerminalLine.Stream, String) -> Unit) {
        withContext(Dispatchers.IO) {
            System.`in`.bufferedReader().useLines { lines ->
                lines.forEach { runBlockingLine(onLine, it) }
            }
        }
    }

    private suspend fun runBlockingLine(
        onLine: suspend (TerminalLine.Stream, String) -> Unit,
        line: String,
    ) {
        onLine(TerminalLine.Stream.STDOUT, line)
    }
}
