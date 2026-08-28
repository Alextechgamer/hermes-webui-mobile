import SwiftUI
import UIKit

private let kanbanStatuses = ["triage", "todo", "ready", "running", "blocked", "done"]
private let kanbanUnassigned = "__unassigned__"

struct TasksPane: View {
    @ObservedObject var store: AppStore
    @State private var open: String?
    @State private var showNew = false
    @State private var newName = ""
    @State private var newSchedule = ""
    @State private var newPrompt = ""
    var body: some View {
        List {
            HStack {
                Text("Scheduled jobs").font(.caption).foregroundColor(Palette.muted)
                Spacer()
                Button("Refresh") { Task { await store.loadPanel() } }.font(.caption).foregroundColor(Palette.muted)
                Button(showNew ? "Cancel" : "+ New job") { showNew.toggle() }.font(.caption).foregroundColor(Palette.accent)
            }.listRowBackground(Palette.bg)
            if showNew {
                VStack(alignment: .leading, spacing: 8) {
                    TextField("Name (optional)", text: $newName).foregroundColor(Palette.text)
                    TextField("Schedule — e.g. 30m, every 2h, 0 9 * * *", text: $newSchedule).foregroundColor(Palette.text)
                    TextField("Prompt", text: $newPrompt).foregroundColor(Palette.text)
                    Button("Create job") {
                        Task { await store.createCron(name: newName, schedule: newSchedule, prompt: newPrompt) }
                        showNew = false; newName = ""; newSchedule = ""; newPrompt = ""
                    }.foregroundColor(Palette.accent)
                }.listRowBackground(Palette.surface)
            }
            ForEach(store.jobs) { job in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(job.name).foregroundColor(Palette.text)
                        Spacer()
                        Text(job.paused ? "paused" : (job.enabled ? "on" : "off"))
                            .font(.caption)
                            .foregroundColor(job.paused ? Palette.muted : (job.enabled ? Palette.ok : Palette.muted))
                            .padding(.horizontal, 8).padding(.vertical, 3)
                            .background(Palette.surface).cornerRadius(6)
                    }
                    Text([job.schedule, job.owner].filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(.caption).foregroundColor(Palette.muted)
                    HStack {
                        if !job.lastStatus.isEmpty {
                            Text(job.lastStatus)
                                .font(.caption)
                                .foregroundColor(job.lastStatus.lowercased().contains("error") || job.lastStatus.lowercased().contains("fail") ? Palette.danger : Palette.ok)
                        }
                        if !job.lastRun.isEmpty { Text("last \(job.lastRun)").font(.caption).foregroundColor(Palette.muted) }
                        if !job.nextRun.isEmpty { Text("next \(job.nextRun)").font(.caption).foregroundColor(Palette.muted) }
                    }
                    if !job.readOnly {
                        HStack {
                            Button("Run") { Task { await store.cronAction(job.id, "run") } }
                            Button(job.paused ? "Resume" : "Pause") { Task { await store.cronAction(job.id, job.paused ? "resume" : "pause") } }
                            Button("Output") { open = job.id; Task { await store.loadJobOutput(job.id) } }
                            Button("Delete") { Task { await store.deleteCron(job.id) } }.foregroundColor(Palette.danger)
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
    @State private var boardsOpen = false
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            toolbar
            if let open = store.kanbanOpen {
                detail(open)
            } else {
                board
            }
        }
        .background(Palette.bg)
    }

    private var q: String { store.kanbanSearch.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }

    private var filtered: [KanbanColumn] {
        store.columns.map { col in
            KanbanColumn(name: col.name, tasks: col.tasks.filter { t in
                q.isEmpty
                    || t.title.lowercased().contains(q)
                    || t.id.lowercased().contains(q)
                    || t.body.lowercased().contains(q)
            })
        }
    }

    private var tenants: [String] {
        Array(Set(store.columns.flatMap { $0.tasks.map(\.tenant) }.filter { !$0.isEmpty })).sorted()
    }

    private var toolbar: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Board").font(.caption).foregroundColor(Palette.muted)
                let current = store.kanbanBoards.first(where: { $0.slug == store.kanbanBoard })
                let label = (current.flatMap { $0.name.isEmpty ? nil : $0.name }) ?? (store.kanbanBoard.isEmpty ? "default" : store.kanbanBoard)
                Button(label) { boardsOpen.toggle() }
                    .foregroundColor(Palette.accent)
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .background(Palette.surface).cornerRadius(8)
                Spacer()
                Button("Refresh") { Task { await store.loadKanban() } }.font(.caption).foregroundColor(Palette.accent)
            }
            if boardsOpen {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(store.kanbanBoards) { b in
                        Button {
                            boardsOpen = false
                            Task { await store.switchKanbanBoard(b.slug) }
                        } label: {
                            HStack {
                                Text(b.name.isEmpty ? b.slug : b.name)
                                    .foregroundColor(b.slug == store.kanbanBoard ? Palette.accent : Palette.text)
                                Spacer()
                                Text("\(b.total)").font(.caption).foregroundColor(Palette.muted)
                            }.padding(10)
                        }
                    }
                    if store.kanbanBoards.isEmpty {
                        Text("No boards.").foregroundColor(Palette.muted).padding(10)
                    }
                }
                .background(Palette.surface).cornerRadius(10)
            }
            TextField("Search tasks", text: $store.kanbanSearch)
                .padding(10).background(Palette.bg).overlay(RoundedRectangle(cornerRadius: 8).stroke(Palette.border)).foregroundColor(Palette.text)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    chip("All assignees", store.kanbanAssignee.isEmpty) {
                        store.kanbanAssignee = ""
                        Task { await store.loadKanban() }
                    }
                    ForEach(store.kanbanAssignees, id: \.self) { a in
                        chip("@\(a)", store.kanbanAssignee == a) {
                            store.kanbanAssignee = a
                            Task { await store.loadKanban() }
                        }
                    }
                }
            }
            if !tenants.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        chip("All tenants", store.kanbanTenant.isEmpty) {
                            store.kanbanTenant = ""
                            Task { await store.loadKanban() }
                        }
                        ForEach(tenants, id: \.self) { t in
                            chip(t, store.kanbanTenant == t) {
                                store.kanbanTenant = t
                                Task { await store.loadKanban() }
                            }
                        }
                    }
                }
            }
            HStack(spacing: 6) {
                chip("Include archived", store.kanbanArchived) {
                    store.kanbanArchived.toggle()
                    Task { await store.loadKanban() }
                }
                chip("Only mine", store.kanbanMine) {
                    store.kanbanMine.toggle()
                    Task { await store.loadKanban() }
                }
            }
            let stats = store.kanbanStats.line()
            if !stats.isEmpty { Text(stats).font(.caption2).foregroundColor(Palette.muted) }
            HStack(spacing: 12) {
                Button("Preview dispatcher") { Task { await store.dispatchKanban(dry: true) } }
                Button("Run dispatcher") { Task { await store.dispatchKanban(dry: false) } }
            }.font(.caption).foregroundColor(Palette.accent)
            HStack {
                TextField("New task", text: $store.kanbanDraft)
                    .padding(10).background(Palette.bg).overlay(RoundedRectangle(cornerRadius: 8).stroke(Palette.border)).foregroundColor(Palette.text)
                Button("Add") { Task { await store.createKanbanTask() } }
                    .foregroundColor(Palette.bg).padding(.horizontal, 12).padding(.vertical, 10)
                    .background(Palette.accent).cornerRadius(8)
            }
            if let err = store.error { Text(err).font(.footnote).foregroundColor(.red) }
        }
        .padding(12)
        .background(Palette.sidebar)
    }

    private var board: some View {
        let lanes = kanbanLanes(filtered)
        return ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                if filtered.allSatisfy({ $0.tasks.isEmpty }) {
                    Text(store.columns.contains(where: { !$0.tasks.isEmpty })
                         ? "No tasks match these filters."
                         : "No tasks on this board. Switch boards or add a new task.")
                        .foregroundColor(Palette.muted).padding(8)
                }
                ForEach(lanes, id: \.0) { lane, cols in
                    Text(lane == kanbanUnassigned ? "unassigned" : lane)
                        .font(.caption.weight(.semibold)).foregroundColor(Palette.muted)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(alignment: .top, spacing: 10) {
                            ForEach(cols) { col in column(col) }
                        }
                    }
                }
            }.padding(12)
        }
    }

    private func column(_ col: KanbanColumn) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(col.name.capitalized).foregroundColor(Palette.text).bold()
                Spacer()
                Text("\(col.tasks.count)").foregroundColor(Palette.muted)
            }
            if col.tasks.isEmpty {
                Text("Empty").font(.caption).foregroundColor(Palette.muted).frame(maxWidth: .infinity).padding(.vertical, 20)
            } else {
                ForEach(col.tasks) { task in card(task) }
            }
        }
        .padding(8)
        .frame(width: 260, alignment: .top)
        .background(Palette.surface)
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Palette.border))
        .cornerRadius(12)
    }

    private func card(_ task: KanbanTask) -> some View {
        let p = task.priority.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "Pp"))
        return VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(task.id).font(.system(.caption2, design: .monospaced)).foregroundColor(Palette.muted).lineLimit(1)
                Spacer()
                if !p.isEmpty && p != "0" {
                    Text("P\(p)").font(.caption2.bold()).foregroundColor(Palette.accent)
                }
            }
            Text(task.title).foregroundColor(Palette.text).font(.subheadline)
            if !task.body.isEmpty {
                Text(String(task.body.split(separator: "\n").first.map(String.init)?.prefix(80) ?? "")).font(.caption).foregroundColor(Palette.muted).lineLimit(2)
            }
            Text(task.assignee.isEmpty ? "unassigned" : "@\(task.assignee)").font(.caption).foregroundColor(Palette.muted)
            HStack(spacing: 12) {
                Button("complete") { Task { await store.moveTask(task.id, "done") } }
                Button("archive") { Task { await store.moveTask(task.id, "archived") } }
            }.font(.caption).foregroundColor(Palette.accent)
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.bg)
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Palette.border))
        .cornerRadius(10)
        .onTapGesture { store.kanbanOpen = task }
    }

    private func detail(_ task: KanbanTask) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Button("← Board") { store.kanbanOpen = nil }.foregroundColor(Palette.accent)
                Text(task.id).font(.system(.caption, design: .monospaced)).foregroundColor(Palette.muted)
                Text(task.title).font(.title3.bold()).foregroundColor(Palette.text)
                if !task.body.isEmpty { Text(task.body).foregroundColor(Palette.text) }
                Text([
                    task.assignee.isEmpty ? "unassigned" : "@\(task.assignee)",
                    task.tenant.isEmpty ? nil : task.tenant,
                    (task.priority.isEmpty || task.priority == "0") ? nil : "P\(task.priority.trimmingCharacters(in: CharacterSet(charactersIn: "Pp")))",
                ].compactMap { $0 }.joined(separator: " · ")).font(.caption).foregroundColor(Palette.muted)
                Text("Move").font(.caption).foregroundColor(Palette.muted)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(kanbanStatuses, id: \.self) { s in
                            chip(s.capitalized, task.status == s) {
                                Task {
                                    await store.moveTask(task.id, s)
                                    store.kanbanOpen = KanbanTask(
                                        id: task.id, title: task.title, status: s,
                                        assignee: task.assignee, priority: task.priority,
                                        body: task.body, tenant: task.tenant, comments: task.comments
                                    )
                                }
                            }
                        }
                    }
                }
                HStack(spacing: 16) {
                    Button("Complete") { Task { await store.moveTask(task.id, "done"); store.kanbanOpen = nil } }
                    Button("Archive") { Task { await store.moveTask(task.id, "archived"); store.kanbanOpen = nil } }
                }.foregroundColor(Palette.accent)
            }.padding(16)
        }
    }

    private func chip(_ label: String, _ on: Bool, _ click: @escaping () -> Void) -> some View {
        Text(label).font(.caption)
            .padding(.horizontal, 10).padding(.vertical, 6)
            .background(on ? Palette.accent : Palette.surface)
            .foregroundColor(on ? Palette.bg : Palette.text)
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(on ? Palette.accent : Palette.border))
            .cornerRadius(8)
            .onTapGesture(perform: click)
    }
}

private func kanbanLanes(_ columns: [KanbanColumn]) -> [(String, [KanbanColumn])] {
    var names = Set<String>()
    columns.forEach { $0.tasks.forEach { names.insert($0.assignee.isEmpty ? kanbanUnassigned : $0.assignee) } }
    let assigned = names.filter { $0 != kanbanUnassigned }.sorted {
        if $0 == "default" { return true }
        if $1 == "default" { return false }
        return $0.localizedCaseInsensitiveCompare($1) == .orderedAscending
    }
    let order = assigned + (names.contains(kanbanUnassigned) ? [kanbanUnassigned] : [])
    if order.isEmpty { return columns.isEmpty ? [] : [("default", columns)] }
    return order.map { lane in
        (lane, columns.map { col in
            KanbanColumn(name: col.name, tasks: col.tasks.filter { ($0.assignee.isEmpty ? kanbanUnassigned : $0.assignee) == lane })
        })
    }
}

struct SkillsPane: View {
    @ObservedObject var store: AppStore
    @State private var open: String?
    @State private var query = ""
    @State private var showNew = false
    @State private var newName = ""
    @State private var newCategory = ""
    @State private var newContent = ""
    var body: some View {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        let shown = store.skills.filter {
            q.isEmpty || $0.name.lowercased().contains(q) || $0.description.lowercased().contains(q) || $0.category.lowercased().contains(q)
        }
        let grouped = Dictionary(grouping: shown, by: { $0.category.isEmpty ? "uncategorized" : $0.category })
        List {
            HStack {
                Text("Installed skills").font(.caption).foregroundColor(Palette.muted)
                Spacer()
                Button(showNew ? "Cancel" : "+ New skill") { showNew.toggle() }.font(.caption).foregroundColor(Palette.accent)
            }.listRowBackground(Palette.bg)
            TextField("Search skills…", text: $query).foregroundColor(Palette.text).listRowBackground(Palette.surface)
            if showNew {
                VStack(alignment: .leading, spacing: 8) {
                    TextField("Name (lowercase, hyphens)", text: $newName).foregroundColor(Palette.text)
                    TextField("Category (optional)", text: $newCategory).foregroundColor(Palette.text)
                    TextField("SKILL.md content", text: $newContent).foregroundColor(Palette.text)
                    Button("Create skill") {
                        Task { await store.saveSkill(name: newName, category: newCategory, content: newContent) }
                        showNew = false; newName = ""; newCategory = ""; newContent = ""
                    }.foregroundColor(Palette.accent)
                }.listRowBackground(Palette.surface)
            }
            ForEach(grouped.keys.sorted(), id: \.self) { cat in
                Section(header: Text("\(cat) (\(grouped[cat]!.count))").foregroundColor(Palette.accent)) {
                    ForEach(grouped[cat]!) { s in
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(s.name).foregroundColor(Palette.text)
                                Spacer()
                                Toggle("", isOn: Binding(
                                    get: { !s.disabled },
                                    set: { _ in Task { await store.toggleSkill(s) } }
                                )).labelsHidden()
                            }
                            if !s.description.isEmpty { Text(s.description).font(.caption).foregroundColor(Palette.muted).lineLimit(3) }
                            if open == s.name {
                                Button("Delete skill") { Task { await store.deleteSkill(s.name); open = nil } }
                                    .font(.caption).foregroundColor(Palette.danger)
                                if !store.skillBody.isEmpty {
                                    Text(String(store.skillBody.prefix(6000))).font(.system(.footnote, design: .monospaced)).foregroundColor(Palette.text)
                                }
                            }
                        }
                        .contentShape(Rectangle())
                        .onTapGesture { open = open == s.name ? nil : s.name; Task { await store.openSkill(s.name) } }
                        .listRowBackground(Palette.surface)
                    }
                }
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
    @State private var showAdd = false
    @State private var newPath = ""
    var body: some View {
        List {
            HStack {
                Text("Add and switch workspaces for your sessions.").font(.caption).foregroundColor(Palette.muted)
                Spacer()
                Button(showAdd ? "Cancel" : "+ Add space") { showAdd.toggle() }.font(.caption).foregroundColor(Palette.accent)
            }.listRowBackground(Palette.bg)
            if showAdd {
                VStack(alignment: .leading) {
                    TextField("Absolute path on the server", text: $newPath).foregroundColor(Palette.text)
                    Button("Add") {
                        Task { await store.addWorkspace(newPath) }
                        showAdd = false; newPath = ""
                    }.foregroundColor(Palette.accent)
                }.listRowBackground(Palette.surface)
            }
            ForEach(store.spaces) { s in
                HStack {
                    VStack(alignment: .leading) {
                        Text(s.name + (s.last ? " · last" : "")).foregroundColor(s.last ? Palette.accent : Palette.text)
                        if !s.path.isEmpty { Text(s.path).font(.system(.caption, design: .monospaced)).foregroundColor(Palette.muted) }
                    }
                    Spacer()
                    if !s.path.isEmpty {
                        Button("Remove") { Task { await store.removeWorkspace(s.path) } }.font(.caption).foregroundColor(Palette.danger)
                    }
                }.listRowBackground(Palette.surface)
            }
            Text("Paths are validated as existing directories before saving.").font(.caption).foregroundColor(Palette.muted).listRowBackground(Palette.bg)
        }.scrollContentBackground(.hidden).background(Palette.bg)
    }
}

struct ProfilesPane: View {
    @ObservedObject var store: AppStore
    @State private var showNew = false
    @State private var newName = ""
    var body: some View {
        List {
            HStack {
                Text("Active profile: \(store.activeProfile.isEmpty ? "default" : store.activeProfile)")
                    .font(.caption).foregroundColor(Palette.muted)
                Spacer()
                Button(showNew ? "Cancel" : "+ New profile") { showNew.toggle() }.font(.caption).foregroundColor(Palette.accent)
            }.listRowBackground(Palette.bg)
            if showNew {
                VStack(alignment: .leading) {
                    TextField("Profile name", text: $newName).foregroundColor(Palette.text)
                    Button("Create") {
                        Task { await store.createProfile(newName) }
                        showNew = false; newName = ""
                    }.foregroundColor(Palette.accent)
                }.listRowBackground(Palette.surface)
            }
            ForEach(store.profiles) { p in
                HStack {
                    Button { Task { await store.switchProfile(p.name) } } label: {
                        VStack(alignment: .leading) {
                            Text(p.name).foregroundColor(p.active ? Palette.accent : Palette.text)
                            if !p.model.isEmpty { Text(p.model).font(.caption).foregroundColor(Palette.muted) }
                        }
                    }
                    Spacer()
                    if p.active { Text("active").font(.caption).foregroundColor(Palette.accent) }
                    else if p.name != "default" {
                        Button("Delete") { Task { await store.deleteProfile(p.name) } }.font(.caption).foregroundColor(Palette.danger)
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
        let ins = store.insights
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text("Insights").font(.headline).foregroundColor(Palette.text)
                    Spacer()
                    Button("Refresh") { Task { await store.loadPanel() } }.foregroundColor(Palette.accent)
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach([7, 30, 90, 365], id: \.self) { d in
                            Text("\(d) days")
                                .font(.caption)
                                .foregroundColor(store.insightsDays == d ? Palette.accent : Palette.muted)
                                .padding(8).background(Palette.surface).cornerRadius(8)
                                .onTapGesture { store.insightsDays = d; Task { await store.loadPanel() } }
                        }
                    }
                }
                HStack {
                    stat("Sessions", "\(ins.sessions)")
                    stat("Messages", "\(ins.messages)")
                }
                HStack {
                    stat("Tokens", ConsoleFmt.tok(Int64(ins.tokens)))
                    stat("Cost", String(format: "$%.2f", ins.cost) + (ins.cacheHit.map { " · \(Int($0))% cache" } ?? ""))
                }
                if !ins.skills.isEmpty {
                    Text("Skill usage").font(.headline).foregroundColor(Palette.text).padding(.top, 8)
                    ForEach(ins.skills) { s in
                        HStack {
                            Text(s.name).foregroundColor(Palette.text).lineLimit(1)
                            Spacer()
                            Text("\(s.uses)").frame(width: 44, alignment: .trailing).foregroundColor(Palette.text)
                            Text("\(s.views)").frame(width: 44, alignment: .trailing).foregroundColor(Palette.muted)
                            Text("\(s.patches)").frame(width: 52, alignment: .trailing).foregroundColor(Palette.muted)
                        }.font(.caption)
                    }
                }
                if !ins.models.isEmpty {
                    Text("Models").font(.headline).foregroundColor(Palette.text).padding(.top, 8)
                    ForEach(ins.models) { m in
                        HStack {
                            Text(m.model).foregroundColor(Palette.text).lineLimit(1)
                            Spacer()
                            Text("\(m.sessions)").frame(width: 36, alignment: .trailing)
                            Text(ConsoleFmt.tok(Int64(m.tokens))).frame(width: 56, alignment: .trailing)
                            Text(m.cacheHitPct.map { "\(Int($0))%" } ?? "—").frame(width: 40, alignment: .trailing)
                            Text(m.cost > 0 ? String(format: "$%.2f", m.cost) : "N/A").frame(width: 52, alignment: .trailing)
                        }.font(.caption).foregroundColor(Palette.muted)
                    }
                }
            }.padding(16)
        }.background(Palette.bg)
    }
    private func stat(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading) {
            Text(label).font(.caption).foregroundColor(Palette.muted)
            Text(value).font(.headline).foregroundColor(Palette.text)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12).background(Palette.surface).cornerRadius(10)
    }
}

struct LogsPane: View {
    @ObservedObject var store: AppStore
    @State private var severity = "all"
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    ForEach(["agent", "errors", "gateway"], id: \.self) { f in
                        Text(f)
                            .foregroundColor(store.logFile == f ? Palette.accent : Palette.muted)
                            .padding(8).background(Palette.surface).cornerRadius(8)
                            .onTapGesture { Task { await store.setLogFile(f) } }
                    }
                }.padding(12)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    ForEach([100, 200, 500, 1000], id: \.self) { n in
                        Text("\(n)")
                            .font(.caption)
                            .foregroundColor(store.logTail == n ? Palette.accent : Palette.muted)
                            .padding(8).background(Palette.surface).cornerRadius(8)
                            .onTapGesture { Task { await store.setLogTail(n) } }
                    }
                    ForEach([("all","All"),("err","Errors"),("warn","Warnings+")], id: \.0) { k, label in
                        Text(label)
                            .font(.caption)
                            .foregroundColor(severity == k ? Palette.accent : Palette.muted)
                            .padding(8).background(Palette.surface).cornerRadius(8)
                            .onTapGesture { severity = k }
                    }
                    Button("Copy all") {
                        UIPasteboard.general.string = store.logLines.joined(separator: "\n")
                    }.font(.caption).foregroundColor(Palette.accent)
                    Button("Refresh") { Task { await store.loadPanel() } }.font(.caption).foregroundColor(Palette.accent)
                }.padding(.horizontal, 12)
            }
            let shown = store.logLines.filter { line in
                switch severity {
                case "err": return line.uppercased().contains("ERROR") || line.uppercased().contains("CRITICAL") || line.contains("Traceback")
                case "warn": return line.uppercased().contains("ERROR") || line.uppercased().contains("WARN") || line.uppercased().contains("CRITICAL")
                default: return true
                }
            }
            ScrollView {
                LazyVStack(alignment: .leading) {
                    ForEach(Array(shown.enumerated()), id: \.offset) { _, line in
                        let color: Color = {
                            let u = line.uppercased()
                            if u.contains("ERROR") || u.contains("CRITICAL") { return Palette.danger }
                            if u.contains("WARN") { return Palette.accent }
                            return Palette.text
                        }()
                        Text(line).font(.system(.footnote, design: .monospaced)).foregroundColor(color)
                    }
                }.padding(12)
            }
        }.background(Palette.bg)
    }
}
