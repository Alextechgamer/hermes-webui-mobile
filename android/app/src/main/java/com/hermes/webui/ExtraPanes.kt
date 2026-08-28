package com.hermes.webui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FilesPane(vm: AppVm) {
    val doc = vm.fileDoc.value
    var newName by remember { mutableStateOf("") }
    var makingDir by remember { mutableStateOf(false) }
    var menuPath by remember { mutableStateOf<String?>(null) }
    var renamePath by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var movePath by remember { mutableStateOf<String?>(null) }
    var moveDraft by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        ErrLine(vm)
        SectionLabel("Workspace files for this chat session — same /api/list + /api/file as desktop")
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Up", color = Wui.Accent, modifier = Modifier.clickable { vm.fsUp() }.padding(8.dp))
            Text("Refresh", color = Wui.Muted, modifier = Modifier.clickable { vm.loadFiles(vm.fsPath.value) }.padding(8.dp))
            Text("+ File", color = Wui.Accent, modifier = Modifier.clickable { makingDir = false }.padding(8.dp))
            Text("+ Folder", color = Wui.Accent, modifier = Modifier.clickable { makingDir = true }.padding(8.dp))
            if (doc != null) Text("Close file", color = Wui.Muted, modifier = Modifier.clickable { vm.closeFile() }.padding(8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(12.dp, 0.dp, 12.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                newName, { newName = it },
                placeholder = { Text(if (makingDir) "folder-name" else "filename.txt", color = Wui.Muted) },
                colors = fieldColors(), singleLine = true, modifier = Modifier.weight(1f),
            )
            Text(if (makingDir) "Create folder" else "Create file", color = Wui.Accent, modifier = Modifier.clickable {
                if (newName.isNotBlank()) {
                    if (makingDir) vm.createDir(newName.trim()) else vm.createFile(newName.trim())
                    newName = ""
                }
            }.padding(8.dp))
        }
        Text(
            (if (vm.fsRoot.value.isNotBlank()) vm.fsRoot.value + " / " else "") + vm.fsPath.value,
            color = Wui.Muted,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 8.dp),
        )
        if (doc != null) {
            Text(doc.path + " · ${doc.lines} lines", color = Wui.Accent, modifier = Modifier.padding(16.dp, 4.dp))
            OutlinedTextField(
                vm.fileDraft.value,
                { vm.fileDraft.value = it },
                colors = fieldColors(),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp),
            )
            TextButton(onClick = { vm.saveFile() }, modifier = Modifier.padding(12.dp)) {
                Text("Save", color = Wui.Accent)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                if (vm.fsEntries.isEmpty()) item {
                    Text("Nothing listed. Open a conversation so Files can use its workspace.", color = Wui.Muted, modifier = Modifier.padding(20.dp))
                }
                items(vm.fsEntries, key = { it.path }) { e ->
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.openFs(e) }.padding(16.dp, 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text((if (e.isDir) "▸ " else "  ") + e.name, color = if (e.isDir) Wui.Accent else Wui.Text, modifier = Modifier.weight(1f))
                            if (!e.isDir && e.size > 0) Text("${e.size}", color = Wui.Muted, fontSize = 11.sp)
                            Text("···", color = Wui.Muted, modifier = Modifier.clickable { menuPath = if (menuPath == e.path) null else e.path }.padding(start = 8.dp))
                        }
                        if (renamePath == e.path) {
                            Row(Modifier.padding(16.dp, 0.dp, 16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(renameDraft, { renameDraft = it }, colors = fieldColors(), singleLine = true, modifier = Modifier.weight(1f))
                                Text("Save", color = Wui.Accent, modifier = Modifier.clickable {
                                    if (renameDraft.isNotBlank()) vm.renameFs(e, renameDraft)
                                    renamePath = null
                                }.padding(8.dp))
                            }
                        }
                        if (menuPath == e.path) {
                            Row(Modifier.padding(16.dp, 0.dp, 16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Rename", color = Wui.Accent, modifier = Modifier.clickable { renamePath = e.path; renameDraft = e.name; menuPath = null })
                                Text("Move", color = Wui.Accent, modifier = Modifier.clickable { movePath = e.path; moveDraft = vm.fsPath.value; menuPath = null })
                                Text("Delete", color = Wui.Danger, modifier = Modifier.clickable { vm.deleteFs(e); menuPath = null })
                            }
                        }
                        if (movePath == e.path) {
                            Row(Modifier.padding(16.dp, 0.dp, 16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(moveDraft, { moveDraft = it }, colors = fieldColors(), singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("dest folder", color = Wui.Muted) })
                                Text("Go", color = Wui.Accent, modifier = Modifier.clickable {
                                    if (moveDraft.isNotBlank()) vm.moveFs(e, moveDraft)
                                    movePath = null
                                }.padding(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TerminalPane(vm: AppVm) {
    var cmd by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        ErrLine(vm)
        SectionLabel("Embedded session terminal — /api/terminal/*  (ANSI stripped on phone)")
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.termRunning.value) {
                Text("Stop", color = Wui.Danger, modifier = Modifier.clickable { vm.stopTerm() }.padding(8.dp))
            } else {
                Text("Start", color = Wui.Accent, modifier = Modifier.clickable { vm.startTerm() }.padding(8.dp))
            }
            Text(if (vm.termRunning.value) "running" else "stopped", color = Wui.Muted, modifier = Modifier.padding(8.dp))
            Text("Resize", color = Wui.Accent, modifier = Modifier.clickable { vm.resizeTerm() }.padding(8.dp))
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                vm.termRows.value.toString(),
                { vm.termRows.value = it.toIntOrNull()?.coerceIn(8, 80) ?: vm.termRows.value },
                label = { Text("rows") }, colors = fieldColors(), singleLine = true, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                vm.termCols.value.toString(),
                { vm.termCols.value = it.toIntOrNull()?.coerceIn(20, 200) ?: vm.termCols.value },
                label = { Text("cols") }, colors = fieldColors(), singleLine = true, modifier = Modifier.weight(1f),
            )
        }
        Text(
            vm.termText.value.ifBlank { "Start a shell, then type a command below." },
            color = Wui.Text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(12.dp)
                .background(Wui.Surface, RoundedCornerShape(8.dp))
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(10.dp),
        )
        Row(Modifier.padding(10.dp)) {
            OutlinedTextField(
                cmd,
                { cmd = it },
                placeholder = { Text("command + enter", color = Wui.Muted) },
                colors = fieldColors(),
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Text(
                "Send",
                color = Wui.Accent,
                modifier = Modifier.padding(10.dp).clickable {
                    if (cmd.isNotBlank()) {
                        vm.termType(cmd)
                        cmd = ""
                    }
                },
            )
        }
    }
}
