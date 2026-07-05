package com.tward.watcher.core.hooks

/**
 * Minimal {placeholder} template rendering for hook action fields.
 *
 * Unknown placeholders are left verbatim so a typo is visible in the output rather
 * than silently swallowed. Available placeholders depend on the trigger, see the
 * placeholder maps built by [HookEngine].
 */
object Templates {
    private val PLACEHOLDER = Regex("""\{([a-zA-Z0-9]+)}""")

    fun render(template: String, values: Map<String, String>): String =
        PLACEHOLDER.replace(template) { match ->
            values[match.groupValues[1]] ?: match.value
        }
}
