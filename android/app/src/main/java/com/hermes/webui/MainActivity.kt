package com.hermes.webui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { App() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: AppVm = viewModel()) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var urlDraft by remember { mutableStateOf(vm.prefs.baseUrl) }
    var dashDraft by remember { mutableStateOf(vm.prefs.dashboardUrl) }
    var password by remember { mutableStateOf("") }
    var showConnect by remember { mutableStateOf(false) }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Wui.Sidebar, modifier = Modifier.fillMaxWidth(0.82f)) {
                Text("HERMES", color = Wui.Text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(20.dp, 20.dp, 20.dp, 4.dp))
                Text("WebUI", color = Wui.Accent, modifier = Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Wui.Border)
                Panel.entries.forEach { p ->
                    val sel = vm.panel.value == p
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (sel) Wui.Surface else Wui.Sidebar)
                            .clickable {
                                vm.go(p)
                                scope.launch { drawer.close() }
                            }
                            .padding(20.dp, 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(p.label, color = if (sel) Wui.Accent else Wui.Text, fontSize = 15.sp)
                    }
                }
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
                            if (vm.ready.value) Text(vm.panel.value.label, color = Wui.Muted, fontSize = 11.sp)
                        }
                    },
                    actions = {
                        if (vm.ready.value && vm.panel.value == Panel.Chat) {
                            IconButton(onClick = { vm.newChat() }) { Icon(Icons.Outlined.Add, "New conversation", tint = Wui.Accent) }
                        }
                        IconButton(onClick = { showConnect = true }) { Icon(Icons.Outlined.Settings, "Connection", tint = Wui.Text) }
                    },
                )
            },
            bottomBar = {
                if (vm.ready.value && vm.panel.value == Panel.Chat) {
                    Column(Modifier.background(Wui.Sidebar)) {
                        if (vm.models.isNotEmpty()) {
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp)) {
                                vm.models.take(12).forEach { m ->
                                    Text(m, color = Wui.Muted, fontSize = 10.sp, modifier = Modifier.padding(end = 10.dp))
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Bottom) {
                            OutlinedTextField(
                                value = draft, onValueChange = { draft = it },
                                placeholder = { Text("Message Hermes…", color = Wui.Muted) },
                                modifier = Modifier.weight(1f),
                                colors = fieldColors(),
                            )
                            Spacer(Modifier.width(8.dp))
                            if (vm.busy.value) {
                                IconButton(onClick = { vm.stop() }) { Icon(Icons.Outlined.Stop, "Stop", tint = Wui.Accent) }
                            } else {
                                IconButton(
                                    onClick = { val t = draft; draft = ""; vm.send(t) },
                                    enabled = draft.isNotBlank(),
                                ) { Icon(Icons.Outlined.Send, "Send", tint = Wui.Accent) }
                            }
                        }
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize().background(Wui.Bg)) {
                when {
                    !vm.configured.value -> ConnectPane(urlDraft, { urlDraft = it }, dashDraft, { dashDraft = it }) {
                        vm.saveUrl(urlDraft, dashDraft)
                    }
                    vm.needsLogin.value -> LoginPane(password, { password = it }, { vm.login(password) }, vm.error.value)
                    else -> when (vm.panel.value) {
                        Panel.Chat -> ChatPane(vm)
                        Panel.Settings -> SettingsPane(vm)
                        Panel.Dashboard -> DashboardPane(vm)
                        else -> ListPane(vm)
                    }
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
private fun ConnectPane(url: String, onUrl: (String) -> Unit, dash: String, onDash: (String) -> Unit, onGo: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("Hermes", color = Wui.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("WebUI", color = Wui.Accent, fontSize = 22.sp)
        Spacer(Modifier.height(12.dp))
        Text("Enter the URL of hermes-webui on your computer. Same screens as the desktop UI.", color = Wui.Muted, fontSize = 14.sp)
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
        err?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
    }
}

@Composable
private fun ChatPane(vm: AppVm) {
    Column(Modifier.fillMaxSize()) {
        if (vm.sessions.isNotEmpty() && vm.bubbles.isEmpty() && vm.live.value.isEmpty()) {
            Text("Conversations", color = Wui.Muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp, 8.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(vm.sessions, key = { it.sid.ifBlank { it.displayTitle } }) { row ->
                    Column(Modifier.fillMaxWidth().clickable { vm.open(row) }.padding(16.dp, 10.dp)) {
                        Text(row.displayTitle, color = Wui.Text)
                        Text("${row.msgCount} messages" + (row.source?.let { " · $it" } ?: ""), color = Wui.Muted, fontSize = 12.sp)
                    }
                    HorizontalDivider(color = Wui.Border)
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(vm.bubbles) { _, b -> BubbleView(b) }
                if (vm.live.value.isNotEmpty()) item { BubbleView(Bubble("assistant", vm.live.value)) }
                vm.error.value?.let { item { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) } }
            }
        }
    }
}

@Composable
private fun ListPane(vm: AppVm) {
    Column(Modifier.fillMaxSize()) {
        vm.error.value?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        if (vm.detail.value.isNotBlank() && vm.rows.isEmpty()) {
            Text(
                vm.detail.value,
                color = Wui.Text,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                if (vm.panel.value == Panel.Tasks) {
                    item {
                        Text("Scheduled jobs — tap Run / Pause", color = Wui.Muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp, 8.dp))
                    }
                }
                items(vm.rows) { row ->
                    Column(Modifier.fillMaxWidth().padding(16.dp, 10.dp)) {
                        Text(row.title, color = Wui.Text)
                        if (row.subtitle.isNotBlank()) Text(row.subtitle, color = Wui.Muted, fontSize = 12.sp)
                        if (vm.panel.value == Panel.Tasks && row.id.isNotBlank()) {
                            Row(Modifier.padding(top = 6.dp)) {
                                Text("Run", color = Wui.Accent, modifier = Modifier.clickable { vm.cronAction(row.id, "run") }.padding(end = 16.dp))
                                Text("Pause", color = Wui.Muted, modifier = Modifier.clickable { vm.cronAction(row.id, "pause") }.padding(end = 16.dp))
                                Text("Resume", color = Wui.Muted, modifier = Modifier.clickable { vm.cronAction(row.id, "resume") })
                            }
                        }
                    }
                    HorizontalDivider(color = Wui.Border)
                }
                if (vm.rows.isEmpty()) item {
                    Text(vm.detail.value.ifBlank { "Nothing here yet." }, color = Wui.Muted, modifier = Modifier.padding(24.dp))
                }
            }
        }
    }
}

@Composable
private fun DashboardPane(vm: AppVm) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Hermes Dashboard", color = Wui.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("Official dashboard is :9119. Usage dashboard is :8790. Status below is live from this WebUI.", color = Wui.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
        vm.rows.forEach { row ->
            Text(row.title, color = Wui.Accent, modifier = Modifier.padding(top = 8.dp))
            Text(row.subtitle, color = Wui.Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Text(vm.detail.value, color = Wui.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun SettingsPane(vm: AppVm) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text("WebUI settings — same keys as Control Center", color = Wui.Muted, modifier = Modifier.padding(16.dp)) }
        if (vm.models.isNotEmpty()) item {
            Text("Models: " + vm.models.take(8).joinToString(), color = Wui.Muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 8.dp))
        }
        items(vm.settingsItems, key = { it.first }) { (k, v) ->
            Column(Modifier.padding(16.dp, 8.dp)) {
                Text(k, color = Wui.Accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Text(v.take(200), color = Wui.Text, fontSize = 13.sp)
            }
            HorizontalDivider(color = Wui.Border)
        }
    }
}

@Composable
private fun BubbleView(b: Bubble) {
    val mine = b.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 320.dp)
                .background(if (mine) Wui.Accent else Wui.Surface, RoundedCornerShape(12.dp))
                .padding(10.dp),
        ) {
            if (!b.tool.isNullOrBlank()) Text("⚙ ${b.tool}", color = if (mine) Wui.Bg else Wui.Accent, fontSize = 12.sp)
            if (b.text.isNotEmpty()) Text(b.text, color = if (mine) Wui.Bg else Wui.Text, fontSize = 14.sp)
        }
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Wui.Text, unfocusedTextColor = Wui.Text,
    focusedBorderColor = Wui.Accent, unfocusedBorderColor = Wui.Border,
    cursorColor = Wui.Accent, focusedLabelColor = Wui.Muted, unfocusedLabelColor = Wui.Muted,
)
