package com.hermes.webui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

@Composable
fun ChatPane(vm: AppVm) {
    var draft by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(vm.bubbles.size, vm.live.value.length) {
        val last = vm.bubbles.size + if (vm.live.value.isNotEmpty()) 1 else 0
        if (last > 0) runCatching { list.animateScrollToItem(last) }
    }
    Column(Modifier.fillMaxSize().background(Wui.Bg)) {
        ErrLine(vm)
        vm.approval.value?.let { ApprovalBanner(it, vm::approve) }
        vm.clarify.value?.let { ClarifyBanner(it, vm::answerClarify) }
        if (vm.truncated.value) {
            Text(
                "Load full history",
                color = Wui.Accent,
                fontSize = 12.sp,
                modifier = Modifier.padding(20.dp, 8.dp).clickable { vm.loadFullHistory() },
            )
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            state = list,
        ) {
            if (vm.bubbles.isEmpty() && vm.live.value.isEmpty()) {
                item { EmptyChat() }
            }
            itemsIndexed(vm.bubbles, key = { i, b -> "${b.id}-$i" }) { _, b ->
                MessageRow(vm, b)
            }
            if (vm.live.value.isNotEmpty()) {
                item { MessageRow(vm, ChatMsg("live", "assistant", vm.live.value), live = true) }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
        Composer(vm, draft, { draft = it }) {
            val t = draft
            draft = ""
            vm.send(t)
        }
    }
}

@Composable
private fun EmptyChat() {
    Column(
        Modifier.fillMaxWidth().padding(top = 72.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("What can I help with?", color = Wui.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Same live chat as desktop Hermes WebUI. Type below — no refresh.",
            color = Wui.Muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Composable
private fun MessageRow(vm: AppVm, b: ChatMsg, live: Boolean = false) {
    val mine = b.role == "user"
    val segs = remember(b.content) { splitChat(b.content) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        if (!mine) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                RoleDot(if (b.tool.isNotBlank()) "T" else "H", user = false)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        b.tool.isNotBlank() -> b.tool
                        live -> "Hermes"
                        else -> "Hermes"
                    },
                    color = Wui.AccentText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (live) {
                    Spacer(Modifier.width(8.dp))
                    Text("streaming", color = Wui.Muted, fontSize = 10.sp)
                }
            }
        }
        val bubbleMod = if (mine) {
            Modifier
                .widthIn(max = 320.dp)
                .clip(WuiShapeLg)
                .background(Wui.UserBubble)
                .border(1.dp, Wui.UserBubbleBorder, WuiShapeLg)
                .padding(10.dp, 10.dp)
        } else {
            Modifier.fillMaxWidth()
        }
        Column(bubbleMod) {
            segs.forEach { seg ->
                when (seg) {
                    is ChatSeg.Text -> if (seg.value.isNotBlank()) {
                        Text(
                            seg.value,
                            color = if (b.role == "system") Wui.Muted else Wui.Text,
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                        )
                    }
                    is ChatSeg.Mermaid -> MermaidBlock(seg.source, darkText = false)
                }
            }
            if (b.tool.isNotBlank() && b.content.isBlank()) {
                Text("tool call", color = Wui.Muted, fontSize = 12.sp)
            }
            if (!mine && b.content.isNotBlank() && !live) {
                Text(
                    "Speak",
                    color = Wui.Muted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp).clickable { vm.speak(b.content) },
                )
            }
        }
    }
}

@Composable
private fun Composer(vm: AppVm, draft: String, onDraft: (String) -> Unit, onSend: () -> Unit) {
    val ctx = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.startListen() else vm.error.value = "Microphone permission denied"
    }
    Column(Modifier.fillMaxWidth().background(Wui.Bg).padding(12.dp, 8.dp, 12.dp, 14.dp)) {
        if (vm.models.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                vm.models.take(16).forEach { m ->
                    WuiChip(m.take(28), selected = vm.selectedModel.value == m) { vm.selectedModel.value = m }
                }
            }
        }
        if (vm.speakReplies.value || vm.listening.value || vm.transcribing.value) {
            Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (vm.listening.value) Text("Listening — tap mic to stop", color = Wui.Accent, fontSize = 11.sp)
                if (vm.transcribing.value) Text("Transcribing…", color = Wui.Accent, fontSize = 11.sp)
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(WuiShapeLg)
                .background(Wui.Surface)
                .border(1.dp, if (focused) Wui.Accent else Wui.Border, WuiShapeLg)
                .padding(bottom = 8.dp),
        ) {
            BasicTextField(
                value = draft,
                onValueChange = onDraft,
                textStyle = TextStyle(color = Wui.Text, fontSize = 16.sp, lineHeight = 24.sp),
                cursorBrush = SolidColor(Wui.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp, 12.dp, 16.dp, 6.dp)
                    .onFocusChanged { focused = it.isFocused },
                decorationBox = { inner ->
                    if (draft.isEmpty()) Text("Message Hermes…", color = Wui.Muted, fontSize = 16.sp)
                    inner()
                },
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (vm.speakReplies.value) "Speak on" else "Speak",
                    color = if (vm.speakReplies.value) Wui.AccentText else Wui.Muted,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(WuiShapeSm)
                        .clickable { vm.speakReplies.value = !vm.speakReplies.value }
                        .padding(8.dp, 6.dp),
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable {
                            if (vm.listening.value) {
                                vm.stopListen { t -> onDraft(if (draft.isBlank()) t else "$draft $t") }
                            } else {
                                val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                if (granted) vm.startListen() else mic.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (vm.listening.value) Icons.Outlined.Stop else Icons.Outlined.Mic,
                        "Dictate",
                        tint = if (vm.listening.value) Wui.Danger else Wui.Muted,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                val canSend = draft.isNotBlank() && !vm.busy.value
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (vm.busy.value || canSend) Wui.Accent else Wui.Border)
                        .clickable(enabled = vm.busy.value || canSend) {
                            if (vm.busy.value) vm.stop() else onSend()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (vm.busy.value) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward,
                        if (vm.busy.value) "Stop" else "Send",
                        tint = Wui.Bg,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ApprovalBanner(a: Approval, on: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(12.dp, 8.dp)
            .clip(WuiShapeMd)
            .background(Wui.AccentBg)
            .border(1.dp, Wui.AccentBgStrong, WuiShapeMd)
            .padding(12.dp),
    ) {
        Text("Approval needed · ${a.tool}", color = Wui.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (a.detail.isNotBlank()) Text(a.detail, color = Wui.Text, fontSize = 12.sp, maxLines = 6, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("once" to "Once", "session" to "Session", "always" to "Always", "deny" to "Deny").forEach { (k, label) ->
                WuiChip(label, selected = k != "deny") { on(k) }
            }
        }
    }
}

@Composable
private fun ClarifyBanner(q: Clarify, on: (String) -> Unit) {
    var other by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(12.dp, 8.dp)
            .clip(WuiShapeMd)
            .background(Wui.Surface)
            .border(1.dp, Wui.Border, WuiShapeMd)
            .padding(12.dp),
    ) {
        Text(q.question.ifBlank { "The agent needs a choice" }, color = Wui.Text, fontSize = 13.sp)
        q.choices.forEach { c ->
            Text(c, color = Wui.Accent, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp).clickable { on(c) })
        }
        OutlinedTextField(
            other,
            { other = it },
            placeholder = { Text("Or type a reply", color = Wui.Muted) },
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            shape = WuiShapeMd,
        )
        Text("Send", color = Wui.Accent, modifier = Modifier.padding(top = 8.dp).clickable { if (other.isNotBlank()) on(other) })
    }
}
