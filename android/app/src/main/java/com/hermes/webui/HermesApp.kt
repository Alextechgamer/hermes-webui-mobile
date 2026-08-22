package com.hermes.webui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

@Composable
fun HermesApp(vm: AppVm = viewModel()) {
    HermesTheme {
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        var showConnect by remember { mutableStateOf(false) }
        var urlDraft by remember { mutableStateOf(vm.prefs.baseUrl) }
        var dashDraft by remember { mutableStateOf(vm.prefs.dashboardUrl) }
        var password by remember { mutableStateOf(vm.prefs.password) }
        val stayInApp = showConnect || drawer.isOpen || vm.canPopBack()
        BackHandler(enabled = stayInApp) {
            when {
                showConnect -> showConnect = false
                drawer.isOpen -> scope.launch { drawer.close() }
                else -> vm.handleBack()
            }
        }

        ModalNavigationDrawer(
            drawerState = drawer,
            gesturesEnabled = vm.ready.value,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = Wui.Sidebar,
                    drawerTonalElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth(0.92f),
                ) {
                    DrawerBody(vm) { scope.launch { drawer.close() } }
                }
            },
        ) {
            Column(Modifier.fillMaxSize().background(Wui.Bg).statusBarsPadding().navigationBarsPadding()) {
                if (vm.ready.value) {
                    WebTopbar(
                        vm = vm,
                        onMenu = { scope.launch { drawer.open() } },
                        onConnect = { showConnect = true },
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth().background(Wui.Bg)) {
                    when {
                        !vm.configured.value -> ConnectPane(urlDraft, { urlDraft = it }, dashDraft, { dashDraft = it }) {
                            vm.saveUrl(urlDraft, dashDraft)
                        }
                        vm.needsLogin.value -> LoginPane(password, { password = it }, { vm.login(password) }, vm.error.value)
                        else -> PanelHost(vm)
                    }
                }
            }
        }

        if (showConnect) {
            AlertDialog(
                onDismissRequest = { showConnect = false },
                containerColor = Wui.Surface,
                title = { Text("Connection", color = Wui.Text, fontWeight = FontWeight.SemiBold) },
                text = {
                    Column {
                        OutlinedTextField(urlDraft, { urlDraft = it }, label = { Text("WebUI URL") }, placeholder = { Text("http://host:8787") }, colors = fieldColors())
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(dashDraft, { dashDraft = it }, label = { Text("Hermes Console URL (optional)") }, placeholder = { Text("http://host:8790") }, colors = fieldColors())
                    }
                },
                confirmButton = {
                    TextButton(onClick = { vm.saveUrl(urlDraft, dashDraft); showConnect = false }) { Text("Save", color = Wui.Accent) }
                },
                dismissButton = { TextButton(onClick = { showConnect = false }) { Text("Close", color = Wui.Muted) } },
            )
        }
    }
}

@Composable
private fun WebTopbar(vm: AppVm, onMenu: () -> Unit, onConnect: () -> Unit) {
    val title = if (vm.panel.value == Panel.Chat && vm.title.value.isNotBlank()) vm.title.value else vm.panel.value.label
    Row(
        Modifier
            .fillMaxWidth()
            .background(Wui.Sidebar)
            .border(width = 0.dp, color = Wui.Border)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .clip(WuiShapeSm)
                .clickable(onClick = onMenu)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Menu, "Menu", tint = Wui.Accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("Menu", color = Wui.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
            Text(title, color = Wui.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, letterSpacing = (-0.15).sp)
            if (vm.panel.value == Panel.Chat && vm.busy.value) {
                Text("Hermes is working…", color = Wui.AccentText, fontSize = 11.sp)
            }
        }
        if (vm.panel.value == Panel.Chat && vm.busy.value) {
            IconBtn(Icons.Outlined.Stop, "Stop", Wui.Accent) { vm.stop() }
        }
        if (vm.panel.value == Panel.Chat) {
            IconBtn(Icons.Outlined.Add, "New conversation", Wui.Accent) { vm.newChat() }
        }
        IconBtn(Icons.Outlined.Link, "Connection", Wui.Muted, onConnect)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Wui.Border))
}

@Composable
private fun IconBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun DrawerBody(vm: AppVm, close: () -> Unit) {
    Column(Modifier.fillMaxHeight().background(Wui.Sidebar).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp, 18.dp, 8.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("HERMES", color = Wui.Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.6.sp)
                Text("WebUI", color = Wui.Muted, fontSize = 12.sp)
            }
            IconBtn(Icons.Outlined.Close, "Close menu", Wui.Muted, close)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("MENU", color = Wui.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, modifier = Modifier.padding(16.dp, 10.dp, 16.dp, 4.dp))
            Panel.entries.filter { it.inRail }.forEach { p ->
                val sel = vm.panel.value == p
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 1.dp)
                        .clip(WuiShapeMd)
                        .background(if (sel) Wui.AccentBg else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable {
                            vm.go(p)
                            if (p != Panel.Chat) close()
                        }
                        .padding(12.dp, 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(p.icon(), p.label, tint = if (sel) Wui.Accent else Wui.Muted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(p.label, color = if (sel) Wui.Accent else Wui.Text, fontSize = 15.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                    if (p == Panel.Chat) {
                        Icon(Icons.Outlined.Add, "New conversation", tint = Wui.Accent, modifier = Modifier.size(18.dp).clickable { vm.newChat(); close() })
                    }
                }
                if (p == Panel.Chat) {
                    ConversationDropdown(vm, close)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ConversationDropdown(vm: AppVm, close: () -> Unit) {
    var open by remember { mutableStateOf(vm.prefs.chatsExpanded) }
    val count = vm.sessions.size
    Column(Modifier.padding(start = 18.dp, end = 8.dp, bottom = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(WuiShapeMd)
                .clickable {
                    open = !open
                    vm.prefs.chatsExpanded = open
                }
                .padding(10.dp, 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (count > 0) "Conversations · $count" else "Conversations",
                color = Wui.Muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                if (open) "Hide conversations" else "Show conversations",
                tint = Wui.Muted,
                modifier = Modifier.size(18.dp),
            )
        }
        if (open) {
            ConversationList(vm, close)
        }
    }
}

@Composable
private fun ConversationList(vm: AppVm, close: () -> Unit) {
    Column {
        Row(
            Modifier
                .padding(vertical = 6.dp)
                .fillMaxWidth()
                .clip(WuiShapeMd)
                .background(Wui.InputBg)
                .border(1.dp, Wui.Border, WuiShapeMd)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, null, tint = Wui.Muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = vm.sessionQuery.value,
                onValueChange = { vm.sessionQuery.value = it },
                singleLine = true,
                textStyle = TextStyle(color = Wui.Text, fontSize = 13.sp),
                cursorBrush = SolidColor(Wui.Accent),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (vm.sessionQuery.value.isEmpty()) Text("Filter conversations…", color = Wui.Muted, fontSize = 13.sp)
                    inner()
                },
            )
        }
        val q = vm.sessionQuery.value.trim().lowercase()
        val shown = vm.sessions.filter {
            q.isEmpty() || it.displayTitle.lowercase().contains(q) || it.preview.lowercase().contains(q)
        }
        if (shown.isEmpty()) {
            Text("No conversations yet.", color = Wui.Muted, fontSize = 12.sp, modifier = Modifier.padding(12.dp, 6.dp))
        }
        shown.take(80).forEach { row ->
            val active = row.sid == vm.sid
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1.dp)
                    .clip(WuiShapeMd)
                    .background(if (active) Wui.AccentBg else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { vm.open(row); close() }
                    .padding(10.dp, 8.dp),
            ) {
                if (active) {
                    Box(Modifier.padding(end = 8.dp).width(2.dp).height(28.dp).clip(CircleShape).background(Wui.Accent))
                }
                Column(Modifier.weight(1f)) {
                    Text(row.displayTitle, color = if (active) Wui.AccentText else Wui.Text, fontSize = 13.sp, maxLines = 2)
                    Text(
                        "${row.msgCount} messages" + (row.source.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                        color = Wui.Muted,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
fun PanelHost(vm: AppVm) {
    when (vm.panel.value) {
        Panel.Chat -> ChatPane(vm)
        Panel.Tasks -> TasksPane(vm)
        Panel.Kanban -> KanbanPane(vm)
        Panel.Skills -> SkillsPane(vm)
        Panel.Memory -> MemoryPane(vm)
        Panel.Spaces -> SpacesPane(vm)
        Panel.Profiles -> ProfilesPane(vm)
        Panel.Todos -> TodosPane(vm)
        Panel.Files -> FilesPane(vm)
        Panel.Terminal -> TerminalPane(vm)
        Panel.Insights -> DashboardPane(vm)
        Panel.Logs -> LogsPane(vm)
        Panel.Dashboard -> DashboardPane(vm)
        Panel.Settings -> SettingsPane(vm)
    }
}

@Composable
private fun ConnectPane(url: String, onUrl: (String) -> Unit, dash: String, onDash: (String) -> Unit, onGo: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("HERMES", color = Wui.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text("WebUI", color = Wui.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
        Spacer(Modifier.height(12.dp))
        Text("Native client — same dark-gold chrome as the desktop WebUI. Not a WebView.", color = Wui.Muted, fontSize = 14.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(url, onUrl, placeholder = { Text("http://host:8787", color = Wui.Muted) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), colors = fieldColors(), modifier = Modifier.fillMaxWidth(), shape = WuiShapeMd)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(dash, onDash, placeholder = { Text("Hermes Console http://host:8790 (optional)", color = Wui.Muted) }, colors = fieldColors(), modifier = Modifier.fillMaxWidth(), shape = WuiShapeMd)
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(WuiShapeMd)
                .background(Wui.Accent)
                .clickable(onClick = onGo)
                .padding(14.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Continue", color = Wui.Bg, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun LoginPane(pw: String, onPw: (String) -> Unit, onGo: () -> Unit, err: String?) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("Sign in", color = Wui.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text("Same password as desktop WebUI.", color = Wui.Muted, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(pw, onPw, visualTransformation = PasswordVisualTransformation(), colors = fieldColors(), modifier = Modifier.fillMaxWidth(), shape = WuiShapeMd)
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().clip(WuiShapeMd).background(Wui.Accent).clickable(onClick = onGo).padding(14.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Sign in", color = Wui.Bg, fontWeight = FontWeight.SemiBold) }
        err?.let { Text(it, color = Wui.Danger, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable
fun ErrLine(vm: AppVm) {
    vm.error.value?.let { Text(it, color = Wui.Danger, fontSize = 12.sp, modifier = Modifier.padding(16.dp, 8.dp)) }
}

@Composable
fun SectionLabel(text: String) {
    Text(text, color = Wui.Muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
}
