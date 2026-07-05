package com.tward.watcher.desktop

import com.tward.watcher.core.protocol.Protocol

/**
 * Parsed command line for the desktop watcher.
 *
 * Grammar:
 *   terminal-watcher run [options] -- <command> [args]
 *   terminal-watcher pipe [options]
 */
data class CliArgs(
    val mode: Mode,
    val command: List<String> = emptyList(),
    val port: Int = Protocol.DEFAULT_PORT,
    val bindHost: String = "0.0.0.0",
    val token: String? = null,
    val hooksPath: String? = null,
    val historySize: Int = DEFAULT_HISTORY,
) {
    enum class Mode { RUN, PIPE }

    class CliException(message: String) : Exception(message)

    companion object {
        const val DEFAULT_HISTORY: Int = 2000

        val USAGE: String = """
            Usage:
              terminal-watcher run [options] -- <command> [args...]
              terminal-watcher pipe [options]

            Modes:
              run    Start <command> and stream its stdout/stderr.
              pipe   Stream lines read from stdin (use: mycmd | terminal-watcher pipe).

            Options:
              --port <n>       Port to serve on (default ${Protocol.DEFAULT_PORT}, 0 picks a free port)
              --bind <host>    Address to bind (default 0.0.0.0)
              --token <t>      Access token clients must present (default: randomly generated)
              --hooks <path>   Path to a hooks JSON file (default: hooks.json if present)
              --history <n>    Number of recent lines replayed to new clients (default $DEFAULT_HISTORY)
        """.trimIndent()

        fun parse(args: Array<String>): CliArgs {
            if (args.isEmpty()) throw CliException("Missing mode: expected 'run' or 'pipe'.")

            val mode = when (args[0]) {
                "run" -> Mode.RUN
                "pipe" -> Mode.PIPE
                else -> throw CliException("Unknown mode '${args[0]}': expected 'run' or 'pipe'.")
            }

            var port = Protocol.DEFAULT_PORT
            var bindHost = "0.0.0.0"
            var token: String? = null
            var hooksPath: String? = null
            var historySize = DEFAULT_HISTORY
            val command = mutableListOf<String>()

            var i = 1
            while (i < args.size) {
                when (val arg = args[i]) {
                    "--" -> {
                        if (mode != Mode.RUN) throw CliException("'--' is only valid in run mode.")
                        command += args.drop(i + 1)
                        i = args.size
                        continue
                    }
                    "--port" -> port = intValue(args, i).also { i += 2 }
                    "--bind" -> bindHost = stringValue(args, i).also { i += 2 }
                    "--token" -> token = stringValue(args, i).also { i += 2 }
                    "--hooks" -> hooksPath = stringValue(args, i).also { i += 2 }
                    "--history" -> historySize = intValue(args, i).also { i += 2 }
                    else -> throw CliException("Unknown option '$arg'.")
                }
            }

            if (port !in 0..65535) throw CliException("--port must be between 0 and 65535.")
            if (historySize <= 0) throw CliException("--history must be positive.")
            if (mode == Mode.RUN && command.isEmpty()) {
                throw CliException("run mode requires a command after '--'.")
            }

            return CliArgs(
                mode = mode,
                command = command,
                port = port,
                bindHost = bindHost,
                token = token,
                hooksPath = hooksPath,
                historySize = historySize,
            )
        }

        private fun stringValue(args: Array<String>, index: Int): String =
            args.getOrNull(index + 1)
                ?: throw CliException("Option '${args[index]}' requires a value.")

        private fun intValue(args: Array<String>, index: Int): Int =
            stringValue(args, index).toIntOrNull()
                ?: throw CliException("Option '${args[index]}' requires an integer value.")
    }
}
