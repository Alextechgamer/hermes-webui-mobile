package com.hermes.webui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * 1:1 native port of the Hermes Console desktop dashboard (:8790).
 * Same cards, same order, same copy: topbar + KPI strip, Live Usage
 * (scope seg, in-flight streams, request feed), Monthly Burn, Real Cost
 * vs Metered, Usage Overview, Popular Models, notes bar, and the
 * Detailed-usage overlay with the five tables. Auto-refresh every 4s.
 */
@Composable
fun DashboardPane(vm: AppVm) {
    val u = vm.console.value
    val t = u?.totals
    val burn = u?.burn
    val sub = u?.subscriptions
    var auto by remember { mutableStateOf(true) }
    var scope by remember { mutableStateOf("all") }
    var detailOpen by remember { mutableStateOf(false) }

    LaunchedEffect(auto) {
        while (auto) {
            delay(4000)
            vm.loadConsole()
        }
    }

    if (detailOpen && u != null) {
        DetailOverlay(u) { detailOpen = false }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize().background(Wui.Bg).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ---- Topbar: brand + subline + conn/auto/refresh pills ----
        item {
            Column(Modifier.padding(top = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text("HERMES ", color = Wui.Text, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.2.sp)
                            Text("CONSOLE", color = Wui.Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.2.sp)
                        }
                        Text(
                            listOfNotNull(
                                u?.db_path?.substringAfterLast('/')?.ifBlank { null },
                                t?.sessions?.takeIf { it > 0 }?.let { "$it sessions" },
                            ).joinToString(" · ").ifBlank { "usage desk :8790" },
                            color = Wui.Muted,
                            fontSize = 11.sp,
                        )
                    }
                    Pill(if (u?.connected == true) "● Connected" else "● Offline", on = u?.connected == true, danger = u?.connected != true)
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(if (auto) "⟳ Auto-refresh" else "⏸ Paused", on = auto, onClick = { auto = !auto })
                    Pill("↻ Refresh", onClick = { vm.loadConsole() })
                    Pill("▤ Detailed usage", onClick = { detailOpen = true })
                }
            }
        }
        vm.consoleError.value?.let {
            item { Text(it, color = Wui.Danger, fontSize = 12.sp) }
        }
        // ---- KPI strip ----
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Kpi("REQUESTS MTD", t?.let { ConsoleFmt.int(it.requests) } ?: "—", Modifier.weight(1f))
                Kpi("TOKENS", t?.let { ConsoleFmt.tok(it.tokens_total) } ?: "—", Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Kpi("EST. BURN MTD", burn?.let { ConsoleFmt.money(it.mtd_spend) } ?: "$—", Modifier.weight(1f), gold = true)
                Kpi("OUT-OF-POCKET", sub?.let { ConsoleFmt.money(it.actual_out_of_pocket_month) } ?: "$—", Modifier.weight(1f))
            }
        }
        // ---- Live Usage card ----
        item {
            ConsoleCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Live Usage", color = Wui.Text, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Pill("● LIVE", on = true)
                    Spacer(Modifier.weight(1f))
                    // scope segment: All sources | WebUI (desktop #scopeSeg)
                    SegBtn("All sources", scope == "all") { scope = "all" }
                    Spacer(Modifier.width(4.dp))
                    SegBtn("WebUI", scope == "webui") { scope = "webui" }
                }
                Text(
                    "IN-FLIGHT STREAMS · ${(u?.inflight_basis ?: "").uppercase().ifBlank { "—" }}",
                    color = Wui.Muted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                )
                val live = (u?.inflight ?: emptyList())
                if (live.isEmpty()) {
                    // desktop idle stream row
                    Column(Modifier.padding(bottom = 4.dp)) {
                        Text("No active stream", color = Wui.Text, fontWeight = FontWeight.SemiBold)
                        Text("idle. nothing in flight right now", color = Wui.Muted, fontSize = 11.sp)
                    }
                } else {
                    live.forEach { row ->
                        val feed = u?.feed?.firstOrNull { it.session_short == row.session_short && it.live }
                            ?: u?.feed?.firstOrNull { it.session_short == row.session_short }
                        val model = row.model ?: feed?.model ?: "model"
                        val task = row.task ?: feed?.task ?: "chat"
                        val provider = row.provider ?: feed?.provider ?: ""
                        Column(Modifier.padding(bottom = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(model, color = Wui.Text, fontWeight = FontWeight.SemiBold)
                                    Text("$provider · $task", color = Wui.Muted, fontSize = 11.sp)
                                }
                                Text("LIVE", color = Wui.Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            val elapsed = if (row.age_s < 1) "<1s" else "${row.age_s.toInt()}s"
                            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Mini("⏱ LAST TOUCHED", elapsed, Modifier.weight(1f))
                                Mini("⚡ REQUESTS", if (row.calls > 0) ConsoleFmt.int(row.calls) else (feed?.calls?.let { ConsoleFmt.int(it) } ?: "—"), Modifier.weight(1f))
                            }
                            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Mini("◆ PROMPT TOKENS", ConsoleFmt.tok(if (row.prompt_in > 0) row.prompt_in else feed?.prompt_in ?: 0), Modifier.weight(1f))
                                Mini("▣ OUTPUT TOKENS", ConsoleFmt.tok(if (row.output > 0) row.output else feed?.output ?: 0), Modifier.weight(1f))
                            }
                            Text(
                                "${row.session_short.ifBlank { feed?.session_short.orEmpty() }} · ${row.source.ifBlank { "lease" }} · cache ${feed?.cache_pct ?: row.cache_pct}%",
                                color = Wui.Muted,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
                // ---- Request feed (desktop #feedHost, scoped, 40 rows) ----
                val rows = (u?.feed ?: emptyList())
                    .let { if (scope == "webui") it.filter { r -> r.source == "webui" } else it }
                    .take(40)
                if (rows.isEmpty()) {
                    Text("No rows for this scope.", color = Wui.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
                rows.forEach { r -> FeedRow(r) }
            }
        }
        // ---- Monthly Burn Rate ----
        item {
            ConsoleCard {
                Text("Monthly Burn Rate", color = Wui.Text, fontWeight = FontWeight.SemiBold)
                Text(
                    if (burn?.cost_basis == "actual") "MTD Spend (actual)" else "MTD Spend (estimated)",
                    color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp),
                )
                Text(burn?.let { ConsoleFmt.money(it.mtd_spend) } ?: "$—", color = Wui.Accent, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Today", color = Wui.Muted, fontSize = 11.sp)
                        Text(burn?.let { ConsoleFmt.money(it.today_spend) } ?: "$—", color = Wui.Text, fontFamily = FontFamily.Monospace)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Average daily pace", color = Wui.Muted, fontSize = 11.sp)
                        Text(burn?.let { ConsoleFmt.money(it.avg_daily_pace) } ?: "$—", color = Wui.Text, fontFamily = FontFamily.Monospace)
                    }
                }
                Text("Projected EOM", color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(burn?.let { ConsoleFmt.money(it.projected_eom) } ?: "$—", color = Wui.Text, fontSize = 20.sp, fontFamily = FontFamily.Monospace)
                    Text(" ↗", color = Wui.Accent, fontSize = 14.sp)
                }
                Text(
                    burn?.monthly_cap?.let { "Cap " + ConsoleFmt.money(it) } ?: "No monthly cap set",
                    color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
                )
                val frac = if (burn != null && burn.days_in_month > 0) burn.day_of_month / burn.days_in_month.toFloat() else 0f
                Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(RoundedCornerShape(4.dp)).background(Wui.Border)) {
                    Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(6.dp).background(Wui.Accent))
                }
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        burn?.let { "Day ${it.day_of_month} of ${it.days_in_month} · ${it.month_label.ifBlank { "this month" }}" } ?: "",
                        color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f),
                    )
                    Text(burn?.cost_basis ?: "", color = Wui.Accent, fontSize = 10.sp)
                }
            }
        }
        // ---- Real Cost vs Metered ----
        item {
            ConsoleCard {
                Text("Real Cost vs Metered", color = Wui.Text, fontWeight = FontWeight.SemiBold)
                Text(
                    "Actual out-of-pocket · ${sub?.month_label?.ifBlank { "this month" } ?: "this month"}",
                    color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
                )
                Text(sub?.let { ConsoleFmt.money(it.actual_out_of_pocket_month) } ?: "$—", color = Wui.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Flat subscriptions", color = Wui.Muted, fontSize = 11.sp)
                        Text(sub?.let { ConsoleFmt.money(it.total_flat_monthly) } ?: "$—", color = Wui.Text, fontFamily = FontFamily.Monospace)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Pay-as-you-go", color = Wui.Muted, fontSize = 11.sp)
                        Text(
                            sub?.let { ConsoleFmt.money(it.payg_metered_mtd, if (it.payg_metered_mtd < 1) 4 else 2) } ?: "$—",
                            color = Wui.Text, fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                Text("Value used (metered-equiv, est.)", color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sub?.let { ConsoleFmt.money(it.all_metered_equivalent_mtd) } ?: "$—", color = Wui.Text, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
                    Text(" MTD", color = Wui.Muted, fontSize = 11.sp)
                }
                // desktop #meteredNote
                if (sub != null) {
                    val delta = sub.all_metered_equivalent_mtd - sub.total_flat_monthly
                    val anyPartial = sub.items.any { !it.all_priced && it.calls_mtd > 0 }
                    Text(
                        "You pay ${ConsoleFmt.money(sub.total_flat_monthly)} flat; same usage metered ≈ ${ConsoleFmt.money(sub.all_metered_equivalent_mtd)} so far. " +
                            (if (delta >= 0) "${ConsoleFmt.money(delta)} of value beyond what you pay"
                            else "${ConsoleFmt.money(kotlin.math.abs(delta))} under breakeven") +
                            (if (anyPartial) " [partial]" else ""),
                        color = if (delta >= 0) Wui.Ok else Wui.Danger,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                sub?.items.orEmpty().forEach { plan ->
                    val noData = plan.calls_mtd == 0L
                    Column(Modifier.padding(top = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(plan.name, color = Wui.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (plan.note.isNotBlank()) {
                                Text(plan.note, color = Wui.Muted, fontSize = 10.sp, modifier = Modifier.padding(end = 6.dp))
                            }
                            Text(ConsoleFmt.money(plan.monthly_usd) + "/mo", color = Wui.Text, fontFamily = FontFamily.Monospace)
                        }
                        Text(
                            if (noData) "no usage this month"
                            else ConsoleFmt.money(plan.metered_mtd, if (plan.metered_mtd < 1) 4 else 2) +
                                " metered-equiv MTD" + (if (plan.all_priced) "" else " [partial]") +
                                " · proj EOM " + ConsoleFmt.money(plan.metered_projected_eom) +
                                " · " + ConsoleFmt.int(plan.calls_mtd) + " calls",
                            color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
                        )
                        val pctRaw = plan.breakevenPct ?: 0.0
                        val over = pctRaw > 100.0
                        val pct = (pctRaw / 100.0).toFloat().coerceIn(0f, 1f)
                        Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(5.dp).clip(RoundedCornerShape(4.dp)).background(Wui.Border)) {
                            Box(Modifier.fillMaxWidth(pct).height(5.dp).background(if (over) Wui.Danger else Wui.Accent))
                        }
                        Text(
                            if (noData) "metered at API rates from model_pricing.json"
                            else (if (plan.savings_vs_metered >= 0)
                                "+${ConsoleFmt.money(plan.savings_vs_metered)} value over the ${ConsoleFmt.money(plan.monthly_usd)} plan"
                            else "${ConsoleFmt.money(kotlin.math.abs(plan.savings_vs_metered))} to breakeven") +
                                " · ${pctRaw.toInt()}% of breakeven" +
                                (if (plan.all_priced) "" else " (some usage has no public rate)"),
                            color = if (noData) Wui.Muted else if (plan.savings_vs_metered >= 0) Wui.Ok else Wui.Danger,
                            fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                // desktop payg subrow
                val payg = sub?.payg_providers.orEmpty()
                if (payg.isNotEmpty()) {
                    Column(Modifier.padding(top = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Pay-as-you-go", color = Wui.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(
                                ConsoleFmt.money(sub!!.payg_metered_mtd, if (sub.payg_metered_mtd < 1) 4 else 2) + " MTD",
                                color = Wui.Text, fontFamily = FontFamily.Monospace,
                            )
                        }
                        Text(
                            payg.joinToString(" · ") { "${it.provider} ${ConsoleFmt.money(it.est, if (it.est < 1) 4 else 2)}" } +
                                ": actually billed, not on a flat plan",
                            color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        // ---- Usage Overview ----
        item {
            ConsoleCard {
                Text("Usage Overview", color = Wui.Text, fontWeight = FontWeight.SemiBold)
                Text(t?.let { "${ConsoleFmt.int(it.requests)} requests" } ?: "—", color = Wui.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Mini("Tokens", t?.let { ConsoleFmt.tok(it.tokens_total) } ?: "—", Modifier.weight(1f))
                    Mini("Est. Cost", t?.let { ConsoleFmt.money(it.est_cost) } ?: "$—", Modifier.weight(1f), gold = true)
                }
                Text(
                    "Detailed usage →",
                    color = Wui.Accent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clip(WuiShapeSm)
                        .background(Wui.AccentBg)
                        .border(1.dp, Wui.AccentBgStrong, WuiShapeSm)
                        .clickable { detailOpen = true }
                        .padding(10.dp),
                )
            }
        }
        // ---- Popular Models (top 6, MDOT dots) ----
        item {
            ConsoleCard {
                Text("Popular Models", color = Wui.Text, fontWeight = FontWeight.SemiBold)
                (u?.models ?: emptyList()).take(6).forEachIndexed { i, m ->
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.width(8.dp).height(8.dp).clip(RoundedCornerShape(4.dp))
                                .background(Color(ConsoleFmt.MDOT[i % ConsoleFmt.MDOT.size])),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.model, color = Wui.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(m.provider, color = Wui.Muted, fontSize = 11.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${ConsoleFmt.int(m.requests)} req", color = Wui.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            Text("${m.share_pct}% · ${ConsoleFmt.tok(m.tokens)} tok", color = Wui.Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        // ---- Notes bar ----
        u?.notes?.takeIf { it.isNotEmpty() }?.let { notes ->
            item {
                Column {
                    notes.forEach { n -> Text("ⓘ $n", color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp)) }
                }
            }
        }
        item { CostEditor(vm) }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

/** Desktop request-feed row: l1 (code · sid · source · model · task · tok · cost badge · ago) + l2 breakdown. */
@Composable
private fun FeedRow(r: ConsoleFeed) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "200",
                color = Wui.Accent,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Wui.AccentBg).padding(6.dp, 2.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(r.session_short, color = Wui.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(6.dp))
            Text(r.source.ifBlank { "—" }, color = Wui.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                if (r.est_cost > 0) ConsoleFmt.money(r.est_cost, 4) else "$0.0000",
                color = if (r.est_cost > 0) Wui.Accent else Wui.Muted,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(4.dp))
            Badge(r.cost_status)
        }
        val title = r.title.take(42)
        Text(
            (if (title.isNotBlank()) "$title · " else "") + "${r.model} · ${r.task} · ${ConsoleFmt.tok(r.tokens_total)} tok · ${ConsoleFmt.age(r.age_s)}",
            color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp),
        )
        val nc = maxOf(0, r.input)
        Text(
            "tokens in ${ConsoleFmt.tok(r.prompt_in)} · cached ${ConsoleFmt.tok(r.cache_read)} · non-cached ${ConsoleFmt.tok(nc)} · out ${ConsoleFmt.tok(r.output)}" +
                (if (r.reasoning > 0) " · reason ${ConsoleFmt.tok(r.reasoning)}" else "") +
                " | cache ${r.cache_pct}% | calls ${ConsoleFmt.int(r.calls)}" +
                (if (r.cost_source.isNotBlank()) " | src ${r.cost_source}" else ""),
            color = Wui.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** est / actual / no-price badge (desktop .badge). */
@Composable
private fun Badge(status: String) {
    val (label, color) = when (status) {
        "actual" -> "actual" to Wui.Ok
        "estimated" -> "est" to Wui.Accent
        else -> "no price" to Wui.Muted
    }
    Text(
        label,
        color = color,
        fontSize = 9.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .padding(4.dp, 1.dp),
    )
}

/** Full-screen Detailed-usage overlay: KPI grid + the 5 desktop tables. */
@Composable
private fun DetailOverlay(u: ConsoleUsage, onClose: () -> Unit) {
    val t = u.totals
    LazyColumn(
        Modifier.fillMaxSize().background(Wui.Bg).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Detailed usage", color = Wui.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Pill("✕ Close", onClick = onClose)
            }
        }
        item {
            Column {
                listOf(
                    listOf("Requests" to ConsoleFmt.int(t.requests), "Tokens" to ConsoleFmt.tok(t.tokens_total)),
                    listOf("Input" to ConsoleFmt.tok(t.input_tokens), "Output" to ConsoleFmt.tok(t.output_tokens)),
                    listOf("Cache read" to ConsoleFmt.tok(t.cache_read_tokens), "Cache write" to ConsoleFmt.tok(t.cache_write_tokens)),
                    listOf("Reasoning" to ConsoleFmt.tok(t.reasoning_tokens), "Est. cost" to ConsoleFmt.money(t.est_cost)),
                    listOf("Sessions" to ConsoleFmt.int(t.sessions), "Buckets" to ConsoleFmt.int(t.buckets)),
                ).forEach { rowPair ->
                    Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowPair.forEach { (k, v) -> Kpi(k.uppercase(), v, Modifier.weight(1f)) }
                    }
                }
            }
        }
        // By provider
        item { DetailSection("By provider") }
        items(u.by_provider) { p ->
            ConsoleCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.provider, color = Wui.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Badge(if (p.unpriced) "" else if (p.statuses.contains("actual")) "actual" else "estimated")
                }
                Text(
                    "${ConsoleFmt.int(p.requests)} req · ${ConsoleFmt.tok(p.tokens)} tok · ${ConsoleFmt.money(p.est_cost)}",
                    color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        // Models
        item { DetailSection("Models") }
        items(u.models.size) { i ->
            val m = u.models[i]
            ConsoleCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.width(8.dp).height(8.dp).clip(RoundedCornerShape(4.dp))
                            .background(Color(ConsoleFmt.MDOT[i % ConsoleFmt.MDOT.size])),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.model, color = Wui.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(m.provider, color = Wui.Muted, fontSize = 11.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${ConsoleFmt.int(m.requests)} req · ${m.share_pct}%", color = Wui.Text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Text("${ConsoleFmt.tok(m.tokens)} tok · ${ConsoleFmt.money(m.est_cost)}", color = Wui.Muted, fontSize = 11.sp)
                    }
                }
            }
        }
        // Spend by day
        item { DetailSection("Spend by day") }
        items(u.spend_by_day) { d ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text(d.date, color = Wui.Text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                Text("${ConsoleFmt.int(d.requests)} req", color = Wui.Muted, fontSize = 12.sp)
                Spacer(Modifier.width(12.dp))
                Text(ConsoleFmt.money(d.est_cost), color = Wui.Accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
        // Recent sessions
        item { DetailSection("Recent sessions") }
        items(u.sessions) { s ->
            ConsoleCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.short.ifBlank { s.id }, color = Wui.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    Text(
                        if (s.act_cost > 0) ConsoleFmt.money(s.act_cost) else ConsoleFmt.money(s.est_cost),
                        color = Wui.Accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    )
                }
                if (s.title.isNotBlank()) {
                    Text(s.title.take(44), color = Wui.Text, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
                Text(
                    listOfNotNull(
                        s.source.ifBlank { null },
                        s.model.ifBlank { null },
                        "${ConsoleFmt.int(s.messages)} msgs",
                        "${ConsoleFmt.int(s.calls)} calls",
                        s.last_activity_at?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date((it * 1000).toLong())) },
                    ).joinToString(" · "),
                    color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        // Full usage buckets (sorted tokens desc), horizontally scrollable rows
        item { DetailSection("Full usage — every accounting bucket (${u.feed.size})") }
        items(u.feed.sortedByDescending { it.tokens_total }) { r ->
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 3.dp)) {
                Text(r.session_short, color = Wui.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text("  ${r.model} · ${r.task}", color = Wui.Text, fontSize = 11.sp)
                Text(
                    "  calls ${ConsoleFmt.int(r.calls)} · in ${ConsoleFmt.tok(r.input)} · cached ${ConsoleFmt.tok(r.cache_read)} · out ${ConsoleFmt.tok(r.output)}" +
                        " · reason ${if (r.reasoning > 0) ConsoleFmt.tok(r.reasoning) else "—"} · cache ${r.cache_pct}%" +
                        " · ${if (r.est_cost > 0) ConsoleFmt.money(r.est_cost, 4) else "$0.0000"} ${r.cost_status.ifBlank { "unknown" }}",
                    color = Wui.Muted, fontSize = 11.sp,
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun DetailSection(title: String) {
    Text(title.uppercase(), color = Wui.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Pill(label: String, on: Boolean = false, danger: Boolean = false, onClick: (() -> Unit)? = null) {
    val fg = if (danger) Wui.Danger else if (on) Wui.Accent else Wui.Muted
    Text(
        label,
        color = fg,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (on) Wui.AccentBg else Wui.Surface)
            .border(1.dp, if (on) Wui.AccentBgStrong else Wui.Border, RoundedCornerShape(999.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(10.dp, 5.dp),
    )
}

@Composable
private fun SegBtn(label: String, sel: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (sel) Wui.Accent else Wui.Muted,
        fontSize = 10.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (sel) Wui.AccentBg else Wui.Bg)
            .border(1.dp, if (sel) Wui.AccentBgStrong else Wui.Border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(8.dp, 4.dp),
    )
}

@Composable
private fun Kpi(label: String, value: String, modifier: Modifier = Modifier, gold: Boolean = false) {
    Column(
        modifier
            .clip(WuiShapeMd)
            .background(Wui.Surface)
            .border(1.dp, Wui.Border, WuiShapeMd)
            .padding(12.dp),
    ) {
        Text(label, color = Wui.Muted, fontSize = 10.sp, letterSpacing = 0.6.sp)
        Text(value, color = if (gold) Wui.Accent else Wui.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Mini(label: String, value: String, modifier: Modifier = Modifier, gold: Boolean = false) {
    Column(
        modifier.clip(WuiShapeSm).background(Wui.Bg).border(1.dp, Wui.Border, WuiShapeSm).padding(8.dp),
    ) {
        Text(label, color = Wui.Muted, fontSize = 9.sp)
        Text(value, color = if (gold) Wui.Accent else Wui.Text, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ConsoleCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(WuiShapeMd)
            .background(Wui.Surface)
            .border(1.dp, Wui.Border, WuiShapeMd)
            .padding(14.dp),
    ) { content() }
}
