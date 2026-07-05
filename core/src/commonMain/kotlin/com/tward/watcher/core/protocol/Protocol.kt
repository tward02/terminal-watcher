package com.tward.watcher.core.protocol

import com.tward.watcher.core.model.TerminalLine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Messages exchanged between the desktop watcher (server) and mobile viewers (clients).
 *
 * The wire format is one JSON object per WebSocket text frame, discriminated by a
 * "type" field. All messages currently flow from server to client; clients
 * authenticate with a token passed as a query parameter on the upgrade request.
 */
@Serializable
sealed interface WatcherMessage

/** First message sent to every client after a successful connection. */
@Serializable
@SerialName("hello")
data class ServerHello(
    val sessionId: String,
    /** Human readable description of what is being watched, e.g. the wrapped command line. */
    val description: String,
    /** Epoch millis at which the watcher session started. */
    val startedAt: Long,
    val protocolVersion: Int = Protocol.VERSION,
) : WatcherMessage

/** A batch of captured output lines, in sequence order. */
@Serializable
@SerialName("output")
data class OutputBatch(val lines: List<TerminalLine>) : WatcherMessage

/** Lifecycle state of the watched process. */
@Serializable
@SerialName("status")
data class ProcessStatus(
    val running: Boolean,
    val exitCode: Int? = null,
    val timestamp: Long = 0,
) : WatcherMessage

/** Emitted when a hook with a notify action fires; viewers surface this as a push notification. */
@Serializable
@SerialName("notification")
data class HookNotification(
    val hookName: String,
    val title: String,
    val body: String,
    val timestamp: Long,
) : WatcherMessage

object Protocol {
    const val VERSION: Int = 1
    const val WEBSOCKET_PATH: String = "/ws"
    const val TOKEN_PARAMETER: String = "token"
    const val DEFAULT_PORT: Int = 8765

    /** WebSocket close code sent when the supplied token is missing or wrong. */
    const val CLOSE_INVALID_TOKEN: Short = 4401

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    fun encode(message: WatcherMessage): String =
        json.encodeToString(WatcherMessage.serializer(), message)

    fun decode(text: String): WatcherMessage =
        json.decodeFromString(WatcherMessage.serializer(), text)
}
