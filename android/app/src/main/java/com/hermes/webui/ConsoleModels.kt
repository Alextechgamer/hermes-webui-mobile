package com.hermes.webui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Hermes Console `GET /api/usage` (port 8790). Unknown fields ignored. */
@Serializable
data class ConsoleUsage(
    val connected: Boolean = false,
    val generated_at: Double = 0.0,
    val db_path: String = "",
    val notes: List<String> = emptyList(),
    val totals: ConsoleTotals = ConsoleTotals(),
    val burn: ConsoleBurn = ConsoleBurn(),
    val models: List<ConsoleModel> = emptyList(),
    val feed: List<ConsoleFeed> = emptyList(),
    val inflight: List<ConsoleInflight> = emptyList(),
    val inflight_basis: String = "",
    val subscriptions: ConsoleSubs = ConsoleSubs(),
    val sessions: List<ConsoleSessionHint> = emptyList(),
)

@Serializable
data class ConsoleTotals(
    val requests: Long = 0,
    val tokens_total: Long = 0,
    val est_cost: Double = 0.0,
    val sessions: Long = 0,
    val cost_basis: String = "estimated",
)

@Serializable
data class ConsoleBurn(
    val mtd_spend: Double = 0.0,
    val today_spend: Double = 0.0,
    val avg_daily_pace: Double = 0.0,
    val projected_eom: Double = 0.0,
    val day_of_month: Int = 0,
    val days_in_month: Int = 0,
    val month_label: String = "",
    val cost_basis: String = "estimated",
)

@Serializable
data class ConsoleModel(
    val model: String = "",
    val provider: String = "",
    val requests: Long = 0,
    val tokens: Long = 0,
    val est_cost: Double = 0.0,
    val share_pct: Double = 0.0,
)

@Serializable
data class ConsoleFeed(
    val session_short: String = "",
    val source: String = "",
    val title: String = "",
    val model: String = "",
    val provider: String = "",
    val task: String = "chat",
    val calls: Long = 0,
    val input: Long = 0,
    val output: Long = 0,
    val cache_read: Long = 0,
    val prompt_in: Long = 0,
    val tokens_total: Long = 0,
    val cache_pct: Int = 0,
    val reasoning: Long = 0,
    val est_cost: Double = 0.0,
    val cost_status: String = "",
    val age_s: Double = 0.0,
    val live: Boolean = false,
)

@Serializable
data class ConsoleInflight(
    val source: String = "",
    val session_short: String = "",
    val model: String? = null,
    val task: String? = null,
    val holder: String? = null,
    val calls: Long = 0,
    val prompt_in: Long = 0,
    val output: Long = 0,
    val cache_pct: Int = 0,
    val age_s: Double = 0.0,
)

@Serializable
data class ConsoleSubs(
    val items: List<ConsolePlan> = emptyList(),
    val total_flat_monthly: Double = 0.0,
    val payg_metered_mtd: Double = 0.0,
    val actual_out_of_pocket_month: Double = 0.0,
    val all_metered_equivalent_mtd: Double = 0.0,
    val month_label: String = "",
)

@Serializable
data class ConsolePlan(
    val name: String = "",
    val monthly_usd: Double = 0.0,
    val note: String = "",
    val metered_mtd: Double = 0.0,
    val metered_projected_eom: Double = 0.0,
    val calls_mtd: Long = 0,
    val all_priced: Boolean = true,
    val savings_vs_metered: Double = 0.0,
    @SerialName("breakeven_pct") val breakevenPct: Double? = null,
)

@Serializable
data class ConsoleSessionHint(
    val id: String = "",
    val title: String = "",
)

object ConsoleFmt {
    fun money(v: Double, digits: Int = 2): String = "$" + "%,.${digits}f".format(v)
    fun tok(n: Long): String = when {
        n >= 1_000_000_000 -> "%.1fB".format(n / 1_000_000_000.0)
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1fK".format(n / 1_000.0)
        else -> n.toString()
    }
    fun int(n: Long): String = "%,d".format(n)
    fun age(s: Double): String = when {
        s < 90 -> "just now"
        s < 3600 -> "${(s / 60).toInt()}m ago"
        s < 86400 -> "${(s / 3600).toInt()}h ago"
        else -> "${(s / 86400).toInt()}d ago"
    }
    fun consoleBase(webui: String, override: String): String {
        val o = override.trim().trimEnd('/')
        if (o.isNotBlank()) return o
        val base = webui.trim().trimEnd('/')
        if (base.isBlank()) return ""
        return if (Regex(":\\d+$").containsMatchIn(base)) base.replace(Regex(":\\d+$"), ":8790")
        else "$base:8790"
    }
}
