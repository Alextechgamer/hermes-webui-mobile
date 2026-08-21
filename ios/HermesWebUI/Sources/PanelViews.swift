import SwiftUI

private let kanbanNext = [
    "triage": "todo", "todo": "ready", "ready": "blocked",
    "blocked": "todo", "running": "done", "done": "todo",
]

struct TasksPane: View {
    @ObservedObject var store: AppStore
    @State private var open: String?
    var body: some View {
        List {
            Text("Scheduled jobs — same /api/crons as the desktop Tasks tab")
                .font(.caption).foregroundColor(Palette.muted).listRowBackground(Palette.bg)
            ForEach(store.jobs) { job in
                VStack(alignment: .leading, spacing: 4) {
                    Text(job.name).foregroundColor(Palette.text)
                    Text([job.schedule, job.paused ? "paused" : (job.enabled ? "on" : "off"), job.owner].filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(.caption).foregroundColor(Palette.muted)
                    if !job.readOnly {
                        HStack {
                            Button("Run") { Task { await store.cronAction(job.id, "run") } }
                            Button(job.paused ? "Resume" : "Pause") { Task { await store.cronAction(job.id, job.paused ? "resume" : "pause") } }
                            Button("Output") { open = job.id; Task { await store.loadJobOutput(job.id) } }
                        }.foregroundColor(Palette.accent).font(.caption)
                    }
                    if open == job.id, !store.jobOutput.isEmpty {
                        Text(String(store.jobOutput.prefix(4000))).font(.system(.footnote, design: .monospaced)).foregroundColor(Palette.text)
                    }
                }
                .listRowBackground(Palette.surface)
            }
            if store.jobs.isEmpty { Text("No cron jobs.").foregroundColor(Palette.muted).listRowBackground(Palette.bg) }
        }
        .scrollContentBackground(.hidden).background(Palette.bg)
    }
}

struct KanbanPane: View {
    @ObservedObject var store: AppStore
    var body: some View {
        ScrollView(.horizontal) {
            HStack(alignment: .top, spacing: 10) {
                ForEach(store.columns) { col in
                    VStack(alignment: .leading, spacing: 8) {
                        Text("\(col.name) · \(col.tasks.count)").foregroundColor(Palette.accent).bold()
                        ForEach(col.tasks) { task in
                            VStack(alignment: .leading) {
                                Text(task.title).foregroundColor(Palette.text).font(.subheadline)
                                if !task.assignee.isEmpty { Text(task.assignee).font(.caption).foregroundColor(Palette.muted) }
                            }
                            .padding(8).frame(maxWidth: .infinity, alignment: .leading)
                            .background(Palette.bg).cornerRadius(8)
                            .onTapGesture {
                                if !task.id.isEmpty {
                                    Task { await store.moveTask(task.id, kanbanNext[task.status] ?? "todo") }
                                }
                            }
                        }
                    }
                    .padding(10).frame(width: 240, alignment: .top)
                    .background(Palette.surface).cornerRadius(12)
                }
                if store.columns.isEmpty { Text("No board data.").foregroundColor(Palette.muted).padding() }
            }.padding(12)
        }
    }
}

struct SkillsPane: View {
    @ObservedObject var store: AppStore
    @State private var open: String?
    var body: some View {
        List {
            Text("Installed skills — toggle writes the active profile")
                .font(.caption).foregroundColor(Palette.muted).listRowBackground(Palette.bg)
            ForEach(store.skills) { s in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        VStack(alignment: .leading) {
                            Text(s.name).foregroundColor(Palette.text)
                            if !s.category.isEmpty { Text(s.category).font(.caption).foregroundColor(Palette.accent) }
                        }
                        Spacer()
                        Toggle("", isOn: Binding(
                            get: { !s.disabled },
                            set: { _ in Task { await store.toggleSkill(s) } }
                        )).labelsHidden()
                    }
                    if !s.description.isEmpty { Text(s.description).font(.caption).foregroundColor(Palette.muted).lineLimit(3) }
                    if open == s.name, !store.skillBody.isEmpty {
                        Text(String(store.skillBody.prefix(6000))).font(.system(.footnote, design: .monospaced)).foregroundColor(Palette.text)
                    }
                }
                .contentShape(Rectangle())
                .onTapGesture { open = s.name; Task { await store.openSkill(s.name) } }
                .listRowBackground(Palette.surface)
            }
        }
        .scrollContentBackground(.hidden).background(Palette.bg)
    }
}

struct MemoryPane: View {
    @ObservedObject var store: AppStore
    @State private var tab = "memory"
    @State private var draft = ""
    var body: some View {
        VStack(alignment: .leading) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    ForEach(["memory", "user", "soul", "project"], id: \.self) { k in
                        let label = k == "project" ? (store.memory.projectName.isEmpty ? "Project" : store.memory.projectName) : k.uppercased()
                        Text(label)
                            .foregroundColor(tab == k ? Palette.accent : Palette.muted)
                            .padding(8).background(Palette.surface).cornerRadius(8)
                            .onTapGesture { tab = k; draft = text(for: k) }
                    }
                }.padding(12)
            }
            TextEditor(text: $draft)
                .scrollContentBackground(.hidden)
                .foregroundColor(Palette.text)
                .padding(8)
                .background(Palette.surface)
            if tab != "project" {
                Button("Save \(tab)") { Task { await store.saveMemory(tab, draft) } }
                    .foregroundColor(Palette.accent).padding(12)
            }
        }
        .onAppear { draft = text(for: tab) }
        .onChange(of: tab) { _ in draft = text(for: tab) }
    }

    private func text(for tab: String) -> String {
        switch tab {
        case "user": return store.memory.user
        case "soul": return store.memory.soul
        case "project": return store.memory.project
        default: return store.memory.memory
        }
    }
}

struct SpacesPane: View {
    @ObservedObject var store: AppStore
    var body: some View {
        List {
            Text("Workspaces known to this WebUI").font(.caption).foregroundColor(Palette.muted).listRowBackground(Palette.bg)
            ForEach(store.spaces) { s in
                VStack(alignment: .leading) {
                    Text(s.name + (s.last ? " · last" : "")).foregroundColor(s.last ? Palette.accent : Palette.text)
                    if !s.path.isEmpty { Text(s.path).font(.system(.caption, design: .monospaced)).foregroundColor(Palette.muted) }
                }.listRowBackground(Palette.surface)
            }
        }.scrollContentBackground(.hidden).background(Palette.bg)
    }
}

struct ProfilesPane: View {
    @ObservedObject var store: AppStore
    var body: some View {
        List {
            Text("Active profile: \(store.activeProfile.isEmpty ? "default" : store.activeProfile)")
                .font(.caption).foregroundColor(Palette.muted).listRowBackground(Palette.bg)
            ForEach(store.profiles) { p in
                Button {
                    Task { await store.switchProfile(p.name) }
                } label: {
                    HStack {
                        VStack(alignment: .leading) {
                            Text(p.name).foregroundColor(p.active ? Palette.accent : Palette.text)
                            if !p.model.isEmpty { Text(p.model).font(.caption).foregroundColor(Palette.muted) }
                        }
                        Spacer()
                        if p.active { Text("active").font(.caption).foregroundColor(Palette.accent) }
                    }
                }.listRowBackground(Palette.surface)
            }
        }.scrollContentBackground(.hidden).background(Palette.bg)
    }
}

struct TodosPane: View {
    @ObservedObject var store: AppStore
    var body: some View {
        List {
            Text("Current task list from this conversation").font(.caption).foregroundColor(Palette.muted).listRowBackground(Palette.bg)
            if store.todos.isEmpty {
                Text("No todos in this session. They appear when the agent uses the todo tool.")
                    .foregroundColor(Palette.muted).listRowBackground(Palette.bg)
            }
            ForEach(store.todos, id: \.displayId) { t in
                HStack(alignment: .top) {
                    Text(mark(t.state)).foregroundColor(color(t.state))
                    VStack(alignment: .leading) {
                        Text(t.label).foregroundColor(Palette.text)
                        Text(t.state).font(.caption).foregroundColor(Palette.muted)
                    }
                }.listRowBackground(Palette.surface)
            }
        }.scrollContentBackground(.hidden).background(Palette.bg)
    }

    private func mark(_ s: String) -> String {
        switch s { case "completed", "done": return "✓"; case "in_progress", "in-progress": return "▶"; case "cancelled": return "✕"; default: return "○" }
    }
    private func color(_ s: String) -> Color {
        switch s { case "completed", "done": return .green; case "in_progress", "in-progress": return Palette.accent; default: return Palette.muted }
    }
}

struct InsightsPane: View {
    @ObservedObject var store: AppStore
    var body: some View {
        let i = store.insights
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text("Insights · last \(i.days) days").font(.title2).foregroundColor(Palette.text)
                row("Sessions", "\(i.sessions)")
                row("Messages", "\(i.messages)")
                row("Tokens", "\(i.tokens)")
                row("Cost", String(format: "$%.4f", i.cost))
                if let h = i.cacheHit { row("Cache hit", String(format: "%.1f%%", h)) }
                Text("Models").foregroundColor(Palette.accent)
                ForEach(i.models) { m in
                    Text(String(format: "%@ · %d sessions · %d tok · $%.4f", m.model, m.sessions, m.tokens, m.cost))
                        .foregroundColor(Palette.text).font(.footnote)
                }
            }.padding(16)
        }
    }
    private func row(_ k: String, _ v: String) -> some View {
        HStack { Text(k).foregroundColor(Palette.muted); Spacer(); Text(v).foregroundColor(Palette.text) }
    }
}

struct LogsPane: View {
    @ObservedObject var store: AppStore
    var body: some View {
        VStack {
            HStack {
                ForEach(["agent", "errors", "gateway"], id: \.self) { f in
                    Text(f)
                        .foregroundColor(store.logFile == f ? Palette.accent : Palette.muted)
                        .padding(8).background(Palette.surface).cornerRadius(8)
                        .onTapGesture { Task { await store.setLogFile(f) } }
                }
                Button("Refresh") { Task { await store.loadPanel() } }.foregroundColor(Palette.accent)
                Spacer()
            }.padding(12)
            ScrollView {
                LazyVStack(alignment: .leading) {
                    ForEach(Array(store.logLines.enumerated()), id: \.offset) { _, line in
                        Text(line).font(.system(.footnote, design: .monospaced)).foregroundColor(Palette.text)
                    }
                }.padding(12)
            }
        }
    }
}

struct DashboardPane: View {
    @ObservedObject var store: AppStore
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Hermes Dashboard").font(.title2).foregroundColor(Palette.text)
                Text("Live from this WebUI. Official dashboard is :9119; usage desk is :8790.")
                    .font(.footnote).foregroundColor(Palette.muted)
                ForEach(store.dash) { card in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(card.title).font(.caption).foregroundColor(Palette.muted)
                        Text(card.value).font(.title3).foregroundColor(Palette.accent)
                        if !card.hint.isEmpty { Text(card.hint).font(.footnote).foregroundColor(Palette.text) }
                    }
                    .padding(14).frame(maxWidth: .infinity, alignment: .leading)
                    .background(Palette.surface).cornerRadius(12)
                }
                Button("Refresh") { Task { await store.loadPanel() } }.foregroundColor(Palette.accent)
            }.padding(16)
        }
    }
}

struct SettingsPane: View {
    @ObservedObject var store: AppStore
    @ObservedObject var settings: AppSettings
    var body: some View {
        List {
            Section("Connection") {
                TextField("WebUI URL", text: $settings.webuiURL)
                    .textInputAutocapitalization(.never).keyboardType(.URL)
            }
            Section("WebUI settings") {
                Text("Same keys as Control Center. Secrets are hidden.")
                    .font(.caption).foregroundColor(Palette.muted)
                if !store.models.isEmpty {
                    Text("Models: " + store.models.prefix(8).joined(separator: ", ")).font(.caption).foregroundColor(Palette.muted)
                }
                ForEach(store.settingsItems) { item in
                    if item.type == "bool" {
                        Toggle(item.key, isOn: Binding(
                            get: { (store.settingEdits[item.key] ?? item.value) == "true" },
                            set: { store.settingEdits[item.key] = $0 ? "true" : "false" }
                        ))
                    } else if item.type != "json" {
                        VStack(alignment: .leading) {
                            Text(item.key).font(.caption).foregroundColor(Palette.muted)
                            TextField("", text: Binding(
                                get: { store.settingEdits[item.key] ?? item.value },
                                set: { store.settingEdits[item.key] = $0 }
                            ))
                        }
                    } else {
                        VStack(alignment: .leading) {
                            Text(item.key).font(.caption).foregroundColor(Palette.muted)
                            Text(item.value).font(.footnote).foregroundColor(Palette.text)
                        }
                    }
                }
                if !store.settingEdits.isEmpty {
                    Button("Save \(store.settingEdits.count) changes") { Task { await store.saveSettings() } }
                        .foregroundColor(Palette.accent)
                }
            }
        }
        .scrollContentBackground(.hidden).background(Palette.bg)
        .task { await store.loadPanel() }
    }
}
