package com.tward.watcher.core.hooks

import com.tward.watcher.core.model.TerminalLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HookConfigTest {

    @Test
    fun parsesFullConfig() {
        val config = HookConfig.parse(
            """
            {
              "hooks": [
                {
                  "name": "error-alert",
                  "trigger": { "type": "output-matches", "pattern": "ERROR.*", "stream": "STDERR" },
                  "actions": [
                    { "type": "notify", "title": "Error", "body": "{line}" },
                    { "type": "webhook", "url": "https://example.com/hook" }
                  ],
                  "once": true
                },
                {
                  "name": "finished",
                  "trigger": { "type": "process-exits", "codes": [0] },
                  "actions": [ { "type": "run-command", "command": "echo done" } ]
                },
                {
                  "name": "stalled",
                  "trigger": { "type": "inactivity", "seconds": 30 },
                  "actions": [ { "type": "notify", "title": "Stalled", "body": "No output for {idleSeconds}s" } ],
                  "enabled": false
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(3, config.hooks.size)

        val errorAlert = config.hooks[0]
        assertEquals("error-alert", errorAlert.name)
        assertEquals(OutputMatches("ERROR.*", TerminalLine.Stream.STDERR), errorAlert.trigger)
        assertEquals(2, errorAlert.actions.size)
        assertTrue(errorAlert.once)
        assertTrue(errorAlert.enabled)

        assertEquals(ProcessExits(listOf(0)), config.hooks[1].trigger)
        assertEquals(RunCommand("echo done"), config.hooks[1].actions.single())

        assertEquals(Inactivity(30), config.hooks[2].trigger)
        assertEquals(false, config.hooks[2].enabled)
    }

    @Test
    fun defaultsAreApplied() {
        val config = HookConfig.parse(
            """
            {
              "hooks": [
                {
                  "name": "minimal",
                  "trigger": { "type": "output-matches", "pattern": "x" },
                  "actions": [ { "type": "notify", "title": "t" } ]
                }
              ]
            }
            """.trimIndent(),
        )
        val hook = config.hooks.single()
        assertTrue(hook.enabled)
        assertEquals(false, hook.once)
        assertEquals(OutputMatches("x", stream = null), hook.trigger)
        assertEquals(Notify(title = "t", body = "{line}"), hook.actions.single())
    }

    @Test
    fun emptyConfigParses() {
        assertEquals(HookConfig(emptyList()), HookConfig.parse("""{"hooks":[]}"""))
        assertEquals(HookConfig(emptyList()), HookConfig.parse("{}"))
    }

    @Test
    fun unknownTriggerTypeFailsLoudly() {
        assertFailsWith<Exception> {
            HookConfig.parse(
                """
                {
                  "hooks": [
                    {
                      "name": "bad",
                      "trigger": { "type": "not-a-trigger" },
                      "actions": []
                    }
                  ]
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun configRoundTripsThroughJson() {
        val config = HookConfig(
            listOf(
                Hook(
                    name = "h",
                    trigger = ProcessExits(null),
                    actions = listOf(Notify("t", "b"), Webhook("https://x.test")),
                    once = true,
                ),
            ),
        )
        assertEquals(config, HookConfig.parse(HookConfig.toJson(config)))
    }
}
