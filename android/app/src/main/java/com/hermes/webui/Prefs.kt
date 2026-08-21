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

    val isConfigured: Boolean get() = baseUrl.isNotBlank()
}
