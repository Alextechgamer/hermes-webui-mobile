import SwiftUI

struct FilesPane: View {
    @ObservedObject var store: AppStore
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Workspace files for this chat — /api/list + /api/file")
                .font(.caption).foregroundColor(Palette.muted).padding(12)
            HStack {
                Button("Up") { Task { await store.fsUp() } }.foregroundColor(Palette.accent)
                Button("Refresh") { Task { await store.loadFiles(store.fsPath) } }.foregroundColor(Palette.muted)
                if store.fileDoc != nil {
                    Button("Close file") { store.fileDoc = nil }.foregroundColor(Palette.muted)
                }
                Spacer()
            }.padding(.horizontal, 12)
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
                        }.listRowBackground(Palette.surface)
                    }
                }.scrollContentBackground(.hidden).background(Palette.bg)
            }
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
