package com.hermes.webui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DashboardPane(vm: AppVm) {
    val u = vm.console.value
    val t = u?.totals
    val burn = u?.burn
    val sub = u?.subscriptions
    LazyColumn(
        Modifier.fillMaxSize().background(Wui.Bg).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("HERMES CONSOLE", color = Wui.Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.2.sp)
                    Text(
                        listOfNotNull(
                            u?.db_path?.substringAfterLast('/')?.ifBlank { null },
                            t?.sessions?.takeIf { it > 0 }?.let { "$it sessions" },
                        ).joinToString(" · ").ifBlank { "usage desk :8790" },
                        color = Wui.Muted,
                        fontSize = 11.sp,
                    )
                }
                Text(
                    if (u?.connected == true) "Connected" else "Offline",
                    color = if (u?.connected == true) Wui.Accent else Wui.Danger,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Wui.AccentBg)
                        .border(1.dp, Wui.AccentBgStrong, RoundedCornerShape(999.dp))
                        .padding(10.dp, 5.dp),
                )
            }
        }
        vm.consoleError.value?.let {
            item { Text(it, color = Wui.Danger, fontSize = 12.sp) }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Kpi("REQUESTS", t?.let { ConsoleFmt.int(it.requests) } ?: "—", Modifier.weight(1f))
                Kpi("TOKENS", t?.let { ConsoleFmt.tok(it.tokens_total) } ?: "—", Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Kpi("BURN MTD", burn?.let { ConsoleFmt.money(it.mtd_spend) } ?: "$—", Modifier.weight(1f), gold = true)
                Kpi("OUT-OF-POCKET", sub?.let { ConsoleFmt.money(it.actual_out_of_pocket_month) } ?: "$—", Modifier.weight(1f), gold = true)
            }
        }
        item {
            ConsoleCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Live Usage", color = Wui.Text, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text("LIVE", color = Wui.Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    "IN-FLIGHT STREAMS · ${(u?.inflight_basis ?: "").uppercase().ifBlank { "NONE" }}",
                    color = Wui.Muted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                val live = (u?.inflight ?: emptyList())
                if (live.isEmpty()) {
                    Text("Idle — nothing in flight.", color = Wui.Muted, fontSize = 12.sp)
                } else {
                    live.forEach { row ->
                        val feed = u?.feed?.firstOrNull { it.session_short == row.session_short && it.live }
                            ?: u?.feed?.firstOrNull { it.session_short == row.session_short }
                        val model = row.model ?: feed?.model ?: "model"
                        val task = row.task ?: feed?.task ?: "chat"
                        Column(Modifier.padding(bottom = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(model, color = Wui.Text, fontWeight = FontWeight.SemiBold)
                                Text(" · $task", color = Wui.Muted, fontSize = 12.sp)
                                Spacer(Modifier.weight(1f))
                                Text("LIVE", color = Wui.Accent, fontSize = 10.sp)
                            }
                            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Mini("LAST TOUCHED", ConsoleFmt.age(row.age_s), Modifier.weight(1f))
                                Mini("REQUESTS", if (row.calls > 0) ConsoleFmt.int(row.calls) else (feed?.calls?.let { ConsoleFmt.int(it) } ?: "—"), Modifier.weight(1f))
                            }
                            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Mini("PROMPT TOKENS", ConsoleFmt.tok(if (row.prompt_in > 0) row.prompt_in else feed?.prompt_in ?: 0), Modifier.weight(1f))
                                Mini("OUTPUT TOKENS", ConsoleFmt.tok(if (row.output > 0) row.output else feed?.output ?: 0), Modifier.weight(1f))
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
            }
        }
        item {
            ConsoleCard {
                Text("Monthly Burn Rate", color = Wui.Text, fontWeight = FontWeight.SemiBold)
                Text("MTD Spend (estimated)", color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
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
                Text(burn?.let { ConsoleFmt.money(it.projected_eom) } ?: "$—", color = Wui.Text, fontSize = 20.sp, fontFamily = FontFamily.Monospace)
                val frac = if (burn != null && burn.days_in_month > 0) burn.day_of_month / burn.days_in_month.toFloat() else 0f
                Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(RoundedCornerShape(4.dp)).background(Wui.Border)) {
                    Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(6.dp).background(Wui.Accent))
                }
                Text(
                    burn?.let { "Day ${it.day_of_month} of ${it.days_in_month} · ${it.month_label.ifBlank { "this month" }}" } ?: "",
                    color = Wui.Muted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        item {
            ConsoleCard {
                Text("Real Cost vs Metered", color = Wui.Text, fontWeight = FontWeight.SemiBold)
                Text("Actual out-of-pocket · ${sub?.month_label.orEmpty()}", color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                Text(sub?.let { ConsoleFmt.money(it.actual_out_of_pocket_month) } ?: "$—", color = Wui.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Flat subscriptions", color = Wui.Muted, fontSize = 11.sp)
                        Text(sub?.let { ConsoleFmt.money(it.total_flat_monthly) } ?: "$—", color = Wui.Text, fontFamily = FontFamily.Monospace)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Pay-as-you-go", color = Wui.Muted, fontSize = 11.sp)
                        Text(sub?.let { ConsoleFmt.money(it.payg_metered_mtd) } ?: "$—", color = Wui.Text, fontFamily = FontFamily.Monospace)
                    }
                }
                Text("Value used (metered-equiv, est.)", color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
                Text(sub?.let { ConsoleFmt.money(it.all_metered_equivalent_mtd) } ?: "$—", color = Wui.Text, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
                sub?.items.orEmpty().forEach { plan ->
                    Column(Modifier.padding(top = 12.dp)) {
                        Row {
                            Text(plan.name, color = Wui.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(ConsoleFmt.money(plan.monthly_usd) + "/mo", color = Wui.Text, fontFamily = FontFamily.Monospace)
                        }
                        val pct = ((plan.breakevenPct ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
                        Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(5.dp).clip(RoundedCornerShape(4.dp)).background(Wui.Border)) {
                            Box(Modifier.fillMaxWidth(pct).height(5.dp).background(Wui.Accent))
                        }
                        Text(
                            "metered-equiv ${ConsoleFmt.money(plan.metered_mtd)} · ${plan.breakevenPct?.toInt() ?: 0}% of breakeven",
                            color = Wui.Muted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        item {
            ConsoleCard {
                Text("Usage Overview", color = Wui.Text, fontWeight = FontWeight.SemiBold)
                Text(t?.let { "${ConsoleFmt.int(it.requests)} requests" } ?: "—", color = Wui.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Mini("Tokens", t?.let { ConsoleFmt.tok(it.tokens_total) } ?: "—", Modifier.weight(1f))
                    Mini("Est. Cost", t?.let { ConsoleFmt.money(it.est_cost) } ?: "$—", Modifier.weight(1f), gold = true)
                }
            }
        }
        item { Text("Popular Models", color = Wui.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp)) }
        items(u?.models ?: emptyList()) { m ->
            ConsoleCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
        item { Text("Request feed", color = Wui.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp)) }
        items(u?.feed?.take(40) ?: emptyList()) { r ->
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
                    Text(r.source, color = Wui.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(if (r.est_cost > 0) ConsoleFmt.money(r.est_cost, 4) else "NO PRICE", color = if (r.est_cost > 0) Wui.Accent else Wui.Muted, fontSize = 11.sp)
                }
                Text(
                    "${r.model} · ${r.task} · ${ConsoleFmt.tok(r.tokens_total)} tok · cache ${r.cache_pct}% · ${ConsoleFmt.age(r.age_s)}",
                    color = Wui.Muted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        u?.notes?.takeIf { it.isNotEmpty() }?.let { notes ->
            item {
                Text(notes.joinToString("\n"), color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
        item {
            Text(
                "Refresh",
                color = Wui.Accent,
                modifier = Modifier.padding(bottom = 20.dp).clickable { vm.loadPanel(Panel.Dashboard) },
            )
        }
    }
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
