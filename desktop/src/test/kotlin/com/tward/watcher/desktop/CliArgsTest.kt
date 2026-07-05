package com.tward.watcher.desktop

import com.tward.watcher.core.protocol.Protocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliArgsTest {

    @Test
    fun parsesRunModeWithDefaults() {
        val args = CliArgs.parse(arrayOf("run", "--", "gradle", "build"))
        assertEquals(CliArgs.Mode.RUN, args.mode)
        assertEquals(listOf("gradle", "build"), args.command)
        assertEquals(Protocol.DEFAULT_PORT, args.port)
        assertEquals("0.0.0.0", args.bindHost)
        assertNull(args.token)
        assertNull(args.hooksPath)
        assertEquals(CliArgs.DEFAULT_HISTORY, args.historySize)
    }

    @Test
    fun parsesAllOptions() {
        val args = CliArgs.parse(
            arrayOf(
                "run",
                "--port", "9000",
                "--bind", "127.0.0.1",
                "--token", "abc",
                "--hooks", "my-hooks.json",
                "--history", "500",
                "--", "npm", "test",
            ),
        )
        assertEquals(9000, args.port)
        assertEquals("127.0.0.1", args.bindHost)
        assertEquals("abc", args.token)
        assertEquals("my-hooks.json", args.hooksPath)
        assertEquals(500, args.historySize)
        assertEquals(listOf("npm", "test"), args.command)
    }

    @Test
    fun commandMayContainOptionLikeArguments() {
        val args = CliArgs.parse(arrayOf("run", "--", "cmd", "--port", "1234"))
        assertEquals(listOf("cmd", "--port", "1234"), args.command)
        assertEquals(Protocol.DEFAULT_PORT, args.port)
    }

    @Test
    fun parsesPipeMode() {
        val args = CliArgs.parse(arrayOf("pipe", "--port", "0"))
        assertEquals(CliArgs.Mode.PIPE, args.mode)
        assertEquals(0, args.port)
        assertTrue(args.command.isEmpty())
    }

    @Test
    fun runWithoutCommandFails() {
        assertFailsWith<CliArgs.CliException> { CliArgs.parse(arrayOf("run")) }
        assertFailsWith<CliArgs.CliException> { CliArgs.parse(arrayOf("run", "--")) }
    }

    @Test
    fun missingModeFails() {
        assertFailsWith<CliArgs.CliException> { CliArgs.parse(emptyArray()) }
    }

    @Test
    fun unknownModeFails() {
        assertFailsWith<CliArgs.CliException> { CliArgs.parse(arrayOf("watch")) }
    }

    @Test
    fun unknownOptionFails() {
        assertFailsWith<CliArgs.CliException> { CliArgs.parse(arrayOf("pipe", "--nope")) }
    }

    @Test
    fun missingOptionValueFails() {
        assertFailsWith<CliArgs.CliException> { CliArgs.parse(arrayOf("pipe", "--port")) }
    }

    @Test
    fun nonNumericPortFails() {
        assertFailsWith<CliArgs.CliException> { CliArgs.parse(arrayOf("pipe", "--port", "web")) }
    }

    @Test
    fun outOfRangePortFails() {
        assertFailsWith<CliArgs.CliException> { CliArgs.parse(arrayOf("pipe", "--port", "70000")) }
    }

    @Test
    fun separatorInPipeModeFails() {
        assertFailsWith<CliArgs.CliException> { CliArgs.parse(arrayOf("pipe", "--", "cmd")) }
    }

    @Test
    fun nonPositiveHistoryFails() {
        assertFailsWith<CliArgs.CliException> { CliArgs.parse(arrayOf("pipe", "--history", "0")) }
    }
}
