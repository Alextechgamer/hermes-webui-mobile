package com.hermes.webui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

@Composable
fun ChatPane(vm: AppVm) {
    var draft by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val firstId = vm.bubbles.firstOrNull()?.id.orEmpty()
    val lastId = vm.bubbles.lastOrNull()?.id.orEmpty()
    val count = vm.bubbles.size
    val liveLen = vm.live.value.length
    val atBottom by remember {
        derivedStateOf {
            val info = list.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount == 0 || lastVisible >= info.totalItemsCount - 2
        }
    }
    suspend fun jumpLatest() {
        val idx = (list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
        runCatching { list.scrollToItem(idx) }
    }
    LaunchedEffect(firstId, lastId, count) {
        kotlinx.coroutines.yield()
        jumpLatest()
        kotlinx.coroutines.delay(48)
        jumpLatest()
    }
    LaunchedEffect(liveLen) {
        if (liveLen > 0 && atBottom) jumpLatest()
    }
    Column(Modifier.fillMaxSize().background(Wui.Bg).imePadding()) {
        ErrLine(vm)
        vm.approval.value?.let { ApprovalBanner(it, vm::approve) }
        vm.clarify.value?.let { ClarifyBanner(it, vm::answerClarify) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                state = list,
            ) {
                if (vm.truncated.value) {
                    item {
                        Text(
                            "Load full history",
                            color = Wui.Muted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).clickable { vm.loadFullHistory() },
                        )
                    }
                }
                if (vm.bubbles.isEmpty() && vm.live.value.isEmpty()) {
                    item { EmptyChat(vm) }
                }
                itemsIndexed(vm.bubbles, key = { i, b -> "${b.id}-$i" }) { _, b ->
                    MessageRow(vm, b)
                }
                if (vm.live.value.isNotEmpty()) {
                    item { MessageRow(vm, ChatMsg("live", "assistant", vm.live.value), live = true) }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
            if (!atBottom && count > 0) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Wui.Surface)
                        .border(1.dp, Wui.Accent, CircleShape)
                        .clickable {
                            scope.launch {
                                jumpLatest()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.ArrowDownward, "Latest message", tint = Wui.Accent, modifier = Modifier.size(18.dp))
                }
            }
        }
        Composer(vm, draft, { draft = it }) {
            val t = draft
            draft = ""
            keyboard?.hide()
            focus.clearFocus()
            vm.send(t)
        }
    }
}

@Composable
private fun EmptyChat(vm: AppVm) {
    Column(
        Modifier.fillMaxWidth().padding(top = 72.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("What can I help with?", color = Wui.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
        if (vm.sessions.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Recent conversations", color = Wui.Muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            vm.sessions.take(12).forEach { row ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Wui.Surface)
                        .clickable { vm.open(row) }
                        .padding(10.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(row.displayTitle, color = Wui.Text)
                    Text("${row.msgCount} messages", color = Wui.Muted, fontSize = 12.sp)
                }
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                "Same live chat as desktop Hermes WebUI. Type below — no refresh.",
                color = Wui.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

@Composable
private fun MessageRow(vm: AppVm, b: ChatMsg, live: Boolean = false) {
    when (b.role) {
        "user" -> UserBubble(b)
        "steer" -> SteerCard(b)
        "thinking" -> ThinkingCard(b)
        "tool" -> ToolCard(b)
        "system" -> Text(b.content, color = Wui.Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
        else -> AssistantBlock(vm, b, live)
    }
}

@Composable
private fun SteerCard(b: ChatMsg) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(WuiShapeMd)
            .background(Wui.AccentBg)
            .border(1.dp, Wui.AccentBgStrong, WuiShapeMd)
            .padding(10.dp, 8.dp),
    ) {
        Text("STEER", color = Wui.Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(b.content, color = Wui.Text, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        Text("Hermes will pick this up at the next tool.", color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun UserBubble(b: ChatMsg) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.End) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(WuiShapeLg)
                .background(Wui.UserBubble)
                .border(1.dp, Wui.UserBubbleBorder, WuiShapeLg)
                .padding(12.dp, 10.dp),
        ) {
            Text(b.content, color = Wui.Text, fontSize = 14.sp, lineHeight = 22.sp)
        }
    }
}

@Composable
private fun AssistantBlock(vm: AppVm, b: ChatMsg, live: Boolean) {
    val ctx = LocalContext.current
    val segs = remember(b.content) { splitChat(b.content) }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            RoleDot("H", user = false)
            Spacer(Modifier.width(8.dp))
            Text("Hermes", color = Wui.AccentText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            if (live) {
                Spacer(Modifier.width(8.dp))
                Text("streaming", color = Wui.Muted, fontSize = 10.sp)
            }
        }
        segs.forEach { seg ->
            when (seg) {
                is ChatSeg.Text -> if (seg.value.isNotBlank()) {
                    Text(seg.value, color = Wui.Text, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(bottom = 8.dp))
                }
                is ChatSeg.Mermaid -> MermaidBlock(seg.source, darkText = false)
            }
        }
        if (b.content.isNotBlank() && !live) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 2.dp)) {
                Text(
                    "Copy",
                    color = Wui.Muted,
                    fontSize = 11.sp,
                    modifier = Modifier.clickable {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("hermes", b.content))
                    },
                )
                Text("Speak", color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.clickable { vm.speak(b.content) })
            }
        }
    }
}

@Composable
private fun ThinkingCard(b: ChatMsg) {
    var open by remember { mutableStateOf(b.running) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(WuiShapeMd)
            .background(Wui.InputBg)
            .border(1.dp, Wui.Border, WuiShapeMd)
            .clickable { open = !open }
            .padding(10.dp, 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lightbulb, null, tint = Wui.Muted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (b.running) "Thinking" else "Thought", color = Wui.Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = Wui.Muted, modifier = Modifier.size(16.dp))
        }
        if (open && b.content.isNotBlank()) {
            Text(b.content, color = Wui.Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun ToolCard(b: ChatMsg) {
    var open by remember { mutableStateOf(false) }
    val label = humanTool(b.tool)
    val preview = b.preview.ifBlank { b.content }.trim()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(WuiShapeMd)
            .background(Wui.InputBg)
            .border(1.dp, Wui.Border, WuiShapeMd)
            .clickable { open = !open }
            .padding(10.dp, 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Build, null, tint = Wui.AccentText, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, color = Wui.AccentText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            if (b.running) {
                Spacer(Modifier.width(8.dp))
                Text("running", color = Wui.Muted, fontSize = 10.sp)
            }
            Spacer(Modifier.weight(1f))
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = Wui.Muted, modifier = Modifier.size(16.dp))
        }
        if (!open && preview.isNotBlank()) {
            Text(preview.take(120), color = Wui.Muted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp, start = 22.dp))
        }
        if (open && preview.isNotBlank()) {
            Text(
                preview.take(4000),
                color = Wui.Text,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private fun humanTool(raw: String): String {
    val n = raw.trim()
    return when (n.lowercase()) {
        "read_file", "read" -> "Read a file"
        "search_files", "grep", "rg" -> "Searched workspace"
        "skill_view", "skill_manage" -> "Loaded a skill"
        "terminal", "execute", "run" -> "Ran a command"
        "web_search" -> "Searched the web"
        "write_file" -> "Wrote a file"
        "patch" -> "Patched a file"
        else -> n.replace('_', ' ').replaceFirstChar { it.uppercase() }.ifBlank { "Tool" }
    }
}

@Composable
private fun Composer(vm: AppVm, draft: String, onDraft: (String) -> Unit, onSend: () -> Unit) {
    val ctx = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    var showPrompts by remember { mutableStateOf(false) }
    var showProfiles by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var showReasoning by remember { mutableStateOf(false) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.attachUris(ctx, uris)
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.startListen() else vm.error.value = "Microphone permission denied"
    }
    val workspace = vm.spaces.firstOrNull { it.last }?.name ?: vm.fsRoot.value.substringAfterLast('/').ifBlank { "Home" }
    val profile = vm.activeProfile.value.ifBlank { "default" }
    val model = vm.selectedModel.value.ifBlank { vm.models.firstOrNull()?.id.orEmpty() }
    val modelChip = ModelIds.forSend(model, vm.models.firstOrNull { it.id == model }?.provider.orEmpty())

    Column(Modifier.fillMaxWidth().background(Wui.Bg).padding(12.dp, 6.dp, 12.dp, 12.dp)) {
        if (vm.listening.value) Text("Listening — tap mic to stop", color = Wui.Accent, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
        if (vm.transcribing.value) Text("Transcribing…", color = Wui.Accent, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
        if (showPrompts && vm.prompts.isNotEmpty()) {
            ChipMenu(vm.prompts.map { it.label to it.text }) { text ->
                onDraft(if (draft.isBlank()) text else "$draft\n$text")
                showPrompts = false
            }
        }
        if (showProfiles && vm.profiles.isNotEmpty()) {
            ChipMenu(vm.profiles.map { it.name to it.name }) { name ->
                vm.switchProfile(name)
                showProfiles = false
            }
        }
        if (showModels && vm.models.isNotEmpty()) {
            ModelPicker(vm.models, vm.selectedModel.value) { m ->
                vm.pickModel(m)
                showModels = false
            }
        }
        if (showReasoning) {
            ChipMenu(vm.reasoning.value.options().map { it.second to it.first }) { effort ->
                vm.setReasoning(effort)
                showReasoning = false
            }
        }
        if (vm.pendingAttach.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                vm.pendingAttach.forEach { a ->
                    Row(
                        Modifier.clip(WuiShapeSm).background(Wui.Surface).border(1.dp, Wui.Border, WuiShapeSm).padding(8.dp, 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(a.name, color = Wui.Text, fontSize = 12.sp, maxLines = 1)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Outlined.Close, "Remove", tint = Wui.Muted, modifier = Modifier.size(14.dp).clickable { vm.dropAttach(a) })
                    }
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(WuiShapeLg)
                .background(Wui.Surface)
                .border(1.dp, if (focused) Wui.Accent else Wui.Border2, WuiShapeLg),
        ) {
            BasicTextField(
                value = draft,
                onValueChange = onDraft,
                textStyle = TextStyle(color = Wui.Text, fontSize = 16.sp, lineHeight = 24.sp),
                cursorBrush = SolidColor(Wui.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp, 12.dp, 16.dp, 4.dp)
                    .onFocusChanged { focused = it.isFocused },
                decorationBox = { inner ->
                    if (draft.isEmpty()) Text("Message Hermes…", color = Wui.Muted, fontSize = 16.sp)
                    inner()
                },
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp, 4.dp, 8.dp, 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconBtnSmall(Icons.Outlined.AttachFile, "Attach from phone") { pickFiles.launch(arrayOf("*/*")) }
                IconBtnSmall(Icons.Outlined.BookmarkBorder, "Saved prompts") { showPrompts = !showPrompts }
                IconBtnSmall(
                    if (vm.listening.value) Icons.Outlined.Stop else Icons.Outlined.Mic,
                    "Dictate",
                    tint = if (vm.listening.value) Wui.Danger else Wui.Muted,
                ) {
                    if (vm.listening.value) {
                        vm.stopListen { t -> onDraft(if (draft.isBlank()) t else "$draft $t") }
                    } else {
                        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        if (granted) vm.startListen() else mic.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                Box(Modifier.width(1.dp).height(16.dp).background(Wui.Border))
                FooterChip(Icons.Outlined.Person, profile) { showProfiles = !showProfiles }
                FooterChip(Icons.Outlined.Folder, workspace) { vm.go(Panel.Files) }
                if (modelChip.isNotBlank()) FooterChip(Icons.Outlined.Memory, modelChip.take(22)) { showModels = !showModels }
                FooterChip(Icons.Outlined.Lightbulb, "Reason ${vm.reasoning.value.label()}") {
                    showReasoning = !showReasoning
                    showModels = false
                    showPrompts = false
                    showProfiles = false
                }
                Spacer(Modifier.width(8.dp))
                if (vm.busy.value) {
                    Text("STEER", color = Wui.Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                }
                val canSend = draft.isNotBlank() || vm.pendingAttach.isNotEmpty()
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (canSend) Wui.Accent else Wui.Border)
                        .clickable(enabled = canSend) { onSend() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.ArrowUpward,
                        if (vm.busy.value) "Steer" else "Send",
                        tint = Wui.Bg,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelPicker(options: List<ModelOption>, selected: String, onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val needle = q.trim().lowercase()
    val filtered = if (needle.isEmpty()) options else options.filter {
        it.id.lowercase().contains(needle) || it.label.lowercase().contains(needle) || it.provider.lowercase().contains(needle)
    }
    val grouped = filtered.groupBy { it.provider.ifBlank { "other" } }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(WuiShapeMd)
            .background(Wui.Surface)
            .border(1.dp, Wui.Border, WuiShapeMd)
            .padding(6.dp),
    ) {
        BasicTextField(
            value = q,
            onValueChange = { q = it },
            singleLine = true,
            textStyle = TextStyle(color = Wui.Text, fontSize = 13.sp),
            cursorBrush = SolidColor(Wui.Accent),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .clip(WuiShapeSm)
                .background(Wui.Bg)
                .padding(10.dp, 8.dp),
            decorationBox = { inner ->
                if (q.isEmpty()) Text("Search models (openrouter, grok…)", color = Wui.Muted, fontSize = 13.sp)
                inner()
            },
        )
        Column(Modifier.height(280.dp).verticalScroll(rememberScrollState())) {
            if (filtered.isEmpty()) {
                Text("No models match.", color = Wui.Muted, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
            }
            grouped.forEach { (provider, rows) ->
                Text(
                    provider,
                    color = Wui.Accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(10.dp, 8.dp, 10.dp, 2.dp),
                )
                rows.take(80).forEach { m ->
                    val sel = m.id == selected
                    Text(
                        m.label.ifBlank { m.id },
                        color = if (sel) Wui.Accent else Wui.Text,
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(m.id) }.padding(10.dp, 7.dp),
                    )
                }
                if (rows.size > 80) {
                    Text("Type to filter ${rows.size - 80} more…", color = Wui.Muted, fontSize = 11.sp, modifier = Modifier.padding(10.dp, 2.dp))
                }
            }
        }
    }
}

@Composable
private fun ChipMenu(items: List<Pair<String, String>>, onPick: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(WuiShapeMd)
            .background(Wui.Surface)
            .border(1.dp, Wui.Border, WuiShapeMd)
            .padding(6.dp),
    ) {
        items.take(16).forEach { (label, value) ->
            Text(
                label,
                color = Wui.Text,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().clickable { onPick(value) }.padding(10.dp, 8.dp),
            )
        }
    }
}

@Composable
private fun FooterChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(WuiShapeSm)
            .clickable(onClick = onClick)
            .padding(8.dp, 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Wui.Muted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, color = Wui.Muted, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun IconBtnSmall(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color = Wui.Muted,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(16.dp))
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
        androidx.compose.material3.OutlinedTextField(
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
