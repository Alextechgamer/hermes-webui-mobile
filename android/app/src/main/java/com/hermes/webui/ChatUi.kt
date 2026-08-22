package com.hermes.webui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

@Composable
fun ChatPane(vm: AppVm) {
    var draft by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(vm.bubbles.size, vm.live.value.length) {
        val last = vm.bubbles.size + if (vm.live.value.isNotEmpty()) 1 else 0
        if (last > 0) runCatching { list.animateScrollToItem(last) }
    }
    Column(Modifier.fillMaxSize()) {
        ErrLine(vm)
        vm.approval.value?.let { ApprovalBanner(it, vm::approve) }
        vm.clarify.value?.let { ClarifyBanner(it, vm::answerClarify) }
        if (vm.truncated.value) {
            TextButton(onClick = { vm.loadFullHistory() }) { Text("Load full history", color = Wui.Accent) }
        }
        if (vm.bubbles.isEmpty() && vm.live.value.isEmpty()) {
            Text(
                "New conversation. Same chat API as desktop WebUI — type below.",
                color = Wui.Muted,
                modifier = Modifier.padding(20.dp),
            )
        }
        LazyColumn(
            Modifier.weight(1f).padding(12.dp),
            state = list,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(vm.bubbles, key = { i, b -> "${b.id}-$i" }) { _, b -> BubbleView(vm, b) }
            if (vm.live.value.isNotEmpty()) item { BubbleView(vm, ChatMsg("live", "assistant", vm.live.value)) }
        }
        Composer(vm, draft, { draft = it }) {
            val t = draft
            draft = ""
            vm.send(t)
        }
    }
}

@Composable
private fun Composer(vm: AppVm, draft: String, onDraft: (String) -> Unit, onSend: () -> Unit) {
    val ctx = LocalContext.current
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.startListen() else vm.error.value = "Microphone permission denied"
    }
    Column(Modifier.background(Wui.Sidebar)) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text(
                if (vm.speakReplies.value) "Speak replies on" else "Speak replies off",
                color = if (vm.speakReplies.value) Wui.Accent else Wui.Muted,
                fontSize = 11.sp,
                modifier = Modifier.clickable { vm.speakReplies.value = !vm.speakReplies.value }.padding(6.dp),
            )
            if (vm.transcribing.value) Text("Transcribing…", color = Wui.Accent, fontSize = 11.sp, modifier = Modifier.padding(6.dp))
            if (vm.listening.value) Text("Listening — tap mic to stop", color = Wui.Accent, fontSize = 11.sp, modifier = Modifier.padding(6.dp))
        }
        if (vm.models.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp)) {
                vm.models.take(16).forEach { m ->
                    val sel = vm.selectedModel.value == m
                    Text(
                        m,
                        color = if (sel) Wui.Accent else Wui.Muted,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .background(if (sel) Wui.Surface else Wui.Sidebar, RoundedCornerShape(8.dp))
                            .clickable { vm.selectedModel.value = m }
                            .padding(6.dp, 4.dp),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                placeholder = { Text("Message Hermes…", color = Wui.Muted) },
                modifier = Modifier.weight(1f),
                colors = fieldColors(),
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = {
                if (vm.listening.value) {
                    vm.stopListen { t -> onDraft(if (draft.isBlank()) t else "$draft $t") }
                } else {
                    val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (granted) vm.startListen() else mic.launch(Manifest.permission.RECORD_AUDIO)
                }
            }) {
                Icon(
                    if (vm.listening.value) Icons.Outlined.Stop else Icons.Outlined.Mic,
                    "Dictate",
                    tint = if (vm.listening.value) Wui.Danger else Wui.Accent,
                )
            }
            if (vm.busy.value) {
                IconButton(onClick = { vm.stop() }) { Icon(Icons.Outlined.Stop, "Stop", tint = Wui.Accent) }
            } else {
                IconButton(onClick = onSend, enabled = draft.isNotBlank()) {
                    Icon(Icons.Outlined.Send, "Send", tint = if (draft.isNotBlank()) Wui.Accent else Wui.Muted)
                }
            }
        }
    }
}

@Composable
private fun ApprovalBanner(a: Approval, on: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Wui.Surface).padding(12.dp)) {
        Text("Approval needed · ${a.tool}", color = Wui.Accent, fontSize = 13.sp)
        if (a.detail.isNotBlank()) Text(a.detail, color = Wui.Text, fontSize = 12.sp, maxLines = 6)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("once" to "Once", "session" to "Session", "always" to "Always", "deny" to "Deny").forEach { (k, label) ->
                Text(
                    label,
                    color = if (k == "deny") Wui.Danger else Wui.Accent,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .background(Wui.Bg, RoundedCornerShape(8.dp))
                        .clickable { on(k) }
                        .padding(10.dp, 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ClarifyBanner(q: Clarify, on: (String) -> Unit) {
    var other by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().background(Wui.Surface).padding(12.dp)) {
        Text(q.question.ifBlank { "The agent needs a choice" }, color = Wui.Text, fontSize = 13.sp)
        q.choices.forEach { c ->
            Text(
                c,
                color = Wui.Accent,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp).clickable { on(c) },
            )
        }
        OutlinedTextField(
            other,
            { other = it },
            placeholder = { Text("Or type a reply", color = Wui.Muted) },
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        TextButton(onClick = { if (other.isNotBlank()) on(other) }) { Text("Send", color = Wui.Accent) }
    }
}

@Composable
private fun BubbleView(vm: AppVm, b: ChatMsg) {
    val mine = b.role == "user"
    val segs = remember(b.content) { splitChat(b.content) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 340.dp)
                .background(if (mine) Wui.Accent else Wui.Surface, RoundedCornerShape(12.dp))
                .padding(10.dp),
        ) {
            if (b.tool.isNotBlank()) Text("⚙ ${b.tool}", color = if (mine) Wui.Bg else Wui.Accent, fontSize = 12.sp)
            segs.forEach { seg ->
                when (seg) {
                    is ChatSeg.Text -> if (seg.value.isNotBlank()) {
                        Text(seg.value, color = if (mine) Wui.Bg else if (b.role == "system") Wui.Muted else Wui.Text, fontSize = 14.sp)
                    }
                    is ChatSeg.Mermaid -> MermaidBlock(seg.source, darkText = mine)
                }
            }
            if (!mine && b.content.isNotBlank()) {
                IconButton(onClick = { vm.speak(b.content) }) {
                    Icon(Icons.Outlined.VolumeUp, "Speak", tint = Wui.Accent)
                }
            }
        }
    }
}
