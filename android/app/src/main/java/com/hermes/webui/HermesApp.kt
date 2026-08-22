package com.hermes.webui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HermesApp(vm: AppVm = viewModel()) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showConnect by remember { mutableStateOf(false) }
    var urlDraft by remember { mutableStateOf(vm.prefs.baseUrl) }
    var dashDraft by remember { mutableStateOf(vm.prefs.dashboardUrl) }
    var password by remember { mutableStateOf("") }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = vm.ready.value,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Wui.Sidebar,
                modifier = Modifier.fillMaxWidth(0.86f),
            ) {
                DrawerBody(vm) { scope.launch { drawer.close() } }
            }
        },
    ) {
        Scaffold(
            containerColor = Wui.Bg,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Wui.Sidebar, titleContentColor = Wui.Text),
                    navigationIcon = {
                        if (vm.ready.value) IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Outlined.Menu, "Menu", tint = Wui.Text)
                        }
                    },
                    title = {
                        Column {
                            Text("Hermes", color = Wui.Text, fontWeight = FontWeight.SemiBold)
                            if (vm.ready.value) {
                                val sub = if (vm.panel.value == Panel.Chat && vm.title.value.isNotBlank()) vm.title.value else vm.panel.value.label
                                Text(sub, color = Wui.Muted, fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    },
                    actions = {
                        if (vm.ready.value && vm.panel.value == Panel.Chat) {
                            if (vm.busy.value) {
                                IconButton(onClick = { vm.stop() }) { Icon(Icons.Outlined.Stop, "Stop", tint = Wui.Accent) }
                            }
                            IconButton(onClick = { vm.newChat() }) { Icon(Icons.Outlined.Add, "New conversation", tint = Wui.Accent) }
                        }
                        IconButton(onClick = { showConnect = true }) { Icon(Icons.Outlined.Settings, "Connection", tint = Wui.Text) }
                    },
                )
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize().background(Wui.Bg)) {
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
            title = { Text("Connection", color = Wui.Text) },
            text = {
                Column {
                    OutlinedTextField(urlDraft, { urlDraft = it }, label = { Text("WebUI URL") }, placeholder = { Text("http://192.168.1.20:8787") }, colors = fieldColors())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(dashDraft, { dashDraft = it }, label = { Text("Dashboard URL (optional)") }, placeholder = { Text("http://192.168.1.20:9119") }, colors = fieldColors())
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.saveUrl(urlDraft, dashDraft); showConnect = false }) { Text("Save", color = Wui.Accent) }
            },
            dismissButton = { TextButton(onClick = { showConnect = false }) { Text("Close", color = Wui.Muted) } },
        )
    }
}

@Composable
private fun DrawerBody(vm: AppVm, close: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("HERMES", color = Wui.Text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(20.dp, 20.dp, 20.dp, 2.dp))
        Text("WebUI", color = Wui.Accent, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = Wui.Border)
        Panel.entries.forEach { p ->
            val sel = vm.panel.value == p
            Text(
                p.label,
                color = if (sel) Wui.Accent else Wui.Text,
                fontSize = 15.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (sel) Wui.Surface else Wui.Sidebar)
                    .clickable { vm.go(p); close() }
                    .padding(20.dp, 12.dp),
            )
        }
        HorizontalDivider(color = Wui.Border)
        Text("Conversations", color = Wui.Muted, fontSize = 12.sp, modifier = Modifier.padding(20.dp, 12.dp, 20.dp, 4.dp))
        OutlinedTextField(
            value = vm.sessionQuery.value,
            onValueChange = { vm.sessionQuery.value = it },
            placeholder = { Text("Search", color = Wui.Muted) },
            colors = fieldColors(),
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            singleLine = true,
        )
        val q = vm.sessionQuery.value.trim().lowercase()
        val shown = vm.sessions.filter {
            q.isEmpty() || it.displayTitle.lowercase().contains(q) || it.preview.lowercase().contains(q)
        }
        shown.take(80).forEach { row ->
            Column(
                Modifier.fillMaxWidth().clickable { vm.open(row); close() }.padding(20.dp, 10.dp),
            ) {
                Text(row.displayTitle, color = if (row.sid == vm.sid) Wui.Accent else Wui.Text, fontSize = 14.sp, maxLines = 2)
                Text(
                    "${row.msgCount} messages" + (row.source.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                    color = Wui.Muted,
                    fontSize = 11.sp,
                )
            }
        }
        if (shown.isEmpty()) Text("No conversations yet.", color = Wui.Muted, modifier = Modifier.padding(20.dp))
        Spacer(Modifier.height(24.dp))
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
        Panel.Insights -> InsightsPane(vm)
        Panel.Logs -> LogsPane(vm)
        Panel.Dashboard -> DashboardPane(vm)
        Panel.Settings -> SettingsPane(vm)
    }
}

@Composable
private fun ConnectPane(url: String, onUrl: (String) -> Unit, dash: String, onDash: (String) -> Unit, onGo: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("Hermes", color = Wui.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("WebUI", color = Wui.Accent, fontSize = 22.sp)
        Spacer(Modifier.height(12.dp))
        Text("Native client — same panels as the desktop WebUI. Not a WebView. Enter the URL of hermes-webui on your computer.", color = Wui.Muted, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(url, onUrl, placeholder = { Text("http://192.168.1.20:8787", color = Wui.Muted) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), colors = fieldColors(), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(dash, onDash, placeholder = { Text("Dashboard http://192.168.1.20:9119 (optional)", color = Wui.Muted) }, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGo, colors = ButtonDefaults.buttonColors(containerColor = Wui.Accent, contentColor = Wui.Bg), modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    }
}

@Composable
private fun LoginPane(pw: String, onPw: (String) -> Unit, onGo: () -> Unit, err: String?) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("Enter your password to continue", color = Wui.Muted)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(pw, onPw, visualTransformation = PasswordVisualTransformation(), colors = fieldColors(), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Button(onClick = onGo, colors = ButtonDefaults.buttonColors(containerColor = Wui.Accent, contentColor = Wui.Bg), modifier = Modifier.fillMaxWidth()) { Text("Sign in") }
        err?.let { Text(it, color = Wui.Danger, fontSize = 12.sp) }
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Wui.Text, unfocusedTextColor = Wui.Text,
    focusedBorderColor = Wui.Accent, unfocusedBorderColor = Wui.Border,
    cursorColor = Wui.Accent, focusedLabelColor = Wui.Muted, unfocusedLabelColor = Wui.Muted,
    focusedPlaceholderColor = Wui.Muted, unfocusedPlaceholderColor = Wui.Muted,
    focusedContainerColor = Wui.Surface, unfocusedContainerColor = Wui.Surface,
)

@Composable
fun ErrLine(vm: AppVm) {
    vm.error.value?.let { Text(it, color = Wui.Danger, fontSize = 12.sp, modifier = Modifier.padding(16.dp, 8.dp)) }
}

@Composable
fun SectionLabel(text: String) {
    Text(text, color = Wui.Muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp, 10.dp, 16.dp, 4.dp))
}
