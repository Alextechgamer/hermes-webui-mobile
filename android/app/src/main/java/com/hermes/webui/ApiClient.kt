package com.hermes.webui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

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

    private fun req(method: String, path: String, body: String? = null): Request {
        val b = Request.Builder().url("$root$path")
        if (csrf.isNotEmpty()) b.header("X-Hermes-CSRF-Token", csrf)
        if (body != null) b.method(method, body.toRequestBody(media))
        else b.method(method, null)
        return b.build()
    }

    private fun exec(r: Request): String {
        http.newCall(r).execute().use { resp ->
            val t = resp.body?.string().orEmpty()
            if (resp.code == 401) throw AuthException()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${t.take(200)}")
            return t
        }
    }

    fun authStatus(): AuthStatus = json.decodeFromString(exec(req("GET", "/api/auth/status")))

    private fun q(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(c)
        }
        append('"')
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
        val t = exec(req("GET", "/api/sessions"))
        val el = json.parseToJsonElement(t)
        val arr = when {
            el is kotlinx.serialization.json.JsonArray -> el
            el is JsonObject && el["sessions"] != null -> el["sessions"]!!.jsonArray
            el is JsonObject && el["data"] != null -> el["data"]!!.jsonArray
            else -> return emptyList()
        }
        return arr.map { json.decodeFromJsonElement(SessionRow.serializer(), it) }
    }

    fun messages(sid: String): List<ChatMsg> {
        val t = exec(req("GET", "/api/session?session_id=$sid"))
        val el = json.parseToJsonElement(t)
        val arr = when {
            el is JsonObject && el["messages"] != null -> el["messages"]!!.jsonArray
            el is JsonObject && el["session"] is JsonObject &&
                el["session"]!!.jsonObject["messages"] != null ->
                el["session"]!!.jsonObject["messages"]!!.jsonArray
            else -> return emptyList()
        }
        return arr.map { json.decodeFromJsonElement(ChatMsg.serializer(), it) }
    }

    fun newSession(): String {
        val t = exec(req("POST", "/api/session/new", "{}"))
        val el = json.parseToJsonElement(t)
        if (el is JsonObject) {
            el["session_id"]?.jsonPrimitive?.content?.let { if (it.isNotBlank()) return it }
            el["session"]?.jsonObject?.get("session_id")?.jsonPrimitive?.content
                ?.let { if (it.isNotBlank()) return it }
        }
        return ""
    }

    fun startChat(sid: String, message: String): ChatStart {
        val body = """{"session_id":${q(sid)},"message":${q(message)}}"""
        return json.decodeFromString(exec(req("POST", "/api/chat/start", body)))
    }

    fun stream(streamId: String, onEvent: (String, String) -> Unit, onClosed: () -> Unit): EventSource {
        val r = Request.Builder()
            .url("$root/api/chat/stream?stream_id=$streamId")
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

    fun settingsRaw(): String = exec(req("GET", "/api/settings"))

    fun saveSettings(body: String) { exec(req("POST", "/api/settings", body)) }

    fun getRaw(path: String): String = exec(req("GET", path))

    fun postRaw(path: String, body: String = "{}"): String = exec(req("POST", path, body))

    fun getJson(path: String): JsonElement = json.parseToJsonElement(getRaw(path))

    fun cancelChat(sid: String) {
        runCatching { postRaw("/api/chat/cancel", """{"session_id":${q(sid)}}""") }
    }

    fun namedList(path: String, arrayKey: String, titleKeys: List<String>, subKeys: List<String> = emptyList()): List<NamedRow> {
        return try {
            val el = getJson(path)
            val arr: JsonArray = when {
                el is JsonArray -> el
                el is JsonObject && el[arrayKey] is JsonArray -> el[arrayKey]!!.jsonArray
                else -> return emptyList()
            }
            arr.mapNotNull { item ->
                when (item) {
                    is JsonObject -> {
                        fun pick(keys: List<String>): String {
                            for (k in keys) {
                                val v = item[k]?.jsonPrimitive?.content
                                if (!v.isNullOrBlank()) return v
                            }
                            return ""
                        }
                        val title = pick(titleKeys).ifBlank { item.keys.firstOrNull() ?: "" }
                        NamedRow(title, pick(subKeys), pick(listOf("id", "job_id", "name", "path", "session_id")))
                    }
                    else -> {
                        val s = item.jsonPrimitive.content
                        if (s.isBlank()) null else NamedRow(s)
                    }
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun prettyJson(path: String): String {
        return try {
            val el = getJson(path)
            json.encodeToString(JsonElement.serializer(), el)
        } catch (e: Exception) { e.message ?: "error" }
    }

    val hostRoot: String get() = root
}

class AuthException : RuntimeException("auth required")
