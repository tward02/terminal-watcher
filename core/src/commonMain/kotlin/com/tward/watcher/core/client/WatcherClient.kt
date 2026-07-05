package com.tward.watcher.core.client

import com.tward.watcher.core.buffer.LineRingBuffer
import com.tward.watcher.core.model.TerminalLine
import com.tward.watcher.core.protocol.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration.Companion.milliseconds

/** Connection lifecycle of a [WatcherClient]. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val hello: ServerHello) : ConnectionState

    /** Connection attempt failed or dropped. [willRetry] is false for fatal errors such as a bad token. */
    data class Failed(val reason: String, val willRetry: Boolean) : ConnectionState
}

/**
 * What the mobile UI programs against. Extracted as an interface so view models can
 * be tested with a fake feed instead of a live socket.
 */
interface WatcherClientApi {
    val state: StateFlow<ConnectionState>
    val lines: StateFlow<List<TerminalLine>>
    val notifications: SharedFlow<HookNotification>
    val processStatus: StateFlow<ProcessStatus?>

    fun connect()
    fun disconnect()

    /** Disconnects and releases the underlying HTTP client. The instance is unusable afterwards. */
    fun close()
}

/**
 * Streams a watcher session over a WebSocket, reconnecting automatically until
 * [disconnect] is called or the server rejects the token.
 */
class WatcherClient(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val scope: CoroutineScope,
    maxLines: Int = 5000,
    private val retryDelayMillis: Long = 3000,
    private val httpClient: HttpClient = HttpClient(CIO) { install(WebSockets) },
) : WatcherClientApi {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state

    private val _lines = MutableStateFlow<List<TerminalLine>>(emptyList())
    override val lines: StateFlow<List<TerminalLine>> = _lines

    private val _notifications = MutableSharedFlow<HookNotification>(extraBufferCapacity = 64)
    override val notifications: SharedFlow<HookNotification> = _notifications

    private val _processStatus = MutableStateFlow<ProcessStatus?>(null)
    override val processStatus: StateFlow<ProcessStatus?> = _processStatus

    private val buffer = LineRingBuffer(maxLines)
    private var job: Job? = null

    override fun connect() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                _state.value = ConnectionState.Connecting
                val fatal = try {
                    session()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.value = ConnectionState.Failed(
                        reason = e.message ?: "Connection failed",
                        willRetry = true,
                    )
                    false
                }
                if (fatal) return@launch
                delay(retryDelayMillis.milliseconds)
            }
        }
    }

    /** Runs one connection until it drops. Returns true when the failure is fatal (no retry). */
    private suspend fun session(): Boolean {
        var fatal = false
        httpClient.webSocket(
            host = host,
            port = port,
            path = Protocol.WEBSOCKET_PATH,
            request = {
                url.parameters.append(Protocol.TOKEN_PARAMETER, token)
            },
        ) {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                onMessage(Protocol.decode(frame.readText()))
            }
            val reason = closeReason.await()
            if (reason?.code == Protocol.CLOSE_INVALID_TOKEN) {
                _state.value = ConnectionState.Failed(
                    reason = "Rejected by server: invalid token",
                    willRetry = false,
                )
                fatal = true
            } else {
                _state.value = ConnectionState.Failed(
                    reason = "Connection closed" + (reason?.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""),
                    willRetry = true,
                )
            }
        }
        return fatal
    }

    private suspend fun onMessage(message: WatcherMessage) {
        when (message) {
            is ServerHello -> {
                // Fresh session: the server replays its history buffer next, so start clean.
                buffer.clear()
                _lines.value = emptyList()
                _state.value = ConnectionState.Connected(message)
            }

            is OutputBatch -> {
                message.lines.forEach(buffer::add)
                _lines.value = buffer.snapshot()
            }

            is ProcessStatus -> _processStatus.value = message

            is HookNotification -> _notifications.emit(message)
        }
    }

    override fun disconnect() {
        job?.cancel()
        job = null
        _state.value = ConnectionState.Disconnected
    }

    override fun close() {
        disconnect()
        httpClient.close()
    }
}
