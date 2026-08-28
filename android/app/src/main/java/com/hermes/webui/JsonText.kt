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

    /** Human "ago/in" for epoch-seconds or ISO strings; falls back to raw text. */
    fun ts(raw: String): String {
        val epoch = raw.toDoubleOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(raw).toEpochSecond().toDouble() }.getOrNull()
            ?: runCatching {
                java.time.LocalDateTime.parse(raw.replace(" ", "T"))
                    .atZone(java.time.ZoneId.systemDefault()).toEpochSecond().toDouble()
            }.getOrNull()
            ?: return raw.take(19)
        val diff = epoch - System.currentTimeMillis() / 1000.0
        val a = kotlin.math.abs(diff)
        val span = when {
            a >= 86400 -> "${(a / 86400).toInt()}d"
            a >= 3600 -> "${(a / 3600).toInt()}h"
            a >= 60 -> "${(a / 60).toInt()}m"
            else -> "${a.toInt()}s"
        }
        return if (diff >= 0) "in $span" else "$span ago"
    }
}
