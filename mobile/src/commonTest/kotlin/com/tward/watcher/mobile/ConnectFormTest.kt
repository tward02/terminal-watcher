package com.tward.watcher.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectFormTest {

    @Test
    fun validFormProducesTrimmedValues() {
        val result = ConnectForm(host = " 192.168.1.10 ", port = "8765", token = " abc ").validate()
        assertEquals(ConnectForm.Validated("192.168.1.10", 8765, "abc"), result.getOrThrow())
    }

    @Test
    fun emptyHostIsRejected() {
        val result = ConnectForm(host = "  ", port = "8765", token = "t").validate()
        assertTrue(result.isFailure)
        assertTrue("Host" in result.exceptionOrNull()!!.message!!)
    }

    @Test
    fun hostWithSpacesIsRejected() {
        assertTrue(ConnectForm(host = "my host", port = "8765", token = "t").validate().isFailure)
    }

    @Test
    fun nonNumericPortIsRejected() {
        val result = ConnectForm(host = "h", port = "abc", token = "t").validate()
        assertTrue(result.isFailure)
        assertTrue("Port" in result.exceptionOrNull()!!.message!!)
    }

    @Test
    fun outOfRangePortIsRejected() {
        assertTrue(ConnectForm(host = "h", port = "0", token = "t").validate().isFailure)
        assertTrue(ConnectForm(host = "h", port = "65536", token = "t").validate().isFailure)
    }

    @Test
    fun emptyTokenIsRejected() {
        val result = ConnectForm(host = "h", port = "8765", token = "").validate()
        assertTrue(result.isFailure)
        assertTrue("Token" in result.exceptionOrNull()!!.message!!)
    }
}
