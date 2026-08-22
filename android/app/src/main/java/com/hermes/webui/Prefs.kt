package com.hermes.webui

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class Prefs(ctx: Context) {
    private val prefs: SharedPreferences = try {
        val mk = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "hermes_webui_prefs", mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (_: Exception) {
        ctx.getSharedPreferences("hermes_webui_prefs_plain", Context.MODE_PRIVATE)
    }

    var baseUrl: String
        get() = prefs.getString("url", "") ?: ""
        set(v) { prefs.edit().putString("url", v.trim()).apply() }

    /** Optional Hermes Dashboard URL (official :9119 or usage dashboard). Empty = derive from WebUI host:9119 */
    var dashboardUrl: String
        get() = prefs.getString("dashboard_url", "") ?: ""
        set(v) { prefs.edit().putString("dashboard_url", v.trim()).apply() }

    var lastSid: String
        get() = prefs.getString("last_sid", "") ?: ""
        set(v) { prefs.edit().putString("last_sid", v).apply() }

    var lastStreamId: String
        get() = prefs.getString("last_stream_id", "") ?: ""
        set(v) { prefs.edit().putString("last_stream_id", v).apply() }

    var chatsExpanded: Boolean
        get() = prefs.getBoolean("chats_expanded", false)
        set(v) { prefs.edit().putBoolean("chats_expanded", v).apply() }

    /** Encrypted. Used only to re-login when the session cookie is gone. */
    var password: String
        get() = prefs.getString("password", "") ?: ""
        set(v) { prefs.edit().putString("password", v).apply() }

    var cookiesJson: String
        get() = prefs.getString("cookies_json", "[]") ?: "[]"
        set(v) { prefs.edit().putString("cookies_json", v).apply() }

    val isConfigured: Boolean get() = baseUrl.isNotBlank()
}
