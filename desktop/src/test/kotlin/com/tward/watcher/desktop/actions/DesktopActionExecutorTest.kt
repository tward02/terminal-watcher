package com.tward.watcher.desktop.actions

import com.tward.watcher.core.hooks.*
import com.tward.watcher.core.protocol.HookNotification
import com.tward.watcher.desktop.session.WatcherSession
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import io.ktor.server.cio.CIO as ServerCIO

class DesktopActionExecutorTest {

    private fun newSession() = WatcherSession(
        sessionId = "s",
        description = "test",
        startedAt = 1L,
        historySize = 10,
    )

    private val hook = Hook("my-hook", ProcessExits(), actions = emptyList())

    @Test
    fun notifyActionBroadcastsRenderedNotification() = runBlocking {
        val session = newSession()
        val executor = DesktopActionExecutor(session, HttpClient(CIO), clock = { 42 })

        val notification = async(start = CoroutineStart.UNDISPATCHED) {
            session.broadcast.filterIsInstance<HookNotification>().first()
        }
        executor.execute(
            hook,
            Notify(title = "Exited {exitCode}", body = "hook {hook}"),
            mapOf("exitCode" to "1", "hook" to "my-hook"),
        )

        val received = withTimeout(10_000.milliseconds) { notification.await() }
        assertEquals(HookNotification("my-hook", "Exited 1", "hook my-hook", 42), received)
    }

    @Test
    fun webhookActionPostsJsonPayload() = runBlocking {
        val received = CompletableDeferred<String>()
        val server = embeddedServer(ServerCIO, port = 0) {
            routing {
                post("/hook") {
                    received.complete(call.receiveText())
                    call.respondText("ok")
                }
            }
        }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val executor = DesktopActionExecutor(newSession(), HttpClient(CIO), clock = { 7 })

            executor.execute(
                hook,
                Webhook("http://127.0.0.1:$port/hook"),
                mapOf("exitCode" to "0"),
            )

            val body = withTimeout(10_000.milliseconds) { received.await() }
            val payload = Json.parseToJsonElement(body).jsonObject
            assertEquals("my-hook", payload.getValue("hook").jsonPrimitive.content)
            assertEquals("7", payload.getValue("timestamp").jsonPrimitive.content)
            assertEquals(
                "0",
                (payload.getValue("context") as JsonObject).getValue("exitCode").jsonPrimitive.content,
            )
        } finally {
            server.stop(100, 500)
        }
    }

    @Test
    fun failedWebhookDoesNotThrow() = runBlocking {
        val executor = DesktopActionExecutor(newSession(), HttpClient(CIO))
        // Nothing listens on this port; execute must swallow the failure.
        executor.execute(hook, Webhook("http://127.0.0.1:1/hook"), emptyMap())
    }

    @Test
    fun runCommandActionExecutesShellCommandWithPlaceholders() = runBlocking {
        val marker = File.createTempFile("terminal-watcher-test", ".txt")
        marker.delete()
        try {
            val executor = DesktopActionExecutor(newSession(), HttpClient(CIO))
            val isWindows = System.getProperty("os.name").startsWith("Windows")
            val command = if (isWindows) {
                "echo {exitCode} > \"${marker.absolutePath}\""
            } else {
                "echo {exitCode} > '${marker.absolutePath}'"
            }

            executor.execute(hook, RunCommand(command), mapOf("exitCode" to "5"))

            assertTrue(marker.exists(), "command should have created the marker file")
            assertEquals("5", marker.readText().trim())
        } finally {
            marker.delete()
        }
    }
}
