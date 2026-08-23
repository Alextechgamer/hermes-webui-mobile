package com.hermes.webui

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class OfficialSession(
    val id: String,
    val title: String,
    val preview: String = "",
    val messages: Int = 0,
    val source: String = "",
    val model: String = "",
)

data class OfficialStatus(
    val version: String = "",
    val gateway: String = "",
    val sessions: Int = 0,
    val overall: String = "",
    val authRequired: Boolean = false,
)

data class OfficialMsg(
    val id: String,
    val role: String,
    val text: String,
)

@Serializable
private data class OffCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAt: Long,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
)

object OfficialUrl {
    fun base(webui: String, override: String): String {
        val o = override.trim().trimEnd('/')
        if (o.isNotBlank()) return o
        val raw = webui.trim()
        if (raw.isBlank()) return ""
        return try {
            val u = URI(raw)
            val host = u.host ?: return ""
            val scheme = (u.scheme ?: "http").ifBlank { "http" }
            "$scheme://$host:9119"
        } catch (_: Exception) {
            ""
        }
    }
}

class OfficialDashClient(private val prefs: Prefs) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jar = object : CookieJar {
        private val store = mutableListOf<Cookie>()
        init { load() }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(store) { store.toList() }
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(store) {
                cookies.forEach { c -> store.removeAll { it.name == c.name && it.matches(url) } }
                store.addAll(cookies)
                persist()
            }
        }
        private fun persist() {
            val recs = synchronized(store) {
                store.map {
                    OffCookie(it.name, it.value, it.domain, it.path, it.expiresAt, it.secure, it.httpOnly, it.hostOnly)
                }
            }
            prefs.officialCookies = json.encodeToString(recs)
        }
        private fun load() {
            runCatching {
                json.decodeFromString<List<OffCookie>>(prefs.officialCookies).forEach { r ->
                    val b = Cookie.Builder().name(r.name).value(r.value).path(r.path).expiresAt(r.expiresAt)
                    if (r.hostOnly) b.hostOnlyDomain(r.domain) else b.domain(r.domain)
                    if (r.secure) b.secure()
                    if (r.httpOnly) b.httpOnly()
                    store.add(b.build())
                }
            }
        }
    }
    private val http = OkHttpClient.Builder()
        .cookieJar(jar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val rpcIds = AtomicInteger(1)
    private var socket: WebSocket? = null

    var base: String = ""

    fun login(user: String, password: String, provider: String = ""): Boolean {
        val prov = provider.ifBlank { passwordProvider() }
        val body = """{"provider":${q(prov)},"username":${q(user)},"password":${q(password)},"next":"/chat"}"""
        val r = post("/auth/password-login", body)
        return r.contains("\"ok\"") && !r.contains("Invalid")
    }

    fun passwordProvider(): String {
        val raw = runCatching { get("/api/auth/providers") }.getOrDefault("")
        val arr = runCatching { json.parseToJsonElement(raw).jsonObject["providers"]?.jsonArray }.getOrNull()
        arr?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val pw = o["supports_password"]?.jsonPrimitive?.booleanOrNull == true
            if (pw) return o["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }
        return "basic"
    }

    fun status(): OfficialStatus {
        val o = parse("/api/status")
        val gw = o.obj("components")?.obj("gateway")
        return OfficialStatus(
            version = o.str("version"),
            gateway = gw?.str("state", "status") ?: o.str("gateway_state"),
            sessions = o.int("active_sessions"),
            overall = o.str("overall"),
            authRequired = o.bool("auth_required"),
        )
    }

    fun sessions(): List<OfficialSession> {
        val o = parse("/api/sessions?limit=40&order=recent&exclude_sources=cron")
        val arr = o.arr("sessions") ?: o.arr("items") ?: JsonArray(emptyList())
        return arr.mapNotNull { el ->
            val s = el as? JsonObject ?: return@mapNotNull null
            val id = s.str("id", "session_id")
            if (id.isBlank()) return@mapNotNull null
            OfficialSession(
                id = id,
                title = s.str("title", "name", "preview").ifBlank { id.take(8) },
                preview = s.str("preview", "last_message", "snippet"),
                messages = s.int("message_count", "messages"),
                source = s.str("source"),
                model = s.str("model"),
            )
        }
    }

    fun messages(id: String): List<OfficialMsg> {
        val o = parse("/api/sessions/${enc(id)}/messages?limit=200&order=latest")
        val arr = o.arr("messages") ?: JsonArray(emptyList())
        val rows = arr.mapNotNull { el ->
            val m = el as? JsonObject ?: return@mapNotNull null
            val role = m.str("role", "type")
            val text = flattenContent(m)
            if (text.isBlank() && role != "assistant") return@mapNotNull null
            OfficialMsg(m.str("id").ifBlank { text.hashCode().toString() }, role.ifBlank { "assistant" }, text)
        }
        return if (rows.size > 1 && rows.first().role == "assistant") rows.reversed() else rows
    }

    fun kanbanBoards(): Pair<String, List<KanbanBoardMeta>> {
        val o = parse("/api/plugins/kanban/boards")
        val current = o.str("current", "active")
        val list = (o.arr("boards") ?: JsonArray(emptyList())).mapNotNull { el ->
            val b = el as? JsonObject ?: return@mapNotNull null
            val slug = b.str("slug", "id", "name")
            if (slug.isBlank()) return@mapNotNull null
            KanbanBoardMeta(slug, b.str("name", "title").ifBlank { slug }, b.int("total"), b.bool("is_current") || slug == current)
        }
        return (list.firstOrNull { it.current }?.slug ?: current) to list
    }

    fun kanban(board: String): List<KanbanColumn> {
        val qs = if (board.isBlank()) "" else "?board=${enc(board)}"
        val o = parse("/api/plugins/kanban/board$qs")
        return (o.arr("columns") ?: JsonArray(emptyList())).map { col ->
            val c = col as JsonObject
            val name = c.str("name", "id").ifBlank { "column" }
            KanbanColumn(
                name = name,
                tasks = (c.arr("tasks") ?: JsonArray(emptyList())).mapNotNull { t ->
                    val o = t as? JsonObject ?: return@mapNotNull null
                    val id = o.str("id", "task_id")
                    if (id.isBlank()) return@mapNotNull null
                    KanbanTask(
                        id = id,
                        title = o.str("title", "name", "summary").ifBlank { id },
                        status = o.str("status").ifBlank { name },
                        assignee = o.str("assignee"),
                        priority = o.anyToString("priority"),
                        body = o.str("body", "description", "prompt", "latest_summary"),
                        tenant = o.str("tenant"),
                        comments = o.int("comment_count"),
                    )
                },
            )
        }
    }

    fun switchBoard(slug: String) {
        post("/api/plugins/kanban/boards/${enc(slug)}/switch", "{}")
    }

    fun createTask(title: String, board: String) {
        val qs = if (board.isBlank()) "" else "?board=${enc(board)}"
        post("/api/plugins/kanban/tasks$qs", """{"title":${q(title)}}""")
    }

    fun moveTask(id: String, status: String, board: String) {
        val qs = if (board.isBlank()) "" else "?board=${enc(board)}"
        patch("/api/plugins/kanban/tasks/${enc(id)}$qs", """{"status":${q(status)}}""")
    }

    fun wsTicket(): String {
        val o = parsePost("/api/auth/ws-ticket", "{}")
        return o.str("ticket")
    }

    fun openChat(onEvent: (String, JsonObject) -> Unit, onState: (String) -> Unit): WebSocket {
        socket?.close(1000, "replace")
        val ticket = runCatching { wsTicket() }.getOrDefault("")
        val httpBase = base.trimEnd('/')
        val wsBase = if (httpBase.startsWith("https")) "wss" + httpBase.removePrefix("https")
        else "ws" + httpBase.removePrefix("http")
        val url = if (ticket.isBlank()) "$wsBase/api/ws" else "$wsBase/api/ws?ticket=${enc(ticket)}"
        val req = Request.Builder().url(url).build()
        val ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                onState("live")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                text.split('\n').forEach { line ->
                    if (line.isBlank()) return@forEach
                    runCatching {
                        val o = json.parseToJsonElement(line).jsonObject
                        val method = o["method"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val params = o["params"] as? JsonObject ?: JsonObject(emptyMap())
                        val type = if (method == "event") params["type"]?.jsonPrimitive?.contentOrNull.orEmpty() else method
                        val payload = (params["payload"] as? JsonObject) ?: params
                        onEvent(type.ifBlank { method }, payload)
                    }
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onMessage(webSocket, bytes.utf8())
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                onState("closed")
                webSocket.close(1000, null)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                onState("error")
            }
        })
        socket = ws
        return ws
    }

    fun rpc(ws: WebSocket, method: String, params: Map<String, String> = emptyMap()): String {
        val id = "m${rpcIds.getAndIncrement()}"
        val p = if (params.isEmpty()) "{}" else params.entries.joinToString(",", "{", "}") { "\"${it.key}\":${q(it.value)}" }
        ws.send("""{"jsonrpc":"2.0","id":${q(id)},"method":${q(method)},"params":$p}""")
        return id
    }

    fun closeWs() {
        socket?.close(1000, "bye")
        socket = null
    }

    private fun flattenContent(m: JsonObject): String {
        val direct = m.str("content", "text", "message")
        if (direct.isNotBlank()) return direct
        val parts = m.arr("content") ?: m.arr("parts") ?: return ""
        return parts.joinToString("") { el ->
            val o = el as? JsonObject ?: return@joinToString (el as? JsonPrimitive)?.contentOrNull.orEmpty()
            o.str("text", "content", "value")
        }
    }

    private fun get(path: String): String {
        val req = Request.Builder().url(base.trimEnd('/') + path).get().build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (r.code == 401 || r.code == 403) throw AuthException()
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code} $path")
            return body
        }
    }

    private fun post(path: String, body: String): String {
        val req = Request.Builder()
            .url(base.trimEnd('/') + path)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 401) throw AuthException()
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code} $path ${text.take(120)}")
            return text
        }
    }

    private fun patch(path: String, body: String): String {
        val req = Request.Builder()
            .url(base.trimEnd('/') + path)
            .patch(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 401) throw AuthException()
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code} $path")
            return text
        }
    }

    private fun parse(path: String): JsonObject =
        json.parseToJsonElement(get(path)).jsonObject

    private fun parsePost(path: String, body: String): JsonObject =
        json.parseToJsonElement(post(path, body)).jsonObject

    private fun JsonObject.str(vararg keys: String): String {
        keys.forEach { k ->
            val v = this[k] ?: return@forEach
            val p = v as? JsonPrimitive
            val s = p?.contentOrNull ?: (v as? JsonObject)?.let { it.str("text", "content") }
            if (!s.isNullOrBlank()) return s
        }
        return ""
    }

    private fun JsonObject.int(vararg keys: String): Int {
        keys.forEach { k ->
            val v = this[k] as? JsonPrimitive ?: return@forEach
            v.intOrNull?.let { return it }
            v.contentOrNull?.toIntOrNull()?.let { return it }
        }
        return 0
    }

    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull == true

    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.anyToString(key: String): String {
        val v = this[key] ?: return ""
        val p = v as? JsonPrimitive ?: return ""
        return p.contentOrNull.orEmpty()
    }

    private fun q(s: String) = JsonPrimitive(s).toString()
    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
