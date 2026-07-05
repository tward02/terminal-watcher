package com.tward.watcher.core.client

import com.tward.watcher.core.model.TerminalLine
import com.tward.watcher.core.protocol.HookNotification
import com.tward.watcher.core.protocol.OutputBatch
import com.tward.watcher.core.protocol.ProcessStatus
import com.tward.watcher.core.protocol.Protocol
import com.tward.watcher.core.protocol.ServerHello
import com.tward.watcher.core.protocol.WatcherMessage
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private const val TOKEN = "secret-token"
private const val TIMEOUT_MS = 15_000L

class WatcherClientIntegrationTest {

    private val cleanups = mutableListOf<() -> Unit>()

    @AfterTest
    fun tearDown() {
        cleanups.reversed().forEach { it() }
        cleanups.clear()
    }

    /** Starts a server that validates the token and runs [session] for valid connections. */
    private fun startServer(
        session: suspend io.ktor.server.websocket.DefaultWebSocketServerSession.() -> Unit,
    ): Int {
        lateinit var server: EmbeddedServer<*, *>
        server = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing {
                webSocket(Protocol.WEBSOCKET_PATH) {
                    val token = call.request.queryParameters[Protocol.TOKEN_PARAMETER]
                    if (token != TOKEN) {
                        close(CloseReason(Protocol.CLOSE_INVALID_TOKEN, "Invalid token"))
                        return@webSocket
                    }
                    session()
                }
            }
        }.start(wait = false)
        cleanups += { server.stop(100, 500) }
        return runBlocking { server.engine.resolvedConnectors().first().port }
    }

    private fun newClient(port: Int, token: String = TOKEN): WatcherClient {
        val scope = CoroutineScope(SupervisorJob())
        cleanups += { scope.cancel() }
        val client = WatcherClient(
            host = "127.0.0.1",
            port = port,
            token = token,
            scope = scope,
            retryDelayMillis = 200,
        )
        cleanups += { client.close() }
        return client
    }

    private suspend fun io.ktor.websocket.WebSocketSession.sendMessage(message: WatcherMessage) {
        send(Frame.Text(Protocol.encode(message)))
    }

    private fun hello() = ServerHello(sessionId = "s1", description = "test", startedAt = 1L)

    private fun line(seq: Long, text: String) =
        TerminalLine(seq, seq, TerminalLine.Stream.STDOUT, text)

    @Test
    fun connectsAndReceivesHelloHistoryAndLiveOutput() = runBlocking {
        val port = startServer {
            sendMessage(hello())
            sendMessage(OutputBatch(listOf(line(1, "history line"))))
            sendMessage(OutputBatch(listOf(line(2, "live line"))))
            awaitCancellation()
        }

        val client = newClient(port)
        client.connect()

        withTimeout(TIMEOUT_MS.milliseconds) {
            val connected = client.state.first { it is ConnectionState.Connected } as ConnectionState.Connected
            assertEquals("s1", connected.hello.sessionId)
            val lines = client.lines.first { it.size == 2 }
            assertEquals(listOf("history line", "live line"), lines.map { it.text })
        }
    }

    @Test
    fun receivesNotificationsAndProcessStatus() = runBlocking {
        val port = startServer {
            sendMessage(hello())
            sendMessage(HookNotification("h", "title", "body", 7))
            sendMessage(ProcessStatus(running = false, exitCode = 1, timestamp = 8))
            awaitCancellation()
        }

        val client = newClient(port)
        val notifications = mutableListOf<HookNotification>()
        val scope = CoroutineScope(SupervisorJob())
        cleanups += { scope.cancel() }
        val collector = scope.launch {
            client.notifications.collect { notifications += it }
        }
        client.connect()

        withTimeout(TIMEOUT_MS.milliseconds) {
            val status = client.processStatus.first { it != null }!!
            assertEquals(1, status.exitCode)
            assertEquals(false, status.running)
        }
        withTimeout(TIMEOUT_MS.milliseconds) {
            while (notifications.isEmpty()) kotlinx.coroutines.delay(20.milliseconds)
        }
        assertEquals("title", notifications.single().title)
        collector.cancel()
    }

    @Test
    fun invalidTokenIsFatalAndStopsRetrying() = runBlocking {
        val port = startServer { awaitCancellation() }

        val client = newClient(port, token = "wrong")
        client.connect()

        withTimeout(TIMEOUT_MS.milliseconds) {
            val failed = client.state.first { it is ConnectionState.Failed } as ConnectionState.Failed
            assertEquals(false, failed.willRetry)
            assertTrue("token" in failed.reason.lowercase())
        }
    }

    @Test
    fun unreachableServerReportsFailureAndRetries() = runBlocking {
        // Port from the ephemeral range with nothing listening.
        val client = newClient(port = 1)
        client.connect()

        withTimeout(TIMEOUT_MS.milliseconds) {
            val failed = client.state.first { it is ConnectionState.Failed } as ConnectionState.Failed
            assertTrue(failed.willRetry)
            // A retry puts the client back into Connecting.
            client.state.first { it is ConnectionState.Connecting }
        }
        client.disconnect()
        assertEquals(ConnectionState.Disconnected, client.state.value)
    }

    @Test
    fun reconnectsAfterServerClosesAndReplacesHistory() = runBlocking {
        var connectionCount = 0
        val port = startServer {
            connectionCount++
            sendMessage(hello())
            sendMessage(OutputBatch(listOf(line(connectionCount.toLong(), "conn $connectionCount"))))
            if (connectionCount == 1) {
                close(CloseReason(CloseReason.Codes.NORMAL, "restart"))
            } else {
                awaitCancellation()
            }
        }

        val client = newClient(port)
        client.connect()

        withTimeout(TIMEOUT_MS.milliseconds) {
            // After the first connection drops, the client reconnects and the replayed
            // buffer replaces the old content rather than appending to it.
            client.lines.first { lines -> lines.map { it.text } == listOf("conn 2") }
        }
        assertTrue(connectionCount >= 2)
    }
}
