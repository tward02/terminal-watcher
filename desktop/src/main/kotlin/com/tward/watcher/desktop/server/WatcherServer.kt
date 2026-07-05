package com.tward.watcher.desktop.server

import com.tward.watcher.core.protocol.OutputBatch
import com.tward.watcher.core.protocol.Protocol
import com.tward.watcher.desktop.session.WatcherSession
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.flow.onSubscription

/**
 * Serves a [WatcherSession] over WebSockets.
 *
 * Endpoints:
 *  - GET /info: plain-text liveness and pairing check.
 *  - WS  /ws?token=...: authenticated stream of [com.tward.watcher.core.protocol.WatcherMessage]s.
 *    New clients receive the hello, the replayed history and the last process
 *    status before live messages; [onSubscription] guarantees no line is lost
 *    between the history snapshot and the live subscription.
 */
class WatcherServer(
    private val session: WatcherSession,
    private val token: String,
    private val bindHost: String = "0.0.0.0",
    private val port: Int = Protocol.DEFAULT_PORT,
) {
    private var engine: EmbeddedServer<*, *>? = null

    /** Starts the server and returns the actual bound port (useful with port 0). */
    suspend fun start(): Int {
        val server = embeddedServer(CIO, host = bindHost, port = port) {
            install(WebSockets)
            routing {
                get("/info") {
                    call.respondText("terminal-watcher session '${session.description}'")
                }
                webSocket(Protocol.WEBSOCKET_PATH) {
                    val presented = call.request.queryParameters[Protocol.TOKEN_PARAMETER]
                    if (presented != token) {
                        close(CloseReason(Protocol.CLOSE_INVALID_TOKEN, "Invalid token"))
                        return@webSocket
                    }
                    session.broadcast
                        .onSubscription {
                            send(Frame.Text(Protocol.encode(session.hello())))
                            val history = session.history()
                            if (history.isNotEmpty()) {
                                send(Frame.Text(Protocol.encode(OutputBatch(history))))
                            }
                            send(Frame.Text(Protocol.encode(session.lastStatus)))
                        }
                        .collect { message ->
                            send(Frame.Text(Protocol.encode(message)))
                        }
                }
            }
        }.start(wait = false)
        engine = server
        return server.engine.resolvedConnectors().first().port
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 100, timeoutMillis = 1000)
        engine = null
    }
}
