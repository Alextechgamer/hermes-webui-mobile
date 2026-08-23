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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val STATUSES = listOf("triage", "todo", "ready", "running", "blocked", "done")
private const val UNASSIGNED = "__unassigned__"

@Composable
fun KanbanPane(vm: AppVm) {
    val q = vm.kanbanSearch.value.trim().lowercase()
    val filtered = remember(vm.columns.toList(), q) {
        vm.columns.map { col ->
            col.copy(tasks = col.tasks.filter { t ->
                q.isEmpty() || t.title.lowercase().contains(q) || t.id.lowercase().contains(q) || t.body.lowercase().contains(q)
            })
        }
    }
    val tenants = remember(vm.columns.toList()) {
        vm.columns.flatMap { it.tasks.map { t -> t.tenant } }.filter { it.isNotBlank() }.distinct().sorted()
    }
    val lanes = remember(filtered) { kanbanLanes(filtered) }
    val open = vm.kanbanOpen.value

    Column(Modifier.fillMaxSize().background(Wui.Bg)) {
        ErrLine(vm)
        KanbanToolbar(vm, tenants)
        if (open != null) {
            KanbanDetail(vm, open)
        } else {
            if (filtered.all { it.tasks.isEmpty() }) {
                Text(
                    if (vm.columns.any { it.tasks.isNotEmpty() }) "No tasks match these filters."
                    else "No tasks on this board. Switch boards or add a new task.",
                    color = Wui.Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(20.dp),
                )
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                lanes.forEach { (lane, cols) ->
                    val count = cols.sumOf { it.tasks.size }
                    Text(
                        "${if (lane == UNASSIGNED) "unassigned" else lane}",
                        color = Wui.Muted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(16.dp, 10.dp, 16.dp, 4.dp),
                    )
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        cols.forEach { col -> KanbanColumnView(vm, col) }
                    }
                    if (count == 0) {
                        Text("Empty", color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.padding(20.dp, 0.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun KanbanToolbar(vm: AppVm, tenants: List<String>) {
    var boardsOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(Wui.Sidebar).padding(12.dp, 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Board", color = Wui.Muted, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            val current = vm.kanbanBoards.firstOrNull { it.slug == vm.kanbanBoard.value }
            Text(
                current?.name?.ifBlank { current.slug } ?: vm.kanbanBoard.value.ifBlank { "default" },
                color = Wui.Accent,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Wui.Surface).clickable { boardsOpen = !boardsOpen }.padding(10.dp, 6.dp),
            )
            Spacer(Modifier.weight(1f))
            Text("Refresh", color = Wui.Accent, fontSize = 12.sp, modifier = Modifier.clickable { vm.loadKanban() })
        }
        if (boardsOpen) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(Wui.Surface)) {
                vm.kanbanBoards.forEach { b ->
                    Row(
                        Modifier.fillMaxWidth().clickable { boardsOpen = false; vm.switchKanbanBoard(b.slug) }.padding(12.dp, 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(b.name.ifBlank { b.slug }, color = if (b.slug == vm.kanbanBoard.value) Wui.Accent else Wui.Text)
                        Spacer(Modifier.weight(1f))
                        Text("${b.total}", color = Wui.Muted, fontSize = 12.sp)
                    }
                }
                if (vm.kanbanBoards.isEmpty()) Text("No boards.", color = Wui.Muted, modifier = Modifier.padding(12.dp))
            }
        }
        OutlinedTextField(
            vm.kanbanSearch.value,
            { vm.kanbanSearch.value = it },
            placeholder = { Text("Search tasks", color = Wui.Muted) },
            singleLine = true,
            colors = kanbanFields(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip("All assignees", vm.kanbanAssignee.value.isEmpty()) { vm.kanbanAssignee.value = ""; vm.loadKanban() }
            vm.kanbanAssignees.forEach { a ->
                FilterChip("@$a", vm.kanbanAssignee.value == a) { vm.kanbanAssignee.value = a; vm.loadKanban() }
            }
        }
        if (tenants.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip("All tenants", vm.kanbanTenant.value.isEmpty()) { vm.kanbanTenant.value = ""; vm.loadKanban() }
                tenants.forEach { t ->
                    FilterChip(t, vm.kanbanTenant.value == t) { vm.kanbanTenant.value = t; vm.loadKanban() }
                }
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("Include archived", vm.kanbanArchived.value) { vm.kanbanArchived.value = !vm.kanbanArchived.value; vm.loadKanban() }
            FilterChip("Only mine", vm.kanbanMine.value) { vm.kanbanMine.value = !vm.kanbanMine.value; vm.loadKanban() }
        }
        val stats = vm.kanbanStats.value.line()
        if (stats.isNotBlank()) Text(stats, color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Preview dispatcher", color = Wui.Accent, fontSize = 12.sp, modifier = Modifier.clickable { vm.dispatchKanban(true) })
            Text("Run dispatcher", color = Wui.Accent, fontSize = 12.sp, modifier = Modifier.clickable { vm.dispatchKanban(false) })
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                vm.kanbanDraft.value,
                { vm.kanbanDraft.value = it },
                placeholder = { Text("New task", color = Wui.Muted) },
                singleLine = true,
                colors = kanbanFields(),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Add",
                color = Wui.Bg,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Wui.Accent).clickable { vm.createKanbanTask() }.padding(12.dp, 10.dp),
            )
        }
    }
}

@Composable
private fun KanbanColumnView(vm: AppVm, col: KanbanColumn) {
    Column(
        Modifier
            .width(260.dp)
            .heightIn(min = 120.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Wui.Border, RoundedCornerShape(12.dp))
            .background(Wui.Surface)
            .padding(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(4.dp, 2.dp, 4.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(col.name.replaceFirstChar { it.uppercase() }, color = Wui.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text("${col.tasks.size}", color = Wui.Muted, fontSize = 12.sp)
        }
        if (col.tasks.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                Text("Empty", color = Wui.Muted, fontSize = 12.sp)
            }
        } else {
            col.tasks.forEach { task -> KanbanCard(vm, task) }
        }
    }
}

@Composable
private fun KanbanCard(vm: AppVm, task: KanbanTask) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Wui.Bg)
            .border(1.dp, Wui.Border, RoundedCornerShape(10.dp))
            .clickable { vm.kanbanOpen.value = task }
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(task.id, color = Wui.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            val p = task.priority.trim().removePrefix("P").removePrefix("p")
            if (p.isNotBlank() && p != "0") {
                Text("P$p", color = Wui.Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
            }
        }
        Text(task.title, color = Wui.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
        if (task.body.isNotBlank()) {
            Text(task.body.lineSequence().first().take(80), color = Wui.Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Text(
            if (task.assignee.isBlank()) "unassigned" else "@${task.assignee}",
            color = Wui.Muted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("complete", color = Wui.Accent, fontSize = 11.sp, modifier = Modifier.clickable { vm.moveTask(task.id, "done") })
            Text("archive", color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.clickable { vm.moveTask(task.id, "archived") })
        }
    }
}

@Composable
private fun KanbanDetail(vm: AppVm, task: KanbanTask) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("← Board", color = Wui.Accent, modifier = Modifier.clickable { vm.kanbanOpen.value = null })
        Text(task.id, color = Wui.Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
        Text(task.title, color = Wui.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
        if (task.body.isNotBlank()) Text(task.body, color = Wui.Text, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        Text(
            listOfNotNull(
                if (task.assignee.isBlank()) "unassigned" else "@${task.assignee}",
                task.tenant.ifBlank { null },
                task.priority.takeIf { it.isNotBlank() && it != "0" }?.let { "P${it.removePrefix("P")}" },
            ).joinToString(" · "),
            color = Wui.Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text("Move", color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            STATUSES.forEach { s ->
                FilterChip(s.replaceFirstChar { it.uppercase() }, task.status == s) {
                    vm.moveTask(task.id, s)
                    vm.kanbanOpen.value = task.copy(status = s)
                }
            }
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Complete", color = Wui.Accent, modifier = Modifier.clickable { vm.moveTask(task.id, "done"); vm.kanbanOpen.value = null })
            Text("Archive", color = Wui.Muted, modifier = Modifier.clickable { vm.moveTask(task.id, "archived"); vm.kanbanOpen.value = null })
        }
    }
}

@Composable
private fun FilterChip(label: String, on: Boolean, click: () -> Unit) {
    Text(
        label,
        color = if (on) Wui.Bg else Wui.Text,
        fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) Wui.Accent else Wui.Surface)
            .border(1.dp, if (on) Wui.Accent else Wui.Border, RoundedCornerShape(8.dp))
            .clickable(onClick = click)
            .padding(10.dp, 6.dp),
    )
}

@Composable
private fun kanbanFields() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Wui.Text,
    unfocusedTextColor = Wui.Text,
    focusedBorderColor = Wui.Accent,
    unfocusedBorderColor = Wui.Border,
    focusedContainerColor = Wui.Bg,
    unfocusedContainerColor = Wui.Bg,
    cursorColor = Wui.Accent,
)

private fun kanbanLanes(columns: List<KanbanColumn>): List<Pair<String, List<KanbanColumn>>> {
    val names = linkedSetOf<String>()
    columns.forEach { col -> col.tasks.forEach { t -> names += if (t.assignee.isBlank()) UNASSIGNED else t.assignee } }
    val assigned = names.filter { it != UNASSIGNED }.sortedWith(compareBy({ it != "default" }, { it.lowercase() }))
    val order = assigned + if (UNASSIGNED in names) listOf(UNASSIGNED) else emptyList()
    if (order.isEmpty()) return listOf("default" to columns)
    return order.map { lane ->
        lane to columns.map { col ->
            col.copy(tasks = col.tasks.filter { t -> (if (t.assignee.isBlank()) UNASSIGNED else t.assignee) == lane })
        }
    }
}
