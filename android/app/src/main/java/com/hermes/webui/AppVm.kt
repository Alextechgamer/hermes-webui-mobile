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
import java.net.URI

class AppVm(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    var api: ApiClient? = null
        private set

    val configured = mutableStateOf(prefs.isConfigured)
    val needsLogin = mutableStateOf(false)
    val ready = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)
    val panel = mutableStateOf(Panel.Chat)
    val sessions = mutableStateListOf<SessionRow>()
    val bubbles = mutableStateListOf<Bubble>()
    val live = mutableStateOf("")
    val busy = mutableStateOf(false)
    val title = mutableStateOf("Hermes")
    val rows = mutableStateListOf<NamedRow>()
    val detail = mutableStateOf("")
    val models = mutableStateListOf<String>()
    val settingsItems = mutableStateListOf<Pair<String, String>>()
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
                loadPanel(panel.value)
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
                loadPanel(panel.value)
            } catch (_: Exception) {
                error.value = "Login failed"
            }
        }
    }

    fun go(p: Panel) {
        panel.value = p
        title.value = if (p == Panel.Chat) "Hermes" else p.label
        loadPanel(p)
    }

    fun loadPanel(p: Panel) {
        val c = api ?: return
        viewModelScope.launch {
            error.value = null
            try {
                when (p) {
                    Panel.Chat -> refreshSessions()
                    Panel.Tasks -> replaceRows {
                        c.namedList("/api/crons", "jobs", listOf("name", "id", "prompt"), listOf("schedule", "enabled", "status"))
                    }
                    Panel.Kanban -> replaceRows {
                        c.namedList("/api/kanban/tasks", "tasks", listOf("title", "name", "id"), listOf("status", "column", "assignee"))
                            .ifEmpty { c.namedList("/api/kanban/board", "columns", listOf("title", "name"), listOf("id")) }
                    }
                    Panel.Spaces -> replaceRows {
                        c.namedList("/api/workspaces", "workspaces", listOf("name", "path", "label"), listOf("path", "last"))
                    }
                    Panel.Skills -> replaceRows {
                        c.namedList("/api/skills", "skills", listOf("name", "title"), listOf("description", "category"))
                    }
                    Panel.Memory -> {
                        val text = withContext(Dispatchers.IO) { c.prettyJson("/api/memory") }
                        detail.value = text.take(12000)
                        rows.clear()
                    }
                    Panel.Logs -> {
                        val text = withContext(Dispatchers.IO) { c.getRaw("/api/logs").take(12000) }
                        detail.value = text
                        rows.clear()
                    }
                    Panel.Profiles -> replaceRows {
                        c.namedList("/api/profiles", "profiles", listOf("name", "id"), listOf("model", "status"))
                    }
                    Panel.Dashboard -> loadDashboard()
                    Panel.Settings -> loadSettings()
                }
            } catch (e: Exception) {
                error.value = e.message
            }
        }
    }

    private suspend fun replaceRows(block: () -> List<NamedRow>) {
        val list = withContext(Dispatchers.IO) { block() }
        rows.clear(); rows.addAll(list)
        detail.value = if (list.isEmpty()) "Nothing here yet." else ""
    }

    private suspend fun loadDashboard() {
        val c = api ?: return
        val sb = StringBuilder()
        withContext(Dispatchers.IO) {
            fun add(label: String, path: String) {
                sb.append("── ").append(label).append(" ──\n")
                sb.append(runCatching { c.prettyJson(path) }.getOrElse { it.message }).append("\n\n")
            }
            add("Health", "/health")
            add("Agent health", "/api/health/agent")
            add("System", "/api/system/health")
            add("Dashboard status", "/api/dashboard/status")
            add("Dashboard config", "/api/dashboard/config")
            val dash = prefs.dashboardUrl.ifBlank { derivedDashboardUrl() }
            if (dash.isNotBlank()) {
                sb.append("── Usage dashboard ").append(dash).append(" ──\n")
                sb.append(runCatching {
                    okhttp3.OkHttpClient().newCall(
                        okhttp3.Request.Builder().url("$dash/api/meta").build()
                    ).execute().use { it.body?.string().orEmpty().take(4000) }
                }.getOrElse { it.message }).append("\n")
            }
        }
        detail.value = sb.toString()
        rows.clear()
        val dash = prefs.dashboardUrl.ifBlank { derivedDashboardUrl() }
        if (dash.isNotBlank()) rows.add(NamedRow("Open dashboard", dash, dash))
    }

    fun derivedDashboardUrl(): String {
        val u = prefs.baseUrl.ifBlank { return "" }
        return try {
            val uri = URI(if (u.contains("://")) u else "http://$u")
            val host = uri.host ?: return ""
            "http://$host:9119"
        } catch (_: Exception) { "" }
    }

    private suspend fun loadSettings() {
        val c = api ?: return
        val map = withContext(Dispatchers.IO) {
            try {
                val obj = Json.parseToJsonElement(c.settingsRaw()) as? JsonObject ?: return@withContext emptyList()
                obj.filterKeys { it != "password_hash" }.map { (k, v) ->
                    k to ((v as? JsonPrimitive)?.contentOrNull ?: v.toString())
                }.sortedBy { it.first }
            } catch (_: Exception) { emptyList() }
        }
        settingsItems.clear(); settingsItems.addAll(map)
        rows.clear()
        detail.value = ""
        withContext(Dispatchers.IO) {
            runCatching {
                val el = c.getJson("/api/models")
                val names = mutableListOf<String>()
                fun walk(e: kotlinx.serialization.json.JsonElement) {
                    when (e) {
                        is kotlinx.serialization.json.JsonArray -> e.forEach { walk(it) }
                        is JsonObject -> {
                            val id = e["id"]?.let { (it as? JsonPrimitive)?.content }
                                ?: e["name"]?.let { (it as? JsonPrimitive)?.content }
                            if (!id.isNullOrBlank()) names.add(id)
                            e.values.forEach { walk(it) }
                        }
                        else -> {}
                    }
                }
                walk(el)
                names.distinct()
            }.getOrDefault(emptyList())
        }.let { models.clear(); models.addAll(it.take(80)) }
    }

    fun refreshSessions() {
        val c = api ?: return
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { c.sessions() }
                sessions.clear(); sessions.addAll(list)
            } catch (e: Exception) { error.value = e.message }
        }
    }

    fun open(row: SessionRow) {
        val c = api ?: return
        sid = row.sid
        title.value = row.displayTitle
        live.value = ""
        panel.value = Panel.Chat
        viewModelScope.launch {
            try {
                val msgs = withContext(Dispatchers.IO) { c.messages(row.sid) }
                bubbles.clear()
                msgs.forEach {
                    if (it.role == "user" || it.role == "assistant" || it.role == "tool") {
                        bubbles.add(Bubble(it.role, it.content, it.tool_name ?: it.name))
                    }
                }
            } catch (e: Exception) { error.value = e.message }
        }
    }

    fun newChat() {
        val c = api ?: return
        viewModelScope.launch {
            try {
                sid = withContext(Dispatchers.IO) { c.newSession() }
                bubbles.clear(); live.value = ""; title.value = "New conversation"
                panel.value = Panel.Chat
                refreshSessions()
            } catch (e: Exception) { error.value = e.message }
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
            panel.value = Panel.Chat
            try {
                val start = withContext(Dispatchers.IO) { c.startChat(sid, t) }
                val streamId = start.stream_id.orEmpty()
                if (streamId.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        es?.cancel()
                        es = c.stream(streamId, { ev, data -> onSse(ev, data) }, {
                            viewModelScope.launch(Dispatchers.Main) {
                                if (live.value.isNotEmpty()) {
                                    bubbles.add(Bubble("assistant", live.value)); live.value = ""
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

    fun stop() {
        val c = api ?: return
        es?.cancel()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { c.cancelChat(sid) }
            busy.value = false
        }
    }

    fun cronAction(id: String, action: String) {
        if (id.isBlank()) return
        val c = api ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val quoted = "\"" + id.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                runCatching { c.postRaw("/api/crons/$action", """{"id":$quoted}""") }
            }
            loadPanel(Panel.Tasks)
        }
    }

    private fun onSse(ev: String, data: String) {
        val e = ev.lowercase()
        viewModelScope.launch(Dispatchers.Main) {
            val obj = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull()
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
                        bubbles.add(Bubble("assistant", live.value)); live.value = ""
                    }
                    busy.value = false
                }
            }
        }
    }

    fun saveUrl(url: String, dashboard: String = prefs.dashboardUrl) {
        prefs.baseUrl = url
        prefs.dashboardUrl = dashboard
        reconnect()
    }
}
