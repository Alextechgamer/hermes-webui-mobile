package com.hermes.webui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

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
    var showSessions by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var urlDraft by remember { mutableStateOf(vm.prefs.baseUrl) }
    var password by remember { mutableStateOf("") }

    Scaffold(
        containerColor = Wui.Bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Wui.Sidebar, titleContentColor = Wui.Text),
                navigationIcon = {
                    if (vm.ready.value) IconButton(onClick = { showSessions = true; vm.refreshSessions() }) {
                        Icon(Icons.Outlined.Menu, "Conversations", tint = Wui.Text)
                    }
                },
                title = { Text(if (vm.ready.value) vm.title.value else "Hermes", color = Wui.Text) },
                actions = {
                    if (vm.ready.value) {
                        IconButton(onClick = { vm.newChat() }) { Icon(Icons.Outlined.Add, "New", tint = Wui.Accent) }
                    }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Outlined.Settings, "Settings", tint = Wui.Text) }
                },
            )
        },
        bottomBar = {
            if (vm.ready.value) {
                Row(
                    Modifier.fillMaxWidth().background(Wui.Sidebar).padding(10.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it },
                        placeholder = { Text("Message Hermes…", color = Wui.Muted) },
                        modifier = Modifier.weight(1f),
                        colors = fieldColors(),
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = { val t = draft; draft = ""; vm.send(t) },
                        enabled = draft.isNotBlank() && !vm.busy.value,
                    ) { Icon(Icons.Outlined.Send, "Send", tint = Wui.Accent) }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(Wui.Bg)) {
            when {
                !vm.configured.value -> ConnectPane(urlDraft, { urlDraft = it }, { vm.saveUrl(urlDraft) })
                vm.needsLogin.value -> LoginPane(password, { password = it }, { vm.login(password) }, vm.error.value)
                else -> ChatPane(vm)
            }
        }
    }

    if (showSessions) {
        ModalBottomSheet(
            onDismissRequest = { showSessions = false },
            containerColor = Wui.Sidebar,
        ) {
            Text("Conversations", color = Wui.Text, modifier = Modifier.padding(16.dp), fontSize = 18.sp)
            LazyColumn {
                items(vm.sessions, key = { it.sid.ifBlank { it.displayTitle } }) { row ->
                    Column(
                        Modifier.fillMaxWidth().clickable { vm.open(row); showSessions = false }.padding(16.dp),
                    ) {
                        Text(row.displayTitle, color = Wui.Text)
                        Text("${row.msgCount} messages", color = Wui.Muted, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            containerColor = Wui.Surface,
            title = { Text("Connection", color = Wui.Text) },
            text = {
                Column {
                    OutlinedTextField(
                        value = urlDraft, onValueChange = { urlDraft = it },
                        label = { Text("WebUI URL") },
                        placeholder = { Text("http://192.168.1.20:8787") },
                        colors = fieldColors(),
                    )
                    vm.error.value?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.saveUrl(urlDraft); showSettings = false }) {
                    Text("Save", color = Wui.Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettings = false }) { Text("Close", color = Wui.Muted) }
            },
        )
    }
}

@Composable
private fun ConnectPane(url: String, onUrl: (String) -> Unit, onGo: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("Connect to Hermes WebUI", color = Wui.Text, fontSize = 22.sp)
        Spacer(Modifier.height(8.dp))
        Text("Enter the URL of hermes-webui on your computer. Nothing is hardcoded.", color = Wui.Muted, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = url, onValueChange = onUrl,
            placeholder = { Text("http://192.168.1.20:8787", color = Wui.Muted) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGo, colors = ButtonDefaults.buttonColors(containerColor = Wui.Accent, contentColor = Wui.Bg), modifier = Modifier.fillMaxWidth()) {
            Text("Continue")
        }
    }
}

@Composable
private fun LoginPane(pw: String, onPw: (String) -> Unit, onGo: () -> Unit, err: String?) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("Enter your password to continue", color = Wui.Muted)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = pw, onValueChange = onPw,
            visualTransformation = PasswordVisualTransformation(),
            colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onGo, colors = ButtonDefaults.buttonColors(containerColor = Wui.Accent, contentColor = Wui.Bg), modifier = Modifier.fillMaxWidth()) {
            Text("Sign in")
        }
        err?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
    }
}

@Composable
private fun ChatPane(vm: AppVm) {
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(vm.bubbles) { i, b -> BubbleView(b) }
        if (vm.live.value.isNotEmpty()) item { BubbleView(Bubble("assistant", vm.live.value)) }
        vm.error.value?.let { item { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) } }
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
            if (!b.tool.isNullOrBlank()) Text("⚙ ${b.tool}", color = Wui.Accent, fontSize = 12.sp)
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
