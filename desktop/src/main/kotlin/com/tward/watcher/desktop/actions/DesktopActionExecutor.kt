package com.tward.watcher.desktop.actions

import com.tward.watcher.core.hooks.Action
import com.tward.watcher.core.hooks.ActionExecutor
import com.tward.watcher.core.hooks.Hook
import com.tward.watcher.core.hooks.Notify
import com.tward.watcher.core.hooks.RunCommand
import com.tward.watcher.core.hooks.Templates
import com.tward.watcher.core.hooks.Webhook
import com.tward.watcher.core.protocol.HookNotification
import com.tward.watcher.desktop.session.WatcherSession
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Executes hook actions on the desktop:
 *  - [Notify] broadcasts a notification to connected viewers,
 *  - [Webhook] POSTs a JSON payload describing the fired hook,
 *  - [RunCommand] runs a shell command on this machine.
 *
 * Failures of individual actions are logged to stderr but never propagate: a
 * broken webhook must not take the watcher down.
 */
class DesktopActionExecutor(
    private val session: WatcherSession,
    private val httpClient: HttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
) : ActionExecutor {

    override suspend fun execute(hook: Hook, action: Action, placeholders: Map<String, String>) {
        try {
            when (action) {
                is Notify -> session.notifyClients(
                    HookNotification(
                        hookName = hook.name,
                        title = Templates.render(action.title, placeholders),
                        body = Templates.render(action.body, placeholders),
                        timestamp = clock(),
                    ),
                )

                is Webhook -> postWebhook(action.url, hook, placeholders)

                is RunCommand -> runShellCommand(Templates.render(action.command, placeholders))
            }
        } catch (e: Exception) {
            System.err.println("[terminal-watcher] action ${action::class.simpleName} of hook '${hook.name}' failed: ${e.message}")
        }
    }

    private suspend fun postWebhook(url: String, hook: Hook, placeholders: Map<String, String>) {
        val payload = JsonObject(
            mapOf(
                "hook" to JsonPrimitive(hook.name),
                "timestamp" to JsonPrimitive(clock()),
                "context" to JsonObject(placeholders.mapValues { JsonPrimitive(it.value) }),
            ),
        )
        httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(JsonObject.serializer(), payload))
        }
    }

    private suspend fun runShellCommand(command: String) {
        val shell = if (System.getProperty("os.name").startsWith("Windows")) {
            listOf("cmd.exe", "/c", command)
        } else {
            listOf("/bin/sh", "-c", command)
        }
        withContext(Dispatchers.IO) {
            ProcessBuilder(shell)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .onExit()
                .await()
        }
    }
}
