import SwiftUI

struct RootView: View {
    @EnvironmentObject var settings: AppSettings
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var store = AppStore()
    @State private var showMenu = false
    @State private var showConnect = false
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
        .preferredColorScheme(.dark)
        .task(id: settings.webuiURL) {
            if let url = settings.normalizedURL {
                store.attach(url: url)
                await store.bootstrap()
            }
        }
        .sheet(isPresented: $showMenu) { MenuSheet(store: store, show: $showMenu) }
        .sheet(isPresented: $showConnect) { ConnectSheet(settings: settings, store: store) }
        .onChange(of: scenePhase) { phase in
            if phase == .active { store.onForeground() }
            else if phase == .background { store.onBackground() }
        }
    }

    private var connect: some View {
        VStack(spacing: 16) {
            titlebar
            Spacer()
            Text("Hermes").font(.largeTitle.bold()).foregroundColor(Palette.text)
            Text("WebUI").font(.title).foregroundColor(Palette.accent)
            Text("Native client — same panels as the desktop WebUI. Not a WebView. Enter the URL of hermes-webui on your computer.")
                .multilineTextAlignment(.center)
                .foregroundColor(Palette.muted)
                .padding(.horizontal, 28)
            TextField("http://192.168.1.20:8787", text: $settings.webuiURL)
                .textInputAutocapitalization(.never).keyboardType(.URL).autocorrectionDisabled()
                .padding(12).background(Palette.surface).cornerRadius(10).foregroundColor(Palette.text)
                .padding(.horizontal, 28)
            TextField("Dashboard http://192.168.1.20:9119 (optional)", text: $settings.dashboardURL)
                .textInputAutocapitalization(.never).keyboardType(.URL).autocorrectionDisabled()
                .padding(12).background(Palette.surface).cornerRadius(10).foregroundColor(Palette.text)
                .padding(.horizontal, 28)
            Button("Continue") { Task { await store.bootstrap() } }
                .buttonStyle(.borderedProminent).tint(Palette.accent).foregroundColor(.black)
            Spacer()
        }
    }

    private var login: some View {
        VStack(spacing: 16) {
            titlebar
            Spacer()
            Text("Enter your password to continue").foregroundColor(Palette.muted)
            SecureField("Password", text: $password)
                .padding(12).background(Palette.surface).cornerRadius(10).padding(.horizontal, 28)
            Button("Sign in") { Task { await store.login(password) } }
                .buttonStyle(.borderedProminent).tint(Palette.accent).foregroundColor(.black)
            if let e = store.error { Text(e).foregroundColor(.red).font(.footnote) }
            Spacer()
        }
    }

    private var main: some View {
        VStack(spacing: 0) {
            titlebar
            if let e = store.error { Text(e).foregroundColor(.red).font(.footnote).padding(.horizontal, 16) }
            Group {
                switch store.panel {
                case .chat: ChatPane(store: store, draft: $draft)
                case .tasks: TasksPane(store: store)
                case .kanban: KanbanPane(store: store)
                case .skills: SkillsPane(store: store)
                case .memory: MemoryPane(store: store)
                case .spaces: SpacesPane(store: store)
                case .profiles: ProfilesPane(store: store)
                case .todos: TodosPane(store: store)
                case .files: FilesPane(store: store)
                case .terminal: TerminalPane(store: store)
                case .insights: InsightsPane(store: store)
                case .logs: LogsPane(store: store)
                case .dashboard: DashboardPane(store: store)
                case .settings: SettingsPane(store: store, settings: settings)
                }
            }
        }
    }

    private var titlebar: some View {
        HStack {
            Button { showMenu = true } label: {
                Image(systemName: "line.3.horizontal").foregroundColor(Palette.text)
            }
            VStack(alignment: .leading, spacing: 1) {
                Text("Hermes").font(.headline).foregroundColor(Palette.text)
                Text(store.panel == .chat ? store.title : store.panel.label)
                    .font(.caption).foregroundColor(Palette.muted).lineLimit(1)
            }
            Spacer()
            if store.panel == .chat {
                if store.busy {
                    Button { Task { await store.stop() } } label: {
                        Image(systemName: "stop.circle").foregroundColor(Palette.accent)
                    }
                }
                Button { Task { await store.newChat() } } label: {
                    Image(systemName: "plus").foregroundColor(Palette.accent)
                }
            }
            Button { showConnect = true } label: {
                Image(systemName: "gearshape").foregroundColor(Palette.text)
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .background(Palette.sidebar)
    }
}

struct MenuSheet: View {
    @ObservedObject var store: AppStore
    @Binding var show: Bool
    @AppStorage("chatsExpanded") private var chatsExpanded = false

    var body: some View {
        NavigationStack {
            List {
                Section("Hermes WebUI") {
                    ForEach(Panel.allCases) { p in
                        Button(p.label) {
                            if p != .chat { show = false }
                            Task { await store.go(p) }
                        }
                        .foregroundColor(store.panel == p ? Palette.accent : Palette.text)
                        .listRowBackground(Palette.surface)
                        if p == .chat {
                            Button {
                                chatsExpanded.toggle()
                            } label: {
                                HStack {
                                    Text(store.sessions.isEmpty ? "Conversations" : "Conversations · \(store.sessions.count)")
                                    Spacer()
                                    Image(systemName: chatsExpanded ? "chevron.up" : "chevron.down")
                                }
                                .font(.subheadline)
                                .foregroundColor(Palette.muted)
                            }
                            .listRowBackground(Palette.surface)
                            if chatsExpanded {
                                TextField("Filter conversations…", text: $store.sessionQuery)
                                    .listRowBackground(Palette.surface)
                                ForEach(filtered) { row in
                                    Button {
                                        show = false
                                        Task { await store.open(row) }
                                    } label: {
                                        VStack(alignment: .leading) {
                                            Text(row.displayTitle).foregroundColor(row.sid == store.currentSid ? Palette.accent : Palette.text)
                                            Text("\(row.msgCount) messages").font(.caption).foregroundColor(Palette.muted)
                                        }
                                    }
                                    .listRowBackground(Palette.surface)
                                }
                            }
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(Palette.bg)
            .navigationTitle("Menu")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { show = false } }
                ToolbarItem(placement: .primaryAction) {
                    Button("New") { Task { await store.newChat(); show = false } }
                }
            }
            .task { await store.loadSessions() }
        }
    }

    private var filtered: [SessionRow] {
        let q = store.sessionQuery.trimmingCharacters(in: .whitespaces).lowercased()
        if q.isEmpty { return store.sessions }
        return store.sessions.filter { $0.displayTitle.lowercased().contains(q) || ($0.preview ?? "").lowercased().contains(q) }
    }
}

struct ConnectSheet: View {
    @ObservedObject var settings: AppSettings
    @ObservedObject var store: AppStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("Connection") {
                    TextField("WebUI URL", text: $settings.webuiURL)
                        .textInputAutocapitalization(.never).keyboardType(.URL)
                    TextField("Dashboard URL (optional)", text: $settings.dashboardURL)
                        .textInputAutocapitalization(.never).keyboardType(.URL)
                }
            }
            .navigationTitle("Connection")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    Button("Save") {
                        if let url = settings.normalizedURL {
                            store.attach(url: url)
                            Task { await store.bootstrap() }
                        }
                        dismiss()
                    }
                }
            }
        }
    }
}
