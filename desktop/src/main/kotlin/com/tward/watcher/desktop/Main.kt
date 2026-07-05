package com.tward.watcher.desktop

import com.tward.watcher.core.hooks.HookConfig
import com.tward.watcher.core.hooks.HookEngine
import com.tward.watcher.core.hooks.Inactivity
import com.tward.watcher.core.model.TerminalLine
import com.tward.watcher.desktop.actions.DesktopActionExecutor
import com.tward.watcher.desktop.server.WatcherServer
import com.tward.watcher.desktop.session.WatcherSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.UUID
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val cli = try {
        CliArgs.parse(args)
    } catch (e: CliArgs.CliException) {
        System.err.println("Error: ${e.message}")
        System.err.println()
        System.err.println(CliArgs.USAGE)
        exitProcess(2)
    }

    val hooks = try {
        loadHooks(cli.hooksPath)
    } catch (e: Exception) {
        System.err.println("Error loading hooks: ${e.message}")
        exitProcess(2)
    }

    val token = cli.token ?: generateToken()
    val description = when (cli.mode) {
        CliArgs.Mode.RUN -> cli.command.joinToString(" ")
        CliArgs.Mode.PIPE -> "stdin pipe"
    }

    runBlocking {
        val session = WatcherSession(
            sessionId = UUID.randomUUID().toString(),
            description = description,
            startedAt = System.currentTimeMillis(),
            historySize = cli.historySize,
        )
        val httpClient = HttpClient(CIO)
        val executor = DesktopActionExecutor(session, httpClient)
        val engine = try {
            HookEngine(hooks.hooks, executor)
        } catch (e: IllegalArgumentException) {
            System.err.println("Error in hooks configuration: ${e.message}")
            exitProcess(2)
        }
        session.hookEngine = engine

        val server = WatcherServer(session, token, cli.bindHost, cli.port)
        val boundPort = server.start()
        printBanner(boundPort, token, hooks.hooks.size, description)

        if (hooks.hooks.any { it.enabled && it.trigger is Inactivity }) {
            session.startInactivityTicker(this)
        }
        session.processStarted()

        when (cli.mode) {
            CliArgs.Mode.RUN -> {
                val exitCode = try {
                    ProcessWatcher(cli.command).run { stream, text ->
                        echo(stream, text)
                        session.emitLine(stream, text)
                    }
                } catch (e: ProcessWatcher.StartException) {
                    System.err.println("Error: ${e.message}")
                    exitProcess(1)
                }
                session.processExited(exitCode)
                println("[terminal-watcher] process exited with code $exitCode; still serving viewers, Ctrl+C to quit")
            }

            CliArgs.Mode.PIPE -> {
                StdinWatcher().run { stream, text ->
                    echo(stream, text)
                    session.emitLine(stream, text)
                }
                session.processExited(0)
                println("[terminal-watcher] stdin closed; still serving viewers, Ctrl+C to quit")
            }
        }

        // Keep serving history to viewers until the user interrupts.
        awaitCancellation()
    }
}

/** Mirrors captured output to the local console so wrapping a command stays transparent. */
private fun echo(stream: TerminalLine.Stream, text: String) {
    when (stream) {
        TerminalLine.Stream.STDERR -> System.err.println(text)
        else -> println(text)
    }
}

private fun loadHooks(path: String?): HookConfig {
    val file = when {
        path != null -> File(path).also {
            if (!it.exists()) throw IllegalArgumentException("hooks file not found: $path")
        }
        else -> File("hooks.json").takeIf { it.exists() } ?: return HookConfig()
    }
    return HookConfig.parse(file.readText())
}

private fun generateToken(): String {
    val bytes = ByteArray(6)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

private fun printBanner(port: Int, token: String, hookCount: Int, description: String) {
    println("[terminal-watcher] watching: $description")
    println("[terminal-watcher] hooks loaded: $hookCount")
    println("[terminal-watcher] token: $token")
    val addresses = lanAddresses()
    if (addresses.isEmpty()) {
        println("[terminal-watcher] serving on port $port (no LAN address detected)")
    } else {
        for (address in addresses) {
            println("[terminal-watcher] connect the app to: $address port $port")
        }
    }
}

/** Site-local IPv4 addresses of this machine, the ones a phone on the same network can reach. */
internal fun lanAddresses(): List<String> =
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.asSequence() }
        .filter { it.isSiteLocalAddress && it.address.size == 4 }
        .map { it.hostAddress }
        .toList()
