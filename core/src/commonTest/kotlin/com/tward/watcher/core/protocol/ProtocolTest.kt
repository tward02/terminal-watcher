package com.tward.watcher.core.protocol

import com.tward.watcher.core.model.TerminalLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProtocolTest {

    private fun roundTrip(message: WatcherMessage): WatcherMessage =
        Protocol.decode(Protocol.encode(message))

    @Test
    fun serverHelloRoundTrips() {
        val hello = ServerHello(
            sessionId = "abc",
            description = "gradle build",
            startedAt = 1234L,
        )
        assertEquals(hello, roundTrip(hello))
    }

    @Test
    fun outputBatchRoundTrips() {
        val batch = OutputBatch(
            listOf(
                TerminalLine(1, 100, TerminalLine.Stream.STDOUT, "hello"),
                TerminalLine(2, 101, TerminalLine.Stream.STDERR, "oh no"),
            ),
        )
        assertEquals(batch, roundTrip(batch))
    }

    @Test
    fun processStatusRoundTrips() {
        val status = ProcessStatus(running = false, exitCode = 3, timestamp = 99)
        assertEquals(status, roundTrip(status))
    }

    @Test
    fun hookNotificationRoundTrips() {
        val notification = HookNotification("build-done", "Build finished", "exit 0", 42)
        assertEquals(notification, roundTrip(notification))
    }

    @Test
    fun encodingUsesTypeDiscriminator() {
        val encoded = Protocol.encode(ProcessStatus(running = true))
        assertTrue("\"type\":\"status\"" in encoded, encoded)
    }

    @Test
    fun decodingIgnoresUnknownFields() {
        val decoded = Protocol.decode(
            """{"type":"status","running":true,"futureField":"ignored"}""",
        )
        assertEquals(ProcessStatus(running = true), decoded)
    }
}
