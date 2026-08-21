package com.hermes.webui

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.sse.EventSource

class AppVm(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    var api: ApiClient? = null
        private set

    val configured = mutableStateOf(prefs.isConfigured)
    val needsLogin = mutableStateOf(false)
    val ready = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)
    val sessions = mutableStateListOf<SessionRow>()
    val bubbles = mutableStateListOf<Bubble>()
    val live = mutableStateOf("")
    val busy = mutableStateOf(false)
    val title = mutableStateOf("Hermes")
    var sid = ""
        private set
    private var es: EventSource? = null

    fun reconnect() {
        val u = prefs.baseUrl
        configured.value = u.isNotBlank()
        if (u.isBlank()) return
        api = ApiClient(if (u.contains("://")) u else "http://$u")
        viewModelScope.launch { bootstrap() }
    }

    init { reconnect() }

    private suspend fun bootstrap() {
        val c = api ?: return
        error.value = null
        try {
            val st = withContext(Dispatchers.IO) { c.authStatus() }
            if (st.auth_enabled && !st.logged_in) {
                needsLogin.value = true
                ready.value = false
            } else {
                needsLogin.value = false
                ready.value = true
                withContext(Dispatchers.IO) { runCatching { c.refreshCsrf() } }
                refreshSessions()
            }
        } catch (e: Exception) {
            error.value = e.message
        }
    }

    fun login(password: String) {
        val c = api ?: return
        viewModelScope.launch {
            error.value = null
            try {
                withContext(Dispatchers.IO) { c.login(password) }
                needsLogin.value = false
                ready.value = true
                refreshSessions()
            } catch (e: Exception) {
                error.value = "Login failed"
            }
        }
    }

    fun refreshSessions() {
        val c = api ?: return
        viewModelScope.launch {
            try {
                val rows = withContext(Dispatchers.IO) { c.sessions() }
                sessions.clear(); sessions.addAll(rows)
            } catch (e: Exception) {
                error.value = e.message
            }
        }
    }

    fun open(row: SessionRow) {
        val c = api ?: return
        sid = row.sid
        title.value = row.displayTitle
        live.value = ""
        viewModelScope.launch {
            try {
                val msgs = withContext(Dispatchers.IO) { c.messages(row.sid) }
                bubbles.clear()
                msgs.forEach {
                    if (it.role == "user" || it.role == "assistant" || it.role == "tool") {
                        bubbles.add(Bubble(it.role, it.content, it.tool_name ?: it.name))
                    }
                }
            } catch (e: Exception) {
                error.value = e.message
            }
        }
    }

    fun newChat() {
        val c = api ?: return
        viewModelScope.launch {
            try {
                sid = withContext(Dispatchers.IO) { c.newSession() }
                bubbles.clear(); live.value = ""; title.value = "New conversation"
                refreshSessions()
            } catch (e: Exception) {
                error.value = e.message
            }
        }
    }

    fun send(text: String) {
        val c = api ?: return
        val t = text.trim(); if (t.isEmpty()) return
        viewModelScope.launch {
            if (sid.isBlank()) {
                try { sid = withContext(Dispatchers.IO) { c.newSession() } }
                catch (e: Exception) { error.value = e.message; return@launch }
            }
            bubbles.add(Bubble("user", t))
            busy.value = true
            live.value = ""
            try {
                val start = withContext(Dispatchers.IO) { c.startChat(sid, t) }
                val streamId = start.stream_id.orEmpty()
                if (streamId.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        es?.cancel()
                        es = c.stream(streamId, { ev, data -> onSse(ev, data) }, {
                            viewModelScope.launch(Dispatchers.Main) {
                                if (live.value.isNotEmpty()) {
                                    bubbles.add(Bubble("assistant", live.value))
                                    live.value = ""
                                }
                                busy.value = false
                            }
                        })
                    }
                } else busy.value = false
            } catch (e: Exception) {
                error.value = e.message
                busy.value = false
            }
        }
    }

    private fun onSse(ev: String, data: String) {
        val e = ev.lowercase()
        viewModelScope.launch(Dispatchers.Main) {
            val obj = runCatching {
                Json.parseToJsonElement(data).jsonObject
            }.getOrNull()
            when {
                e == "token" || e == "delta" -> {
                    val piece = obj?.get("text")?.let { (it as? JsonPrimitive)?.contentOrNull }
                        ?: obj?.get("delta")?.let { (it as? JsonPrimitive)?.contentOrNull }
                        ?: ""
                    live.value += piece
                }
                e.contains("tool") -> {
                    val name = obj?.get("name")?.let { (it as? JsonPrimitive)?.contentOrNull }
                        ?: obj?.get("tool")?.let { (it as? JsonPrimitive)?.contentOrNull }
                        ?: "tool"
                    bubbles.add(Bubble("assistant", "", name))
                }
                e == "done" || e.contains("complete") -> {
                    if (live.value.isNotEmpty()) {
                        bubbles.add(Bubble("assistant", live.value))
                        live.value = ""
                    }
                    busy.value = false
                }
            }
        }
    }

    fun saveUrl(url: String) {
        prefs.baseUrl = url
        reconnect()
    }

    fun loadSettingsMap(): Map<String, String> {
        val c = api ?: return emptyMap()
        return try {
            val raw = c.settingsRaw()
            val obj = Json.parseToJsonElement(raw) as? JsonObject ?: return emptyMap()
            obj.filterKeys { it != "password_hash" }.mapValues { (_, v) ->
                (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
            }
        } catch (_: Exception) { emptyMap() }
    }
}
