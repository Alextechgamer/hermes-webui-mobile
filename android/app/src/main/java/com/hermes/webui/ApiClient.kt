package com.hermes.webui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

class AuthException : RuntimeException("auth required")

class ApiClient(base: String) {
    private val root = base.trim().trimEnd('/')
    private var csrf = ""
    private val jar = object : CookieJar {
        private val store = mutableListOf<Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            store.removeAll { c -> cookies.any { it.name == c.name } }
            store.addAll(cookies)
        }
        override fun loadForRequest(url: HttpUrl) = store.toList()
    }
    private val http = OkHttpClient.Builder()
        .cookieJar(jar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val media = "application/json; charset=utf-8".toMediaType()

    val hostRoot: String get() = root

    private fun req(method: String, path: String, body: String? = null): Request {
        val b = Request.Builder().url("$root$path")
        if (csrf.isNotEmpty()) b.header("X-Hermes-CSRF-Token", csrf)
        return if (body != null) b.method(method, body.toRequestBody(media)).build()
        else b.method(method, null).build()
    }

    private fun exec(r: Request): String {
        http.newCall(r).execute().use { resp ->
            val t = resp.body?.string().orEmpty()
            if (resp.code == 401) throw AuthException()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${t.take(240)}")
            return t
        }
    }

    fun getRaw(path: String): String = exec(req("GET", path))
    fun postRaw(path: String, body: String = "{}"): String = exec(req("POST", path, body))
    fun patchRaw(path: String, body: String): String = exec(req("PATCH", path, body))
    fun parse(path: String): JsonElement = json.parseToJsonElement(getRaw(path))

    fun q(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
        append('"')
    }

    fun authStatus(): AuthStatus {
        val o = parse("/api/auth/status").asObj()
        return AuthStatus(o.bool("auth_enabled"), o.bool("logged_in"))
    }

    fun login(password: String) {
        exec(req("POST", "/api/auth/login", """{"password":${q(password)}}"""))
        refreshCsrf()
    }

    fun refreshCsrf() {
        http.newCall(Request.Builder().url("$root/").build()).execute().use { resp ->
            val html = resp.body?.string().orEmpty()
            val pats = listOf(
                Regex("""csrf_token"\s*:\s*"([^"]+)""""),
                Regex("""__CSRF_TOKEN_JSON__\s*=\s*"([^"]+)""""),
                Regex("""name="csrf-token" content="([^"]+)""""),
            )
            for (p in pats) {
                val m = p.find(html) ?: continue
                csrf = m.groupValues[1]
                return
            }
        }
    }

    fun sessions(): List<SessionRow> {
        val el = parse("/api/sessions")
        val arr = el.arrayOf("sessions", "data", "items")
        return arr.mapNotNull { it.asObjOrNull()?.toSession() }
    }

    fun loadSession(sid: String, full: Boolean = false): SessionLoad {
        val qsid = java.net.URLEncoder.encode(sid, Charsets.UTF_8)
        val extra = if (full) "&full=1" else "&msg_limit=80"
        val el = parse("/api/session?session_id=$qsid&messages=1&resolve_model=0$extra")
        val rootObj = el.asObj()
        val sess = rootObj.obj("session") ?: rootObj
        val msgs = (sess.arr("messages") ?: rootObj.arr("messages") ?: JsonArray(emptyList()))
            .flatMap { it.asObjOrNull()?.toChatRows() ?: emptyList() }
        val todos = extractTodos(sess) ?: extractTodos(rootObj) ?: emptyList()
        return SessionLoad(
            title = sess.str("title").ifBlank { rootObj.str("title") },
            model = sess.str("model").ifBlank { rootObj.str("model") },
            messages = msgs,
            todos = todos,
            truncated = sess.bool("_messages_truncated") || rootObj.bool("_messages_truncated"),
            activeStreamId = sess.str("active_stream_id").ifBlank { rootObj.str("active_stream_id") },
        )
    }

    fun newSession(): String {
        val el = json.parseToJsonElement(postRaw("/api/session/new", "{}")).asObj()
        return el.str("session_id").ifBlank { el.obj("session")?.str("session_id").orEmpty() }
    }

    fun deleteSession(sid: String) {
        postRaw("/api/session/delete", """{"session_id":${q(sid)}}""")
    }

    fun startChat(sid: String, message: String, model: String?): String {
        val extra = if (!model.isNullOrBlank()) ""","model":${q(model)}""" else ""
        val body = """{"session_id":${q(sid)},"message":${q(message)}$extra}"""
        val el = json.parseToJsonElement(exec(req("POST", "/api/chat/start", body))).asObj()
        return el.str("stream_id")
    }

    fun stream(
        streamId: String,
        replay: Boolean = false,
        afterSeq: Long = 0,
        afterEventId: String = "",
        onEvent: (String, String, String) -> Unit,
        onClosed: () -> Unit,
    ): EventSource {
        val qs = buildString {
            append("/api/chat/stream?stream_id=").append(enc(streamId))
            if (replay) append("&replay=1")
            if (afterSeq > 0) append("&after_seq=").append(afterSeq)
            if (afterEventId.isNotBlank()) append("&after_event_id=").append(enc(afterEventId))
        }
        val r = Request.Builder()
            .url("$root$qs")
            .header("Accept", "text/event-stream")
            .build()
        return EventSources.createFactory(http).newEventSource(r, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                onEvent(type ?: "", data, id.orEmpty())
            }
            override fun onClosed(eventSource: EventSource) { onClosed() }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) { onClosed() }
        })
    }

    fun streamStatus(streamId: String): Boolean {
        return runCatching { parse("/api/chat/stream/status?stream_id=${enc(streamId)}").asObj().bool("active") }.getOrDefault(false)
    }

    fun sessionStatus(sid: String): Pair<String, Int> {
        val o = parse("/api/session/status?session_id=${enc(sid)}").asObj()
        return o.str("active_stream_id") to o.int("message_count")
    }

    fun streamSession(sid: String, knownCount: Int, onEvent: (String, String) -> Unit, onClosed: () -> Unit): EventSource {
        val r = Request.Builder()
            .url("$root/api/session/stream?session_id=${enc(sid)}&known_count=$knownCount")
            .header("Accept", "text/event-stream")
            .build()
        return EventSources.createFactory(http).newEventSource(r, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                onEvent(type ?: "", data)
            }
            override fun onClosed(eventSource: EventSource) { onClosed() }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) { onClosed() }
        })
    }

    fun streamSessionList(onEvent: (String, String) -> Unit, onClosed: () -> Unit): EventSource {
        val r = Request.Builder()
            .url("$root/api/sessions/events")
            .header("Accept", "text/event-stream")
            .build()
        return EventSources.createFactory(http).newEventSource(r, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                onEvent(type ?: "", data)
            }
            override fun onClosed(eventSource: EventSource) { onClosed() }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) { onClosed() }
        })
    }

    fun cancelChat(sid: String) {
        runCatching { postRaw("/api/chat/cancel", """{"session_id":${q(sid)}}""") }
    }

    fun approval(sid: String): Approval? {
        val o = parse("/api/approval/pending?session_id=${enc(sid)}").asObj()
        val p = o.obj("pending") ?: return null
        val tool = p.str("tool", "name", "function", "title").ifBlank { "tool" }
        val detail = p.str("description", "command", "detail", "preview", "args").take(400)
        return Approval(p.str("approval_id", "id"), tool, detail, o.int("pending_count"))
    }

    fun respondApproval(sid: String, approvalId: String, choice: String) {
        postRaw(
            "/api/approval/respond",
            """{"session_id":${q(sid)},"approval_id":${q(approvalId)},"choice":${q(choice)}}""",
        )
    }

    fun clarify(sid: String): Clarify? {
        val o = parse("/api/clarify/pending?session_id=${enc(sid)}").asObj()
        val p = o.obj("pending") ?: return null
        val choices = (p.arr("choices") ?: p.arr("choices_offered") ?: JsonArray(emptyList()))
            .mapNotNull { it.asObjOrNull()?.str("label", "text")?.ifBlank { null } ?: it.primitiveOrNull() }
            .filter { it.isNotBlank() }
        return Clarify(p.str("clarify_id", "id"), p.str("question", "prompt", "text"), choices)
    }

    fun respondClarify(sid: String, clarifyId: String, response: String) {
        postRaw(
            "/api/clarify/respond",
            """{"session_id":${q(sid)},"clarify_id":${q(clarifyId)},"response":${q(response)}}""",
        )
    }

    fun crons(): List<CronJob> {
        val o = parse("/api/crons").asObj()
        return (o.arr("jobs") ?: JsonArray(emptyList())).mapNotNull { el ->
            val j = el.asObjOrNull() ?: return@mapNotNull null
            CronJob(
                id = j.str("id", "job_id"),
                name = j.str("name", "id", "job_id").ifBlank { "job" },
                schedule = j.str("schedule"),
                enabled = j.bool("enabled", default = true),
                paused = j.bool("paused"),
                prompt = j.str("prompt"),
                lastStatus = j.str("last_status", "status", "last_error"),
                lastRun = j.anyToString("last_run", "last_run_at", "next_run"),
                owner = j.str("owner_profile", "profile"),
                readOnly = j.bool("read_only"),
            )
        }.filter { it.id.isNotBlank() }
    }

    fun cronAction(jobId: String, action: String) {
        postRaw("/api/crons/$action", """{"job_id":${q(jobId)}}""")
    }

    fun cronOutput(jobId: String): String {
        return runCatching { getRaw("/api/crons/output?job_id=${enc(jobId)}").take(12000) }.getOrDefault("")
    }

    fun kanban(): List<KanbanColumn> {
        val o = parse("/api/kanban/board").asObj()
        return (o.arr("columns") ?: JsonArray(emptyList())).map { col ->
            val c = col.asObj()
            KanbanColumn(
                name = c.str("name", "id", "title").ifBlank { "column" },
                tasks = (c.arr("tasks") ?: JsonArray(emptyList())).mapNotNull { t ->
                    val o = t.asObjOrNull() ?: return@mapNotNull null
                    KanbanTask(
                        id = o.str("id", "task_id"),
                        title = o.str("title", "name", "id").ifBlank { "task" },
                        status = o.str("status").ifBlank { c.str("name") },
                        assignee = o.str("assignee"),
                        priority = o.anyToString("priority"),
                    )
                },
            )
        }
    }

    fun moveKanban(id: String, status: String) {
        patchRaw("/api/kanban/tasks/${enc(id)}", """{"status":${q(status)}}""")
    }

    fun skills(): List<SkillRow> {
        val o = parse("/api/skills").asObj()
        return (o.arr("skills") ?: JsonArray(emptyList())).mapNotNull { el ->
            val s = el.asObjOrNull() ?: return@mapNotNull null
            SkillRow(
                name = s.str("name"),
                description = s.str("description"),
                category = s.str("category"),
                disabled = s.bool("disabled"),
            )
        }.filter { it.name.isNotBlank() }
    }

    fun skillContent(name: String): String {
        val o = parse("/api/skills/content?name=${enc(name)}").asObj()
        return o.str("content", "text", "body")
    }

    fun toggleSkill(name: String, enabled: Boolean) {
        postRaw("/api/skills/toggle", """{"name":${q(name)},"enabled":$enabled}""")
    }

    fun memory(): MemoryDoc {
        val o = parse("/api/memory").asObj()
        return MemoryDoc(
            memory = o.str("memory"),
            user = o.str("user"),
            soul = o.str("soul"),
            project = o.str("project_context"),
            projectName = o.str("project_context_name"),
        )
    }

    fun writeMemory(section: String, content: String) {
        postRaw("/api/memory/write", """{"section":${q(section)},"content":${q(content)}}""")
    }

    fun spaces(): List<SpaceRow> {
        val o = parse("/api/workspaces").asObj()
        val last = o.str("last")
        return (o.arr("workspaces") ?: JsonArray(emptyList())).mapNotNull { el ->
            when (el) {
                is JsonPrimitive -> {
                    val p = el.content
                    SpaceRow(p.substringAfterLast('/').ifBlank { p }, p, p == last)
                }
                is JsonObject -> {
                    val path = el.str("path", "cwd", "dir")
                    val name = el.str("name", "label").ifBlank { path.substringAfterLast('/').ifBlank { path } }
                    SpaceRow(name, path, path == last || el.bool("last"))
                }
                else -> null
            }
        }
    }

    fun profiles(): Pair<String, List<ProfileRow>> {
        val o = parse("/api/profiles").asObj()
        val active = o.str("active")
        val rows = (o.arr("profiles") ?: JsonArray(emptyList())).mapNotNull { el ->
            when (el) {
                is JsonPrimitive -> ProfileRow(el.content, active = el.content == active)
                is JsonObject -> {
                    val name = el.str("name", "id")
                    ProfileRow(name, el.str("model", "default_model"), name == active || el.bool("active"))
                }
                else -> null
            }
        }.filter { it.name.isNotBlank() }
        return active to rows
    }

    fun switchProfile(name: String) {
        postRaw("/api/profile/switch", """{"name":${q(name)}}""")
    }

    fun prompts(): List<SavedPrompt> {
        val o = parse("/api/prompts").asObj()
        return (o.arr("prompts") ?: JsonArray(emptyList())).mapNotNull { el ->
            val p = el.asObjOrNull() ?: return@mapNotNull null
            val text = p.str("text", "content", "prompt")
            if (text.isBlank()) return@mapNotNull null
            SavedPrompt(p.str("id").ifBlank { text.hashCode().toString() }, p.str("label", "title").ifBlank { text.take(48) }, text)
        }
    }

    fun insights(): Insights {
        val o = parse("/api/insights?days=30").asObj()
        val models = (o.arr("models") ?: JsonArray(emptyList())).mapNotNull { el ->
            val m = el.asObjOrNull() ?: return@mapNotNull null
            InsightModel(m.str("model"), m.int("sessions"), m.int("total_tokens"), m.double("cost"))
        }
        val hit = o.obj("total_cache_hit_percent")?.double("value")
            ?: o.doubleOrNull("total_cache_hit_percent")
        return Insights(
            days = o.int("period_days").takeIf { it > 0 } ?: 30,
            sessions = o.int("total_sessions"),
            messages = o.int("total_messages"),
            tokens = o.int("total_tokens"),
            cost = o.double("total_cost"),
            cacheHit = hit,
            models = models,
        )
    }

    fun logs(file: String = "agent"): List<String> {
        val o = parse("/api/logs?file=${enc(file)}&tail=250").asObj()
        return (o.arr("lines") ?: JsonArray(emptyList())).mapNotNull { it.primitiveOrNull() }
    }

    fun consoleUsage(dashBase: String): ConsoleUsage {
        val root = dashBase.trim().trimEnd('/')
        if (root.isBlank()) throw IllegalStateException("Set the Hermes Console URL (:8790)")
        val req = Request.Builder().url("$root/api/usage").header("Accept", "application/json").build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("Console ${resp.code}")
            return json.decodeFromString(ConsoleUsage.serializer(), body)
        }
    }

    fun models(): Pair<String, List<ModelOption>> {
        val root = parse("/api/models").asObj()
        val default = root.str("default_model", "model")
        val out = linkedMapOf<String, ModelOption>()
        fun add(provider: String, el: JsonElement?) {
            val o = el?.asObjOrNull() ?: return
            val id = o.str("id", "model").ifBlank { o.str("name") }
            if (id.isBlank()) return
            val label = o.str("label", "name", "display").ifBlank { id }
            out.putIfAbsent(id, ModelOption(id, label, provider.ifBlank { id.substringBefore('/', "") }))
        }
        fun addBucket(provider: String, arr: JsonArray?) {
            arr?.forEach { add(provider, it) }
        }
        (root.arr("groups") ?: JsonArray(emptyList())).forEach { gEl ->
            val g = gEl.asObjOrNull() ?: return@forEach
            val provider = g.str("provider", "provider_id", "id", "name")
            addBucket(provider, g.arr("models"))
            addBucket(provider, g.arr("extra_models"))
        }
        addBucket(root.str("active_provider"), root.arr("models"))
        addBucket("extra", root.arr("extra_models"))
        if (out.isEmpty()) {
            // last resort: walk ids, but skip provider/group metadata keys
            fun walk(e: JsonElement) {
                when (e) {
                    is JsonArray -> e.forEach { walk(it) }
                    is JsonObject -> {
                        val id = e.str("id")
                        if (id.contains('/') || e.containsKey("label")) add(e.str("provider"), e)
                        e.values.forEach { walk(it) }
                    }
                    else -> {}
                }
            }
            walk(root)
        }
        return default to out.values.toList()
    }

    fun settings(): List<SettingItem> {
        val el = parse("/api/settings")
        val obj = when {
            el is JsonObject && el["settings"] is JsonObject -> el.obj("settings")!!
            el is JsonObject -> el
            else -> return emptyList()
        }
        return obj.entries.mapNotNull { (k, v) ->
            if (secretKey(k)) return@mapNotNull null
            when (v) {
                is JsonPrimitive -> {
                    val type = when {
                        v.booleanOrNull != null -> "bool"
                        v.intOrNull != null -> "number"
                        else -> "string"
                    }
                    SettingItem(k, type, v.contentOrNull ?: v.toString())
                }
                is JsonNull -> SettingItem(k, "string", "")
                else -> SettingItem(k, "json", v.toString().take(200))
            }
        }.sortedBy { it.key }
    }

    fun saveSettings(edits: Map<String, Any>) {
        if (edits.isEmpty()) return
        val body = buildString {
            append('{')
            edits.entries.forEachIndexed { i, (k, v) ->
                if (i > 0) append(',')
                append(q(k)).append(':')
                append(
                    when (v) {
                        is Boolean, is Number -> v.toString()
                        else -> q(v.toString())
                    },
                )
            }
            append('}')
        }
        postRaw("/api/settings", body)
    }

    fun listDir(sid: String, path: String): Pair<String, List<FsEntry>> {
        val o = parse("/api/list?session_id=${enc(sid)}&path=${enc(path)}").asObj()
        val entries = (o.arr("entries") ?: JsonArray(emptyList())).mapNotNull { el ->
            val e = el.asObjOrNull() ?: return@mapNotNull null
            val name = e.str("name")
            if (name.isBlank()) return@mapNotNull null
            val isDir = e.bool("is_dir") || e.str("type") == "dir"
            FsEntry(name, e.str("path").ifBlank { name }, isDir, e.int("size").toLong())
        }.sortedWith(compareByDescending<FsEntry> { it.isDir }.thenBy { it.name.lowercase() })
        return o.str("workspace") to entries
    }

    fun readFile(sid: String, path: String): FileDoc {
        val o = parse("/api/file?session_id=${enc(sid)}&path=${enc(path)}").asObj()
        return FileDoc(o.str("path").ifBlank { path }, o.str("content"), o.int("size"), o.int("lines"))
    }

    fun saveFile(sid: String, path: String, content: String) {
        postRaw("/api/file/save", """{"session_id":${q(sid)},"path":${q(path)},"content":${q(content)}}""")
    }

    fun startTerminal(sid: String, rows: Int = 24, cols: Int = 80): String {
        val o = json.parseToJsonElement(
            postRaw("/api/terminal/start", """{"session_id":${q(sid)},"rows":$rows,"cols":$cols}"""),
        ).asObj()
        if (o.str("error").isNotBlank()) throw RuntimeException(o.str("error", "message"))
        return o.str("workspace")
    }

    fun terminalInput(sid: String, data: String) {
        postRaw("/api/terminal/input", """{"session_id":${q(sid)},"data":${q(data)}}""")
    }

    fun closeTerminal(sid: String) {
        runCatching { postRaw("/api/terminal/close", """{"session_id":${q(sid)}}""") }
    }

    fun streamTerminal(sid: String, onEvent: (String, String) -> Unit, onClosed: () -> Unit): EventSource {
        val r = Request.Builder()
            .url("$root/api/terminal/output?session_id=${enc(sid)}")
            .header("Accept", "text/event-stream")
            .build()
        return EventSources.createFactory(http).newEventSource(r, object : EventSourceListener() {
            override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
                onEvent(type ?: "", data)
            }
            override fun onClosed(es: EventSource) { onClosed() }
            override fun onFailure(es: EventSource, t: Throwable?, resp: okhttp3.Response?) { onClosed() }
        })
    }

    fun transcribe(file: java.io.File): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                file.name,
                file.asRequestBody("audio/mp4".toMediaType()),
            )
            .build()
        val b = Request.Builder().url("$root/api/transcribe")
        if (csrf.isNotEmpty()) b.header("X-Hermes-CSRF-Token", csrf)
        val o = json.parseToJsonElement(exec(b.post(body).build())).asObj()
        if (o.str("error").isNotBlank()) throw RuntimeException(o.str("error"))
        return o.str("transcript", "text")
    }

    fun tts(text: String, engine: String = "edge"): ByteArray {
        val body = """{"text":${q(text.take(4000))},"engine":${q(engine)}}"""
        http.newCall(req("POST", "/api/tts", body)).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            if (resp.code == 401) throw AuthException()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${String(bytes).take(200)}")
            return bytes
        }
    }

    fun transcribeAvailable(): Boolean {
        return runCatching { parse("/api/transcribe/capability").asObj().bool("available") }.getOrDefault(false)
    }

    companion object {
        fun secretKey(k: String): Boolean {
            val s = k.lowercase()
            return listOf("password", "secret", "token", "api_key", "apikey", "csrf", "cookie").any { s.contains(it) }
        }

        fun fmtSec(sec: Double): String {
            if (sec <= 0) return "—"
            val s = sec.toLong()
            val h = s / 3600
            val m = (s % 3600) / 60
            return if (h > 0) "${h}h ${m}m" else "${m}m ${s % 60}s"
        }
    }
}

private fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8)

private fun JsonElement.asObj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
private fun JsonElement.asObjOrNull(): JsonObject? = this as? JsonObject
private fun JsonElement.primitiveOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

private fun JsonElement.arrayOf(vararg keys: String): JsonArray {
    if (this is JsonArray) return this
    val o = this as? JsonObject ?: return JsonArray(emptyList())
    for (k in keys) o[k]?.let { if (it is JsonArray) return it }
    return JsonArray(emptyList())
}

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
private fun JsonObject.str(vararg keys: String): String {
    for (k in keys) {
        val v = this[k] ?: continue
        when (v) {
            is JsonPrimitive -> v.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
            is JsonObject, is JsonArray -> return v.toString()
            else -> {}
        }
    }
    return ""
}
private fun JsonObject.bool(key: String, default: Boolean = false): Boolean {
    val v = this[key] ?: return default
    return (v as? JsonPrimitive)?.booleanOrNull
        ?: (v as? JsonPrimitive)?.contentOrNull?.equals("true", true)
        ?: default
}
private fun JsonObject.int(key: String): Int {
    val v = this[key] as? JsonPrimitive ?: return 0
    return v.intOrNull ?: v.longOrNull?.toInt() ?: v.contentOrNull?.toIntOrNull() ?: 0
}
private fun JsonObject.double(key: String): Double {
    val v = this[key] as? JsonPrimitive ?: return 0.0
    return v.doubleOrNull ?: v.contentOrNull?.toDoubleOrNull() ?: 0.0
}
private fun JsonObject.doubleOrNull(key: String): Double? {
    val v = this[key] as? JsonPrimitive ?: return null
    return v.doubleOrNull ?: v.contentOrNull?.toDoubleOrNull()
}
private fun JsonObject.anyToString(vararg keys: String): String {
    for (k in keys) {
        val v = this[k] ?: continue
        if (v is JsonNull) continue
        val s = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
        if (s.isNotBlank() && s != "null") return s
    }
    return ""
}

private fun JsonObject.toSession(): SessionRow? {
    val sid = str("session_id", "id")
    if (sid.isBlank()) return null
    val count = int("messages").takeIf { it > 0 } ?: int("message_count")
    return SessionRow(
        sid = sid,
        title = str("title"),
        preview = str("preview", "snippet", "last_message"),
        msgCount = count,
        source = str("source"),
        model = str("model"),
        pinned = bool("pinned"),
    )
}

private fun JsonObject.toChatRows(): List<ChatMsg> {
    val role = str("role").ifBlank { "assistant" }
    val id = str("id").ifBlank { "${role}-${contentHash()}" }
    val content = str("content", "text")
    val tool = str("tool_name", "name", "tool")
    val out = mutableListOf<ChatMsg>()
    arr("tool_calls")?.forEachIndexed { i, el ->
        val o = el.asObjOrNull() ?: return@forEachIndexed
        val fn = o.obj("function")
        val name = o.str("name", "tool").ifBlank { fn?.str("name").orEmpty() }.ifBlank { "tool" }
        val args = o.str("arguments").ifBlank { fn?.str("arguments").orEmpty() }.ifBlank { o.anyToString("args", "input") }
        val preview = o.str("preview", "snippet", "result").ifBlank { args.take(180) }
        out.add(ChatMsg("$id-tc-$i", "tool", args, name, preview))
    }
    when {
        role == "thinking" || role == "reasoning" ->
            if (content.isNotBlank()) out.add(ChatMsg(id, "thinking", content))
        role == "tool" ->
            out.add(ChatMsg(id, "tool", content, tool.ifBlank { "tool" }, content.take(240)))
        role == "user" ->
            out.add(ChatMsg(id, "user", content))
        role == "system" ->
            if (content.isNotBlank()) out.add(ChatMsg(id, "system", content))
        else -> {
            if (content.isNotBlank()) out.add(ChatMsg(id, "assistant", content))
            else if (out.isEmpty() && tool.isNotBlank()) out.add(ChatMsg(id, "tool", "", tool))
        }
    }
    return out
}

private fun JsonObject.contentHash(): String =
    (str("content") + roleHint()).hashCode().toString()

private fun JsonObject.roleHint(): String = str("role")

private fun extractTodos(o: JsonObject): List<TodoItem>? {
    val state = o.obj("todo_state")
    val arr = state?.arr("todos") ?: o.arr("todos") ?: return null
    return arr.mapIndexed { i, el ->
        val t = el.asObjOrNull()
        if (t == null) TodoItem("$i", el.primitiveOrNull().orEmpty(), "pending")
        else TodoItem(t.str("id").ifBlank { "$i" }, t.str("content", "text", "title"), t.str("status").ifBlank { "pending" })
    }
}
