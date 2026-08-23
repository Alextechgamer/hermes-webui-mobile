package com.hermes.webui

/**
 * Provider model ids as the upstream API expects them.
 *
 * Desktop WebUI always POSTs `model` + `model_provider` on /api/chat/start.
 * Without the provider, Hermes can treat a Grok id like a Claude id and
 * rewrite `grok-4.6` → `grok-4-6`, which xAI 404s.
 *
 * xAI chat ids use a version *dot* (`grok-4.6`, `grok-4.20`). A stale
 * catalog hyphen (`grok-4-6`) is repaired here. Claude / Anthropic ids
 * are left hyphenated.
 */
object ModelIds {
    private val versionDash = Regex("""(\d)-(\d)""")

    fun forSend(id: String, provider: String = ""): String {
        val raw = id.trim()
        if (raw.isEmpty() || !isGrokFamily(raw, provider)) return raw
        val slash = raw.lastIndexOf('/')
        val prefix = if (slash >= 0) raw.substring(0, slash + 1) else ""
        var bare = if (slash >= 0) raw.substring(slash + 1) else raw
        var next = versionDash.replace(bare) { m -> "${m.groupValues[1]}.${m.groupValues[2]}" }
        var guard = 0
        while (next != bare && guard++ < 4) {
            bare = next
            next = versionDash.replace(bare) { m -> "${m.groupValues[1]}.${m.groupValues[2]}" }
        }
        return prefix + next
    }

    fun isGrokFamily(id: String, provider: String = ""): Boolean {
        val p = provider.trim().lowercase()
        if (p.contains("xai") || p == "x-ai") return true
        val bare = id.substringAfterLast('/').lowercase()
        return bare.startsWith("grok")
    }
}
