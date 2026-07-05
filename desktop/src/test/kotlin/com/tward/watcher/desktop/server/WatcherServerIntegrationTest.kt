package com.tward.watcher.desktop.server

import com.tward.watcher.core.model.TerminalLine
import com.tward.watcher.core.protocol.HookNotification
import com.tward.watcher.core.protocol.OutputBatch
import com.tward.watcher.core.protocol.ProcessStatus
import com.tward.watcher.core.protocol.Protocol
import com.tward.watcher.core.protocol.ServerHello
import com.tward.watcher.core.protocol.WatcherMessage
import com.tward.watcher.desktop.session.WatcherSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private const val TOKEN = "test-token"
private const val TIMEOUT_MS = 15_000L

/**
 * End-to-end test of the desktop server: a real session served over a real
 * WebSocket, consumed with a plain Ktor client (mirroring what the app does).
 */
class WatcherServerIntegrationTest {

    private lateinit var session: WatcherSession
    private lateinit var server: WatcherServer
    private var port: Int = 0
    private val httpClient = HttpClient(CIO) { install(WebSockets) }

    @BeforeTest
    fun setUp() {
        session = WatcherSession(
            sessionId = "session-1",
            description = "integration test",
            startedAt = 123L,
            historySize = 100,
        )
        server = WatcherServer(session, TOKEN, bindHost = "127.0.0.1", port = 0)
        port = runBlocking { server.start() }
    }

    @AfterTest
    fun tearDown() {
        server.stop()
        httpClient.close()
    }

    private suspend fun connectAndCollect(
        token: String,
        messages: Channel<WatcherMessage>,
    ) {
        httpClient.webSocket(
            host = "127.0.0.1",
            port = port,
            path = Protocol.WEBSOCKET_PATH,
            request = { url.parameters.append(Protocol.TOKEN_PARAMETER, token) },
        ) {
            for (frame in incoming) {
                if (frame is Frame.Text) messages.send(Protocol.decode(frame.readText()))
            }
        }
    }

    @Test
    fun newClientReceivesHelloHistoryAndStatusThenLiveLines() = runBlocking {
        session.emitLine(TerminalLine.Stream.STDOUT, "before connect")

        val messages = Channel<WatcherMessage>(Channel.UNLIMITED)
        val connection = launch { connectAndCollect(TOKEN, messages) }

        withTimeout(TIMEOUT_MS.milliseconds) {
            val hello = messages.receive()
            assertIs<ServerHello>(hello)
            assertEquals("session-1", hello.sessionId)
            assertEquals("integration test", hello.description)

            val history = messages.receive()
            assertIs<OutputBatch>(history)
            assertEquals(listOf("before connect"), history.lines.map { it.text })

            val status = messages.receive()
            assertIs<ProcessStatus>(status)
            assertTrue(status.running)

            // The handshake is complete, so the server is subscribed to the broadcast
            // and this line must arrive as a live message.
            session.emitLine(TerminalLine.Stream.STDERR, "live line")

            val live = messages.receive()
            assertIs<OutputBatch>(live)
            assertEquals("live line", live.lines.single().text)
            assertEquals(TerminalLine.Stream.STDERR, live.lines.single().stream)
        }
        connection.cancel()
    }

    @Test
    fun notificationsReachConnectedClients() = runBlocking {
        val messages = Channel<WatcherMessage>(Channel.UNLIMITED)
        val connection = launch { connectAndCollect(TOKEN, messages) }

        withTimeout(TIMEOUT_MS.milliseconds) {
            // Handshake first (hello + status; no history in a fresh session).
            assertIs<ServerHello>(messages.receive())
            assertIs<ProcessStatus>(messages.receive())

            session.notifyClients(HookNotification("h", "title", "body", 1))

            val notification = messages.receive()
            assertIs<HookNotification>(notification)
            assertEquals("title", notification.title)
        }
        connection.cancel()
    }

    @Test
    fun invalidTokenIsRejectedWithDedicatedCloseCode() = runBlocking {
        var closeCode: Short? = null
        withTimeout(TIMEOUT_MS.milliseconds) {
            httpClient.webSocket(
                host = "127.0.0.1",
                port = port,
                path = Protocol.WEBSOCKET_PATH,
                request = { url.parameters.append(Protocol.TOKEN_PARAMETER, "wrong") },
            ) {
                // Server closes immediately; draining incoming lets us observe the close reason.
                for (frame in incoming) { /* no messages expected */ }
                closeCode = closeReason.await()?.code
            }
        }
        assertEquals(Protocol.CLOSE_INVALID_TOKEN, closeCode)
    }

    @Test
    fun missingTokenIsRejected() = runBlocking {
        var closeCode: Short? = null
        withTimeout(TIMEOUT_MS.milliseconds) {
            httpClient.webSocket(
                host = "127.0.0.1",
                port = port,
                path = Protocol.WEBSOCKET_PATH,
            ) {
                for (frame in incoming) { /* no messages expected */ }
                closeCode = closeReason.await()?.code
            }
        }
        assertEquals(Protocol.CLOSE_INVALID_TOKEN, closeCode)
    }

    @Test
    fun multipleClientsReceiveTheSameBroadcast() = runBlocking {
        val a = Channel<WatcherMessage>(Channel.UNLIMITED)
        val b = Channel<WatcherMessage>(Channel.UNLIMITED)
        val connections = listOf(
            launch { connectAndCollect(TOKEN, a) },
            launch { connectAndCollect(TOKEN, b) },
        )

        withTimeout(TIMEOUT_MS.milliseconds) {
            // Wait for both to finish the handshake (hello + status).
            for (channel in listOf(a, b)) {
                assertIs<ServerHello>(channel.receive())
                assertIs<ProcessStatus>(channel.receive())
            }

            session.emitLine(TerminalLine.Stream.STDOUT, "for everyone")

            for (channel in listOf(a, b)) {
                val batch = channel.receive()
                assertIs<OutputBatch>(batch)
                assertEquals("for everyone", batch.lines.single().text)
            }
        }
        connections.forEach { it.cancel() }
    }
}
