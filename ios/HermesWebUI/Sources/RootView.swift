import SwiftUI

struct RootView: View {
    @EnvironmentObject var settings: AppSettings
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var store = AppStore()
    @State private var showMenu = false
    @State private var showConnect = false
    @State private var draft = ""
    @State private var password = ""
    @State private var urlDraft = ""
    @State private var dashDraft = ""

    var body: some View {
        ZStack {
            Palette.bg.ignoresSafeArea()
            VStack(spacing: 0) {
                if store.ready { topbar }
                Group {
                    if store.needsLogin {
                        login
                    } else if store.ready {
                        panelHost
                    } else {
                        connect
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            if showMenu { drawerOverlay }
        }
        .preferredColorScheme(.dark)
        .task {
            if urlDraft.isEmpty { urlDraft = settings.webuiURL }
            if dashDraft.isEmpty { dashDraft = settings.dashboardURL }
            guard !store.ready, !store.needsLogin else { return }
            let saved = settings.webuiURL.trimmingCharacters(in: .whitespacesAndNewlines)
            guard saved.contains(".") || saved.contains("://"), let url = settings.normalizedURL else { return }
            store.attach(url: url)
            await store.bootstrap()
        }
        .sheet(isPresented: $showConnect) { ConnectSheet(settings: settings, store: store) }
        .onChange(of: scenePhase) { phase in
            if phase == .active { store.onForeground() }
            else if phase == .background { store.onBackground() }
        }
        .onAppear {
            if password.isEmpty { password = settings.password }
            if urlDraft.isEmpty { urlDraft = settings.webuiURL }
            if dashDraft.isEmpty { dashDraft = settings.dashboardURL }
        }
    }

    private var connect: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("HERMES")
                    .font(.system(size: 13, weight: .bold))
                    .tracking(2)
                    .foregroundColor(Palette.accent)
                Text("WebUI")
                    .font(.system(size: 28, weight: .semibold))
                    .tracking(-0.4)
                    .foregroundColor(Palette.text)
                Text("Native client — same dark-gold chrome as the desktop WebUI. Not a WebView.")
                    .font(.system(size: 14))
                    .foregroundColor(Palette.muted)
                    .fixedSize(horizontal: false, vertical: true)
                TextField("", text: $urlDraft, prompt: Text("http://host:8787").foregroundColor(Palette.muted))
                    .textContentType(.username)
                    .textInputAutocapitalization(.never).keyboardType(.URL).autocorrectionDisabled()
                    .submitLabel(.next)
                    .padding(14)
                    .background(Palette.surface)
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Palette.border, lineWidth: 1))
                    .cornerRadius(12)
                    .foregroundColor(Palette.text)
                SecureField("", text: $password, prompt: Text("Password (same as desktop WebUI)").foregroundColor(Palette.muted))
                    .textContentType(.password)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.continue)
                    .padding(14)
                    .background(Palette.surface)
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Palette.border, lineWidth: 1))
                    .cornerRadius(12)
                    .foregroundColor(Palette.text)
                    .onSubmit { connectNow() }
                TextField("", text: $dashDraft, prompt: Text("Hermes Console http://host:8790 (optional)").foregroundColor(Palette.muted))
                    .textInputAutocapitalization(.never).keyboardType(.URL).autocorrectionDisabled()
                    .padding(14)
                    .background(Palette.surface)
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Palette.border, lineWidth: 1))
                    .cornerRadius(12)
                    .foregroundColor(Palette.text)
                Button(action: connectNow) {
                    HStack {
                        if store.bootstrapping {
                            ProgressView().tint(Palette.bg)
                        }
                        Text(store.bootstrapping ? "Connecting…" : "Continue")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(Palette.bg)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(14)
                    .background(
                        (store.bootstrapping || urlDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                            ? Palette.accent.opacity(0.45) : Palette.accent
                    )
                    .cornerRadius(12)
                }
                .disabled(store.bootstrapping || urlDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                if let e = store.error {
                    Text(e).font(.system(size: 12)).foregroundColor(Palette.danger)
                }
            }
            .padding(28)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .scrollDismissesKeyboard(.interactively)
    }

    private func connectNow() {
        let urlText = urlDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !urlText.isEmpty else { return }
        settings.webuiURL = urlText
        settings.dashboardURL = dashDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        if !password.isEmpty { settings.password = password }
        guard let url = settings.normalizedURL else { return }
        store.attach(url: url)
        Task { await store.bootstrap() }
    }

    private var login: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Sign in").font(.system(size: 22, weight: .semibold)).foregroundColor(Palette.text)
            Text("Saved on this phone and offered to Proton Pass / iCloud Keychain.")
                .font(.system(size: 13)).foregroundColor(Palette.muted)
            TextField("", text: $urlDraft, prompt: Text("WebUI address").foregroundColor(Palette.muted))
                .textContentType(.username)
                .textInputAutocapitalization(.never).keyboardType(.URL).autocorrectionDisabled()
                .padding(14)
                .background(Palette.surface)
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Palette.border, lineWidth: 1))
                .cornerRadius(12)
                .foregroundColor(Palette.text)
            SecureField("Password", text: $password)
                .textContentType(.password)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(14)
                .background(Palette.surface)
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Palette.border, lineWidth: 1))
                .cornerRadius(12)
            Button {
                if !urlDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    settings.webuiURL = urlDraft
                }
                settings.password = password
                Task { await store.login(password) }
            } label: {
                Text("Sign in")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(Palette.bg)
                    .frame(maxWidth: .infinity)
                    .padding(14)
                    .background(Palette.accent)
                    .cornerRadius(12)
            }
            if let e = store.error {
                Text(e).font(.system(size: 12)).foregroundColor(Palette.danger)
            }
        }
        .padding(28)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
    }

    private var panelHost: some View {
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

    private var topbar: some View {
        VStack(spacing: 0) {
            HStack(spacing: 6) {
                Button { withAnimation(.easeOut(duration: 0.2)) { showMenu = true } } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "line.3.horizontal")
                        Text("Menu").font(.system(size: 13, weight: .semibold))
                    }
                    .foregroundColor(Palette.accent)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 6)
                }
                VStack(alignment: .leading, spacing: 1) {
                    Text(barTitle)
                        .font(.system(size: 15, weight: .semibold))
                        .tracking(-0.15)
                        .foregroundColor(Palette.text)
                        .lineLimit(1)
                    if store.panel == .chat && store.busy {
                        Text("Hermes is working…")
                            .font(.system(size: 11))
                            .foregroundColor(Palette.accentText)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if store.panel == .chat && store.busy {
                    Button { Task { await store.stop() } } label: {
                        Image(systemName: "stop.circle").foregroundColor(Palette.accent)
                    }
                    .frame(width: 40, height: 40)
                }
                if store.panel == .chat {
                    Button { Task { await store.newChat() } } label: {
                        Image(systemName: "plus").foregroundColor(Palette.accent)
                    }
                    .frame(width: 40, height: 40)
                }
                Button { showConnect = true } label: {
                    Image(systemName: "link").foregroundColor(Palette.muted)
                }
                .frame(width: 40, height: 40)
            }
            .padding(.horizontal, 6)
            .padding(.vertical, 10)
            .background(Palette.sidebar)
            Rectangle().fill(Palette.border).frame(height: 1)
        }
    }

    private var barTitle: String {
        if store.panel == .chat && !store.title.isEmpty { return store.title }
        return store.panel.label
    }

    private var drawerOverlay: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Color.black.opacity(0.45)
                    .ignoresSafeArea()
                    .onTapGesture { withAnimation(.easeOut(duration: 0.2)) { showMenu = false } }
                DrawerBody(store: store, show: $showMenu)
                    .padding(.top, geo.safeAreaInsets.top)
                    .padding(.bottom, geo.safeAreaInsets.bottom)
                    .frame(width: min(geo.size.width * 0.92, 360), alignment: .topLeading)
                    .frame(maxHeight: .infinity)
                    .background(Palette.sidebar.ignoresSafeArea(edges: .vertical))
            }
        }
        .transition(.opacity)
    }
}

struct DrawerBody: View {
    @ObservedObject var store: AppStore
    @Binding var show: Bool
    @AppStorage("chatsExpanded") private var chatsExpanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("HERMES")
                        .font(.system(size: 13, weight: .bold))
                        .tracking(1.6)
                        .foregroundColor(Palette.accent)
                    Text("WebUI").font(.system(size: 12)).foregroundColor(Palette.muted)
                }
                Spacer()
                Button { withAnimation(.easeOut(duration: 0.2)) { show = false } } label: {
                    Image(systemName: "xmark").foregroundColor(Palette.muted).frame(width: 40, height: 40)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 8)

            ScrollView {
                VStack(alignment: .leading, spacing: 1) {
                    Text("MENU")
                        .font(.system(size: 11, weight: .semibold))
                        .tracking(1.2)
                        .foregroundColor(Palette.muted)
                        .padding(.horizontal, 16)
                        .padding(.top, 10)
                        .padding(.bottom, 4)
                    ForEach(Panel.allCases.filter(\.inRail)) { p in
                        let sel = store.panel == p
                        HStack(spacing: 12) {
                            Image(systemName: p.symbol)
                                .font(.system(size: 16))
                                .foregroundColor(sel ? Palette.accent : Palette.muted)
                                .frame(width: 18)
                            Text(p.label)
                                .font(.system(size: 15, weight: sel ? .semibold : .regular))
                                .foregroundColor(sel ? Palette.accent : Palette.text)
                            Spacer()
                            if p == .chat {
                                Button {
                                    Task { await store.newChat(); withAnimation { show = false } }
                                } label: {
                                    Image(systemName: "plus").foregroundColor(Palette.accent)
                                }
                            }
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 11)
                        .background(sel ? Palette.accentBg : Color.clear)
                        .cornerRadius(12)
                        .padding(.horizontal, 8)
                        .contentShape(Rectangle())
                        .onTapGesture {
                            Task { await store.go(p) }
                            if p != .chat { withAnimation { show = false } }
                        }
                        if p == .chat { conversationDropdown }
                    }
                }
                .padding(.bottom, 24)
            }
        }
        .background(Palette.sidebar)
        .task { await store.loadSessions() }
    }

    private var conversationDropdown: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                chatsExpanded.toggle()
            } label: {
                HStack {
                    Text(store.sessions.isEmpty ? "Conversations" : "Conversations · \(store.sessions.count)")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(Palette.muted)
                    Spacer()
                    Image(systemName: chatsExpanded ? "chevron.up" : "chevron.down")
                        .font(.system(size: 12))
                        .foregroundColor(Palette.muted)
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
            }
            if chatsExpanded {
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass").foregroundColor(Palette.muted).font(.system(size: 13))
                    TextField("Filter conversations…", text: $store.sessionQuery)
                        .foregroundColor(Palette.text)
                        .font(.system(size: 13))
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                .background(Palette.inputBg)
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Palette.border, lineWidth: 1))
                .cornerRadius(12)
                .padding(.vertical, 6)

                if filtered.isEmpty {
                    Text("No conversations yet.")
                        .font(.system(size: 12))
                        .foregroundColor(Palette.muted)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                }
                ForEach(filtered.prefix(80)) { row in
                    let active = row.sid == store.currentSid
                    Button {
                        withAnimation { show = false }
                        Task { await store.open(row) }
                    } label: {
                        HStack(alignment: .top, spacing: 8) {
                            if active {
                                RoundedRectangle(cornerRadius: 1)
                                    .fill(Palette.accent)
                                    .frame(width: 2, height: 28)
                            }
                            VStack(alignment: .leading, spacing: 2) {
                                Text(row.displayTitle)
                                    .font(.system(size: 13))
                                    .foregroundColor(active ? Palette.accentText : Palette.text)
                                    .lineLimit(2)
                                    .multilineTextAlignment(.leading)
                                Text("\(row.msgCount) messages" + ((row.source?.isEmpty == false) ? " · \(row.source!)" : ""))
                                    .font(.system(size: 11))
                                    .foregroundColor(Palette.muted)
                            }
                            Spacer(minLength: 0)
                        }
                        .padding(10)
                        .background(active ? Palette.accentBg : Color.clear)
                        .cornerRadius(12)
                    }
                }
            }
        }
        .padding(.leading, 18)
        .padding(.trailing, 8)
        .padding(.bottom, 8)
    }

    private var filtered: [SessionRow] {
        let q = store.sessionQuery.trimmingCharacters(in: .whitespaces).lowercased()
        if q.isEmpty { return store.sessions }
        return store.sessions.filter {
            $0.displayTitle.lowercased().contains(q) || ($0.preview ?? "").lowercased().contains(q)
        }
    }
}

struct ConnectSheet: View {
    @ObservedObject var settings: AppSettings
    @ObservedObject var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var urlDraft = ""
    @State private var dashDraft = ""
    @State private var password = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Connection") {
                    TextField("WebUI URL", text: $urlDraft)
                        .textContentType(.username)
                        .textInputAutocapitalization(.never).keyboardType(.URL)
                    SecureField("Password", text: $password)
                        .textContentType(.password)
                    TextField("Hermes Console URL (optional)", text: $dashDraft)
                        .textInputAutocapitalization(.never).keyboardType(.URL)
                        .textContentType(.none)
                }
            }
            .scrollContentBackground(.hidden)
            .background(Palette.bg)
            .navigationTitle("Connection")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    Button("Save") {
                        settings.webuiURL = urlDraft.trimmingCharacters(in: .whitespacesAndNewlines)
                        settings.dashboardURL = dashDraft.trimmingCharacters(in: .whitespacesAndNewlines)
                        if !password.isEmpty { settings.password = password }
                        if let url = settings.normalizedURL {
                            store.attach(url: url)
                            Task { await store.bootstrap() }
                        }
                        dismiss()
                    }
                    .foregroundColor(Palette.accent)
                }
            }
            .onAppear {
                urlDraft = settings.webuiURL
                dashDraft = settings.dashboardURL
                password = settings.password
            }
        }
        .preferredColorScheme(.dark)
    }
}
