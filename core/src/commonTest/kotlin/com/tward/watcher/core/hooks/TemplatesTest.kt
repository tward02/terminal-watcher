package com.tward.watcher.core.hooks

import kotlin.test.Test
import kotlin.test.assertEquals

class TemplatesTest {

    @Test
    fun replacesKnownPlaceholders() {
        val result = Templates.render(
            "Build {group1} finished on {stream}",
            mapOf("group1" to "42", "stream" to "STDOUT"),
        )
        assertEquals("Build 42 finished on STDOUT", result)
    }

    @Test
    fun leavesUnknownPlaceholdersVerbatim() {
        assertEquals("Hello {nope}", Templates.render("Hello {nope}", emptyMap()))
    }

    @Test
    fun rendersTemplateWithoutPlaceholdersUnchanged() {
        assertEquals("plain text", Templates.render("plain text", mapOf("line" to "x")))
    }

    @Test
    fun replacesRepeatedPlaceholders() {
        assertEquals("a a", Templates.render("{v} {v}", mapOf("v" to "a")))
    }

    @Test
    fun ignoresMalformedBraces() {
        assertEquals("{not closed", Templates.render("{not closed", mapOf("not" to "x")))
    }
}
