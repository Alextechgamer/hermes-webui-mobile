package com.hermes.webui

import org.junit.Assert.assertEquals
import org.junit.Test

class OfficialUrlTest {
    @Test
    fun overrideWins() {
        assertEquals("http://box:9119", OfficialUrl.base("http://box:8787", "http://box:9119"))
    }

    @Test
    fun derivesPort9119FromWebui() {
        assertEquals("http://10.0.0.5:9119", OfficialUrl.base("http://10.0.0.5:8787", ""))
    }

    @Test
    fun emptyWhenNoHost() {
        assertEquals("", OfficialUrl.base("", ""))
    }
}
