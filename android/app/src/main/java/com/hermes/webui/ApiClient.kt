package com.hermes.webui

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
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

@Serializable
private data class CookieRec(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAt: Long,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
)

private class PersistentCookieJar(private val prefs: Prefs) : CookieJar {
    private val json = Json { ignoreUnknownKeys = true }
    private val store = mutableListOf<Cookie>()

    init {
        runCatching {
            json.decodeFromString<List<CookieRec>>(prefs.cookiesJson).forEach { rec ->
                val b = Cookie.Builder().name(rec.name).value(rec.value).path(rec.path.ifBlank { "/" }).expiresAt(rec.expiresAt)
                if (rec.secure) b.secure()
                if (rec.httpOnly) b.httpOnly()
                if (rec.hostOnly) b.hostOnlyDomain(rec.domain.ifBlank { "localhost" })
                else b.domain(rec.domain.trimStart('.').ifBlank { "localhost" })
                store += b.build()
            }
        }
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        store.removeAll { c -> cookies.any { it.name == c.name && it.domain == c.domain } }
        store.addAll(cookies)
        persist()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val before = store.size
        store.removeAll { it.expiresAt < now }
        if (store.size != before) persist()
        return store.filter { it.matches(url) }
    }

    private fun persist() {
        val recs = store.map {
            CookieRec(it.name, it.value, it.domain, it.path, it.expiresAt, it.secure, it.httpOnly, it.hostOnly)
        }
        prefs.cookiesJson = json.encodeToString(recs)
    }
}

class ApiClient(base: String, prefs: Prefs) {
    private val root = base.trim().trimEnd('/')
    private var csrf = ""
    private val jar = PersistentCookieJar(prefs)
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

    fun sessions(source: String = "", includeArchived: Boolean = false): SessionsResult {
        val parts = mutableListOf<String>()
        if (source == "cli") parts += "source=cli"
        if (includeArchived) parts += "include_archived=1"
        val qs = if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
        val el = parse("/api/sessions$qs")
        val arr = el.arrayOf("sessions", "data", "items")
        val o = el.asObj()
        return SessionsResult(
            rows = arr.mapNotNull { it.asObjOrNull()?.toSession() },
            webuiCount = o.int("webui_session_count"),
            cliCount = o.int("cli_session_count"),
        )
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

    fun pinSession(sid: String, pinned: Boolean) {
        postRaw("/api/session/pin", """{"session_id":${q(sid)},"pinned":$pinned}""")
    }

    fun archiveSession(sid: String, archived: Boolean) {
        postRaw("/api/session/archive", """{"session_id":${q(sid)},"archived":$archived}""")
    }

    fun renameSession(sid: String, title: String) {
        postRaw("/api/session/rename", """{"session_id":${q(sid)},"title":${q(title)}}""")
    }

    fun duplicateSession(sid: String): String {
        val el = json.parseToJsonElement(postRaw("/api/session/duplicate", """{"session_id":${q(sid)}}""")).asObj()
        return el.obj("session")?.str("session_id").orEmpty().ifBlank { el.str("session_id") }
    }

    fun moveSession(sid: String, projectId: String?) {
        val pid = if (projectId.isNullOrBlank()) "null" else q(projectId)
        postRaw("/api/session/move", """{"session_id":${q(sid)},"project_id":$pid}""")
    }

    fun clearSession(sid: String) {
        postRaw("/api/session/clear", """{"session_id":${q(sid)}}""")
    }

    fun shareCreate(sid: String): String {
        val el = json.parseToJsonElement(postRaw("/api/share/create", """{"session_id":${q(sid)}}""")).asObj()
        return el.obj("share")?.str("url").orEmpty().ifBlank { el.str("url", "share_url") }
    }

    fun shareRevoke(sid: String) {
        postRaw("/api/share/revoke", """{"session_id":${q(sid)}}""")
    }

    fun branchSession(sid: String, title: String = ""): String {
        val body = if (title.isBlank()) """{"session_id":${q(sid)}}""" else """{"session_id":${q(sid)},"title":${q(title)}}"""
        val el = json.parseToJsonElement(postRaw("/api/session/branch", body)).asObj()
        return el.str("session_id").ifBlank { el.obj("session")?.str("session_id").orEmpty() }
    }

    fun importSession(jsonBody: String): String {
        val el = json.parseToJsonElement(postRaw("/api/session/import", jsonBody)).asObj()
        return el.obj("session")?.str("session_id").orEmpty().ifBlank { el.str("session_id") }
    }

    fun searchSessions(q: String): List<SessionRow> {
        if (q.isBlank()) return emptyList()
        val el = runCatching { parse("/api/sessions/search?q=${enc(q)}").asObj() }.getOrNull() ?: return emptyList()
        val arr = el.arrayOf("sessions", "data", "items")
        return arr.mapNotNull { it.asObjOrNull()?.toSession() }
    }

    fun downloadExport(sid: String, format: String, dest: java.io.File): java.io.File {
        val qs = if (format == "html") "?session_id=${enc(sid)}&format=html" else "?session_id=${enc(sid)}"
        http.newCall(req("GET", "/api/session/export$qs")).execute().use { resp ->
            if (resp.code == 401) throw AuthException()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            dest.outputStream().use { out -> resp.body?.byteStream()?.copyTo(out) }
        }
        return dest
    }

    fun mcpServers(): List<McpServer> {
        val o = runCatching { parse("/api/mcp/servers").asObj() }.getOrNull() ?: return emptyList()
        return (o.arr("servers") ?: JsonArray(emptyList())).mapNotNull { el ->
            val s = el.asObjOrNull() ?: return@mapNotNull null
            val name = s.str("name", "id")
            if (name.isBlank()) return@mapNotNull null
            McpServer(name, s.bool("enabled", true) && !s.bool("disabled"), s.str("description", "status").take(240))
        }
    }

    fun personalities(): List<PersonalityRow> {
        val o = runCatching { parse("/api/personalities").asObj() }.getOrNull() ?: return emptyList()
        return (o.arr("personalities") ?: JsonArray(emptyList())).mapNotNull { el ->
            val p = el.asObjOrNull() ?: return@mapNotNull null
            val name = p.str("name")
            if (name.isBlank()) return@mapNotNull null
            PersonalityRow(name, p.str("description").take(200))
        }
    }

    fun setPersonality(sid: String, name: String) {
        postRaw("/api/personality/set", """{"session_id":${q(sid)},"name":${q(name)}}""")
    }

    fun setDefaultModel(provider: String, model: String) {
        postRaw("/api/model/set", """{"scope":"main","task":"","provider":${q(provider)},"model":${q(model)},"advanced":{}}""")
    }

    fun auxModels(): List<AuxModelRow> {
        val o = runCatching { parse("/api/model/auxiliary").asObj() }.getOrNull() ?: return emptyList()
        return (o.arr("tasks") ?: JsonArray(emptyList())).mapNotNull { el ->
            val t = el.asObjOrNull() ?: return@mapNotNull null
            val task = t.str("task")
            if (task.isBlank()) return@mapNotNull null
            AuxModelRow(task, t.str("label").ifBlank { task }, t.str("provider"), t.str("model"), t.str("description"))
        }
    }

    fun refreshModels(provider: String): String {
        val el = json.parseToJsonElement(postRaw("/api/models/refresh", """{"provider":${q(provider)}}""")).asObj()
        return if (el.bool("ok")) "ok" else el.str("error").ifBlank { "refresh failed" }
    }

    fun deleteProviderKey(provider: String) {
        postRaw("/api/providers/delete", """{"provider":${q(provider)}}""")
    }

    fun extensionsRegistry(): List<RegistryEntry> {
        val o = runCatching { parse("/api/extensions/registry").asObj() }.getOrNull() ?: return emptyList()
        return (o.arr("entries") ?: JsonArray(emptyList())).mapNotNull { el ->
            val e = el.asObjOrNull() ?: return@mapNotNull null
            val id = e.str("id")
            if (id.isBlank()) return@mapNotNull null
            RegistryEntry(
                id = id,
                name = e.str("name").ifBlank { id },
                description = e.str("description").take(240),
                version = e.str("version"),
                author = e.str("author"),
                downloadUrl = e.str("download_url", "download"),
                sha256 = e.str("sha256"),
                installed = e.bool("installed"),
            )
        }
    }

    fun extensionToggle(id: String, enabled: Boolean) {
        postRaw("/api/extensions/toggle", """{"id":${q(id)},"enabled":$enabled}""")
    }

    fun extensionInstall(entry: RegistryEntry) {
        val parts = mutableListOf("\"id\":${q(entry.id)}")
        if (entry.downloadUrl.isNotBlank()) parts += "\"download_url\":${q(entry.downloadUrl)}"
        if (entry.sha256.isNotBlank()) parts += "\"sha256\":${q(entry.sha256)}"
        postRaw("/api/extensions/install", "{" + parts.joinToString(",") + "}")
    }

    fun extensionUninstall(id: String) {
        postRaw("/api/extensions/uninstall", """{"id":${q(id)}}""")
    }

    fun compressStart(sid: String): String {
        val el = json.parseToJsonElement(postRaw("/api/session/compress/start", """{"session_id":${q(sid)}}""")).asObj()
        return el.str("status").ifBlank { "started" }
    }

    fun compressStatus(sid: String): Pair<String, String> {
        val el = parse("/api/session/compress/status?session_id=${enc(sid)}").asObj()
        return el.str("status") to el.str("error")
    }

    fun workspacesSuggest(prefix: String): List<String> {
        val o = runCatching { parse("/api/workspaces/suggest?prefix=${enc(prefix)}").asObj() }.getOrNull() ?: return emptyList()
        return (o.arr("suggestions") ?: JsonArray(emptyList())).mapNotNull { it.primitiveOrNull() }
    }

    fun workspacesReorder(paths: List<String>) {
        postRaw("/api/workspaces/reorder", """{"paths":[${paths.joinToString(",") { q(it) }}]}""")
    }

    fun health(): HealthInfo {
        val o = runCatching { parse("/api/system/health").asObj() }.getOrNull() ?: return HealthInfo()
        val agent = runCatching { parse("/api/health/agent").asObj() }.getOrNull()
        val gw = runCatching { parse("/api/gateway/status").asObj() }.getOrNull()
        val runtime = o.obj("webui_runtime")
        return HealthInfo(
            status = o.str("status"),
            cpuPct = o.obj("cpu")?.double("percent") ?: 0.0,
            memPct = o.obj("memory")?.double("percent") ?: 0.0,
            diskPct = o.obj("disk")?.double("percent") ?: 0.0,
            agentAlive = agent?.bool("alive") ?: false,
            gatewayRunning = gw?.bool("running") ?: false,
            residentSessions = runtime?.obj("sessions")?.int("resident") ?: 0,
            activeStreams = runtime?.obj("streams")?.int("active") ?: 0,
        )
    }

    fun cronsRunning(): Set<String> {
        val o = runCatching { parse("/api/crons/status").asObj() }.getOrNull() ?: return emptySet()
        return o.obj("running")?.keys ?: emptySet()
    }

    fun projects(): List<ProjectRow> {
        val o = runCatching { parse("/api/projects").asObj() }.getOrNull() ?: return emptyList()
        return (o.arr("projects") ?: JsonArray(emptyList())).mapNotNull { el ->
            val p = el.asObjOrNull() ?: return@mapNotNull null
            val id = p.str("project_id", "id")
            if (id.isBlank()) return@mapNotNull null
            ProjectRow(id, p.str("name", "title").ifBlank { "project" }, p.str("color"))
        }
    }

    fun createProject(name: String): ProjectRow? {
        val el = json.parseToJsonElement(postRaw("/api/projects/create", """{"name":${q(name)}}""")).asObj()
        val p = el.obj("project") ?: el
        val id = p.str("project_id", "id")
        if (id.isBlank()) return null
        return ProjectRow(id, p.str("name").ifBlank { name }, p.str("color"))
    }

    fun deleteProject(id: String) {
        postRaw("/api/projects/delete", """{"project_id":${q(id)}}""")
    }

    fun renameProject(id: String, name: String) {
        postRaw("/api/projects/rename", """{"project_id":${q(id)},"name":${q(name)}}""")
    }

    fun startChat(
        sid: String,
        message: String,
        model: String?,
        attachments: List<String> = emptyList(),
        modelProvider: String? = null,
    ): String {
        val parts = mutableListOf("\"session_id\":${q(sid)}", "\"message\":${q(message)}")
        val sendModel = if (model.isNullOrBlank()) null else ModelIds.forSend(model, modelProvider.orEmpty())
        if (!sendModel.isNullOrBlank()) {
            parts += "\"model\":${q(sendModel)}"
            parts += "\"explicit_model_pick\":true"
        }
        if (!modelProvider.isNullOrBlank()) parts += "\"model_provider\":${q(modelProvider)}"
        if (attachments.isNotEmpty()) parts += "\"attachments\":[" + attachments.joinToString(",") { q(it) } + "]"
        val el = json.parseToJsonElement(exec(req("POST", "/api/chat/start", "{" + parts.joinToString(",") + "}"))).asObj()
        return el.str("stream_id")
    }

    fun steer(sid: String, text: String): Boolean {
        if (sid.isBlank() || text.isBlank()) return false
        val parts = listOf("\"session_id\":${q(sid)}", "\"text\":${q(text)}")
        val el = json.parseToJsonElement(exec(req("POST", "/api/chat/steer", "{" + parts.joinToString(",") + "}"))).asObj()
        return el.bool("accepted")
    }

    fun upload(sid: String, file: java.io.File, mime: String = "application/octet-stream"): PendingAttach {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("session_id", sid)
            .addFormDataPart("file", file.name, file.asRequestBody(mime.toMediaType()))
            .build()
        val b = Request.Builder().url("$root/api/upload")
        if (csrf.isNotEmpty()) b.header("X-Hermes-CSRF-Token", csrf)
        val o = json.parseToJsonElement(exec(b.post(body).build())).asObj()
        if (o.str("error").isNotBlank()) throw RuntimeException(o.str("error"))
        return PendingAttach(
            name = o.str("filename").ifBlank { file.name },
            path = o.str("path"),
            mime = o.str("mime").ifBlank { mime },
            isImage = o.bool("is_image") || mime.startsWith("image/"),
        )
    }

    fun providers(): List<ProviderRow> {
        val o = parse("/api/providers").asObj()
        val arr = o.arr("providers") ?: o.arr("items") ?: JsonArray(emptyList())
        return arr.mapNotNull { el ->
            val p = el.asObjOrNull() ?: return@mapNotNull null
            val id = p.str("id", "provider", "name")
            if (id.isBlank()) return@mapNotNull null
            ProviderRow(
                id = id,
                displayName = p.str("display_name", "label", "name").ifBlank { id },
                hasKey = p.bool("has_key") || p.bool("configured") || p.bool("logged_in"),
                configurable = p.bool("configurable", true),
                keySource = p.str("key_source", "source"),
            )
        }
    }

    fun setProviderKey(id: String, key: String) {
        postRaw("/api/providers", "{\"provider\":${q(id)},\"api_key\":${q(key)}}")
    }

    fun plugins(): List<PluginRow> {
        val o = parse("/api/plugins").asObj()
        val arr = o.arr("plugins") ?: JsonArray(emptyList())
        return arr.mapNotNull { el ->
            val p = el.asObjOrNull() ?: return@mapNotNull null
            val name = p.str("name", "id", "title")
            if (name.isBlank()) return@mapNotNull null
            PluginRow(name, p.str("description", "summary", "hooks").take(240), p.bool("enabled", true))
        }
    }

    fun extensions(): List<ExtensionRow> {
        val o = parse("/api/extensions/status").asObj()
        val arr = o.arr("extensions") ?: o.arr("installed") ?: o.arr("items") ?: JsonArray(emptyList())
        return arr.mapNotNull { el ->
            val p = el.asObjOrNull() ?: return@mapNotNull null
            val id = p.str("id", "name")
            if (id.isBlank()) return@mapNotNull null
            ExtensionRow(id, p.str("name", "title").ifBlank { id }, p.bool("enabled") || p.bool("active"), p.str("description", "summary").take(240))
        }
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
            // schedule can be an object {kind, expr, display} — never show raw JSON
            val schedObj = j.obj("schedule")
            val schedule = j.str("schedule_display")
                .ifBlank { schedObj?.str("display").orEmpty() }
                .ifBlank { schedObj?.str("expr").orEmpty() }
                .ifBlank { if (schedObj == null) j.str("schedule") else "" }
            CronJob(
                id = j.str("id", "job_id"),
                name = j.str("name", "id", "job_id").ifBlank { "job" },
                schedule = schedule,
                enabled = j.bool("enabled", default = true),
                paused = j.str("state").equals("paused", true) || j.bool("paused") || j["paused_at"].let { it != null && it !is JsonNull },
                prompt = j.str("prompt"),
                lastStatus = j.str("last_status", "status", "last_error"),
                lastRun = j.anyToString("last_run", "last_run_at"),
                nextRun = j.anyToString("next_run_at", "next_run"),
                owner = j.str("owner_profile", "profile"),
                readOnly = j.bool("read_only"),
                deliver = j.str("deliver").ifBlank { "local" },
            )
        }.filter { it.id.isNotBlank() }
    }

    fun createCron(name: String, schedule: String, prompt: String) {
        val parts = mutableListOf("\"schedule\":${q(schedule)}", "\"prompt\":${q(prompt)}")
        if (name.isNotBlank()) parts += "\"name\":${q(name)}"
        postRaw("/api/crons/create", "{" + parts.joinToString(",") + "}")
    }

    fun deleteCron(jobId: String) {
        postRaw("/api/crons/delete", """{"job_id":${q(jobId)}}""")
    }

    fun cronAction(jobId: String, action: String) {
        postRaw("/api/crons/$action", """{"job_id":${q(jobId)}}""")
    }

    fun cronOutput(jobId: String): String {
        return runCatching { getRaw("/api/crons/output?job_id=${enc(jobId)}").take(12000) }.getOrDefault("")
    }

    fun cronHistory(jobId: String): List<CronRun> {
        val o = runCatching { parse("/api/crons/history?job_id=${enc(jobId)}&limit=50").asObj() }.getOrNull() ?: return emptyList()
        return (o.arr("runs") ?: JsonArray(emptyList())).mapNotNull { el ->
            val r = el.asObjOrNull() ?: return@mapNotNull null
            val name = r.str("filename", "name")
            if (name.isBlank()) return@mapNotNull null
            CronRun(name, r.int("size"), r.anyToString("modified"))
        }
    }

    fun updateCron(jobId: String, name: String, schedule: String, prompt: String, deliver: String) {
        val parts = mutableListOf("\"job_id\":${q(jobId)}", "\"schedule\":${q(schedule)}")
        if (name.isNotBlank()) parts += "\"name\":${q(name)}"
        if (prompt.isNotBlank()) parts += "\"prompt\":${q(prompt)}"
        if (deliver.isNotBlank()) parts += "\"deliver\":${q(deliver)}"
        postRaw("/api/crons/update", "{" + parts.joinToString(",") + "}")
    }

    fun kanban(
        board: String = "",
        assignee: String = "",
        tenant: String = "",
        includeArchived: Boolean = false,
        onlyMine: Boolean = false,
    ): List<KanbanColumn> {
        val o = parse("/api/kanban/board${kanbanQs(board, assignee, tenant, includeArchived, onlyMine)}").asObj()
        val cols = o.arr("columns") ?: JsonArray(emptyList())
        return cols.map { col ->
            val c = col.asObj()
            KanbanColumn(
                name = c.str("name", "id", "title").ifBlank { "column" },
                tasks = (c.arr("tasks") ?: JsonArray(emptyList())).mapNotNull { t -> parseKanbanTask(t.asObjOrNull(), c.str("name")) },
            )
        }
    }

    fun kanbanBoards(): Pair<String, List<KanbanBoardMeta>> {
        val o = parse("/api/kanban/boards").asObj()
        val current = o.str("current", "active")
        val list = (o.arr("boards") ?: JsonArray(emptyList())).mapNotNull { el ->
            val b = el.asObjOrNull() ?: return@mapNotNull null
            val slug = b.str("slug", "id", "name")
            if (slug.isBlank()) return@mapNotNull null
            KanbanBoardMeta(
                slug = slug,
                name = b.str("name", "title").ifBlank { slug },
                total = b.int("total"),
                current = b.bool("is_current") || slug == current,
            )
        }
        val cur = list.firstOrNull { it.current }?.slug ?: current
        return cur to list
    }

    fun kanbanStats(board: String): KanbanStats {
        val o = runCatching { parse("/api/kanban/stats${kanbanQs(board)}").asObj() }.getOrNull() ?: return KanbanStats()
        fun mapOf(key: String): Map<String, Int> {
            val obj = o.obj(key) ?: return emptyMap()
            return obj.keys.associateWith { obj.int(it) }
        }
        return KanbanStats(mapOf("by_status"), mapOf("by_assignee"))
    }

    fun kanbanAssignees(board: String): List<String> {
        val o = runCatching { parse("/api/kanban/assignees${kanbanQs(board)}").asObj() }.getOrNull() ?: return emptyList()
        return (o.arr("assignees") ?: JsonArray(emptyList())).mapNotNull {
            it.primitiveOrNull()?.takeIf { s -> s.isNotBlank() }
        }
    }

    fun switchKanbanBoard(slug: String) {
        postRaw("/api/kanban/boards/${enc(slug)}/switch", "{}")
    }

    fun createKanbanBoard(name: String, slug: String) {
        postRaw(
            "/api/kanban/boards",
            """{"slug":${q(slug)},"name":${q(name)},"description":"","icon":"","color":"","switch":true}""",
        )
    }

    fun bulkKanban(ids: List<String>, status: String, board: String) {
        if (ids.isEmpty()) return
        val body = """{"ids":[${ids.joinToString(",") { q(it) }}],"status":${q(status)}}"""
        postRaw("/api/kanban/tasks/bulk${kanbanQs(board)}", body)
    }

    fun createKanbanTask(title: String, board: String) {
        postRaw("/api/kanban/tasks${kanbanQs(board)}", """{"title":${q(title)}}""")
    }

    fun dispatchKanban(board: String, dryRun: Boolean) {
        val extra = if (dryRun) "dry_run=1" else ""
        val qs = kanbanQs(board).let { if (it.isEmpty()) if (extra.isEmpty()) "" else "?$extra" else if (extra.isEmpty()) it else "$it&$extra" }
        postRaw("/api/kanban/dispatch$qs", "{}")
    }

    fun moveKanban(id: String, status: String, board: String = "") {
        patchRaw("/api/kanban/tasks/${enc(id)}${kanbanQs(board)}", """{"status":${q(status)}}""")
    }

    private fun parseKanbanTask(o: JsonObject?, fallbackStatus: String): KanbanTask? {
        if (o == null) return null
        val id = o.str("id", "task_id")
        if (id.isBlank()) return null
        val comments = o.int("comment_count")
        return KanbanTask(
            id = id,
            title = o.str("title", "name", "summary", "id").ifBlank { "task" },
            status = o.str("status").ifBlank { fallbackStatus },
            assignee = o.str("assignee"),
            priority = o.anyToString("priority"),
            body = o.str("body", "description", "prompt"),
            tenant = o.str("tenant"),
            comments = comments,
        )
    }

    private fun kanbanQs(
        board: String = "",
        assignee: String = "",
        tenant: String = "",
        includeArchived: Boolean = false,
        onlyMine: Boolean = false,
    ): String {
        val parts = mutableListOf<String>()
        if (board.isNotBlank()) parts += "board=${enc(board)}"
        if (assignee.isNotBlank()) parts += "assignee=${enc(assignee)}"
        if (tenant.isNotBlank()) parts += "tenant=${enc(tenant)}"
        if (includeArchived) parts += "include_archived=1"
        if (onlyMine) parts += "only_mine=1"
        return if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
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

    fun createProfile(name: String) {
        postRaw("/api/profile/create", """{"name":${q(name)}}""")
    }

    fun deleteProfile(name: String) {
        postRaw("/api/profile/delete", """{"name":${q(name)}}""")
    }

    fun addWorkspace(path: String) {
        postRaw("/api/workspaces/add", """{"path":${q(path)}}""")
    }

    fun removeWorkspace(path: String) {
        postRaw("/api/workspaces/remove", """{"path":${q(path)}}""")
    }

    fun renameWorkspace(path: String, name: String) {
        postRaw("/api/workspaces/rename", """{"path":${q(path)},"name":${q(name)}}""")
    }

    fun saveSkill(name: String, category: String, content: String) {
        val parts = mutableListOf("\"name\":${q(name)}", "\"content\":${q(content)}")
        if (category.isNotBlank()) parts += "\"category\":${q(category)}"
        postRaw("/api/skills/save", "{" + parts.joinToString(",") + "}")
    }

    fun deleteSkill(name: String) {
        postRaw("/api/skills/delete", """{"name":${q(name)}}""")
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

    fun insights(days: Int = 30): Insights {
        val o = parse("/api/insights?days=$days").asObj()
        val models = (o.arr("models") ?: JsonArray(emptyList())).mapNotNull { el ->
            val m = el.asObjOrNull() ?: return@mapNotNull null
            InsightModel(
                m.str("model"), m.int("sessions"), m.int("total_tokens"), m.double("cost"),
                cacheHitPct = m.doubleOrNull("cache_hit_percent"),
                costShare = m.doubleOrNull("cost_share"),
            )
        }
        val skillsUsage = runCatching {
            val su = parse("/api/skills/usage?days=$days").asObj().obj("usage") ?: JsonObject(emptyMap())
            su.entries.mapNotNull { (name, v) ->
                val u = v.asObjOrNull() ?: return@mapNotNull null
                SkillUsage(name, u.int("use_count"), u.int("view_count"), u.int("patch_count"))
            }.sortedByDescending { it.uses }.take(20)
        }.getOrDefault(emptyList())
        val hit = o.obj("total_cache_hit_percent")?.double("value")
            ?: o.doubleOrNull("total_cache_hit_percent")
        return Insights(
            days = o.int("period_days").takeIf { it > 0 } ?: days,
            sessions = o.int("total_sessions"),
            messages = o.int("total_messages"),
            tokens = o.int("total_tokens"),
            cost = o.double("total_cost"),
            cacheHit = hit,
            models = models,
            skills = skillsUsage,
        )
    }

    fun logs(file: String = "agent", tail: Int = 200): List<String> {
        val o = parse("/api/logs?file=${enc(file)}&tail=$tail").asObj()
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

    fun costConfig(dashBase: String): CostConfig {
        val root = dashBase.trim().trimEnd('/')
        if (root.isBlank()) throw IllegalStateException("Set the Hermes Console URL (:8790)")
        val req = Request.Builder().url("$root/api/cost-config").header("Accept", "application/json").build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("Console ${resp.code}")
            return json.decodeFromString(CostConfig.serializer(), body)
        }
    }

    fun saveCostConfig(dashBase: String, body: String) {
        val root = dashBase.trim().trimEnd('/')
        if (root.isBlank()) throw IllegalStateException("Set the Hermes Console URL (:8790)")
        val req = Request.Builder()
            .url("$root/api/cost-config")
            .header("Accept", "application/json")
            .post(body.toRequestBody(media))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("Console ${resp.code}: ${text.take(200)}")
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

    fun reasoning(model: String, provider: String): ReasoningStatus {
        val qs = buildString {
            append("/api/reasoning")
            val parts = mutableListOf<String>()
            if (model.isNotBlank()) parts += "model=${enc(model)}"
            if (provider.isNotBlank()) parts += "provider=${enc(provider)}"
            if (parts.isNotEmpty()) append("?").append(parts.joinToString("&"))
        }
        return parseReasoning(parse(qs).asObj())
    }

    fun setReasoning(effort: String, model: String, provider: String): ReasoningStatus {
        val parts = mutableListOf("\"effort\":${q(effort)}")
        if (model.isNotBlank()) parts += "\"model\":${q(model)}"
        if (provider.isNotBlank()) parts += "\"provider\":${q(provider)}"
        val el = json.parseToJsonElement(exec(req("POST", "/api/reasoning", "{" + parts.joinToString(",") + "}"))).asObj()
        return parseReasoning(el)
    }

    private fun parseReasoning(o: JsonObject): ReasoningStatus {
        val efforts = (o.arr("supported_efforts") ?: JsonArray(emptyList())).mapNotNull { el ->
            (el as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }
        return ReasoningStatus(
            effort = o.str("reasoning_effort", "effort"),
            supported = efforts,
            showToggle = o.bool("supports_thinking_toggle", true) || efforts.isNotEmpty(),
        )
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

    fun createFile(sid: String, path: String) {
        postRaw("/api/file/create", """{"session_id":${q(sid)},"path":${q(path)},"content":""}""")
    }

    fun createDir(sid: String, path: String) {
        postRaw("/api/file/create-dir", """{"session_id":${q(sid)},"path":${q(path)}}""")
    }

    fun deleteFile(sid: String, path: String, recursive: Boolean = true) {
        postRaw("/api/file/delete", """{"session_id":${q(sid)},"path":${q(path)},"recursive":$recursive}""")
    }

    fun renameFile(sid: String, path: String, newName: String) {
        postRaw("/api/file/rename", """{"session_id":${q(sid)},"path":${q(path)},"new_name":${q(newName)}}""")
    }

    fun moveFile(sid: String, path: String, destDir: String) {
        postRaw("/api/file/move", """{"session_id":${q(sid)},"path":${q(path)},"dest_dir":${q(destDir)}}""")
    }

    fun retrySession(sid: String) {
        postRaw("/api/session/retry", """{"session_id":${q(sid)}}""")
    }

    fun undoSession(sid: String) {
        postRaw("/api/session/undo", """{"session_id":${q(sid)}}""")
    }

    fun regenerateTitle(sid: String): String {
        val el = json.parseToJsonElement(postRaw("/api/session/title/regenerate", """{"session_id":${q(sid)}}""")).asObj()
        return el.str("title").ifBlank { el.obj("session")?.str("title").orEmpty() }
    }

    fun yoloStatus(sid: String): Boolean {
        val el = parse("/api/session/yolo?session_id=${enc(sid)}").asObj()
        return el.bool("yolo_enabled")
    }

    fun setYolo(sid: String, enabled: Boolean): Boolean {
        val el = json.parseToJsonElement(postRaw("/api/session/yolo", """{"session_id":${q(sid)},"enabled":$enabled}""")).asObj()
        return if ("yolo_enabled" in el) el.bool("yolo_enabled") else enabled
    }

    fun commands(): List<SlashCommand> {
        val o = runCatching { parse("/api/commands").asObj() }.getOrNull() ?: return emptyList()
        return (o.arr("commands") ?: JsonArray(emptyList())).mapNotNull { el ->
            val c = el.asObjOrNull() ?: return@mapNotNull null
            val name = c.str("name")
            if (name.isBlank()) return@mapNotNull null
            SlashCommand(
                name = name,
                description = c.str("description"),
                category = c.str("category"),
                argsHint = c.str("args_hint"),
                cliOnly = c.bool("cli_only"),
            )
        }
    }

    fun execCommand(command: String): String {
        val el = json.parseToJsonElement(postRaw("/api/commands/exec", """{"command":${q(command)}}""")).asObj()
        return el.str("output").ifBlank { el.str("error", "message") }
    }

    fun logout() {
        runCatching { postRaw("/api/auth/logout", "{}") }
    }

    fun updatesCheck(): UpdatesStatus {
        val el = json.parseToJsonElement(postRaw("/api/updates/check", """{"force":true}""")).asObj()
        fun target(key: String): UpdateTarget {
            val t = el.obj(key) ?: return UpdateTarget(key)
            return UpdateTarget(
                name = t.str("name").ifBlank { key },
                behind = t.int("behind"),
                current = t.str("current_version", "current_sha"),
                latest = t.str("latest_version", "latest_sha"),
                dirty = t.bool("dirty"),
            )
        }
        return UpdatesStatus(webui = target("webui"), agent = target("agent"), checkedAt = el.str("checked_at"))
    }

    fun updatesApply(target: String): String {
        val el = json.parseToJsonElement(postRaw("/api/updates/apply", """{"target":${q(target)}}""")).asObj()
        return if (el.bool("ok") || el.str("ok") == "true") "ok" else el.str("error", "message").ifBlank { "apply failed" }
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

    fun resizeTerminal(sid: String, rows: Int, cols: Int) {
        postRaw("/api/terminal/resize", """{"session_id":${q(sid)},"rows":$rows,"cols":$cols}""")
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
    val count = JsonText.sessionCount(intOrNull("messages"), intOrNull("message_count"))
    return SessionRow(
        sid = sid,
        title = str("title"),
        preview = str("preview", "snippet", "last_message"),
        msgCount = count,
        source = str("source"),
        model = str("model"),
        pinned = bool("pinned"),
        archived = bool("archived"),
        projectId = str("project_id"),
    )
}

private fun JsonObject.toChatRows(): List<ChatMsg> {
    val role = str("role").ifBlank { "assistant" }
    val id = str("id").ifBlank { "${role}-${contentHash()}" }
    val content = JsonText.value(this["content"]).ifBlank { JsonText.value(this["text"]) }
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

private fun JsonObject.intOrNull(key: String): Int? {
    val v = this[key] as? JsonPrimitive ?: return null
    return v.intOrNull ?: v.longOrNull?.toInt() ?: v.contentOrNull?.toIntOrNull()
}

private fun JsonObject.contentHash(): String =
    (JsonText.value(this["content"]) + roleHint()).hashCode().toString()

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
