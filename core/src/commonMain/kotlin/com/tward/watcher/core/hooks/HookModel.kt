package com.tward.watcher.core.hooks

import com.tward.watcher.core.model.TerminalLine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Condition under which a [Hook] fires.
 *
 * Triggers are a sealed, serializable hierarchy: to add a new trigger type, add a
 * subclass with a @SerialName discriminator and teach [HookEngine.handle] to match it.
 */
@Serializable
sealed interface Trigger

/** Fires when a captured output line matches [pattern] (a regular expression, find semantics). */
@Serializable
@SerialName("output-matches")
data class OutputMatches(
    val pattern: String,
    /** Restrict matching to one stream; null matches stdout, stderr and system lines. */
    val stream: TerminalLine.Stream? = null,
) : Trigger

/** Fires when the watched process exits. [codes] restricts to specific exit codes; null means any. */
@Serializable
@SerialName("process-exits")
data class ProcessExits(val codes: List<Int>? = null) : Trigger

/** Fires when no output has been produced for [seconds]. Re-arms once new output arrives. */
@Serializable
@SerialName("inactivity")
data class Inactivity(val seconds: Long) : Trigger

/**
 * What happens when a hook fires.
 *
 * Like triggers, actions are a sealed, serializable hierarchy; platform-specific
 * execution lives behind [ActionExecutor]. String fields support {placeholder}
 * templates, see [Templates].
 */
@Serializable
sealed interface Action

/** Send a notification to all connected viewers, surfaced as a push notification on the phone. */
@Serializable
@SerialName("notify")
data class Notify(
    val title: String,
    val body: String = "{line}",
) : Action

/** POST a JSON payload describing the fired hook to [url]. */
@Serializable
@SerialName("webhook")
data class Webhook(val url: String) : Action

/** Run a shell command on the desktop machine. */
@Serializable
@SerialName("run-command")
data class RunCommand(val command: String) : Action

@Serializable
data class Hook(
    val name: String,
    val trigger: Trigger,
    val actions: List<Action>,
    val enabled: Boolean = true,
    /** When true the hook fires at most once per watcher session. */
    val once: Boolean = false,
)

@Serializable
data class HookConfig(val hooks: List<Hook> = emptyList()) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
            prettyPrint = true
        }

        fun parse(text: String): HookConfig = json.decodeFromString(serializer(), text)

        fun toJson(config: HookConfig): String = json.encodeToString(serializer(), config)
    }
}
