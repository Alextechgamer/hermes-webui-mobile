import SwiftUI

struct RootView: View {
    @EnvironmentObject var settings: AppSettings
    @StateObject private var store = AppStore()
    @State private var showSettings = false
    @State private var showSessions = false
    @State private var draft = ""
    @State private var password = ""

    var body: some View {
        ZStack {
            Palette.bg.ignoresSafeArea()
            if !settings.isConfigured {
                connect
            } else if store.needsLogin {
                login
            } else if store.loggedIn || !store.authEnabled {
                main
            } else {
                ProgressView().tint(Palette.accent)
            }
        }
        .task(id: settings.webuiURL) {
            if let url = settings.normalizedURL {
                store.attach(url: url)
                await store.bootstrap()
            }
        }
    }

    private var connect: some View {
        VStack(spacing: 16) {
            titlebar
            Spacer()
            Text("Connect to Hermes WebUI")
                .font(.title2.weight(.semibold)).foregroundColor(Palette.text)
            Text("Enter the URL of hermes-webui on your computer. Nothing is hardcoded.")
                .multilineTextAlignment(.center)
                .foregroundColor(Palette.muted)
                .padding(.horizontal, 28)
            TextField("http://192.168.1.20:8787", text: $settings.webuiURL)
                .textInputAutocapitalization(.never)
                .keyboardType(.URL)
                .autocorrectionDisabled()
                .padding(12)
                .background(Palette.surface)
                .cornerRadius(10)
                .foregroundColor(Palette.text)
                .padding(.horizontal, 28)
            TextField("Dashboard http://192.168.1.20:9119 (optional)", text: $settings.dashboardURL)
                .textInputAutocapitalization(.never)
                .keyboardType(.URL)
                .autocorrectionDisabled()
                .padding(12)
                .background(Palette.surface)
                .cornerRadius(10)
                .foregroundColor(Palette.text)
                .padding(.horizontal, 28)
            Button("Continue") {
                Task { await store.bootstrap() }
            }
            .buttonStyle(.borderedProminent)
            .tint(Palette.accent)
            .foregroundColor(.black)
            Spacer()
        }
    }

    private var login: some View {
        VStack(spacing: 16) {
            titlebar
            Spacer()
            Text("Enter your password to continue")
                .foregroundColor(Palette.muted)
            SecureField("Password", text: $password)
                .padding(12).background(Palette.surface).cornerRadius(10)
                .padding(.horizontal, 28)
            Button("Sign in") { Task { await store.login(password) } }
                .buttonStyle(.borderedProminent).tint(Palette.accent).foregroundColor(.black)
            if let e = store.error { Text(e).foregroundColor(.red).font(.footnote) }
            Spacer()
        }
    }

    private var main: some View {
        VStack(spacing: 0) {
            titlebar
            ScrollView {
                if store.panel != .chat {
                    Text(store.detail.isEmpty ? "Loading…" : store.detail)
                        .font(.system(.footnote, design: .monospaced))
                        .foregroundColor(Palette.text)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(16)
                } else {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        ForEach(store.messages, id: \.displayId) { m in
                            bubble(m)
                        }
                        if !store.liveText.isEmpty {
                            bubble(ChatMessage(role: "assistant", content: store.liveText))
                        }
                    }
                    .padding(16)
                }
            }
            composer
        }
        .sheet(isPresented: $showSessions) { menuSheet }
        .sheet(isPresented: $showSettings) { SettingsSheet(store: store, settings: settings) }
    }

    private var titlebar: some View {
        HStack {
            Button { showSessions = true } label: {
                Image(systemName: "line.3.horizontal").foregroundColor(Palette.text)
            }
            Text("Hermes").font(.headline).foregroundColor(Palette.text)
            Spacer()
            Button { Task { await store.newChat() } } label: {
                Image(systemName: "plus").foregroundColor(Palette.accent)
            }
            Button { showSettings = true } label: {
                Image(systemName: "gearshape").foregroundColor(Palette.text)
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .background(Palette.sidebar)
    }

    private var composer: some View {
        Group {
            if store.panel == .chat {
                HStack(alignment: .bottom) {
                    TextField("Message Hermes…", text: $draft, axis: .vertical)
                        .lineLimit(1...6)
                        .padding(10)
                        .background(Palette.surface)
                        .cornerRadius(10)
                        .foregroundColor(Palette.text)
                    Button {
                        let t = draft; draft = ""
                        Task { await store.send(t) }
                    } label: {
                        Image(systemName: "arrow.up.circle.fill")
                            .font(.system(size: 28))
                            .foregroundColor(Palette.accent)
                    }
                    .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || store.busy)
                }
                .padding(12)
                .background(Palette.sidebar)
            }
        }
    }

    private func bubble(_ m: ChatMessage) -> some View {
        let mine = m.role == "user"
        return HStack {
            if mine { Spacer(minLength: 40) }
            VStack(alignment: .leading, spacing: 4) {
                if let tool = m.tool_name, !tool.isEmpty {
                    Text("⚙ \(tool)").font(.caption).foregroundColor(Palette.accent)
                }
                if !m.content.isEmpty {
                    Text(m.content).foregroundColor(mine ? .black : Palette.text)
                }
            }
            .padding(10)
            .background(mine ? Palette.accent : Palette.surface)
            .cornerRadius(12)
            if !mine { Spacer(minLength: 40) }
        }
    }

    private var menuSheet: some View {
        NavigationStack {
            List {
                Section("Hermes WebUI") {
                    ForEach(Panel.allCases) { p in
                        Button(p.label) {
                            showSessions = false
                            Task { await store.go(p) }
                        }
                        .foregroundColor(store.panel == p ? Palette.accent : Palette.text)
                        .listRowBackground(Palette.surface)
                    }
                }
                Section("Conversations") {
                    ForEach(store.sessions) { row in
                        Button {
                            showSessions = false
                            Task { await store.open(row) }
                        } label: {
                            VStack(alignment: .leading) {
                                Text(row.displayTitle).foregroundColor(Palette.text)
                                Text("\(row.msgCount) messages").font(.caption).foregroundColor(Palette.muted)
                            }
                        }
                        .listRowBackground(Palette.surface)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(Palette.bg)
            .navigationTitle("Menu")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { showSessions = false }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button("New") { Task { await store.newChat(); showSessions = false } }
                }
            }
            .task { await store.loadSessions() }
        }
    }
}

struct SettingsSheet: View {
    @ObservedObject var store: AppStore
    @ObservedObject var settings: AppSettings
    @Environment(\.dismiss) private var dismiss
    @State private var items: [(key: String, type: String, value: String)] = []
    @State private var edits: [String: String] = [:]

    var body: some View {
        NavigationStack {
            List {
                Section("Connection") {
                    TextField("WebUI URL", text: $settings.webuiURL)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                }
                Section("WebUI settings") {
                    ForEach(items, id: \.key) { it in
                        if it.type == "bool" {
                            Toggle(it.key, isOn: Binding(
                                get: { (edits[it.key] ?? it.value) == "true" },
                                set: { edits[it.key] = $0 ? "true" : "false" }
                            ))
                        } else {
                            VStack(alignment: .leading) {
                                Text(it.key).font(.caption).foregroundColor(Palette.muted)
                                TextField("", text: Binding(
                                    get: { edits[it.key] ?? it.value },
                                    set: { edits[it.key] = $0 }
                                ))
                            }
                        }
                    }
                }
            }
            .navigationTitle("Settings")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    Button("Save") { Task { await save() } }
                }
            }
            .task { await load() }
        }
    }

    func load() async {
        guard let c = store.client else { return }
        do {
            let d = try await c.settingsJSON()
            items = d.keys.sorted().compactMap { k in
                if k == "password_hash" { return nil }
                let v = d[k]
                if let b = v as? Bool { return (k, "bool", b ? "true" : "false") }
                if let n = v as? NSNumber { return (k, "number", n.stringValue) }
                if let s = v as? String { return (k, "string", s) }
                return (k, "json", String(describing: v ?? ""))
            }
        } catch {}
    }

    func save() async {
        guard let c = store.client else { return }
        var body: [String: Any] = [:]
        for (k, v) in edits {
            if v == "true" || v == "false" { body[k] = (v == "true") }
            else if let n = Int(v) { body[k] = n }
            else { body[k] = v }
        }
        guard !body.isEmpty else { dismiss(); return }
        try? await c.saveSettings(body)
        dismiss()
    }
}
