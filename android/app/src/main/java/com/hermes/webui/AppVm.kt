package com.hermes.webui

import android.app.Application
import android.content.Context
import android.net.Uri
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
import java.util.ArrayDeque

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
    val models = mutableStateListOf<ModelOption>()
    val selectedModel = mutableStateOf("")
    val reasoning = mutableStateOf(ReasoningStatus())
    val jobs = mutableStateListOf<CronJob>()
    val columns = mutableStateListOf<KanbanColumn>()
    val kanbanBoards = mutableStateListOf<KanbanBoardMeta>()
    val kanbanBoard = mutableStateOf("")
    val kanbanSearch = mutableStateOf("")
    val kanbanAssignee = mutableStateOf("")
    val kanbanTenant = mutableStateOf("")
    val kanbanArchived = mutableStateOf(false)
    val kanbanMine = mutableStateOf(false)
    val kanbanAssignees = mutableStateListOf<String>()
    val kanbanStats = mutableStateOf(KanbanStats())
    val kanbanDraft = mutableStateOf("")
    val kanbanOpen = mutableStateOf<KanbanTask?>(null)
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
    val console = mutableStateOf<ConsoleUsage?>(null)
    val consoleError = mutableStateOf<String?>(null)
    val costConfig = mutableStateOf<CostConfig?>(null)
    val settingsItems = mutableStateListOf<SettingItem>()
    val settingEdits = mutableStateOf<Map<String, String>>(emptyMap())
    val approval = mutableStateOf<Approval?>(null)
    val clarify = mutableStateOf<Clarify?>(null)
    val jobOutput = mutableStateOf("")
    val fsPath = mutableStateOf(".")
    val fsRoot = mutableStateOf("")
    val fsEntries = mutableStateListOf<FsEntry>()
    val fileDoc = mutableStateOf<FileDoc?>(null)
    val fileDraft = mutableStateOf("")
    val termText = mutableStateOf("")
    val termRunning = mutableStateOf(false)
    val speakReplies = mutableStateOf(false)
    val listening = mutableStateOf(false)
    val transcribing = mutableStateOf(false)
    val voiceAvailable = mutableStateOf(false)
    val prompts = mutableStateListOf<SavedPrompt>()
    val showModelPicker = mutableStateOf(false)
    val pendingAttach = mutableStateListOf<PendingAttach>()
    val settingsSection = mutableStateOf(SettingsSection.Conversation)
    val providers = mutableStateListOf<ProviderRow>()
    val plugins = mutableStateListOf<PluginRow>()
    val extensions = mutableStateListOf<ExtensionRow>()
    var sid = ""
        private set
    private var streamId = ""
    private var afterSeq = 0L
    private var afterEventId = ""
    private var userStopped = false
    private var es: EventSource? = null
    private var sessionEs: EventSource? = null
    private var listEs: EventSource? = null
    private var termEs: EventSource? = null
    private var poll: Job? = null
    private var reconnect: Job? = null
    private var player: android.media.MediaPlayer? = null
    private var recorder: android.media.MediaRecorder? = null
    private var recFile: java.io.File? = null
    private val outbound = ArrayDeque<Pair<String, List<String>>>()

    fun reconnect() {
        val u = prefs.baseUrl
        configured.value = u.isNotBlank()
        if (u.isBlank()) return
        api = ApiClient(if (u.contains("://")) u else "http://$u", prefs)
        viewModelScope.launch { bootstrap() }
    }

    init { reconnect() }

    private suspend fun bootstrap() {
        val c = api ?: return
        error.value = null
        try {
            val st = withContext(Dispatchers.IO) { c.authStatus() }
            if (st.authEnabled && !st.loggedIn) {
                val saved = prefs.password
                if (saved.isNotBlank()) {
                    runCatching { withContext(Dispatchers.IO) { c.login(saved) } }
                        .onFailure {
                            needsLogin.value = true
                            ready.value = false
                            return
                        }
                    needsLogin.value = false
                    ready.value = true
                } else {
                    needsLogin.value = true
                    ready.value = false
                    return
                }
            } else {
                needsLogin.value = false
                ready.value = true
            }
            withContext(Dispatchers.IO) { runCatching { c.refreshCsrf() } }
            refreshSessions()
            loadModels()
            startPoll()
            attachListStream()
            if (prefs.lastSid.isNotBlank()) open(prefs.lastSid, keepPanel = true)
            voiceAvailable.value = withContext(Dispatchers.IO) { runCatching { c.transcribeAvailable() }.getOrDefault(false) }
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
                prefs.password = password
                commitAutofill()
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

    private fun commitAutofill() {
        runCatching {
            getApplication<Application>()
                .getSystemService(android.view.autofill.AutofillManager::class.java)
                ?.commit()
        }
    }

    fun go(p: Panel) {
        panel.value = p
        title.value = if (p == Panel.Chat) (sessions.firstOrNull { it.sid == sid }?.displayTitle ?: "Hermes") else p.label
        loadPanel(p)
    }

    /** True when system back should stay in-app instead of finishing the Activity. */
    fun canPopBack(): Boolean {
        if (fileDoc.value != null) return true
        if (listening.value) return true
        if (panel.value == Panel.Files) {
            val p = fsPath.value
            if (p.isNotBlank() && p != ".") return true
        }
        return panel.value != Panel.Chat
    }

    fun handleBack(): Boolean {
        if (fileDoc.value != null) {
            closeFile()
            return true
        }
        if (listening.value) {
            cancelListen()
            return true
        }
        if (panel.value == Panel.Files) {
            val p = fsPath.value
            if (p.isNotBlank() && p != ".") {
                fsUp()
                return true
            }
        }
        if (panel.value != Panel.Chat) {
            go(Panel.Chat)
            return true
        }
        return false
    }

    fun cancelListen() {
        listening.value = false
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release()
        recorder = null
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
                    Panel.Kanban -> loadKanban()
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
                    Panel.Files -> loadFiles(fsPath.value)
                    Panel.Terminal -> { }
                    Panel.Insights -> loadConsole(includeConfig = true)
                    Panel.Logs -> {
                        val lines = withContext(Dispatchers.IO) { c.logs(logFile.value) }
                        logLines.clear(); logLines.addAll(lines)
                    }
                    Panel.Settings -> {
                        val items = withContext(Dispatchers.IO) { c.settings() }
                        settingsItems.clear(); settingsItems.addAll(items)
                        settingEdits.value = emptyMap()
                        loadModels()
                        val prov = withContext(Dispatchers.IO) { runCatching { c.providers() }.getOrDefault(emptyList()) }
                        providers.clear(); providers.addAll(prov)
                        val plug = withContext(Dispatchers.IO) { runCatching { c.plugins() }.getOrDefault(emptyList()) }
                        plugins.clear(); plugins.addAll(plug)
                        val ext = withContext(Dispatchers.IO) { runCatching { c.extensions() }.getOrDefault(emptyList()) }
                        extensions.clear(); extensions.addAll(ext)
                    }
                }
            } catch (e: AuthException) {
                val saved = prefs.password
                if (saved.isNotBlank()) {
                    val ok = withContext(Dispatchers.IO) { runCatching { c.login(saved); true }.getOrDefault(false) }
                    if (ok) {
                        loadPanel(p)
                        return@launch
                    }
                }
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
            val (default, list) = withContext(Dispatchers.IO) {
                runCatching { c.models() }.getOrDefault("" to emptyList<ModelOption>())
            }
            models.clear(); models.addAll(list)
            if (selectedModel.value.isBlank() && default.isNotBlank()) selectedModel.value = default
            loadReasoning()
        }
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { c.prompts() }.getOrDefault(emptyList()) }
            prompts.clear(); prompts.addAll(list)
        }
    }

    fun pickModel(id: String) {
        selectedModel.value = id
        loadReasoning()
    }

    fun setReasoning(effort: String) {
        val c = api ?: return
        viewModelScope.launch {
            val model = selectedModel.value
            val provider = models.firstOrNull { it.id == model }?.provider.orEmpty()
            reasoning.value = withContext(Dispatchers.IO) {
                runCatching { c.setReasoning(effort, model, provider) }.getOrDefault(ReasoningStatus(effort = effort))
            }
        }
    }

    fun loadReasoning() {
        val c = api ?: return
        val model = selectedModel.value
        val provider = models.firstOrNull { it.id == model }?.provider.orEmpty()
        viewModelScope.launch {
            reasoning.value = withContext(Dispatchers.IO) {
                runCatching { c.reasoning(model, provider) }.getOrDefault(ReasoningStatus())
            }
        }
    }

    fun open(row: SessionRow) = open(row.sid)

    fun open(id: String, keepPanel: Boolean = false) {
        val c = api ?: return
        sid = id
        prefs.lastSid = id
        live.value = ""
        if (!keepPanel) panel.value = Panel.Chat
        attachSessionStream()
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
        if (load.model.isNotBlank()) loadReasoning()
        truncated.value = load.truncated
        val steers = bubbles.filter { it.role == "steer" }
        bubbles.clear()
        load.messages.forEach { m ->
            if (m.role in setOf("user", "assistant", "tool", "thinking", "system")) bubbles.add(m)
        }
        bubbles.addAll(steers)
        todos.clear(); todos.addAll(load.todos)
        val liveId = load.activeStreamId
        if (liveId.isNotBlank()) {
            if (streamId != liveId || es == null) attachStream(liveId, replay = streamId == liveId && afterSeq > 0)
        } else if (!userStopped && busy.value) {
            settleTurn()
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
                prefs.lastSid = sid
                bubbles.clear(); live.value = ""; todos.clear()
                title.value = "New conversation"
                truncated.value = false
                panel.value = Panel.Chat
                attachSessionStream()
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
        val t = text.trim()
        val files = pendingAttach.map { it.path }
        if (t.isEmpty() && files.isEmpty()) return
        pendingAttach.clear()
        val shown = t.ifBlank { files.joinToString { it.substringAfterLast('/') } }
        if (busy.value) {
            bubbles.add(ChatMsg("s-${System.currentTimeMillis()}", "steer", shown))
            viewModelScope.launch {
                val c = api ?: return@launch
                if (sid.isBlank()) {
                    outbound.addLast(t.ifBlank { "(attachments)" } to files)
                    return@launch
                }
                val steerText = t.ifBlank { "See attached files." }
                if (t.isNotEmpty()) {
                    val ok = withContext(Dispatchers.IO) { runCatching { c.steer(sid, steerText) }.getOrDefault(false) }
                    when {
                        !ok && files.isEmpty() -> outbound.addLast(steerText to emptyList())
                        !ok && files.isNotEmpty() -> outbound.addLast(steerText to files)
                        ok && files.isNotEmpty() -> outbound.addLast("See attached files." to files)
                    }
                } else if (files.isNotEmpty()) {
                    outbound.addLast(steerText to files)
                }
            }
            return
        }
        bubbles.add(ChatMsg("u-${System.currentTimeMillis()}", "user", shown))
        startTurn(t.ifBlank { "See attached files." }, files)
    }

    private fun startTurn(text: String, attachments: List<String>) {
        val c = api ?: return
        viewModelScope.launch {
            if (sid.isBlank()) {
                try { sid = withContext(Dispatchers.IO) { c.newSession() } }
                catch (e: Exception) { error.value = e.message; return@launch }
            }
            busy.value = true
            live.value = ""
            userStopped = false
            afterSeq = 0
            afterEventId = ""
            panel.value = Panel.Chat
            keepAlive(true)
            try {
                val newStream = withContext(Dispatchers.IO) {
                    c.startChat(
                        sid,
                        text,
                        selectedModel.value.ifBlank { null },
                        attachments,
                        models.firstOrNull { it.id == selectedModel.value }?.provider,
                    )
                }
                if (newStream.isNotEmpty()) attachStream(newStream, replay = false) else recoverLive()
            } catch (e: Exception) {
                error.value = e.message
                busy.value = false
                keepAlive(false)
            }
        }
    }

    fun dropAttach(item: PendingAttach) {
        pendingAttach.removeAll { it.path == item.path }
    }

    fun attachUris(ctx: Context, uris: List<Uri>) {
        val c = api ?: return
        viewModelScope.launch {
            if (sid.isBlank()) {
                try { sid = withContext(Dispatchers.IO) { c.newSession() } }
                catch (e: Exception) { error.value = e.message; return@launch }
            }
            for (uri in uris) {
                try {
                    val uploaded = withContext(Dispatchers.IO) {
                        val name = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "file" } ?: "file"
                        val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
                        val dest = java.io.File(ctx.cacheDir, "up-${System.currentTimeMillis()}-$name")
                        ctx.contentResolver.openInputStream(uri)?.use { input ->
                            dest.outputStream().use { input.copyTo(it) }
                        } ?: throw RuntimeException("Could not read $name")
                        c.upload(sid, dest, mime)
                    }
                    pendingAttach.add(uploaded)
                } catch (e: Exception) {
                    error.value = e.message
                }
            }
        }
    }

    private fun attachStream(id: String, replay: Boolean) {
        val c = api ?: return
        if (id.isBlank()) return
        streamId = id
        prefs.lastStreamId = id
        busy.value = true
        keepAlive(true)
        es?.cancel()
        es = c.stream(
            streamId = id,
            replay = replay,
            afterSeq = afterSeq,
            afterEventId = afterEventId,
            onEvent = { ev, data, eid -> onSse(ev, data, eid) },
            onClosed = { onChatStreamClosed() },
        )
    }

    private fun onChatStreamClosed() {
        if (userStopped) {
            viewModelScope.launch(Dispatchers.Main) { settleTurn() }
            return
        }
        reconnect?.cancel()
        reconnect = viewModelScope.launch {
            delay(800)
            val c = api ?: return@launch
            val id = streamId.ifBlank { prefs.lastStreamId }
            if (id.isBlank() || sid.isBlank()) {
                recoverLive()
                return@launch
            }
            val still = withContext(Dispatchers.IO) { runCatching { c.streamStatus(id) }.getOrDefault(false) }
            if (still) {
                attachStream(id, replay = true)
            } else {
                recoverLive()
            }
        }
    }

    fun stop() {
        val c = api ?: return
        userStopped = true
        reconnect?.cancel()
        es?.cancel()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { c.cancelChat(sid) }
            settleTurn()
        }
    }

    private fun settleTurn() {
        flushLive()
        for (i in bubbles.indices) {
            if (bubbles[i].running) bubbles[i] = bubbles[i].copy(running = false)
        }
        busy.value = false
        streamId = ""
        prefs.lastStreamId = ""
        keepAlive(false)
        refreshSessions()
        val next = outbound.pollFirst()
        if (next != null) startTurn(next.first, next.second)
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

    fun loadKanban() {
        val c = api ?: return
        viewModelScope.launch {
            try {
                val (cur, boards) = withContext(Dispatchers.IO) { runCatching { c.kanbanBoards() }.getOrDefault("" to emptyList()) }
                kanbanBoards.clear(); kanbanBoards.addAll(boards)
                if (kanbanBoard.value.isBlank() || boards.none { it.slug == kanbanBoard.value }) {
                    kanbanBoard.value = cur.ifBlank { boards.firstOrNull()?.slug.orEmpty() }
                }
                val board = kanbanBoard.value
                val list = withContext(Dispatchers.IO) {
                    c.kanban(board, kanbanAssignee.value, kanbanTenant.value, kanbanArchived.value, kanbanMine.value)
                }
                columns.clear(); columns.addAll(list)
                kanbanStats.value = withContext(Dispatchers.IO) { runCatching { c.kanbanStats(board) }.getOrDefault(KanbanStats()) }
                val people = withContext(Dispatchers.IO) { runCatching { c.kanbanAssignees(board) }.getOrDefault(emptyList()) }
                kanbanAssignees.clear(); kanbanAssignees.addAll(people)
            } catch (e: Exception) {
                error.value = e.message
            }
        }
    }

    fun switchKanbanBoard(slug: String) {
        val c = api ?: return
        kanbanBoard.value = slug
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { c.switchKanbanBoard(slug) } }
            loadKanban()
        }
    }

    fun createKanbanTask() {
        val title = kanbanDraft.value.trim()
        if (title.isEmpty()) return
        val c = api ?: return
        kanbanDraft.value = ""
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { c.createKanbanTask(title, kanbanBoard.value) } }
            loadKanban()
        }
    }

    fun dispatchKanban(dry: Boolean) {
        val c = api ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { c.dispatchKanban(kanbanBoard.value, dry) } }
            loadKanban()
        }
    }

    fun moveTask(id: String, status: String) {
        val c = api ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { c.moveKanban(id, status, kanbanBoard.value) } }
            loadKanban()
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

    fun setProviderKey(id: String, key: String) {
        val c = api ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { c.setProviderKey(id, key) } }
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

    fun saveUrl(url: String, dashboard: String = prefs.dashboardUrl, password: String? = null) {
        prefs.baseUrl = url
        prefs.dashboardUrl = dashboard
        if (!password.isNullOrBlank()) prefs.password = password
        reconnect()
    }

    fun loadConsole(includeConfig: Boolean = false) {
        val c = api ?: return
        viewModelScope.launch {
            try {
                val base = ConsoleFmt.consoleBase(prefs.baseUrl, prefs.dashboardUrl)
                console.value = withContext(Dispatchers.IO) { c.consoleUsage(base) }
                if (includeConfig || costConfig.value == null) {
                    costConfig.value = withContext(Dispatchers.IO) { runCatching { c.costConfig(base) }.getOrNull() }
                }
                consoleError.value = null
            } catch (e: Exception) {
                consoleError.value = e.message ?: "Could not reach Hermes Console (:8790). Set the Console URL in Connection."
            }
        }
    }

    fun saveCostPlans(plans: List<CostPlan>) {
        val c = api ?: return
        viewModelScope.launch {
            try {
                val base = ConsoleFmt.consoleBase(prefs.baseUrl, prefs.dashboardUrl)
                val body = kotlinx.serialization.json.Json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(CostPlan.serializer()),
                    plans,
                )
                withContext(Dispatchers.IO) { c.saveCostConfig(base, """{"subscriptions":$body}""") }
                loadConsole(includeConfig = true)
            } catch (e: Exception) {
                consoleError.value = e.message
            }
        }
    }

    fun saveCostRates(rates: List<CostModelRate>) {
        val c = api ?: return
        viewModelScope.launch {
            try {
                val base = ConsoleFmt.consoleBase(prefs.baseUrl, prefs.dashboardUrl)
                val body = kotlinx.serialization.json.Json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(CostModelRate.serializer()),
                    rates,
                )
                withContext(Dispatchers.IO) { c.saveCostConfig(base, """{"models":$body}""") }
                loadConsole(includeConfig = true)
            } catch (e: Exception) {
                consoleError.value = e.message
            }
        }
    }

    private fun startPoll() {
        poll?.cancel()
        poll = viewModelScope.launch {
            while (isActive) {
                delay(4000)
                val c = api ?: continue
                if (panel.value == Panel.Insights) loadConsole()
                if (!ready.value || sid.isBlank()) continue
                runCatching {
                    val a = withContext(Dispatchers.IO) { c.approval(sid) }
                    val q = withContext(Dispatchers.IO) { c.clarify(sid) }
                    approval.value = a
                    clarify.value = q
                    val (liveId, count) = withContext(Dispatchers.IO) {
                        runCatching { c.sessionStatus(sid) }.getOrDefault("" to 0)
                    }
                    if (liveId.isNotBlank() && (streamId != liveId || es == null) && !userStopped) {
                        attachStream(liveId, replay = streamId == liveId)
                    }
                    if (count > 0 && count > bubbles.size && !busy.value) {
                        val load = withContext(Dispatchers.IO) { c.loadSession(sid) }
                        applyLoad(load)
                    }
                }
            }
        }
    }

    fun onForeground() {
        if (!ready.value) return
        recoverLive()
        attachSessionStream()
        attachListStream()
    }

    fun recoverLive() {
        val c = api ?: return
        val id = sid.ifBlank { prefs.lastSid }
        if (id.isBlank()) return
        viewModelScope.launch {
            try {
                val load = withContext(Dispatchers.IO) { c.loadSession(id) }
                if (sid.isBlank()) {
                    sid = id
                    attachSessionStream()
                }
                applyLoad(load)
            } catch (e: Exception) {
                error.value = e.message
            }
        }
    }

    private var sessionStreamBackoff = 1_500L
    private var listStreamBackoff = 2_000L

    private fun attachSessionStream() {
        val c = api ?: return
        if (sid.isBlank()) return
        sessionEs?.cancel()
        sessionEs = c.streamSession(sid, bubbles.size, { ev, data ->
            sessionStreamBackoff = 1_500L
            val e = ev.lowercase()
            val obj = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull()
            when {
                e == "server_turn_started" -> {
                    val id = obj?.prim("stream_id", "streamId") ?: ""
                    if (id.isNotBlank() && !userStopped) {
                        viewModelScope.launch(Dispatchers.Main) { attachStream(id, replay = streamId == id) }
                    }
                }
                e == "session_updated" || e.contains("complete") -> {
                    viewModelScope.launch { recoverLive() }
                }
            }
        }, {
            viewModelScope.launch {
                delay(sessionStreamBackoff)
                sessionStreamBackoff = (sessionStreamBackoff * 2).coerceAtMost(30_000L)
                if (ready.value && sid.isNotBlank()) attachSessionStream()
            }
        })
    }

    private fun attachListStream() {
        val c = api ?: return
        listEs?.cancel()
        listEs = c.streamSessionList({ ev, _ ->
            listStreamBackoff = 2_000L
            if (ev.contains("session") || ev.isEmpty() || ev == "sessions_changed") {
                refreshSessions()
            }
        }, {
            viewModelScope.launch {
                delay(listStreamBackoff)
                listStreamBackoff = (listStreamBackoff * 2).coerceAtMost(30_000L)
                if (ready.value) attachListStream()
            }
        })
    }

    private fun keepAlive(on: Boolean) {
        val ctx = getApplication<Application>()
        runCatching {
            if (on) HermesLiveService.start(ctx) else HermesLiveService.stop(ctx)
        }
    }

    private fun flushLive() {
        if (live.value.isNotEmpty()) {
            val text = live.value
            bubbles.add(ChatMsg("a-${System.currentTimeMillis()}", "assistant", text))
            live.value = ""
            if (speakReplies.value) speak(text)
        }
    }

    suspend fun ensureSid(): Boolean {
        if (sid.isNotBlank()) return true
        val c = api ?: return false
        return try {
            sid = withContext(Dispatchers.IO) { c.newSession() }
            sid.isNotBlank()
        } catch (e: Exception) {
            error.value = e.message
            false
        }
    }

    fun loadFiles(path: String) {
        val c = api ?: return
        viewModelScope.launch {
            if (!ensureSid()) {
                error.value = "Open or create a chat first — Files uses that session workspace."
                return@launch
            }
            try {
                val (root, list) = withContext(Dispatchers.IO) { c.listDir(sid, path) }
                fsRoot.value = root
                fsPath.value = path
                fsEntries.clear(); fsEntries.addAll(list)
                fileDoc.value = null
            } catch (e: Exception) { error.value = e.message }
        }
    }

    fun openFs(entry: FsEntry) {
        if (entry.isDir) loadFiles(entry.path) else openFile(entry.path)
    }

    fun fsUp() {
        val p = fsPath.value
        if (p.isBlank() || p == ".") return
        val parent = p.trimEnd('/').substringBeforeLast('/', ".")
        loadFiles(parent.ifBlank { "." })
    }

    fun openFile(path: String) {
        val c = api ?: return
        viewModelScope.launch {
            try {
                val doc = withContext(Dispatchers.IO) { c.readFile(sid, path) }
                fileDoc.value = doc
                fileDraft.value = doc.content
            } catch (e: Exception) { error.value = e.message }
        }
    }

    fun saveFile() {
        val c = api ?: return
        val path = fileDoc.value?.path ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { c.saveFile(sid, path, fileDraft.value) }
                error.value = null
            } catch (e: Exception) { error.value = e.message }
        }
    }

    fun closeFile() { fileDoc.value = null }

    fun startTerm() {
        val c = api ?: return
        viewModelScope.launch {
            if (!ensureSid()) {
                error.value = "Open or create a chat first — Terminal is bound to that session."
                return@launch
            }
            try {
                withContext(Dispatchers.IO) { c.startTerminal(sid) }
                termRunning.value = true
                termEs?.cancel()
                termEs = c.streamTerminal(sid, { ev, data -> onTerm(ev, data) }, {
                    viewModelScope.launch(Dispatchers.Main) { termRunning.value = false }
                })
            } catch (e: Exception) { error.value = e.message }
        }
    }

    fun termType(line: String) {
        val c = api ?: return
        val payload = if (line.endsWith("\n")) line else "$line\n"
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { c.terminalInput(sid, payload) } }
        }
    }

    fun stopTerm() {
        val c = api ?: return
        termEs?.cancel()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { c.closeTerminal(sid) }
            termRunning.value = false
        }
    }

    private fun onTerm(ev: String, data: String) {
        viewModelScope.launch(Dispatchers.Main) {
            val obj = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull()
            val piece = obj?.prim("text") ?: ""
            when (ev.lowercase()) {
                "output" -> {
                    if (piece.isNotEmpty()) {
                        val next = stripAnsi(termText.value + piece)
                        termText.value = if (next.length > 24000) next.takeLast(20000) else next
                    }
                }
                "terminal_closed", "terminal_error" -> {
                    if (ev.lowercase() == "terminal_error") error.value = obj?.prim("error") ?: "terminal error"
                    termRunning.value = false
                }
            }
        }
    }

    fun speak(text: String) {
        val c = api ?: return
        val clean = text.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { c.tts(clean) }
                if (bytes.isEmpty()) return@launch
                val f = java.io.File(getApplication<Application>().cacheDir, "hermes-tts.mp3")
                withContext(Dispatchers.IO) { f.writeBytes(bytes) }
                player?.release()
                player = android.media.MediaPlayer().apply {
                    setDataSource(f.absolutePath)
                    prepare()
                    start()
                }
            } catch (e: Exception) { error.value = e.message }
        }
    }

    fun stopSpeak() {
        runCatching { player?.stop() }
        player?.release()
        player = null
    }

    fun startListen() {
        if (listening.value) return
        val ctx = getApplication<Application>()
        val f = java.io.File(ctx.cacheDir, "hermes-dictation.m4a")
        recFile = f
        try {
            recorder = android.media.MediaRecorder().apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setOutputFile(f.absolutePath)
                prepare()
                start()
            }
            listening.value = true
        } catch (e: Exception) {
            error.value = e.message ?: "Could not start microphone"
            recorder?.release()
            recorder = null
        }
    }

    fun stopListen(onText: (String) -> Unit) {
        val c = api ?: return
        val f = recFile
        listening.value = false
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release()
        recorder = null
        if (f == null || !f.exists() || f.length() < 64) return
        viewModelScope.launch {
            transcribing.value = true
            try {
                val text = withContext(Dispatchers.IO) { c.transcribe(f) }
                if (text.isNotBlank()) onText(text)
            } catch (e: Exception) { error.value = e.message }
            transcribing.value = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        es?.cancel()
        sessionEs?.cancel()
        listEs?.cancel()
        termEs?.cancel()
        reconnect?.cancel()
        stopSpeak()
        runCatching { recorder?.release() }
        // Do not cancel the agent run — leaving the Activity must not fail the turn.
    }

    private fun onSse(ev: String, data: String, eventId: String) {
        val e = ev.lowercase()
        if (eventId.isNotBlank()) {
            afterEventId = eventId
            eventId.toLongOrNull()?.let { afterSeq = maxOf(afterSeq, it) }
        } else {
            afterSeq += 1
        }
        viewModelScope.launch(Dispatchers.Main) {
            val obj = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull()
            when {
                e == "token" || e == "delta" -> {
                    val piece = obj?.prim("text") ?: obj?.prim("delta") ?: ""
                    live.value += piece
                    busy.value = true
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
                e.contains("think") || e == "reasoning" -> {
                    val piece = obj?.prim("text", "delta", "content", "thinking") ?: ""
                    val idx = bubbles.indexOfLast { it.role == "thinking" && it.running }
                    if (idx >= 0) {
                        val cur = bubbles[idx]
                        bubbles[idx] = cur.copy(content = cur.content + piece)
                    } else if (piece.isNotBlank()) {
                        bubbles.add(ChatMsg("th-${System.currentTimeMillis()}", "thinking", piece, running = true))
                    }
                }
                e.contains("tool") -> {
                    val name = obj?.prim("name", "tool", "function") ?: "tool"
                    val preview = obj?.prim("preview", "snippet", "result", "output", "text") ?: ""
                    val done = e.contains("done") || e.contains("result") || e.contains("end") || obj?.prim("done") == "true"
                    val idx = bubbles.indexOfLast { it.role == "tool" && it.tool == name && it.running }
                    if (idx >= 0) {
                        val cur = bubbles[idx]
                        bubbles[idx] = cur.copy(
                            preview = preview.ifBlank { cur.preview },
                            content = if (preview.isNotBlank()) preview else cur.content,
                            running = !done,
                        )
                    } else {
                        bubbles.add(ChatMsg("t-${System.currentTimeMillis()}", "tool", preview, name, preview, running = !done))
                    }
                }
                e == "done" || e.contains("complete") -> {
                    settleTurn()
                }
                e == "error" -> {
                    error.value = obj?.prim("error", "message") ?: "stream error"
                    settleTurn()
                }
            }
        }
    }

}

private val ANSI = Regex("\\u001B\\[[0-9;?]*[A-Za-z]|\\u001B\\].*?(\\u0007|\\u001B\\\\)")

private fun stripAnsi(s: String): String = ANSI.replace(s, "")

private fun JsonObject.prim(vararg keys: String): String {
    for (k in keys) {
        val v = this[k] as? JsonPrimitive ?: continue
        val s = v.contentOrNull
        if (!s.isNullOrBlank()) return s
    }
    return ""
}
