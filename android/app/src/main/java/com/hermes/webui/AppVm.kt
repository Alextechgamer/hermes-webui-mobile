package com.hermes.webui

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    val panel = mutableStateOf(Panel.Chat)
    val sessions = mutableStateListOf<SessionRow>()
    val sessionQuery = mutableStateOf("")
    val bubbles = mutableStateListOf<ChatMsg>()
    val live = mutableStateOf("")
    val busy = mutableStateOf(false)
    val title = mutableStateOf("Hermes")
    val truncated = mutableStateOf(false)
    val models = mutableStateListOf<String>()
    val selectedModel = mutableStateOf("")
    val jobs = mutableStateListOf<CronJob>()
    val columns = mutableStateListOf<KanbanColumn>()
    val skills = mutableStateListOf<SkillRow>()
    val skillBody = mutableStateOf("")
    val memory = mutableStateOf(MemoryDoc())
    val spaces = mutableStateListOf<SpaceRow>()
    val profiles = mutableStateListOf<ProfileRow>()
    val activeProfile = mutableStateOf("")
    val todos = mutableStateListOf<TodoItem>()
    val insights = mutableStateOf(Insights())
    val logLines = mutableStateListOf<String>()
    val logFile = mutableStateOf("agent")
    val dash = mutableStateListOf<DashCard>()
    val settingsItems = mutableStateListOf<SettingItem>()
    val settingEdits = mutableStateOf<Map<String, String>>(emptyMap())
    val approval = mutableStateOf<Approval?>(null)
    val clarify = mutableStateOf<Clarify?>(null)
    val jobOutput = mutableStateOf("")
    var sid = ""
        private set
    private var es: EventSource? = null
    private var poll: Job? = null

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
            if (st.authEnabled && !st.loggedIn) {
                needsLogin.value = true
                ready.value = false
            } else {
                needsLogin.value = false
                ready.value = true
                withContext(Dispatchers.IO) { runCatching { c.refreshCsrf() } }
                refreshSessions()
                loadModels()
                startPoll()
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
                loadModels()
                startPoll()
            } catch (_: Exception) {
                error.value = "Login failed"
            }
        }
    }

    fun go(p: Panel) {
        panel.value = p
        title.value = if (p == Panel.Chat) (sessions.firstOrNull { it.sid == sid }?.displayTitle ?: "Hermes") else p.label
        loadPanel(p)
    }

    fun loadPanel(p: Panel) {
        val c = api ?: return
        viewModelScope.launch {
            error.value = null
            try {
                when (p) {
                    Panel.Chat -> refreshSessions()
                    Panel.Tasks -> {
                        val list = withContext(Dispatchers.IO) { c.crons() }
                        jobs.clear(); jobs.addAll(list)
                    }
                    Panel.Kanban -> {
                        val list = withContext(Dispatchers.IO) { c.kanban() }
                        columns.clear(); columns.addAll(list)
                    }
                    Panel.Skills -> {
                        val list = withContext(Dispatchers.IO) { c.skills() }
                        skills.clear(); skills.addAll(list)
                    }
                    Panel.Memory -> memory.value = withContext(Dispatchers.IO) { c.memory() }
                    Panel.Spaces -> {
                        val list = withContext(Dispatchers.IO) { c.spaces() }
                        spaces.clear(); spaces.addAll(list)
                    }
                    Panel.Profiles -> {
                        val (active, list) = withContext(Dispatchers.IO) { c.profiles() }
                        activeProfile.value = active
                        profiles.clear(); profiles.addAll(list)
                    }
                    Panel.Todos -> if (sid.isNotBlank()) open(sid, keepPanel = true)
                    Panel.Insights -> insights.value = withContext(Dispatchers.IO) { c.insights() }
                    Panel.Logs -> {
                        val lines = withContext(Dispatchers.IO) { c.logs(logFile.value) }
                        logLines.clear(); logLines.addAll(lines)
                    }
                    Panel.Dashboard -> {
                        val cards = withContext(Dispatchers.IO) { c.dashboard() }
                        dash.clear(); dash.addAll(cards)
                    }
                    Panel.Settings -> {
                        val items = withContext(Dispatchers.IO) { c.settings() }
                        settingsItems.clear(); settingsItems.addAll(items)
                        settingEdits.value = emptyMap()
                        loadModels()
                    }
                }
            } catch (e: AuthException) {
                needsLogin.value = true
                ready.value = false
            } catch (e: Exception) {
                error.value = e.message
            }
        }
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

    private fun loadModels() {
        val c = api ?: return
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { c.models() }.getOrDefault(emptyList()) }
            models.clear(); models.addAll(list.take(80))
        }
    }

    fun open(row: SessionRow) = open(row.sid)

    fun open(id: String, keepPanel: Boolean = false) {
        val c = api ?: return
        sid = id
        live.value = ""
        if (!keepPanel) panel.value = Panel.Chat
        viewModelScope.launch {
            try {
                val load = withContext(Dispatchers.IO) { c.loadSession(id) }
                applyLoad(load)
            } catch (e: Exception) { error.value = e.message }
        }
    }

    private fun applyLoad(load: SessionLoad) {
        if (load.title.isNotBlank()) title.value = load.title
        if (load.model.isNotBlank() && selectedModel.value.isBlank()) selectedModel.value = load.model
        truncated.value = load.truncated
        bubbles.clear()
        load.messages.forEach { m ->
            if (m.role in setOf("user", "assistant", "tool", "system")) bubbles.add(m)
        }
        todos.clear(); todos.addAll(load.todos)
        if (load.activeStreamId.isNotBlank() && !busy.value) {
            attachStream(load.activeStreamId)
        }
    }

    fun loadFullHistory() {
        val c = api ?: return
        if (sid.isBlank()) return
        viewModelScope.launch {
            try {
                val load = withContext(Dispatchers.IO) { c.loadSession(sid, full = true) }
                applyLoad(load)
            } catch (e: Exception) { error.value = e.message }
        }
    }

    fun newChat() {
        val c = api ?: return
        viewModelScope.launch {
            try {
                sid = withContext(Dispatchers.IO) { c.newSession() }
                bubbles.clear(); live.value = ""; todos.clear()
                title.value = "New conversation"
                truncated.value = false
                panel.value = Panel.Chat
                refreshSessions()
            } catch (e: Exception) { error.value = e.message }
        }
    }

    fun deleteSession(id: String) {
        val c = api ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { c.deleteSession(id) } }
            if (sid == id) {
                sid = ""; bubbles.clear(); live.value = ""; title.value = "Hermes"
            }
            refreshSessions()
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
            bubbles.add(ChatMsg("u-${System.currentTimeMillis()}", "user", t))
            busy.value = true
            live.value = ""
            panel.value = Panel.Chat
            try {
                val streamId = withContext(Dispatchers.IO) {
                    c.startChat(sid, t, selectedModel.value.ifBlank { null })
                }
                if (streamId.isNotEmpty()) attachStream(streamId) else busy.value = false
            } catch (e: Exception) {
                error.value = e.message
                busy.value = false
            }
        }
    }

    private fun attachStream(streamId: String) {
        val c = api ?: return
        busy.value = true
        es?.cancel()
        es = c.stream(streamId, { ev, data -> onSse(ev, data) }, {
            viewModelScope.launch(Dispatchers.Main) {
                flushLive()
                busy.value = false
                refreshSessions()
            }
        })
    }

    fun stop() {
        val c = api ?: return
        es?.cancel()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { c.cancelChat(sid) }
            flushLive()
            busy.value = false
        }
    }

    fun cronAction(id: String, action: String) {
        val c = api ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { c.cronAction(id, action) } }
            loadPanel(Panel.Tasks)
        }
    }

    fun loadJobOutput(id: String) {
        val c = api ?: return
        viewModelScope.launch {
            jobOutput.value = withContext(Dispatchers.IO) { c.cronOutput(id) }
        }
    }

    fun moveTask(id: String, status: String) {
        val c = api ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { c.moveKanban(id, status) } }
            loadPanel(Panel.Kanban)
        }
    }

    fun toggleSkill(row: SkillRow) {
        val c = api ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { c.toggleSkill(row.name, row.disabled) } }
            loadPanel(Panel.Skills)
        }
    }

    fun openSkill(name: String) {
        val c = api ?: return
        viewModelScope.launch {
            skillBody.value = withContext(Dispatchers.IO) { runCatching { c.skillContent(name) }.getOrDefault("") }
        }
    }

    fun saveMemory(section: String, content: String) {
        val c = api ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { c.writeMemory(section, content) } }
            loadPanel(Panel.Memory)
        }
    }

    fun switchProfile(name: String) {
        val c = api ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { c.switchProfile(name) } }
            loadPanel(Panel.Profiles)
            refreshSessions()
            loadModels()
        }
    }

    fun setLogFile(file: String) {
        logFile.value = file
        loadPanel(Panel.Logs)
    }

    fun editSetting(key: String, value: String) {
        settingEdits.value = settingEdits.value + (key to value)
    }

    fun saveSettings() {
        val c = api ?: return
        val edits = settingEdits.value
        if (edits.isEmpty()) return
        viewModelScope.launch {
            val typed = linkedMapOf<String, Any>()
            for ((k, v) in edits) {
                val item = settingsItems.find { it.key == k }
                typed[k] = when {
                    item?.type == "bool" || v == "true" || v == "false" -> v == "true"
                    item?.type == "number" || v.toIntOrNull() != null -> v.toInt()
                    else -> v
                }
            }
            withContext(Dispatchers.IO) { runCatching { c.saveSettings(typed) } }
            loadPanel(Panel.Settings)
        }
    }

    fun approve(choice: String) {
        val c = api ?: return
        val a = approval.value ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { c.respondApproval(sid, a.approvalId, choice) } }
            approval.value = null
        }
    }

    fun answerClarify(text: String) {
        val c = api ?: return
        val q = clarify.value ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { c.respondClarify(sid, q.clarifyId, text) } }
            clarify.value = null
        }
    }

    fun saveUrl(url: String, dashboard: String = prefs.dashboardUrl) {
        prefs.baseUrl = url
        prefs.dashboardUrl = dashboard
        reconnect()
    }

    private fun startPoll() {
        poll?.cancel()
        poll = viewModelScope.launch {
            while (isActive) {
                delay(2500)
                val c = api ?: continue
                if (!ready.value || sid.isBlank()) continue
                runCatching {
                    val a = withContext(Dispatchers.IO) { c.approval(sid) }
                    val q = withContext(Dispatchers.IO) { c.clarify(sid) }
                    approval.value = a
                    clarify.value = q
                }
            }
        }
    }

    private fun flushLive() {
        if (live.value.isNotEmpty()) {
            bubbles.add(ChatMsg("a-${System.currentTimeMillis()}", "assistant", live.value))
            live.value = ""
        }
    }

    private fun onSse(ev: String, data: String) {
        val e = ev.lowercase()
        viewModelScope.launch(Dispatchers.Main) {
            val obj = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull()
            when {
                e == "token" || e == "delta" -> {
                    val piece = obj?.prim("text") ?: obj?.prim("delta") ?: ""
                    live.value += piece
                }
                e == "todo_state" -> {
                    val arr = obj?.get("todos")
                    if (arr is kotlinx.serialization.json.JsonArray) {
                        todos.clear()
                        arr.forEachIndexed { i, el ->
                            val o = el as? JsonObject
                            if (o != null) {
                                todos.add(
                                    TodoItem(
                                        o.prim("id").ifBlank { "$i" },
                                        o.prim("content", "text", "title"),
                                        o.prim("status").ifBlank { "pending" },
                                    ),
                                )
                            }
                        }
                    }
                }
                e.contains("tool") -> {
                    val name = obj?.prim("name", "tool", "function") ?: "tool"
                    bubbles.add(ChatMsg("t-${System.currentTimeMillis()}", "assistant", "", name))
                }
                e == "done" || e.contains("complete") || e == "error" -> {
                    if (e == "error") error.value = obj?.prim("error", "message") ?: "stream error"
                    flushLive()
                    busy.value = false
                }
            }
        }
    }
}

private fun JsonObject.prim(vararg keys: String): String {
    for (k in keys) {
        val v = this[k] as? JsonPrimitive ?: continue
        val s = v.contentOrNull
        if (!s.isNullOrBlank()) return s
    }
    return ""
}
