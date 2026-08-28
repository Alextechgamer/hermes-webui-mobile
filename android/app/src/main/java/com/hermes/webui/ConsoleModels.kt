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
    val by_provider: List<ConsoleProvider> = emptyList(),
    val models: List<ConsoleModel> = emptyList(),
    val feed: List<ConsoleFeed> = emptyList(),
    val spend_by_day: List<ConsoleDay> = emptyList(),
    val inflight: List<ConsoleInflight> = emptyList(),
    val inflight_basis: String = "",
    val subscriptions: ConsoleSubs = ConsoleSubs(),
    val sessions: List<ConsoleSession> = emptyList(),
)

@Serializable
data class CostConfig(
    val subscriptions: List<CostPlan> = emptyList(),
    val models: List<CostModelRate> = emptyList(),
    val providers: List<String> = emptyList(),
)

@Serializable
data class CostPlan(
    val name: String = "",
    val price_usd: Double = 0.0,
    val cycle: String = "monthly",
    val note: String = "",
    val covers_providers: List<String> = emptyList(),
)

@Serializable
data class CostModelRate(
    val id: String = "",
    val provider: String = "",
    val requests: Long = 0,
    val input: Double = 0.0,
    val output: Double = 0.0,
    val cache_read: Double = 0.0,
    val cache_write: Double = 0.0,
    val has_rate: Boolean = false,
)

@Serializable
data class ConsoleTotals(
    val requests: Long = 0,
    val buckets: Long = 0,
    val input_tokens: Long = 0,
    val output_tokens: Long = 0,
    val cache_read_tokens: Long = 0,
    val cache_write_tokens: Long = 0,
    val reasoning_tokens: Long = 0,
    val tokens_total: Long = 0,
    val prompt_tokens: Long = 0,
    val est_cost: Double = 0.0,
    val act_cost: Double = 0.0,
    val sessions: Long = 0,
    val cost_basis: String = "estimated",
)

@Serializable
data class ConsoleBurn(
    val mtd_spend: Double = 0.0,
    val mtd_actual: Double = 0.0,
    val today_spend: Double = 0.0,
    val avg_daily_pace: Double = 0.0,
    val projected_eom: Double = 0.0,
    val day_of_month: Int = 0,
    val days_in_month: Int = 0,
    val month_label: String = "",
    val monthly_cap: Double? = null,
    val cost_basis: String = "estimated",
)

@Serializable
data class ConsoleProvider(
    val provider: String = "",
    val requests: Long = 0,
    val tokens: Long = 0,
    val est_cost: Double = 0.0,
    val act_cost: Double = 0.0,
    val statuses: List<String> = emptyList(),
    val unpriced: Boolean = false,
)

@Serializable
data class ConsoleModel(
    val model: String = "",
    val provider: String = "",
    val requests: Long = 0,
    val tokens: Long = 0,
    val est_cost: Double = 0.0,
    /** Desktop 27 Aug 2026: tokens burned but no public rate (OAuth routes etc.). */
    val unpriced: Boolean = false,
    val share_pct: Double = 0.0,
)

@Serializable
data class ConsoleDay(
    val date: String = "",
    val requests: Long = 0,
    val est_cost: Double = 0.0,
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
    val cache_write: Long = 0,
    val prompt_in: Long = 0,
    val tokens_total: Long = 0,
    val cache_pct: Int = 0,
    val reasoning: Long = 0,
    val est_cost: Double = 0.0,
    val act_cost: Double = 0.0,
    val cost_status: String = "",
    val cost_source: String = "",
    val age_s: Double = 0.0,
    val live: Boolean = false,
)

@Serializable
data class ConsoleInflight(
    val source: String = "",
    val session_short: String = "",
    val model: String? = null,
    val provider: String? = null,
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
    val total_metered_on_plans_mtd: Double = 0.0,
    val payg_metered_mtd: Double = 0.0,
    val payg_providers: List<ConsolePayg> = emptyList(),
    val actual_out_of_pocket_month: Double = 0.0,
    val all_metered_equivalent_mtd: Double = 0.0,
    val month_label: String = "",
)

@Serializable
data class ConsolePayg(
    val provider: String = "",
    val est: Double = 0.0,
    val calls: Long = 0,
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
data class ConsoleSession(
    val id: String = "",
    val short: String = "",
    val source: String = "",
    val model: String = "",
    val provider: String = "",
    val messages: Long = 0,
    val calls: Long = 0,
    val est_cost: Double = 0.0,
    val act_cost: Double = 0.0,
    val title: String = "",
    val last_activity_at: Double? = null,
)

object ConsoleFmt {
    /** Desktop console model-dot palette (MDOT). */
    val MDOT = listOf(0xFFFFB020, 0xFF6FB1FF, 0xFF57C98A, 0xFFC792EA, 0xFFFF8F2E, 0xFF7BD88F, 0xFFEC6A9C, 0xFF4BD2C9)

    fun money(v: Double, digits: Int = 2): String = "$" + "%,.${digits}f".format(v)
    fun tok(n: Long): String = when {
        n >= 1_000_000_000 -> "%.1fB".format(n / 1_000_000_000.0)
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1fK".format(n / 1_000.0)
        else -> n.toString()
    }
    fun int(n: Long): String = "%,d".format(n)
    /** Matches desktop console ago(): <5s just now, then s/m/h/d. */
    fun age(s: Double): String {
        val t = kotlin.math.max(0.0, s).toInt()
        return when {
            t < 5 -> "just now"
            t < 60 -> "${t}s ago"
            t < 3600 -> "${t / 60}m ago"
            t < 86400 -> "${t / 3600}h ago"
            else -> "${t / 86400}d ago"
        }
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
