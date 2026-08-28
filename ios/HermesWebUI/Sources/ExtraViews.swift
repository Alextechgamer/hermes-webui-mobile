import SwiftUI

struct FilesPane: View {
    @ObservedObject var store: AppStore
    @State private var newName = ""
    @State private var makingDir = false
    @State private var renameTarget: FsEntry?
    @State private var renameDraft = ""
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Workspace files for this chat — /api/list + /api/file")
                .font(.caption).foregroundColor(Palette.muted).padding(12)
            HStack {
                Button("Up") { Task { await store.fsUp() } }.foregroundColor(Palette.accent)
                Button("Refresh") { Task { await store.loadFiles(store.fsPath) } }.foregroundColor(Palette.muted)
                Button("+ File") { makingDir = false }.foregroundColor(Palette.accent)
                Button("+ Folder") { makingDir = true }.foregroundColor(Palette.accent)
                if store.fileDoc != nil {
                    Button("Close file") { store.fileDoc = nil }.foregroundColor(Palette.muted)
                }
                Spacer()
            }.padding(.horizontal, 12)
            HStack {
                TextField(makingDir ? "folder-name" : "filename.txt", text: $newName)
                    .foregroundColor(Palette.text)
                    .padding(8).background(Palette.surface).cornerRadius(8)
                Button(makingDir ? "Create folder" : "Create file") {
                    let n = newName.trimmingCharacters(in: .whitespaces)
                    guard !n.isEmpty else { return }
                    Task {
                        if makingDir { await store.createDir(n) } else { await store.createFile(n) }
                    }
                    newName = ""
                }.foregroundColor(Palette.accent)
            }.padding(.horizontal, 12).padding(.bottom, 8)
            Text((store.fsRoot.isEmpty ? "" : store.fsRoot + " / ") + store.fsPath)
                .font(.system(.caption, design: .monospaced)).foregroundColor(Palette.muted).padding(12)
            if let doc = store.fileDoc {
                Text("\(doc.path) · \(doc.lines) lines").foregroundColor(Palette.accent).padding(.horizontal, 12)
                TextEditor(text: $store.fileDraft)
                    .scrollContentBackground(.hidden)
                    .foregroundColor(Palette.text)
                    .padding(8)
                    .background(Palette.surface)
                Button("Save") { Task { await store.saveFile() } }.foregroundColor(Palette.accent).padding(12)
            } else {
                List {
                    if store.fsEntries.isEmpty {
                        Text("Nothing listed. Open a conversation so Files can use its workspace.")
                            .foregroundColor(Palette.muted).listRowBackground(Palette.bg)
                    }
                    ForEach(store.fsEntries) { e in
                        Button {
                            Task { await store.openFs(e) }
                        } label: {
                            HStack {
                                Text((e.isDir ? "▸ " : "  ") + e.name)
                                    .foregroundColor(e.isDir ? Palette.accent : Palette.text)
                                Spacer()
                                if !e.isDir && e.size > 0 { Text("\(e.size)").font(.caption).foregroundColor(Palette.muted) }
                            }
                        }
                        .listRowBackground(Palette.surface)
                        .contextMenu {
                            Button("Rename") { renameTarget = e; renameDraft = e.name }
                            Button("Delete", role: .destructive) { Task { await store.deleteFs(e) } }
                        }
                    }
                }.scrollContentBackground(.hidden).background(Palette.bg)
            }
        }
        .alert("Rename", isPresented: Binding(get: { renameTarget != nil }, set: { if !$0 { renameTarget = nil } })) {
            TextField("Name", text: $renameDraft)
            Button("Save") {
                if let e = renameTarget { Task { await store.renameFs(e, renameDraft) } }
                renameTarget = nil
            }
            Button("Cancel", role: .cancel) { renameTarget = nil }
        }
    }
}

struct TerminalPane: View {
    @ObservedObject var store: AppStore
    @State private var cmd = ""
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Embedded session terminal — /api/terminal/* (ANSI stripped)")
                .font(.caption).foregroundColor(Palette.muted).padding(12)
            HStack {
                if store.termRunning {
                    Button("Stop") { Task { await store.stopTerm() } }.foregroundColor(.red)
                } else {
                    Button("Start") { Task { await store.startTerm() } }.foregroundColor(Palette.accent)
                }
                Text(store.termRunning ? "running" : "stopped").foregroundColor(Palette.muted)
                Spacer()
            }.padding(.horizontal, 12)
            ScrollView {
                Text(store.termText.isEmpty ? "Start a shell, then type a command below." : store.termText)
                    .font(.system(.footnote, design: .monospaced))
                    .foregroundColor(Palette.text)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(10)
            }
            .background(Palette.surface)
            .padding(12)
            HStack {
                TextField("command", text: $cmd)
                    .padding(8).background(Palette.surface).cornerRadius(8).foregroundColor(Palette.text)
                Button("Send") {
                    let t = cmd; cmd = ""
                    Task { await store.termType(t) }
                }.foregroundColor(Palette.accent)
            }.padding(12)
        }
    }
}
