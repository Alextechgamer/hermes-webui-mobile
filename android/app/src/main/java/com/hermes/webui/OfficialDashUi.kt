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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun OfficialDashPane(vm: AppVm) {
    Column(Modifier.fillMaxSize().background(Wui.Bg).imePadding()) {
        Row(Modifier.fillMaxWidth().background(Wui.Sidebar).padding(12.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row {
                    Text("HERMES ", color = Wui.Text, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
                    Text("DASHBOARD", color = Wui.Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
                }
                Text(vm.officialBase().ifBlank { "same host :9119" }, color = Wui.Muted, fontSize = 11.sp)
            }
            Text(vm.officialWsState.value, color = if (vm.officialWsState.value == "live") Wui.Ok else Wui.Muted, fontSize = 11.sp)
        }
        vm.officialError.value?.let { Text(it, color = Wui.Danger, fontSize = 12.sp, modifier = Modifier.padding(12.dp, 6.dp)) }
        if (vm.officialNeedsLogin.value) {
            OfficialLogin(vm)
            return
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(10.dp, 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("chat" to "Chat", "sessions" to "Sessions", "kanban" to "Kanban", "status" to "Status").forEach { (id, label) ->
                val on = vm.officialTab.value == id
                Text(
                    label,
                    color = if (on) Wui.Bg else Wui.Text,
                    fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Wui.Accent else Wui.Surface)
                        .clickable {
                            vm.officialTab.value = id
                            when (id) {
                                "kanban" -> vm.loadOfficialKanban()
                                "sessions", "chat" -> vm.loadOfficialSessions()
                                "status" -> vm.loadOfficial()
                            }
                        }.padding(10.dp, 6.dp),
                )
            }
        }
        when (vm.officialTab.value) {
            "kanban" -> OfficialKanban(vm)
            "sessions" -> OfficialSessions(vm)
            "status" -> OfficialStatusView(vm)
            else -> OfficialChat(vm)
        }
    }
}

@Composable
private fun OfficialLogin(vm: AppVm) {
    var user by remember { mutableStateOf(vm.officialUser.value) }
    var pw by remember { mutableStateOf(vm.officialPassword.value) }
    var url by remember { mutableStateOf(vm.officialUrlDraft.value.ifBlank { vm.officialBase() }) }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center) {
        Text("Official dashboard sign-in", color = Wui.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("This is hermes dashboard :9119 — not WebUI :8787 and not Console :8790.", color = Wui.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(url, { url = it }, label = { Text("Dashboard URL") }, placeholder = { Text("http://host:9119") }, colors = dashFields(), modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(user, { user = it }, label = { Text("Username") }, colors = dashFields(), modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), colors = dashFields(), modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Wui.Accent).clickable {
                vm.saveOfficialUrl(url)
                vm.officialLogin(user, pw)
            }.padding(14.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Sign in", color = Wui.Bg, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun OfficialChat(vm: AppVm) {
    val list = rememberLazyListState()
    LaunchedEffect(vm.officialBubbles.size, vm.officialLive.value.length) {
        val last = vm.officialBubbles.size + if (vm.officialLive.value.isNotEmpty()) 1 else 0
        if (last > 0) runCatching { list.animateScrollToItem(last) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("New chat", color = Wui.Accent, fontSize = 12.sp, modifier = Modifier.clickable { vm.newOfficialChat() })
            Spacer(Modifier.weight(1f))
            Text("${vm.officialSessions.size} sessions", color = Wui.Muted, fontSize = 11.sp)
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = list) {
            items(vm.officialBubbles, key = { it.id }) { m ->
                val mine = m.role == "user"
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                    Text(if (mine) "You" else "Hermes", color = Wui.Muted, fontSize = 10.sp)
                    Text(
                        m.text,
                        color = Wui.Text,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (mine) Wui.UserBubble else Wui.Surface)
                            .padding(10.dp),
                    )
                }
            }
            if (vm.officialLive.value.isNotBlank()) {
                item {
                    Text(vm.officialLive.value, color = Wui.Text, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                vm.officialDraft.value,
                { vm.officialDraft.value = it },
                placeholder = { Text(if (vm.officialBusy.value) "Steer / send" else "Message", color = Wui.Muted) },
                colors = dashFields(),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (vm.officialBusy.value) "STEER" else "Send",
                color = Wui.Bg,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Wui.Accent).clickable { vm.sendOfficial() }.padding(12.dp, 10.dp),
            )
        }
    }
}

@Composable
private fun OfficialSessions(vm: AppVm) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (vm.officialSessions.isEmpty()) item { Text("No sessions on this dashboard.", color = Wui.Muted, modifier = Modifier.padding(20.dp)) }
        items(vm.officialSessions, key = { it.id }) { s ->
            Column(
                Modifier.fillMaxWidth().clickable {
                    vm.officialTab.value = "chat"
                    vm.openOfficialSession(s.id)
                }.padding(16.dp, 10.dp),
            ) {
                Text(s.title, color = Wui.Text, fontWeight = FontWeight.SemiBold)
                Text(
                    listOf("${s.messages} messages", s.model, s.source).filter { it.isNotBlank() }.joinToString(" · "),
                    color = Wui.Muted,
                    fontSize = 12.sp,
                )
                if (s.preview.isNotBlank()) Text(s.preview, color = Wui.Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun OfficialStatusView(vm: AppVm) {
    val s = vm.officialStatus.value
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("This is the official Hermes Dashboard (:9119), native — not a WebView of /chat.", color = Wui.Muted, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        if (s == null) Text("No status yet.", color = Wui.Muted)
        else {
            StatusRow("Version", s.version)
            StatusRow("Gateway", s.gateway)
            StatusRow("Active sessions", s.sessions.toString())
            StatusRow("Overall", s.overall)
        }
        Spacer(Modifier.height(16.dp))
        Text("Browser /chat is an xterm TUI over /api/pty. This app uses the same dashboard backend: REST sessions + JSON-RPC /api/ws (session.create / prompt.submit), which is what Hermes Desktop uses.", color = Wui.Muted, fontSize = 12.sp)
    }
}

@Composable
private fun StatusRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(k, color = Wui.Muted, modifier = Modifier.width(140.dp))
        Text(v.ifBlank { "—" }, color = Wui.Text)
    }
}

@Composable
private fun OfficialKanban(vm: AppVm) {
    val lanes = remember(vm.officialColumns.toList()) { officialLanes(vm.officialColumns) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp, 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            vm.officialBoards.forEach { b ->
                val on = b.slug == vm.officialBoard.value
                Text(
                    "${b.name.ifBlank { b.slug }} ${b.total}".trim(),
                    color = if (on) Wui.Bg else Wui.Text,
                    fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Wui.Accent else Wui.Surface)
                        .clickable { vm.switchOfficialBoard(b.slug) }.padding(10.dp, 6.dp),
                )
            }
            if (vm.officialBoards.isEmpty()) Text("No boards", color = Wui.Muted, fontSize = 12.sp)
        }
        Row(Modifier.padding(12.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                vm.officialTaskDraft.value,
                { vm.officialTaskDraft.value = it },
                placeholder = { Text("New task", color = Wui.Muted) },
                colors = dashFields(),
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            Text("Add", color = Wui.Bg, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Wui.Accent).clickable { vm.createOfficialTask() }.padding(12.dp, 10.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            if (lanes.isEmpty()) {
                Text("No tasks on this board.", color = Wui.Muted, modifier = Modifier.padding(20.dp))
            }
            lanes.forEach { (lane, cols) ->
                Text(if (lane == "__unassigned__") "unassigned" else lane, color = Wui.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(16.dp, 10.dp, 16.dp, 4.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    cols.forEach { col ->
                        Column(
                            Modifier.width(250.dp).heightIn(min = 100.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Wui.Border, RoundedCornerShape(12.dp)).background(Wui.Surface).padding(8.dp),
                        ) {
                            Row {
                                Text(col.name.replaceFirstChar { it.uppercase() }, color = Wui.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Spacer(Modifier.weight(1f))
                                Text("${col.tasks.size}", color = Wui.Muted, fontSize = 12.sp)
                            }
                            if (col.tasks.isEmpty()) Text("Empty", color = Wui.Muted, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                            col.tasks.forEach { task ->
                                Column(
                                    Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).background(Wui.Bg).padding(8.dp),
                                ) {
                                    Text(task.id, color = Wui.Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(task.title, color = Wui.Text, fontSize = 13.sp)
                                    if (task.body.isNotBlank()) Text(task.body.lineSequence().first().take(80), color = Wui.Muted, fontSize = 11.sp, maxLines = 2)
                                    Text(if (task.assignee.isBlank()) "unassigned" else "@${task.assignee}", color = Wui.Muted, fontSize = 11.sp)
                                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("complete", color = Wui.Accent, fontSize = 11.sp, modifier = Modifier.clickable { vm.moveOfficialTask(task.id, "done") })
                                        Text("archive", color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.clickable { vm.moveOfficialTask(task.id, "archived") })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun dashFields() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Wui.Text,
    unfocusedTextColor = Wui.Text,
    focusedBorderColor = Wui.Accent,
    unfocusedBorderColor = Wui.Border,
    focusedContainerColor = Wui.Bg,
    unfocusedContainerColor = Wui.Bg,
    cursorColor = Wui.Accent,
)

private fun officialLanes(columns: List<KanbanColumn>): List<Pair<String, List<KanbanColumn>>> {
    val names = linkedSetOf<String>()
    columns.forEach { col -> col.tasks.forEach { t -> names += if (t.assignee.isBlank()) "__unassigned__" else t.assignee } }
    val assigned = names.filter { it != "__unassigned__" }.sortedWith(compareBy({ it != "default" }, { it.lowercase() }))
    val order = assigned + if ("__unassigned__" in names) listOf("__unassigned__") else emptyList()
    if (order.isEmpty()) return if (columns.isEmpty()) emptyList() else listOf("default" to columns)
    return order.map { lane ->
        lane to columns.map { col ->
            col.copy(tasks = col.tasks.filter { t -> (if (t.assignee.isBlank()) "__unassigned__" else t.assignee) == lane })
        }
    }
}
