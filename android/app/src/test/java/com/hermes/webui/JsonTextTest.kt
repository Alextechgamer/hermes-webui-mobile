package com.hermes.webui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Test

class JsonTextTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun stringContent() {
        assertEquals("hello", JsonText.value(json.parseToJsonElement("\"hello\"")))
    }

    @Test
    fun partsArrayJoinsText() {
        val el = json.parseToJsonElement(
            """[{"type":"text","text":"one"},{"type":"text","text":"two"}]""",
        )
        assertEquals("one\ntwo", JsonText.value(el))
    }

    @Test
    fun partsArrayPrefersTextOverType() {
        val el = json.parseToJsonElement("""[{"type":"text","content":"body"}]""")
        assertEquals("body", JsonText.value(el))
    }

    @Test
    fun objectContent() {
        val el = json.parseToJsonElement("""{"text":"plain"}""")
        assertEquals("plain", JsonText.value(el))
    }

    @Test
    fun nullAndEmpty() {
        assertEquals("", JsonText.value(null))
        assertEquals("", JsonText.value(JsonNull))
        assertEquals("", JsonText.value(json.parseToJsonElement("[]")))
    }

    @Test
    fun sessionCountPrefersMessages() {
        assertEquals(12, JsonText.sessionCount(12, 0))
        assertEquals(7, JsonText.sessionCount(null, 7))
        assertEquals(0, JsonText.sessionCount(null, null))
        assertEquals(0, JsonText.sessionCount(0, 99))
    }
}
