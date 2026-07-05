package com.tward.watcher.core.hooks

import com.tward.watcher.core.model.TerminalLine

/** Events fed into the [HookEngine] by the platform hosting it. */
sealed interface WatcherEvent {
    data class Line(val line: TerminalLine) : WatcherEvent
    data class ProcessStarted(val timestamp: Long) : WatcherEvent
    data class ProcessExited(val exitCode: Int, val timestamp: Long) : WatcherEvent

    /** Periodic clock tick; required for [Inactivity] triggers to fire. */
    data class Tick(val timestamp: Long) : WatcherEvent
}

/** Record of a hook that fired, with the placeholder values its actions were rendered with. */
data class FiredHook(val hook: Hook, val placeholders: Map<String, String>)

/**
 * Executes a single action of a fired hook. Implementations are platform specific:
 * the desktop executor broadcasts notifications, posts webhooks and runs commands.
 * Adding a new [Action] subtype only requires handling it in the executor.
 */
fun interface ActionExecutor {
    suspend fun execute(hook: Hook, action: Action, placeholders: Map<String, String>)
}

/**
 * Matches [WatcherEvent]s against configured hooks and dispatches their actions.
 *
 * Not thread safe: callers must feed events from a single coroutine at a time,
 * which the desktop session guarantees.
 *
 * @throws IllegalArgumentException at construction when a hook has an invalid
 *   regular expression, so misconfiguration is reported at startup rather than
 *   silently at match time.
 */
class HookEngine(
    hooks: List<Hook>,
    private val executor: ActionExecutor,
) {
    private val active: List<Hook> = hooks.filter { it.enabled }

    private val patterns: Map<String, Regex> = active
        .mapNotNull { hook ->
            val trigger = hook.trigger as? OutputMatches ?: return@mapNotNull null
            val regex = try {
                Regex(trigger.pattern)
            } catch (e: Throwable) {
                throw IllegalArgumentException(
                    "Hook '${hook.name}' has an invalid pattern '${trigger.pattern}': ${e.message}",
                )
            }
            hook.name to regex
        }
        .toMap()

    private val firedOnce = mutableSetOf<String>()
    private val idleFired = mutableSetOf<String>()
    private var lastActivityAt: Long? = null

    suspend fun handle(event: WatcherEvent): List<FiredHook> {
        val fired = mutableListOf<FiredHook>()
        when (event) {
            is WatcherEvent.ProcessStarted -> lastActivityAt = event.timestamp

            is WatcherEvent.Line -> {
                lastActivityAt = event.line.timestamp
                idleFired.clear()
                for (hook in active) {
                    val trigger = hook.trigger as? OutputMatches ?: continue
                    if (trigger.stream != null && trigger.stream != event.line.stream) continue
                    val match = patterns.getValue(hook.name).find(event.line.text) ?: continue
                    val placeholders = buildMap {
                        put("hook", hook.name)
                        put("line", event.line.text)
                        put("stream", event.line.stream.name)
                        put("match", match.value)
                        match.groupValues.forEachIndexed { index, value ->
                            if (index > 0) put("group$index", value)
                        }
                    }
                    fire(hook, placeholders, fired)
                }
            }

            is WatcherEvent.ProcessExited -> {
                for (hook in active) {
                    val trigger = hook.trigger as? ProcessExits ?: continue
                    if (trigger.codes != null && event.exitCode !in trigger.codes) continue
                    val placeholders = mapOf(
                        "hook" to hook.name,
                        "exitCode" to event.exitCode.toString(),
                    )
                    fire(hook, placeholders, fired)
                }
            }

            is WatcherEvent.Tick -> {
                val lastActivity = lastActivityAt ?: return fired
                for (hook in active) {
                    val trigger = hook.trigger as? Inactivity ?: continue
                    if (hook.name in idleFired) continue
                    val idleMillis = event.timestamp - lastActivity
                    if (idleMillis < trigger.seconds * 1000) continue
                    idleFired += hook.name
                    val placeholders = mapOf(
                        "hook" to hook.name,
                        "idleSeconds" to (idleMillis / 1000).toString(),
                    )
                    fire(hook, placeholders, fired)
                }
            }
        }
        return fired
    }

    private suspend fun fire(
        hook: Hook,
        placeholders: Map<String, String>,
        fired: MutableList<FiredHook>,
    ) {
        if (hook.once && hook.name in firedOnce) return
        firedOnce += hook.name
        fired += FiredHook(hook, placeholders)
        for (action in hook.actions) {
            executor.execute(hook, action, placeholders)
        }
    }
}
