package com.hermes.webui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Flatten WebUI JSON `content` / session counts the same way iOS `textValue` does. */
object JsonText {
    fun value(v: JsonElement?): String {
        if (v == null || v is JsonNull) return ""
        return when (v) {
            is JsonPrimitive -> v.contentOrNull.orEmpty()
            is JsonArray -> v.map { value(it) }.filter { it.isNotBlank() }.joinToString("\n")
            is JsonObject -> {
                for (k in listOf("text", "content", "value")) {
                    val s = value(v[k])
                    if (s.isNotBlank()) return s
                }
                ""
            }
            else -> ""
        }
    }

    /** Server field is `messages`; `message_count` is fallback. Missing key = null. */
    fun sessionCount(messages: Int?, messageCount: Int?): Int = messages ?: messageCount ?: 0
}
