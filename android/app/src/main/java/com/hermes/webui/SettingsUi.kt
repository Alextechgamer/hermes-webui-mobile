package com.hermes.webui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun SettingItem.section(): SettingsSection {
    val k = key.lowercase()
    return when {
        listOf("theme", "skin", "font", "accent", "density", "rtl", "appearance").any { k.contains(it) } -> SettingsSection.Appearance
        listOf("provider", "api_key", "openrouter", "openai", "anthropic").any { k.contains(it) } -> SettingsSection.Providers
        listOf("password", "auth", "port", "update", "max_token", "check_for", "version").any { k.contains(it) } -> SettingsSection.System
        listOf(
            "sidebar", "session", "tool", "think", "mermaid", "message_mode", "conversation",
            "transcript", "stream", "compact", "pin",
        ).any { k.contains(it) } -> SettingsSection.Conversation
        else -> SettingsSection.Preferences
    }
}

@Composable
fun SettingsPane(vm: AppVm) {
    val section = vm.settingsSection.value
    Row(Modifier.fillMaxSize().background(Wui.Bg)) {
        Column(
            Modifier
                .width(168.dp)
                .fillMaxSize()
                .background(Wui.Sidebar)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            Text("SETTINGS", color = Wui.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp, modifier = Modifier.padding(16.dp, 8.dp))
            SettingsSection.entries.forEach { s ->
                val sel = s == section
                Text(
                    s.label,
                    color = if (sel) Wui.Accent else Wui.Text,
                    fontSize = 14.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 1.dp)
                        .clip(WuiShapeMd)
                        .background(if (sel) Wui.AccentBg else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { vm.settingsSection.value = s }
                        .padding(12.dp, 10.dp),
                )
            }
        }
        Column(Modifier.weight(1f).fillMaxSize()) {
            ErrLine(vm)
            when (section) {
                SettingsSection.Providers -> ProvidersSettings(vm)
                SettingsSection.Plugins -> PluginsSettings(vm)
                SettingsSection.Extensions -> ExtensionsSettings(vm)
                SettingsSection.Help -> HelpSettings()
                SettingsSection.System -> SystemSettings(vm)
                else -> KeySettings(vm, section)
            }
        }
    }
}

@Composable
private fun KeySettings(vm: AppVm, section: SettingsSection) {
    val items = vm.settingsItems.filter { it.section() == section }
    Column(Modifier.fillMaxSize()) {
        Text(section.label, color = Wui.Text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.padding(16.dp, 14.dp, 16.dp, 4.dp))
        Text(
            when (section) {
                SettingsSection.Conversation -> "Transcript, tools, and how this chat behaves."
                SettingsSection.Appearance -> "Theme, accent, and visual style."
                SettingsSection.Preferences -> "Defaults and UI behavior."
                else -> "Same keys as desktop WebUI. Secrets stay hidden."
            },
            color = Wui.Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 8.dp),
        )
        if (items.isEmpty()) {
            Text("No ${section.label.lowercase()} keys on this server.", color = Wui.Muted, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.weight(1f)) {
            items(items, key = { it.key }) { item -> SettingRow(vm, item) }
        }
        if (vm.settingEdits.value.isNotEmpty()) {
            TextButton(onClick = { vm.saveSettings() }, modifier = Modifier.padding(12.dp)) {
                Text("Save ${vm.settingEdits.value.size} changes", color = Wui.Accent)
            }
        }
    }
}

@Composable
private fun SettingRow(vm: AppVm, item: SettingItem) {
    val current = vm.settingEdits.value[item.key] ?: item.value
    Column(Modifier.padding(16.dp, 8.dp)) {
        Text(item.key, color = Wui.AccentText, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        if (item.type == "bool") {
            Switch(
                checked = current == "true",
                onCheckedChange = { vm.editSetting(item.key, if (it) "true" else "false") },
                colors = SwitchDefaults.colors(checkedThumbColor = Wui.Bg, checkedTrackColor = Wui.Accent),
            )
        } else if (item.type != "json") {
            OutlinedTextField(current, { vm.editSetting(item.key, it) }, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
        } else {
            Text(item.value, color = Wui.Muted, fontSize = 12.sp)
        }
    }
    HorizontalDivider(color = Wui.Border)
}

@Composable
private fun ProvidersSettings(vm: AppVm) {
    var editing by remember { mutableStateOf<String?>(null) }
    var key by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Providers", color = Wui.Text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Text("API keys for AI providers. Same as desktop Settings → Providers.", color = Wui.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        if (vm.providers.isEmpty()) Text("No providers returned.", color = Wui.Muted)
        vm.providers.forEach { p ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(WuiShapeMd)
                    .background(Wui.Surface)
                    .border(1.dp, Wui.Border, WuiShapeMd)
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.displayName, color = Wui.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(if (p.hasKey) "configured" else "no key", color = if (p.hasKey) Wui.Ok else Wui.Muted, fontSize = 12.sp)
                }
                Text(p.id + if (p.keySource.isNotBlank()) " · ${p.keySource}" else "", color = Wui.Muted, fontSize = 11.sp)
                if (p.configurable) {
                    if (editing == p.id) {
                        OutlinedTextField(key, { key = it }, label = { Text("API key") }, colors = fieldColors(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        Row {
                            TextButton(onClick = { vm.setProviderKey(p.id, key); editing = null; key = "" }) { Text("Save", color = Wui.Accent) }
                            TextButton(onClick = { editing = null; key = "" }) { Text("Cancel", color = Wui.Muted) }
                        }
                    } else {
                        Text("Set key", color = Wui.Accent, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp).clickable { editing = p.id; key = "" })
                    }
                }
            }
        }
        val leftover = vm.settingsItems.filter { it.section() == SettingsSection.Providers }
        leftover.forEach { SettingRow(vm, it) }
        if (vm.settingEdits.value.isNotEmpty()) {
            TextButton(onClick = { vm.saveSettings() }) { Text("Save setting changes", color = Wui.Accent) }
        }
    }
}

@Composable
private fun PluginsSettings(vm: AppVm) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Plugins", color = Wui.Text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Text("Installed Hermes plugins. Read-only, same as desktop.", color = Wui.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        if (vm.plugins.isEmpty()) Text("No plugins installed.", color = Wui.Muted)
        vm.plugins.forEach { p ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(WuiShapeMd).background(Wui.Surface).padding(12.dp)) {
                Text(p.name, color = Wui.Text, fontWeight = FontWeight.SemiBold)
                if (p.description.isNotBlank()) Text(p.description, color = Wui.Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ExtensionsSettings(vm: AppVm) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Extensions", color = Wui.Text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Text("WebUI extensions on this instance.", color = Wui.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        if (vm.extensions.isEmpty()) Text("No extensions installed.", color = Wui.Muted)
        vm.extensions.forEach { e ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(WuiShapeMd).background(Wui.Surface).padding(12.dp)) {
                Row {
                    Text(e.name, color = Wui.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(if (e.enabled) "on" else "off", color = if (e.enabled) Wui.Ok else Wui.Muted, fontSize = 12.sp)
                }
                if (e.description.isNotBlank()) Text(e.description, color = Wui.Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SystemSettings(vm: AppVm) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("System", color = Wui.Text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Text("Instance access and Hermes Console.", color = Wui.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        Row(
            Modifier.fillMaxWidth().clip(WuiShapeMd).background(Wui.Surface).clickable { vm.go(Panel.Console) }.padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Hermes Console", color = Wui.Text, fontWeight = FontWeight.SemiBold)
                Text("Usage, burn, live streams — :8790 /api/usage", color = Wui.Muted, fontSize = 12.sp)
            }
            Text("Open", color = Wui.Accent)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().clip(WuiShapeMd).background(Wui.Surface).clickable { vm.go(Panel.Terminal) }.padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Terminal", color = Wui.Text, fontWeight = FontWeight.SemiBold)
            Text("Open", color = Wui.Accent)
        }
        Spacer(Modifier.height(12.dp))
        vm.settingsItems.filter { it.section() == SettingsSection.System }.forEach { SettingRow(vm, it) }
        if (vm.settingEdits.value.isNotEmpty()) {
            TextButton(onClick = { vm.saveSettings() }) { Text("Save ${vm.settingEdits.value.size} changes", color = Wui.Accent) }
        }
    }
}

@Composable
private fun HelpSettings() {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Help", color = Wui.Text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Text("Native client of hermes-webui. Not a WebView.", color = Wui.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        Text("Docs: github.com/NousResearch/hermes-webui", color = Wui.AccentText, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
        Text("This app: github.com/Alextechgamer/hermes-webui-mobile", color = Wui.AccentText, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    }
}
