package com.hermes.webui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ---------------------------------- Tasks ---------------------------------- */

@Composable
fun TasksPane(vm: AppVm) {
    var openId by remember { mutableStateOf<String?>(null) }
    var editId by remember { mutableStateOf<String?>(null) }
    var showNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newSchedule by remember { mutableStateOf("") }
    var newPrompt by remember { mutableStateOf("") }
    var editName by remember { mutableStateOf("") }
    var editSchedule by remember { mutableStateOf("") }
    var editPrompt by remember { mutableStateOf("") }
    var editDeliver by remember { mutableStateOf("local") }
    Column(Modifier.fillMaxSize().background(Wui.Bg)) {
        ErrLine(vm)
        Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Scheduled jobs", color = Wui.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("Refresh", color = Wui.Muted, fontSize = 12.sp, modifier = Modifier.clickable { vm.loadPanel(Panel.Tasks) }.padding(end = 14.dp))
            Text(if (showNew) "Cancel" else "+ New job", color = Wui.Accent, fontSize = 12.sp, modifier = Modifier.clickable { showNew = !showNew })
        }
        if (showNew) {
            Column(Modifier.fillMaxWidth().padding(16.dp, 4.dp).background(Wui.Surface, RoundedCornerShape(10.dp)).padding(12.dp)) {
                OutlinedTextField(newName, { newName = it }, label = { Text("Name (optional)") }, colors = fieldColors(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(newSchedule, { newSchedule = it }, label = { Text("Schedule — e.g. 30m, every 2h, 0 9 * * *") }, colors = fieldColors(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(newPrompt, { newPrompt = it }, label = { Text("Prompt") }, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
                TextButton(onClick = {
                    if (newSchedule.isNotBlank() && newPrompt.isNotBlank()) {
                        vm.createCron(newName.trim(), newSchedule.trim(), newPrompt.trim())
                        showNew = false; newName = ""; newSchedule = ""; newPrompt = ""
                    }
                }) { Text("Create job", color = Wui.Accent) }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (vm.jobs.isEmpty()) item { Text("No cron jobs.", color = Wui.Muted, modifier = Modifier.padding(20.dp)) }
            items(vm.jobs, key = { it.id }) { job ->
                Column(Modifier.fillMaxWidth().padding(16.dp, 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(job.name, color = Wui.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (job.id in vm.runningCrons.value) {
                            Text("running", color = Wui.Accent, fontSize = 11.sp, modifier = Modifier.padding(end = 6.dp).background(Wui.AccentBg, RoundedCornerShape(6.dp)).padding(8.dp, 3.dp))
                        }
                        val chip = if (job.paused) "paused" else if (job.enabled) "on" else "off"
                        val chipColor = if (job.paused) Wui.Muted else if (job.enabled) Wui.Ok else Wui.Muted
                        Text(chip, color = chipColor, fontSize = 11.sp, modifier = Modifier.background(Wui.Surface, RoundedCornerShape(6.dp)).padding(8.dp, 3.dp))
                    }
                    Text(
                        listOf(job.schedule, job.owner).filter { it.isNotBlank() }.joinToString(" · "),
                        color = Wui.Muted,
                        fontSize = 12.sp,
                    )
                    val statusColor = when {
                        job.lastStatus.contains("error", true) || job.lastStatus.contains("fail", true) -> Wui.Danger
                        job.lastStatus.contains("ok", true) || job.lastStatus.contains("success", true) -> Wui.Ok
                        else -> Wui.Muted
                    }
                    if (job.lastStatus.isNotBlank() || job.lastRun.isNotBlank() || job.nextRun.isNotBlank()) {
                        Row {
                            if (job.lastStatus.isNotBlank()) Text(job.lastStatus, color = statusColor, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
                            Text(
                                listOf(
                                    job.lastRun.takeIf { it.isNotBlank() }?.let { "last " + JsonText.ts(it) },
                                    job.nextRun.takeIf { it.isNotBlank() }?.let { "next " + JsonText.ts(it) },
                                ).filterNotNull().joinToString(" · "),
                                color = Wui.Muted, fontSize = 12.sp,
                            )
                        }
                    }
                    if (!job.readOnly) {
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("Run", color = Wui.Accent, modifier = Modifier.clickable { vm.cronAction(job.id, "run") })
                            if (job.paused) Text("Resume", color = Wui.Muted, modifier = Modifier.clickable { vm.cronAction(job.id, "resume") })
                            else Text("Pause", color = Wui.Muted, modifier = Modifier.clickable { vm.cronAction(job.id, "pause") })
                            Text("Output", color = Wui.Muted, modifier = Modifier.clickable {
                                openId = if (openId == job.id) null else job.id
                                vm.loadJobOutput(job.id)
                            })
                            Text("Edit", color = Wui.Muted, modifier = Modifier.clickable {
                                editId = if (editId == job.id) null else job.id
                                editName = job.name
                                editSchedule = job.schedule
                                editPrompt = job.prompt
                                editDeliver = job.deliver
                            })
                            Text("Delete", color = Wui.Danger, modifier = Modifier.clickable { vm.deleteCron(job.id) })
                        }
                    }
                    if (editId == job.id) {
                        Column(Modifier.fillMaxWidth().padding(top = 8.dp).background(Wui.Surface, RoundedCornerShape(10.dp)).padding(10.dp)) {
                            OutlinedTextField(editName, { editName = it }, label = { Text("Name") }, colors = fieldColors(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                            OutlinedTextField(editSchedule, { editSchedule = it }, label = { Text("Schedule") }, colors = fieldColors(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                            OutlinedTextField(editPrompt, { editPrompt = it }, label = { Text("Prompt") }, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(editDeliver, { editDeliver = it }, label = { Text("Deliver (local, origin, …)") }, colors = fieldColors(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                            TextButton(onClick = {
                                vm.updateCron(job.id, editName.trim(), editSchedule.trim(), editPrompt.trim(), editDeliver.trim())
                                editId = null
                            }) { Text("Save job", color = Wui.Accent) }
                        }
                    }
                    if (openId == job.id) {
                        if (vm.cronRuns.isNotEmpty()) {
                            Text("History", color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                            vm.cronRuns.take(12).forEach { run ->
                                Text(
                                    "${run.filename} · ${run.size} · ${if (run.modified.isNotBlank()) JsonText.ts(run.modified) else ""}",
                                    color = Wui.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                        if (vm.jobOutput.value.isNotBlank()) {
                            Text(vm.jobOutput.value.take(4000), color = Wui.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
                HorizontalDivider(color = Wui.Border)
            }
        }
    }
}

/* ---------------------------------- Skills --------------------------------- */

@Composable
fun SkillsPane(vm: AppVm) {
    var open by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var showNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newCategory by remember { mutableStateOf("") }
    var newContent by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Wui.Bg)) {
        ErrLine(vm)
        Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Installed skills", color = Wui.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(if (showNew) "Cancel" else "+ New skill", color = Wui.Accent, fontSize = 12.sp, modifier = Modifier.clickable { showNew = !showNew })
        }
        OutlinedTextField(
            query, { query = it },
            placeholder = { Text("Search skills…", color = Wui.Muted) },
            colors = fieldColors(), modifier = Modifier.fillMaxWidth().padding(16.dp, 0.dp), singleLine = true,
        )
        if (showNew) {
            Column(Modifier.fillMaxWidth().padding(16.dp, 8.dp).background(Wui.Surface, RoundedCornerShape(10.dp)).padding(12.dp)) {
                OutlinedTextField(newName, { newName = it }, label = { Text("Name (lowercase, hyphens)") }, colors = fieldColors(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(newCategory, { newCategory = it }, label = { Text("Category (optional)") }, colors = fieldColors(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(newContent, { newContent = it }, label = { Text("SKILL.md content") }, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
                TextButton(onClick = {
                    if (newName.isNotBlank() && newContent.isNotBlank()) {
                        vm.saveSkill(newName.trim(), newCategory.trim(), newContent)
                        showNew = false; newName = ""; newCategory = ""; newContent = ""
                    }
                }) { Text("Create skill", color = Wui.Accent) }
            }
        }
        val q = query.trim().lowercase()
        val shown = vm.skills.filter {
            q.isEmpty() || it.name.lowercase().contains(q) || it.description.lowercase().contains(q) || it.category.lowercase().contains(q)
        }
        val grouped = shown.groupBy { it.category.ifBlank { "uncategorized" } }.toSortedMap()
        LazyColumn(Modifier.fillMaxSize()) {
            if (shown.isEmpty()) item { Text(if (q.isEmpty()) "No skills found." else "No skills match \"$query\".", color = Wui.Muted, modifier = Modifier.padding(20.dp)) }
            grouped.forEach { (cat, list) ->
                item(key = "cat-$cat") {
                    Text(
                        "$cat (${list.size})",
                        color = Wui.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().background(Wui.Surface).padding(16.dp, 6.dp),
                    )
                }
                items(list, key = { it.name }) { s ->
                    Column(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable {
                                open = if (open == s.name) null else s.name
                                vm.openSkill(s.name)
                            }) {
                                Text(s.name, color = Wui.Text, fontWeight = FontWeight.SemiBold)
                            }
                            Switch(
                                checked = !s.disabled,
                                onCheckedChange = { vm.toggleSkill(s) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Wui.Bg, checkedTrackColor = Wui.Accent),
                            )
                        }
                        if (s.description.isNotBlank()) Text(s.description, color = Wui.Muted, fontSize = 12.sp, maxLines = 3)
                        if (open == s.name) {
                            Text("Delete skill", color = Wui.Danger, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp).clickable { vm.deleteSkill(s.name); open = null })
                            if (vm.skillBody.value.isNotBlank()) {
                                Text(vm.skillBody.value.take(6000), color = Wui.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                    HorizontalDivider(color = Wui.Border)
                }
            }
        }
    }
}

/* ---------------------------------- Memory --------------------------------- */

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

/* ---------------------------------- Spaces --------------------------------- */

@Composable
fun SpacesPane(vm: AppVm) {
    var showAdd by remember { mutableStateOf(false) }
    var newPath by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Wui.Bg)) {
        ErrLine(vm)
        Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Add and switch workspaces for your sessions.", color = Wui.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(if (showAdd) "Cancel" else "+ Add space", color = Wui.Accent, fontSize = 12.sp, modifier = Modifier.clickable { showAdd = !showAdd })
        }
        if (showAdd) {
            Column(Modifier.fillMaxWidth().padding(16.dp, 4.dp).background(Wui.Surface, RoundedCornerShape(10.dp)).padding(12.dp)) {
                OutlinedTextField(
                    newPath,
                    { newPath = it; vm.suggestWorkspaces(it.trim()) },
                    label = { Text("Absolute path on the server") },
                    colors = fieldColors(), modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                vm.wsSuggestions.take(5).forEach { s ->
                    Text(s, color = Wui.AccentText, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().clickable { newPath = s }.padding(vertical = 4.dp))
                }
                TextButton(onClick = {
                    if (newPath.isNotBlank()) { vm.addWorkspace(newPath.trim()); showAdd = false; newPath = "" }
                }) { Text("Add", color = Wui.Accent) }
            }
        }
        LazyColumn {
            if (vm.spaces.isEmpty()) item { Text("No spaces.", color = Wui.Muted, modifier = Modifier.padding(20.dp)) }
            items(vm.spaces, key = { it.path.ifBlank { it.name } }) { s ->
                Row(Modifier.fillMaxWidth().padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name + if (s.last) " · last" else "", color = if (s.last) Wui.Accent else Wui.Text)
                        if (s.path.isNotBlank()) Text(s.path, color = Wui.Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                    if (s.path.isNotBlank()) {
                        Text("↑", color = Wui.Muted, fontSize = 14.sp, modifier = Modifier.padding(end = 10.dp).clickable { vm.moveWorkspace(s.path, up = true) })
                        Text("↓", color = Wui.Muted, fontSize = 14.sp, modifier = Modifier.padding(end = 10.dp).clickable { vm.moveWorkspace(s.path, up = false) })
                        Text("Remove", color = Wui.Danger, fontSize = 12.sp, modifier = Modifier.clickable { vm.removeWorkspace(s.path) })
                    }
                }
                HorizontalDivider(color = Wui.Border)
            }
            item {
                Text("Paths are validated as existing directories before saving.", color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.padding(16.dp, 10.dp))
            }
        }
    }
}

/* --------------------------------- Profiles -------------------------------- */

@Composable
fun ProfilesPane(vm: AppVm) {
    var showNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Wui.Bg)) {
        ErrLine(vm)
        Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Active profile: ${vm.activeProfile.value.ifBlank { "default" }}", color = Wui.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(if (showNew) "Cancel" else "+ New profile", color = Wui.Accent, fontSize = 12.sp, modifier = Modifier.clickable { showNew = !showNew })
        }
        if (showNew) {
            Column(Modifier.fillMaxWidth().padding(16.dp, 4.dp).background(Wui.Surface, RoundedCornerShape(10.dp)).padding(12.dp)) {
                OutlinedTextField(newName, { newName = it }, label = { Text("Profile name") }, colors = fieldColors(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                TextButton(onClick = {
                    if (newName.isNotBlank()) { vm.createProfile(newName.trim()); showNew = false; newName = "" }
                }) { Text("Create", color = Wui.Accent) }
            }
        }
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
                    else if (p.name != "default") {
                        Text("Delete", color = Wui.Danger, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp).clickable { vm.deleteProfile(p.name) })
                    }
                }
                HorizontalDivider(color = Wui.Border)
            }
        }
    }
}

/* ---------------------------------- Todos ---------------------------------- */

@Composable
fun TodosPane(vm: AppVm) {
    Column(Modifier.fillMaxSize().background(Wui.Bg)) {
        ErrLine(vm)
        SectionLabel("Current task list from this conversation (todo tool / SSE)")
        LazyColumn {
            if (vm.todos.isEmpty()) item {
                Text("No active task list in this session.", color = Wui.Muted, modifier = Modifier.padding(20.dp))
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

/* --------------------------------- Insights -------------------------------- */

@Composable
fun InsightsPane(vm: AppVm) {
    val ins = vm.insights.value
    Column(Modifier.fillMaxSize().background(Wui.Bg)) {
        ErrLine(vm)
        Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Insights", color = Wui.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("Refresh", color = Wui.Accent, fontSize = 12.sp, modifier = Modifier.clickable { vm.loadPanel(Panel.Insights) })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(16.dp, 0.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 30, 90, 365).forEach { d ->
                Text(
                    "$d days",
                    color = if (vm.insightsDays.value == d) Wui.Accent else Wui.Muted,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .background(Wui.Surface, RoundedCornerShape(8.dp))
                        .clickable { vm.insightsDays.value = d; vm.loadPanel(Panel.Insights) }
                        .padding(10.dp, 6.dp),
                )
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(16.dp, 8.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("Sessions", "${ins.sessions}", Modifier.weight(1f))
                    StatCard("Messages", "${ins.messages}", Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("Tokens", ConsoleFmt.tok(ins.tokens.toLong()), Modifier.weight(1f))
                    StatCard("Cost", "$" + String.format("%.2f", ins.cost) + (ins.cacheHit?.let { " · ${it.toInt()}% cache" } ?: ""), Modifier.weight(1f))
                }
            }
            if (ins.skills.isNotEmpty()) {
                item {
                    Text("Skill usage", color = Wui.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text("Skill", color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text("Uses", color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.width(52.dp))
                        Text("Views", color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.width(52.dp))
                        Text("Patches", color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.width(56.dp))
                    }
                }
                items(ins.skills, key = { "sk-" + it.name }) { s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(s.name, color = Wui.Text, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                        Text("${s.uses}", color = Wui.Text, fontSize = 12.sp, modifier = Modifier.width(52.dp))
                        Text("${s.views}", color = Wui.Muted, fontSize = 12.sp, modifier = Modifier.width(52.dp))
                        Text("${s.patches}", color = Wui.Muted, fontSize = 12.sp, modifier = Modifier.width(56.dp))
                    }
                }
            }
            if (ins.models.isNotEmpty()) {
                item {
                    Text("Models", color = Wui.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text("Model", color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text("Sess", color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.width(44.dp))
                        Text("Tokens", color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.width(64.dp))
                        Text("Cache", color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.width(48.dp))
                        Text("Cost", color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.width(56.dp))
                    }
                }
                items(ins.models, key = { "m-" + it.model }) { m ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(m.model, color = Wui.Text, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                        Text("${m.sessions}", color = Wui.Text, fontSize = 12.sp, modifier = Modifier.width(44.dp))
                        Text(ConsoleFmt.tok(m.tokens.toLong()), color = Wui.Muted, fontSize = 12.sp, modifier = Modifier.width(64.dp))
                        Text(m.cacheHitPct?.let { "${it.toInt()}%" } ?: "—", color = Wui.Muted, fontSize = 12.sp, modifier = Modifier.width(48.dp))
                        Text(if (m.cost > 0) "$" + String.format("%.2f", m.cost) else "N/A", color = Wui.Muted, fontSize = 12.sp, modifier = Modifier.width(56.dp))
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.background(Wui.Surface, RoundedCornerShape(10.dp)).padding(12.dp)) {
        Text(label, color = Wui.Muted, fontSize = 11.sp)
        Text(value, color = Wui.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

/* ----------------------------------- Logs ---------------------------------- */

@Composable
fun LogsPane(vm: AppVm) {
    val ctx = LocalContext.current
    var tail by remember { mutableStateOf(200) }
    var severity by remember { mutableStateOf("all") }
    Column(Modifier.fillMaxSize().background(Wui.Bg)) {
        ErrLine(vm)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp, 12.dp, 12.dp, 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("agent", "errors", "gateway").forEach { f ->
                Text(
                    f,
                    color = if (vm.logFile.value == f) Wui.Accent else Wui.Muted,
                    modifier = Modifier.background(Wui.Surface, RoundedCornerShape(8.dp)).clickable { vm.setLogFile(f) }.padding(10.dp, 6.dp),
                )
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp, 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(100, 200, 500, 1000).forEach { n ->
                Text(
                    "$n",
                    fontSize = 12.sp,
                    color = if (tail == n) Wui.Accent else Wui.Muted,
                    modifier = Modifier.background(Wui.Surface, RoundedCornerShape(8.dp)).clickable { tail = n; vm.setLogTail(n) }.padding(10.dp, 5.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            listOf("all" to "All", "err" to "Errors", "warn" to "Warnings+").forEach { (k, label) ->
                Text(
                    label,
                    fontSize = 12.sp,
                    color = if (severity == k) Wui.Accent else Wui.Muted,
                    modifier = Modifier.background(Wui.Surface, RoundedCornerShape(8.dp)).clickable { severity = k }.padding(10.dp, 5.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            Text("Copy all", fontSize = 12.sp, color = Wui.Accent, modifier = Modifier.clickable {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("logs", vm.logLines.joinToString("\n")))
            }.padding(10.dp, 5.dp))
            Text("Refresh", fontSize = 12.sp, color = Wui.Accent, modifier = Modifier.clickable { vm.loadPanel(Panel.Logs) }.padding(10.dp, 5.dp))
        }
        val shown = vm.logLines.filter { line ->
            when (severity) {
                "err" -> line.contains("ERROR", true) || line.contains("CRITICAL", true) || line.contains("Traceback")
                "warn" -> line.contains("ERROR", true) || line.contains("WARN", true) || line.contains("CRITICAL", true)
                else -> true
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
            if (shown.isEmpty()) item { Text("No log lines.", color = Wui.Muted) }
            items(shown.size) { idx ->
                val line = shown[idx]
                val color = when {
                    line.contains("ERROR", true) || line.contains("CRITICAL", true) -> Wui.Danger
                    line.contains("WARN", true) -> Wui.Accent
                    else -> Wui.Text
                }
                Text(line, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
    }
}
