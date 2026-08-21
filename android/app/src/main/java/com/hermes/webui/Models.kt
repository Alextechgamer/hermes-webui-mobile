package com.hermes.webui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class AuthStatus(
    val auth_enabled: Boolean = false,
    val logged_in: Boolean = false,
    val password_auth_enabled: Boolean = false,
)

@Serializable
data class SessionRow(
    val session_id: String? = null,
    val id: String? = null,
    val title: String? = null,
    val preview: String? = null,
    val message_count: Int? = null,
    val messages: Int? = null,
    val source: String? = null,
) {
    val sid: String get() = session_id ?: id ?: ""
    val displayTitle: String get() = title?.trim().orEmpty().ifBlank { "New conversation" }
    val msgCount: Int get() = message_count ?: messages ?: 0
}

@Serializable
data class ChatMsg(
    val id: Long = 0,
    val role: String = "",
    val content: String = "",
    val tool_name: String? = null,
    val name: String? = null,
)

@Serializable
data class ChatStart(val stream_id: String? = null, val session_id: String? = null, val title: String? = null)

data class Bubble(
    val role: String,
    val text: String,
    val tool: String? = null,
)
