package com.hermes.webui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val KANBAN_NEXT = mapOf(
    "triage" to "todo",
    "todo" to "ready",
    "ready" to "blocked",
    "blocked" to "todo",
    "running" to "done",
    "done" to "todo",
)

@Composable
fun TasksPane(vm: AppVm) {
    var openId by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(Wui.Bg)) {
        ErrLine(vm)
        SectionLabel("Scheduled jobs — same /api/crons as the desktop Tasks tab")
        LazyColumn(Modifier.fillMaxSize()) {
            if (vm.jobs.isEmpty()) item { Text("No cron jobs.", color = Wui.Muted, modifier = Modifier.padding(20.dp)) }
            items(vm.jobs, key = { it.id }) { job ->
                Column(Modifier.fillMaxWidth().padding(16.dp, 10.dp)) {
                    Text(job.name, color = Wui.Text, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOf(job.schedule, if (job.paused) "paused" else if (job.enabled) "on" else "off", job.owner)
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        color = Wui.Muted,
                        fontSize = 12.sp,
                    )
                    if (job.lastStatus.isNotBlank() || job.lastRun.isNotBlank()) {
                        Text(listOf(job.lastStatus, job.lastRun).filter { it.isNotBlank() }.joinToString(" · "), color = Wui.Muted, fontSize = 12.sp)
                    }
                    if (!job.readOnly) {
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("Run", color = Wui.Accent, modifier = Modifier.clickable { vm.cronAction(job.id, "run") })
                            if (job.paused) Text("Resume", color = Wui.Muted, modifier = Modifier.clickable { vm.cronAction(job.id, "resume") })
                            else Text("Pause", color = Wui.Muted, modifier = Modifier.clickable { vm.cronAction(job.id, "pause") })
                            Text("Output", color = Wui.Muted, modifier = Modifier.clickable {
                                openId = job.id
                                vm.loadJobOutput(job.id)
                            })
                        }
                    }
                    if (openId == job.id && vm.jobOutput.value.isNotBlank()) {
                        Text(vm.jobOutput.value.take(4000), color = Wui.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                HorizontalDivider(color = Wui.Border)
            }
        }
    }
}

@Composable
fun KanbanPane(vm: AppVm) {
    Column(Modifier.fillMaxSize().background(Wui.Bg)) {
        ErrLine(vm)
        SectionLabel("Board columns — tap a card to advance status")
        Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (vm.columns.isEmpty()) Text("No board data.", color = Wui.Muted)
            vm.columns.forEach { col ->
                Column(
                    Modifier.width(240.dp).background(Wui.Surface, RoundedCornerShape(12.dp)).padding(10.dp),
                ) {
                    Text("${col.name} · ${col.tasks.size}", color = Wui.Accent, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    col.tasks.forEach { task ->
                        Column(
                            Modifier.fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .background(Wui.Bg, RoundedCornerShape(8.dp))
                                .clickable {
                                    val next = KANBAN_NEXT[task.status] ?: "todo"
                                    if (task.id.isNotBlank()) vm.moveTask(task.id, next)
                                }
                                .padding(8.dp),
                        ) {
                            Text(task.title, color = Wui.Text, fontSize = 13.sp)
                            val sub = listOf(task.assignee, task.priority).filter { it.isNotBlank() }.joinToString(" · ")
                            if (sub.isNotBlank()) Text(sub, color = Wui.Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SkillsPane(vm: AppVm) {
    var open by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(Wui.Bg)) {
        ErrLine(vm)
        SectionLabel("Installed skills — toggle writes the active profile")
        LazyColumn(Modifier.fillMaxSize()) {
            if (vm.skills.isEmpty()) item { Text("No skills found.", color = Wui.Muted, modifier = Modifier.padding(20.dp)) }
            items(vm.skills, key = { it.name }) { s ->
                Column(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable {
                            open = s.name
                            vm.openSkill(s.name)
                        }) {
                            Text(s.name, color = Wui.Text, fontWeight = FontWeight.SemiBold)
                            if (s.category.isNotBlank()) Text(s.category, color = Wui.Accent, fontSize = 11.sp)
                        }
                        Switch(
                            checked = !s.disabled,
                            onCheckedChange = { vm.toggleSkill(s) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Wui.Bg, checkedTrackColor = Wui.Accent),
                        )
                    }
                    if (s.description.isNotBlank()) Text(s.description, color = Wui.Muted, fontSize = 12.sp, maxLines = 3)
                    if (open == s.name && vm.skillBody.value.isNotBlank()) {
                        Text(vm.skillBody.value.take(6000), color = Wui.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                HorizontalDivider(color = Wui.Border)
            }
        }
    }
}

@Composable
fun MemoryPane(vm: AppVm) {
    val doc = vm.memory.value
    var tab by remember { mutableStateOf("memory") }
    val text = when (tab) {
        "user" -> doc.user
        "soul" -> doc.soul
        "project" -> doc.project
        else -> doc.memory
    }
    var draft by remember(tab, text) { mutableStateOf(text) }
    Column(Modifier.fillMaxSize().background(Wui.Bg)) {
        ErrLine(vm)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("memory" to "MEMORY", "user" to "USER", "soul" to "SOUL", "project" to (doc.projectName.ifBlank { "Project" })).forEach { (k, label) ->
                Text(
                    label,
                    color = if (tab == k) Wui.Accent else Wui.Muted,
                    modifier = Modifier
                        .background(Wui.Surface, RoundedCornerShape(8.dp))
                        .clickable { tab = k }
                        .padding(10.dp, 6.dp),
                )
            }
        }
        OutlinedTextField(
            draft,
            { draft = it },
            colors = fieldColors(),
            modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp),
        )
        if (tab != "project") {
            TextButton(onClick = { vm.saveMemory(tab, draft) }, modifier = Modifier.padding(12.dp)) {
                Text("Save $tab", color = Wui.Accent)
            }
        }
    }
}

@Composable
fun SpacesPane(vm: AppVm) {
    Column(Modifier.fillMaxSize().background(Wui.Bg)) {
        ErrLine(vm)
        SectionLabel("Workspaces known to this WebUI")
        LazyColumn {
            if (vm.spaces.isEmpty()) item { Text("No spaces.", color = Wui.Muted, modifier = Modifier.padding(20.dp)) }
            items(vm.spaces, key = { it.path.ifBlank { it.name } }) { s ->
                Column(Modifier.fillMaxWidth().padding(16.dp, 10.dp)) {
                    Text(s.name + if (s.last) " · last" else "", color = if (s.last) Wui.Accent else Wui.Text)
                    if (s.path.isNotBlank()) Text(s.path, color = Wui.Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                HorizontalDivider(color = Wui.Border)
            }
        }
    }
}

@Composable
fun ProfilesPane(vm: AppVm) {
    Column(Modifier.fillMaxSize().background(Wui.Bg)) {
        ErrLine(vm)
        SectionLabel("Active profile: ${vm.activeProfile.value.ifBlank { "default" }}")
        LazyColumn {
            if (vm.profiles.isEmpty()) item { Text("No profiles.", color = Wui.Muted, modifier = Modifier.padding(20.dp)) }
            items(vm.profiles, key = { it.name }) { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.switchProfile(p.name) }.padding(16.dp, 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = if (p.active) Wui.Accent else Wui.Text, fontWeight = FontWeight.SemiBold)
                        if (p.model.isNotBlank()) Text(p.model, color = Wui.Muted, fontSize = 12.sp)
                    }
                    if (p.active) Text("active", color = Wui.Accent, fontSize = 12.sp)
                }
                HorizontalDivider(color = Wui.Border)
            }
        }
    }
}

@Composable
fun TodosPane(vm: AppVm) {
    Column(Modifier.fillMaxSize().background(Wui.Bg)) {
        ErrLine(vm)
        SectionLabel("Current task list from this conversation (todo tool / SSE)")
        LazyColumn {
            if (vm.todos.isEmpty()) item {
                Text("No todos in this session. They appear when the agent uses the todo tool.", color = Wui.Muted, modifier = Modifier.padding(20.dp))
            }
            items(vm.todos, key = { it.id }) { t ->
                Row(Modifier.fillMaxWidth().padding(16.dp, 10.dp), verticalAlignment = Alignment.Top) {
                    Text(
                        when (t.status) {
                            "completed", "done" -> "✓"
                            "in_progress", "in-progress" -> "▶"
                            "cancelled" -> "✕"
                            else -> "○"
                        },
                        color = when (t.status) {
                            "completed", "done" -> Wui.Ok
                            "in_progress", "in-progress" -> Wui.Accent
                            else -> Wui.Muted
                        },
                        modifier = Modifier.padding(end = 10.dp),
                    )
                    Column {
                        Text(t.content, color = Wui.Text)
                        Text(t.status, color = Wui.Muted, fontSize = 11.sp)
                    }
                }
                HorizontalDivider(color = Wui.Border)
            }
        }
    }
}

@Composable
fun InsightsPane(vm: AppVm) {
    val i = vm.insights.value
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        ErrLine(vm)
        Text("Insights · last ${i.days} days", color = Wui.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Stat("Sessions", i.sessions.toString())
        Stat("Messages", i.messages.toString())
        Stat("Tokens", i.tokens.toString())
        Stat("Cost", "$" + "%.4f".format(i.cost))
        i.cacheHit?.let { Stat("Cache hit", "${"%.1f".format(it)}%") }
        Spacer(Modifier.height(12.dp))
        Text("Models", color = Wui.Accent)
        i.models.forEach { m ->
            Text("${m.model} · ${m.sessions} sessions · ${m.tokens} tok · $${"%.4f".format(m.cost)}", color = Wui.Text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
fun LogsPane(vm: AppVm) {
    Column(Modifier.fillMaxSize().background(Wui.Bg)) {
        ErrLine(vm)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("agent", "errors", "gateway").forEach { f ->
                Text(
                    f,
                    color = if (vm.logFile.value == f) Wui.Accent else Wui.Muted,
                    modifier = Modifier.background(Wui.Surface, RoundedCornerShape(8.dp)).clickable { vm.setLogFile(f) }.padding(10.dp, 6.dp),
                )
            }
            Text("Refresh", color = Wui.Accent, modifier = Modifier.clickable { vm.loadPanel(Panel.Logs) }.padding(10.dp, 6.dp))
        }
        LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
            if (vm.logLines.isEmpty()) item { Text("No log lines.", color = Wui.Muted) }
            items(vm.logLines.size) { idx ->
                Text(vm.logLines[idx], color = Wui.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
    }
}

@Composable
fun SettingsPane(vm: AppVm) {
    Column(Modifier.fillMaxSize().background(Wui.Bg)) {
        ErrLine(vm)
        SectionLabel("WebUI settings — same keys as Control Center. Secrets are hidden.")
        if (vm.models.isNotEmpty()) {
            Text("Models: " + vm.models.take(8).joinToString { it.id }, color = Wui.Muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 8.dp))
        }
        LazyColumn(Modifier.weight(1f)) {
            items(vm.settingsItems, key = { it.key }) { item ->
                val current = vm.settingEdits.value[item.key] ?: item.value
                Column(Modifier.padding(16.dp, 8.dp)) {
                    Text(item.key, color = Wui.Accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
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
        }
        if (vm.settingEdits.value.isNotEmpty()) {
            TextButton(onClick = { vm.saveSettings() }, modifier = Modifier.padding(12.dp)) { Text("Save ${vm.settingEdits.value.size} changes", color = Wui.Accent) }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Wui.Muted)
        Text(value, color = Wui.Text, fontWeight = FontWeight.SemiBold)
    }
}
