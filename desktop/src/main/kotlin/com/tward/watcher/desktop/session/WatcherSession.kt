package com.tward.watcher.desktop.session

import com.tward.watcher.core.buffer.LineRingBuffer
import com.tward.watcher.core.hooks.HookEngine
import com.tward.watcher.core.hooks.WatcherEvent
import com.tward.watcher.core.model.TerminalLine
import com.tward.watcher.core.protocol.HookNotification
import com.tward.watcher.core.protocol.OutputBatch
import com.tward.watcher.core.protocol.ProcessStatus
import com.tward.watcher.core.protocol.ServerHello
import com.tward.watcher.core.protocol.WatcherMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Central hub of a watcher run: assigns sequence numbers, keeps the history
 * buffer, feeds the hook engine and broadcasts protocol messages to whatever
 * WebSocket sessions are subscribed to [broadcast].
 *
 * [hookEngine] is assigned after construction because the desktop action executor
 * needs a reference back to the session (to broadcast notifications).
 */
class WatcherSession(
    val sessionId: String,
    val description: String,
    val startedAt: Long,
    historySize: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var hookEngine: HookEngine? = null

    private val mutex = Mutex()
    private val buffer = LineRingBuffer(historySize)
    private var nextSeq = 1L

    @Volatile
    var lastStatus: ProcessStatus = ProcessStatus(running = true)
        private set

    private val _broadcast = MutableSharedFlow<WatcherMessage>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val broadcast: SharedFlow<WatcherMessage> = _broadcast

    fun hello(): ServerHello =
        ServerHello(sessionId = sessionId, description = description, startedAt = startedAt)

    suspend fun history(): List<TerminalLine> = mutex.withLock { buffer.snapshot() }

    /** Captures one output line: buffers it, broadcasts it and runs hooks against it. */
    suspend fun emitLine(stream: TerminalLine.Stream, text: String) {
        val line = mutex.withLock {
            TerminalLine(seq = nextSeq++, timestamp = clock(), stream = stream, text = text)
                .also(buffer::add)
        }
        _broadcast.emit(OutputBatch(listOf(line)))
        // The engine is not thread safe; all handle() calls are serialised by the mutex.
        mutex.withLock {
            hookEngine?.handle(WatcherEvent.Line(line))
        }
    }

    suspend fun processStarted() {
        mutex.withLock {
            hookEngine?.handle(WatcherEvent.ProcessStarted(clock()))
        }
    }

    suspend fun processExited(exitCode: Int) {
        val status = ProcessStatus(running = false, exitCode = exitCode, timestamp = clock())
        lastStatus = status
        _broadcast.emit(status)
        emitLine(TerminalLine.Stream.SYSTEM, "Process exited with code $exitCode")
        mutex.withLock {
            hookEngine?.handle(WatcherEvent.ProcessExited(exitCode, clock()))
        }
    }

    /** Broadcasts a fired hook notification to all connected viewers. */
    suspend fun notifyClients(notification: HookNotification) {
        _broadcast.emit(notification)
    }

    /**
     * Drives inactivity triggers with a once-per-second tick. Callers only need
     * this when at least one hook uses an inactivity trigger.
     */
    fun startInactivityTicker(scope: CoroutineScope, intervalMillis: Long = 1000) =
        scope.launch {
            while (isActive) {
                delay(intervalMillis.milliseconds)
                mutex.withLock {
                    hookEngine?.handle(WatcherEvent.Tick(clock()))
                }
            }
        }
}
