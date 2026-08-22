package com.hermes.webui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CostEditor(vm: AppVm) {
    val cfg = vm.costConfig.value
    val plans = remember(cfg) {
        mutableStateListOf<CostPlan>().also { it.addAll(cfg?.subscriptions ?: emptyList()) }
    }
    val rates = remember(cfg) {
        mutableStateListOf<CostModelRate>().also { it.addAll(cfg?.models ?: emptyList()) }
    }
    var newName by remember { mutableStateOf("") }
    var newPrice by remember { mutableStateOf("") }
    var newCovers by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("YOUR PLANS", color = Wui.Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
        Text(
            "What you actually pay each month. The Console then shows that next to the estimated pay-as-you-go API cost.",
            color = Wui.Muted,
            fontSize = 12.sp,
        )
        if (plans.isEmpty()) Text("No subscriptions yet.", color = Wui.Muted, fontSize = 12.sp)
        plans.forEachIndexed { i, p ->
            Column(
                Modifier.fillMaxWidth().background(Wui.Surface, WuiShapeMd).border(1.dp, Wui.Border, WuiShapeMd).padding(12.dp),
            ) {
                OutlinedTextField(p.name, { plans[i] = p.copy(name = it) }, label = { Text("Plan name") }, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        p.price_usd.toString(),
                        { plans[i] = p.copy(price_usd = it.toDoubleOrNull() ?: 0.0) },
                        label = { Text("USD") },
                        colors = fieldColors(),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (p.cycle == "yearly") "yearly" else "monthly",
                        color = Wui.Accent,
                        modifier = Modifier.padding(top = 20.dp).clickable {
                            plans[i] = p.copy(cycle = if (p.cycle == "yearly") "monthly" else "yearly")
                        },
                    )
                }
                OutlinedTextField(
                    p.covers_providers.joinToString(", "),
                    { plans[i] = p.copy(covers_providers = it.split(',').map { s -> s.trim() }.filter { s -> s.isNotEmpty() }) },
                    label = { Text("Covers providers (xai-oauth, anthropic…)") },
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Remove", color = Wui.Danger, modifier = Modifier.padding(top = 6.dp).clickable { plans.removeAt(i) })
            }
        }
        OutlinedTextField(newName, { newName = it }, label = { Text("New plan name") }, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(newPrice, { newPrice = it }, label = { Text("USD / month") }, colors = fieldColors(), modifier = Modifier.weight(1f))
            OutlinedTextField(newCovers, { newCovers = it }, label = { Text("providers") }, colors = fieldColors(), modifier = Modifier.weight(1f))
        }
        Row {
            Text(
                "+ Add plan",
                color = Wui.Accent,
                modifier = Modifier.clickable {
                    val n = newName.trim()
                    if (n.isEmpty()) return@clickable
                    plans.add(
                        CostPlan(
                            name = n,
                            price_usd = newPrice.toDoubleOrNull() ?: 0.0,
                            cycle = "monthly",
                            covers_providers = newCovers.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                        ),
                    )
                    newName = ""; newPrice = ""; newCovers = ""
                },
            )
            Spacer(Modifier.width(16.dp))
            Text("Save plans", color = Wui.Accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { vm.saveCostPlans(plans.toList()) })
        }

        Spacer(Modifier.height(8.dp))
        Text("MODEL API RATES", color = Wui.Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
        Text(
            "USD per 1M tokens. Used to estimate what this usage would cost on the public API.",
            color = Wui.Muted,
            fontSize = 12.sp,
        )
        rates.forEachIndexed { i, m ->
            Column(
                Modifier.fillMaxWidth().background(Wui.Surface, WuiShapeMd).border(1.dp, Wui.Border, WuiShapeMd).padding(12.dp),
            ) {
                Text(m.id, color = Wui.Text, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(
                        m.provider.takeIf { it.isNotBlank() },
                        if (m.requests > 0) "${m.requests} requests" else "catalog",
                        if (m.has_rate) "priced" else "no rate yet",
                    ).joinToString(" · "),
                    color = Wui.Muted,
                    fontSize = 11.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RateField("in", m.input) { rates[i] = m.copy(input = it) }
                    RateField("out", m.output) { rates[i] = m.copy(output = it) }
                    RateField("cache", m.cache_read) { rates[i] = m.copy(cache_read = it) }
                }
            }
        }
        Text("Save rates", color = Wui.Accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { vm.saveCostRates(rates.toList()) })
    }
}

@Composable
private fun RateField(label: String, value: Double, on: (Double) -> Unit) {
    OutlinedTextField(
        if (value == 0.0) "" else value.toString(),
        { on(it.toDoubleOrNull() ?: 0.0) },
        label = { Text(label) },
        colors = fieldColors(),
        modifier = Modifier.width(92.dp),
    )
}
