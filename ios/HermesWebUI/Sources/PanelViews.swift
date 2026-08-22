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
                                let sub = [task.assignee, task.priority].filter { !$0.isEmpty }.joined(separator: " · ")
                                if !sub.isEmpty { Text(sub).font(.caption).foregroundColor(Palette.muted) }
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
        let u = store.console
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    VStack(alignment: .leading) {
                        Text("HERMES CONSOLE").font(.caption.bold()).foregroundColor(Palette.accent)
                        Text(u.db_path.split(separator: "/").last.map(String.init) ?? "usage desk :8790")
                            .font(.caption).foregroundColor(Palette.muted)
                    }
                    Spacer()
                    Text(u.connected ? "Connected" : "Offline").font(.caption).foregroundColor(u.connected ? Palette.accent : .red)
                }
                if let e = store.consoleError { Text(e).font(.footnote).foregroundColor(.red) }
                HStack {
                    kpi("REQUESTS", ConsoleFmt.int(u.totals.requests))
                    kpi("TOKENS", ConsoleFmt.tok(u.totals.tokens_total))
                }
                HStack {
                    kpi("BURN MTD", ConsoleFmt.money(u.burn.mtd_spend))
                    kpi("OUT-OF-POCKET", ConsoleFmt.money(u.subscriptions.actual_out_of_pocket_month))
                }
                card {
                    Text("Live Usage").foregroundColor(Palette.text).bold()
                    Text(u.inflight_basis).font(.caption).foregroundColor(Palette.muted)
                    if u.inflight.isEmpty {
                        Text("Idle — nothing in flight.").font(.footnote).foregroundColor(Palette.muted)
                    } else {
                        ForEach(u.inflight) { row in
                            Text((row.model ?? "model") + " · " + (row.task ?? "chat")).foregroundColor(Palette.text)
                            Text(row.session_short).font(.caption).foregroundColor(Palette.muted)
                        }
                    }
                }
                card {
                    Text("Monthly Burn Rate").foregroundColor(Palette.text).bold()
                    Text(ConsoleFmt.money(u.burn.mtd_spend)).font(.largeTitle.monospacedDigit()).foregroundColor(Palette.accent)
                    Text("today \(ConsoleFmt.money(u.burn.today_spend)) · pace \(ConsoleFmt.money(u.burn.avg_daily_pace)) · EOM \(ConsoleFmt.money(u.burn.projected_eom))")
                        .font(.caption).foregroundColor(Palette.muted)
                }
                card {
                    Text("Real Cost vs Metered").foregroundColor(Palette.text).bold()
                    Text(ConsoleFmt.money(u.subscriptions.actual_out_of_pocket_month)).font(.title.monospacedDigit()).foregroundColor(Palette.text)
                    Text("flat \(ConsoleFmt.money(u.subscriptions.total_flat_monthly)) + payg \(ConsoleFmt.money(u.subscriptions.payg_metered_mtd))")
                        .font(.caption).foregroundColor(Palette.muted)
                    ForEach(u.subscriptions.items) { plan in
                        HStack {
                            Text(plan.name).foregroundColor(Palette.text)
                            Spacer()
                            Text(ConsoleFmt.money(plan.monthly_usd) + "/mo").foregroundColor(Palette.text)
                        }
                    }
                }
                card {
                    Text("Usage Overview").foregroundColor(Palette.text).bold()
                    Text("\(ConsoleFmt.int(u.totals.requests)) requests").font(.title2).foregroundColor(Palette.text)
                    Text("\(ConsoleFmt.tok(u.totals.tokens_total)) tok · \(ConsoleFmt.money(u.totals.est_cost)) est")
                        .font(.caption).foregroundColor(Palette.muted)
                }
                Text("Popular Models").foregroundColor(Palette.text).bold()
                ForEach(u.models) { m in
                    card {
                        HStack {
                            VStack(alignment: .leading) {
                                Text(m.model).foregroundColor(Palette.text)
                                Text(m.provider).font(.caption).foregroundColor(Palette.muted)
                            }
                            Spacer()
                            Text("\(ConsoleFmt.int(m.requests)) req").foregroundColor(Palette.text)
                        }
                    }
                }
                Text("Request feed").foregroundColor(Palette.text).bold()
                ForEach(u.feed.prefix(40)) { r in
                    VStack(alignment: .leading, spacing: 3) {
                        HStack {
                            Text(r.session_short).font(.system(size: 11, design: .monospaced)).foregroundColor(Palette.muted)
                            Text(r.source).font(.system(size: 12, weight: .semibold)).foregroundColor(Palette.text)
                            Spacer()
                            Text(r.est_cost > 0 ? ConsoleFmt.money(r.est_cost, digits: 4) : "NO PRICE")
                                .font(.system(size: 11))
                                .foregroundColor(r.est_cost > 0 ? Palette.accent : Palette.muted)
                        }
                        Text("\(r.model) · \(r.task) · \(ConsoleFmt.tok(r.tokens_total)) tok · cache \(r.cache_pct)% · \(ConsoleFmt.age(r.age_s))")
                            .font(.system(size: 11)).foregroundColor(Palette.muted)
                    }
                    .padding(.vertical, 6)
                }
                Button("Refresh") { Task { await store.loadConsole() } }.foregroundColor(Palette.accent)
            }.padding(16)
        }
    }

    private func kpi(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading) {
            Text(label).font(.caption2).foregroundColor(Palette.muted)
            Text(value).font(.title3.monospacedDigit()).foregroundColor(Palette.text)
        }
        .padding(12).frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.surface).cornerRadius(12)
    }

    private func card<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6, content: content)
            .padding(14).frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.surface).cornerRadius(12)
    }
}


